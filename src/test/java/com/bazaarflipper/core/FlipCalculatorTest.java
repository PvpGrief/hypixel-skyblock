package com.bazaarflipper.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.bazaarflipper.core.calc.Flag;
import com.bazaarflipper.core.calc.FlipCalculator;
import com.bazaarflipper.core.calc.FlipMode;
import com.bazaarflipper.core.calc.FlipResult;
import com.bazaarflipper.core.calc.FlipSettings;
import com.bazaarflipper.core.calc.TradeMethod;
import com.bazaarflipper.core.data.Recipe;
import com.bazaarflipper.core.data.SkyblockData;
import com.bazaarflipper.core.market.PriceHistory;
import com.bazaarflipper.core.model.BazaarSnapshot;
import com.bazaarflipper.core.model.ItemInfo;

class FlipCalculatorTest {
	static final long NOW_SEC = 5_000_000;
	static final long NOW_MS = NOW_SEC * 1000;

	static FlipSettings settings(FlipMode mode) {
		FlipSettings s = new FlipSettings();
		s.mode = mode;
		s.budget = 1e9;
		s.maxItemsPerHour = 0;
		s.minWeeklyVolume = 0;
		s.marketSharePercent = 5;
		s.taxPercent = 1.25;
		return s;
	}

	static List<FlipResult> run(BazaarSnapshot snap, FlipSettings s, PriceHistory h, Map<String, ItemInfo> items, SkyblockData data) {
		return new FlipCalculator(snap, items, data, h, s, NOW_MS).compute();
	}

	static List<FlipResult> run(BazaarSnapshot snap, FlipSettings s, PriceHistory h) {
		return run(snap, s, h, Map.of(), SkyblockData.empty());
	}

	/** A history where X traded at bid 100 / ask 120 for the last 20 minutes. */
	static PriceHistory stableHistory(double bid, double ask) {
		PriceHistory h = new PriceHistory(60);
		for (int i = 20; i >= 1; i--) {
			h.record(Fixtures.snapshot((NOW_SEC - i * 60L) * 1000, Fixtures.product("X").bid(bid, 5000).ask(ask, 5000)));
		}
		return h;
	}

	static FlipResult only(List<FlipResult> rs) {
		assertEquals(1, rs.size(), () -> "results: " + rs);
		return rs.getFirst();
	}

	@Test
	void orderFlipProfitAndRate() {
		BazaarSnapshot snap = Fixtures.snapshot(NOW_MS, Fixtures.product("X").bid(100, 5000, 5).ask(120, 5000, 5).weekly(168_000, 168_000));
		FlipResult r = only(run(snap, settings(FlipMode.BAZAAR_FLIP), stableHistory(100, 120)));
		double cost = 100.1;
		double revenue = 119.9 * (1 - 0.0125);
		assertEquals(cost, r.costPerItem(), 1e-6);
		assertEquals(revenue, r.revenuePerItem(), 1e-6);
		// 168k/week = 1000/h, 5% share = 50/h
		assertEquals(50, r.itemsPerHour(), 1e-6);
		assertEquals((revenue - cost) * 50, r.profitPerHour(), 1e-6);
		assertFalse(r.flags().contains(Flag.NEW));
	}

	@Test
	void pumpedSellPriceIsCappedByHistoryMedian() {
		// Someone bought out the sell offers and relisted at 300; history says it normally sells at 120.
		BazaarSnapshot snap = Fixtures.snapshot(NOW_MS, Fixtures.product("X").bid(100, 5000, 5).ask(300, 5000, 5));
		FlipSettings s = settings(FlipMode.BAZAAR_FLIP);
		s.maxRisk = 100;
		FlipResult r = only(run(snap, s, stableHistory(100, 120)));
		assertTrue(r.flags().contains(Flag.SPIKE));
		assertTrue(r.revenuePerItem() <= 120 * 1.15 * (1 - 0.0125) + 1e-6, "revenue " + r.revenuePerItem());
	}

	@Test
	void pumpWithoutHistoryIsHiddenInSafeMode() {
		BazaarSnapshot snap = Fixtures.snapshot(NOW_MS, Fixtures.product("X").bid(100, 5000, 5).ask(300, 5000, 5));
		assertTrue(run(snap, settings(FlipMode.BAZAAR_FLIP), new PriceHistory(60)).isEmpty());

		FlipSettings unsafe = settings(FlipMode.BAZAAR_FLIP);
		unsafe.safeMode = false;
		FlipResult r = only(run(snap, unsafe, new PriceHistory(60)));
		assertTrue(r.flags().containsAll(List.of(Flag.SPIKE, Flag.SPREAD, Flag.NEW)));
	}

	@Test
	void dumpedBuyPriceIsRaisedToHistoryFloor() {
		// Buy orders were dumped down to 50; history says 100.
		BazaarSnapshot snap = Fixtures.snapshot(NOW_MS, Fixtures.product("X").bid(50, 5000, 5).ask(120, 5000, 5));
		FlipSettings s = settings(FlipMode.BAZAAR_FLIP);
		s.maxRisk = 100;
		FlipResult r = only(run(snap, s, stableHistory(100, 120)));
		assertTrue(r.flags().contains(Flag.DUMP));
		assertEquals(85, r.costPerItem(), 1e-6);
	}

	@Test
	void baitSellOfferDoesNotFakeTheUndercutPrice() {
		BazaarSnapshot snap = Fixtures.snapshot(NOW_MS,
				Fixtures.product("X").bid(100, 5000, 5).ask(105, 1, 1).ask(120, 5000, 5)); // 1-item bait offer at 105
		FlipResult r = only(run(snap, settings(FlipMode.BAZAAR_FLIP), stableHistory(100, 120)));
		assertTrue(r.flags().contains(Flag.BAIT));
		assertEquals(119.9 * (1 - 0.0125), r.revenuePerItem(), 1e-6);
	}

	@Test
	void instabuyWalksTheBookInsteadOfTrustingOneCheapOrder() {
		// One 1-item sell offer at 1 coin, the real supply is at 100. NPC pays 50.
		BazaarSnapshot snap = Fixtures.snapshot(NOW_MS,
				Fixtures.product("X").bid(90, 5000, 5).ask(1, 1, 1).ask(100, 50_000, 10));
		FlipSettings s = settings(FlipMode.BAZAAR_TO_NPC);
		s.buyMethod = TradeMethod.INSTANT;
		Map<String, ItemInfo> items = Map.of("X", new ItemInfo("X", "Thing", 50));
		assertTrue(run(snap, s, stableHistory(90, 100), items, SkyblockData.empty()).isEmpty());
	}

	@Test
	void bazaarToNpc() {
		BazaarSnapshot snap = Fixtures.snapshot(NOW_MS, Fixtures.product("X").bid(2, 50_000, 10).ask(2.5, 50_000, 10));
		FlipSettings s = settings(FlipMode.BAZAAR_TO_NPC);
		PriceHistory h = new PriceHistory(60);
		for (int i = 20; i >= 1; i--) {
			h.record(Fixtures.snapshot((NOW_SEC - i * 60L) * 1000, Fixtures.product("X").bid(2, 5000).ask(2.5, 5000)));
		}
		FlipResult r = only(run(snap, s, h, Map.of("X", new ItemInfo("X", "Thing", 3)), SkyblockData.empty()));
		assertEquals(3 - 2.1, r.profitPerItem(), 1e-6);
		assertEquals("Thing", r.name());
	}

	@Test
	void npcToBazaarFlagsShopLimit() {
		BazaarSnapshot snap = Fixtures.snapshot(NOW_MS, Fixtures.product("X").bid(100, 5000, 5).ask(120, 5000, 5));
		SkyblockData data = new SkyblockData(List.of(), List.of(),
				Map.of("X", new SkyblockData.NpcOffer("X", 50, "Farm Merchant")), Map.of(), "test");
		FlipSettings s = settings(FlipMode.NPC_TO_BAZAAR);
		FlipResult r = only(run(snap, s, stableHistory(100, 120), Map.of(), data));
		assertEquals(50, r.costPerItem(), 1e-6);
		assertTrue(r.flags().contains(Flag.NPC_LIMIT));
	}

	@Test
	void craftUsesIngredientCostsAndOutputPrice() {
		PriceHistory h = new PriceHistory(60);
		for (int i = 20; i >= 1; i--) {
			h.record(Fixtures.snapshot((NOW_SEC - i * 60L) * 1000,
					Fixtures.product("RAW").bid(1, 500_000).ask(1.2, 500_000),
					Fixtures.product("ENCH").bid(150, 5000).ask(200, 5000)));
		}
		BazaarSnapshot snap = Fixtures.snapshot(NOW_MS,
				Fixtures.product("RAW").bid(1, 500_000, 10).ask(1.2, 500_000, 10).weekly(16_800_000, 16_800_000),
				Fixtures.product("ENCH").bid(150, 5000, 5).ask(200, 5000, 5).weekly(168_000, 168_000));
		SkyblockData data = new SkyblockData(
				List.of(new Recipe("ENCH", 1, Fixtures.map("RAW", 160), 0, 0, "Requires: Raw III")),
				List.of(), Map.of(), Map.of("ENCH", "Enchanted Raw"), "test");
		FlipResult r = only(run(snap, settings(FlipMode.CRAFT), h, Map.of(), data));
		assertEquals(160 * 1.1, r.costPerItem(), 1e-6);
		assertEquals(199.9 * (1 - 0.0125), r.revenuePerItem(), 1e-6);
		assertEquals("Enchanted Raw", r.name());
		assertTrue(r.details().stream().anyMatch(d -> d.contains("Requires: Raw III")));
	}

	@Test
	void forgeIsLimitedBySlots() {
		BazaarSnapshot snap = Fixtures.snapshot(NOW_MS,
				Fixtures.product("ORE").bid(10, 500_000, 10).ask(11, 500_000, 10).weekly(1_680_000, 1_680_000),
				Fixtures.product("X").bid(100_000, 5000, 5).ask(120_000, 5000, 5).weekly(168_000, 168_000));
		SkyblockData data = new SkyblockData(List.of(),
				List.of(new Recipe("X", 1, Fixtures.map("ORE", 100), 0, 3600 * 4, "")), Map.of(), Map.of(), "test");
		FlipSettings s = settings(FlipMode.FORGE);
		s.safeMode = false;
		s.forgeSlots = 2;
		FlipResult r = only(run(snap, s, new PriceHistory(60), Map.of(), data));
		assertEquals(0.5, r.itemsPerHour(), 1e-9); // 2 slots / 4 hours
		assertEquals("forge slots", r.limitedBy());
	}

	@Test
	void budgetLimitsRateAndExcludesUnaffordable() {
		BazaarSnapshot snap = Fixtures.snapshot(NOW_MS, Fixtures.product("X").bid(100, 5000, 5).ask(120, 5000, 5).weekly(168_000, 168_000));
		FlipSettings s = settings(FlipMode.BAZAAR_FLIP);
		s.budget = 1001; // ~10 items per hour
		FlipResult r = only(run(snap, s, stableHistory(100, 120)));
		assertEquals("budget", r.limitedBy());
		assertEquals(1001 / 100.1, r.itemsPerHour(), 1e-6);

		s.budget = 50; // cannot afford a single item
		assertTrue(run(snap, s, stableHistory(100, 120)).isEmpty());
	}

	@Test
	void lowVolumeItemsAreFilteredOut() {
		BazaarSnapshot snap = Fixtures.snapshot(NOW_MS, Fixtures.product("X").bid(100, 5000, 5).ask(120, 5000, 5).weekly(500, 500));
		FlipSettings s = settings(FlipMode.BAZAAR_FLIP);
		s.minWeeklyVolume = 1000;
		assertTrue(run(snap, s, stableHistory(100, 120)).isEmpty());
	}

	@Test
	void fewOrdersAreFlagged() {
		BazaarSnapshot snap = Fixtures.snapshot(NOW_MS, Fixtures.product("X").bid(100, 5000, 1).ask(120, 5000, 1).counts(1, 1));
		FlipSettings s = settings(FlipMode.BAZAAR_FLIP);
		s.maxRisk = 100;
		FlipResult r = only(run(snap, s, stableHistory(100, 120)));
		assertTrue(r.flags().contains(Flag.FEW_ORDERS));
	}

	@Test
	void unprofitableFlipsAreNotShown() {
		BazaarSnapshot snap = Fixtures.snapshot(NOW_MS, Fixtures.product("X").bid(100, 5000, 5).ask(100.5, 5000, 5));
		assertTrue(run(snap, settings(FlipMode.BAZAAR_FLIP), stableHistory(100, 100.5)).isEmpty());
	}

	@Test
	void prettyNamesForEnchantments() {
		assertEquals("Ultimate Wise V", SkyblockData.prettyId("ENCHANTMENT_ULTIMATE_WISE_5"));
		assertEquals("Sharpness VII", SkyblockData.prettyId("ENCHANTMENT_SHARPNESS_7"));
		assertEquals("Chimera I", SkyblockData.prettyId("ENCHANTMENT_ULTIMATE_CHIMERA_1"));
	}
}
