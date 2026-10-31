package com.bazaarflipper.core;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import org.junit.jupiter.api.Test;

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

/** Runs every mode over the real bundled recipe data with randomised (and partly hostile) order books. */
class BundledDataTest {
	static SkyblockData load() throws IOException {
		try (InputStream in = BundledDataTest.class.getResourceAsStream("/assets/bazaarflipper/skyblock_data.json")) {
			assertNotNull(in, "bundled data missing from classpath");
			return SkyblockData.load(in);
		}
	}

	@Test
	void bundledDataLoads() throws IOException {
		SkyblockData d = load();
		assertTrue(d.crafts().size() > 1000, "crafts: " + d.crafts().size());
		assertTrue(d.forge().size() > 50, "forge: " + d.forge().size());
		assertTrue(d.npcShop().size() > 100, "npc: " + d.npcShop().size());
		assertTrue(d.crafts().stream().anyMatch(r -> r.output().equals("ENCHANTED_DIAMOND") && r.inputs().getOrDefault("DIAMOND", 0.0) == 160));
	}

	@Test
	void allModesSurviveRandomAndHostileBooks() throws IOException {
		SkyblockData data = load();
		Set<String> ids = new HashSet<>();
		for (Recipe r : data.crafts()) {
			ids.add(r.output());
			ids.addAll(r.inputs().keySet());
		}
		for (Recipe r : data.forge()) {
			ids.add(r.output());
			ids.addAll(r.inputs().keySet());
		}
		ids.addAll(data.npcShop().keySet());

		Random rnd = new Random(42);
		long now = 9_000_000_000L;
		PriceHistory history = new PriceHistory(60);
		Map<String, ItemInfo> items = new HashMap<>();
		List<Fixtures.P> products = new ArrayList<>();
		for (String id : ids) {
			double base = Math.pow(10, rnd.nextDouble() * 6); // 1 .. 1M coins
			Fixtures.P p = Fixtures.product(id).weekly(rnd.nextInt(5_000_000), rnd.nextInt(5_000_000))
					.counts(rnd.nextInt(60), rnd.nextInt(60));
			int levels = rnd.nextInt(6);
			for (int i = 0; i < levels; i++) {
				p.bid(base * (1 - 0.01 * i - rnd.nextDouble() * 0.01), 1 + rnd.nextInt(rnd.nextBoolean() ? 3 : 100_000), 1 + rnd.nextInt(5));
				p.ask(base * (1.05 + 0.01 * i + rnd.nextDouble() * (rnd.nextInt(10) == 0 ? 5 : 0.05)), 1 + rnd.nextInt(rnd.nextBoolean() ? 3 : 100_000), 1 + rnd.nextInt(5));
			}
			products.add(p);
			items.put(id, new ItemInfo(id, id, rnd.nextInt(4) == 0 ? base * (0.5 + rnd.nextDouble()) : 0));
		}
		BazaarSnapshot snap = Fixtures.snapshot(now, products.toArray(new Fixtures.P[0]));
		history.record(snap);

		for (FlipMode mode : FlipMode.values()) {
			for (TradeMethod buy : TradeMethod.values()) {
				for (TradeMethod sell : TradeMethod.values()) {
					for (boolean safe : new boolean[]{true, false}) {
						FlipSettings s = new FlipSettings();
						s.mode = mode;
						s.buyMethod = buy;
						s.sellMethod = sell;
						s.safeMode = safe;
						s.minWeeklyVolume = 0;
						List<FlipResult> rs = new FlipCalculator(snap, items, data, history, s, now).compute();
						for (FlipResult r : rs) {
							assertTrue(Double.isFinite(r.profitPerHour()) && r.profitPerHour() > 0, r.toString());
							assertTrue(Double.isFinite(r.itemsPerHour()) && r.itemsPerHour() > 0, r.toString());
							assertTrue(r.costPerItem() > 0 && r.revenuePerItem() > r.costPerItem(), r.toString());
							assertTrue(r.capitalNeeded() <= s.budget * 1.0001 + 1, r.toString());
							assertTrue(r.risk() >= 0 && r.risk() <= 100);
							assertFalse(r.details().isEmpty());
							if (safe) {
								assertTrue(r.risk() <= s.maxRisk, r.toString());
							}
						}
					}
				}
			}
		}
	}
}
