package com.bazaarflipper.core.calc;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.bazaarflipper.core.Coins;
import com.bazaarflipper.core.data.Recipe;
import com.bazaarflipper.core.data.SkyblockData;
import com.bazaarflipper.core.market.PriceHistory;
import com.bazaarflipper.core.model.BazaarSnapshot;
import com.bazaarflipper.core.model.ItemInfo;
import com.bazaarflipper.core.model.Product;

/**
 * Ranks flips by sustainable profit per hour. Every mode is modelled the same way: a set of input legs
 * (bought on the Bazaar or from an NPC), an optional coin cost and wait time, and one output leg (sold on
 * the Bazaar or to an NPC). The hourly rate is limited by market volume (times your expected share),
 * your budget, your items-per-hour cap and, for the Forge, your forge slots.
 */
public final class FlipCalculator {
	/** Capital is assumed to cycle at most once per hour (order fill time). */
	static final double MIN_HOLD_HOURS = 1.0;

	private final BazaarSnapshot bazaar;
	private final Map<String, ItemInfo> items;
	private final SkyblockData data;
	private final FlipSettings s;
	private final MarketGuard guard;

	public FlipCalculator(BazaarSnapshot bazaar, Map<String, ItemInfo> items, SkyblockData data, PriceHistory history, FlipSettings settings, long nowMs) {
		this.bazaar = bazaar;
		this.items = items == null ? Map.of() : items;
		this.data = data == null ? SkyblockData.empty() : data;
		this.s = settings.copy().sanitize();
		this.guard = new MarketGuard(history, this.s, nowMs / 1000);
	}

	public String name(String id) {
		ItemInfo info = items.get(id);
		if (info != null && info.name() != null && !info.name().isBlank() && !info.name().equals("Enchanted Book")) {
			return info.name();
		}
		String bundled = data.bundledName(id);
		return bundled != null ? bundled : SkyblockData.prettyId(id);
	}

	// ---------------------------------------------------------------------------------------------------
	// Plan model

	private enum Source { BAZAAR, NPC }

	private record Input(String id, double perCraft, Source source, double npcUnitCost) {
	}

	private record Plan(String outputId, double outCount, Source sink, double npcSellPrice, List<Input> inputs,
	                    double coinsPerCraft, int durationSec, String requirement) {
	}

	public List<FlipResult> compute() {
		if (bazaar == null) {
			return List.of();
		}
		List<Plan> plans = switch (s.mode) {
			case BAZAAR_FLIP -> bazaarFlipPlans();
			case CRAFT -> recipePlans(data.crafts());
			case FORGE -> recipePlans(data.forge());
			case BAZAAR_TO_NPC -> bazaarToNpcPlans();
			case NPC_TO_BAZAAR -> npcToBazaarPlans();
		};
		String search = s.search.trim().toLowerCase(Locale.ROOT);
		Map<String, FlipResult> best = new HashMap<>();
		for (Plan plan : plans) {
			FlipResult r = evaluate(plan);
			if (r == null) {
				continue;
			}
			if (!search.isEmpty() && !r.name().toLowerCase(Locale.ROOT).contains(search)
					&& !r.id().toLowerCase(Locale.ROOT).contains(search)) {
				continue;
			}
			best.merge(r.id(), r, (a, b) -> a.profitPerHour() >= b.profitPerHour() ? a : b);
		}
		List<FlipResult> out = new ArrayList<>(best.values());
		out.sort(comparator(s.sort));
		return out;
	}

	static Comparator<FlipResult> comparator(SortOrder sort) {
		Comparator<FlipResult> byHour = Comparator.comparingDouble(FlipResult::profitPerHour).reversed();
		return switch (sort) {
			case PROFIT_PER_HOUR -> byHour;
			case PROFIT_PER_ITEM -> Comparator.comparingDouble(FlipResult::profitPerItem).reversed().thenComparing(byHour);
			case ROI -> Comparator.comparingDouble(FlipResult::roi).reversed().thenComparing(byHour);
			case SAFEST -> Comparator.comparingInt(FlipResult::risk).thenComparing(byHour);
		};
	}

	private List<Plan> bazaarFlipPlans() {
		List<Plan> plans = new ArrayList<>();
		for (Product p : bazaar.products().values()) {
			plans.add(new Plan(p.id(), 1, Source.BAZAAR, 0, List.of(new Input(p.id(), 1, Source.BAZAAR, 0)), 0, 0, ""));
		}
		return plans;
	}

	private List<Plan> bazaarToNpcPlans() {
		List<Plan> plans = new ArrayList<>();
		for (Product p : bazaar.products().values()) {
			ItemInfo info = items.get(p.id());
			if (info == null || !(info.npcSellPrice() > 0)) {
				continue;
			}
			plans.add(new Plan(p.id(), 1, Source.NPC, info.npcSellPrice(), List.of(new Input(p.id(), 1, Source.BAZAAR, 0)), 0, 0, ""));
		}
		return plans;
	}

	private List<Plan> npcToBazaarPlans() {
		List<Plan> plans = new ArrayList<>();
		for (SkyblockData.NpcOffer offer : data.npcShop().values()) {
			if (bazaar.get(offer.id()) == null) {
				continue;
			}
			String where = offer.npc().isBlank() ? "" : "Sold by " + offer.npc();
			plans.add(new Plan(offer.id(), 1, Source.BAZAAR, 0, List.of(new Input(offer.id(), 1, Source.NPC, offer.unitCost())), 0, 0, where));
		}
		return plans;
	}

	private List<Plan> recipePlans(List<Recipe> recipes) {
		List<Plan> plans = new ArrayList<>();
		recipe:
		for (Recipe r : recipes) {
			if (bazaar.get(r.output()) == null || r.inputs().containsKey(r.output())) {
				continue;
			}
			List<Input> inputs = new ArrayList<>();
			for (Map.Entry<String, Double> in : r.inputs().entrySet()) {
				Product bz = bazaar.get(in.getKey());
				SkyblockData.NpcOffer npc = s.npcIngredients ? data.npcShop().get(in.getKey()) : null;
				if (bz == null && npc == null) {
					continue recipe; // ingredient cannot be bought anywhere we know
				}
				if (npc != null && (bz == null || npc.unitCost() < roughUnitCost(bz))) {
					inputs.add(new Input(in.getKey(), in.getValue(), Source.NPC, npc.unitCost()));
				} else {
					inputs.add(new Input(in.getKey(), in.getValue(), Source.BAZAAR, 0));
				}
			}
			plans.add(new Plan(r.output(), r.outputCount(), Source.BAZAAR, 0, inputs, r.coins(), r.durationSec(), r.requirement()));
		}
		return plans;
	}

	/** Rough cost used only to choose between NPC and Bazaar for an ingredient. */
	private double roughUnitCost(Product p) {
		double v = s.buyMethod == TradeMethod.ORDER ? p.topBuyOrder() : p.lowestSellOffer();
		return Double.isNaN(v) ? Double.MAX_VALUE : v;
	}

	// ---------------------------------------------------------------------------------------------------
	// Evaluation

	private FlipResult evaluate(Plan plan) {
		double tax = s.taxPercent / 100.0;
		double holdHours = Math.max(MIN_HOLD_HOURS, plan.durationSec / 3600.0);
		double capRate = s.maxItemsPerHour > 0 ? s.maxItemsPerHour / plan.outCount : Double.MAX_VALUE;
		double forgeRate = plan.durationSec > 0 ? s.forgeSlots * 3600.0 / plan.durationSec : Double.MAX_VALUE;

		// Price the book for a rough rate, then re-price with the resulting rate until it settles.
		double crafts = Math.min(capRate, forgeRate);
		if (crafts == Double.MAX_VALUE) {
			crafts = 1000;
		}
		Evaluation e = null;
		for (int pass = 0; pass < 3; pass++) {
			e = price(plan, crafts, tax);
			if (e == null) {
				return null;
			}
			double budgetRate = s.budget / (e.costPerCraft * holdHours);
			double rate = Math.min(Math.min(e.marketRate, capRate), Math.min(forgeRate, budgetRate));
			e.limitedBy = rate == e.marketRate ? e.marketLimitedBy
					: rate == budgetRate ? "budget"
					: rate == forgeRate ? "forge slots"
					: "your items/h cap";
			e.craftsPerHour = rate;
			if (Math.abs(rate - crafts) <= crafts * 0.05) {
				break;
			}
			crafts = Math.max(rate, 1e-6);
		}
		if (e.costPerCraft > s.budget || !(e.craftsPerHour > 0)) {
			return null;
		}

		double profitPerCraft = e.revenuePerCraft - e.costPerCraft;
		if (!(profitPerCraft > 0)) {
			return null;
		}
		double itemsPerHour = e.craftsPerHour * plan.outCount;
		double profitPerHour = profitPerCraft * e.craftsPerHour;
		if (profitPerHour < s.minProfitPerHour) {
			return null;
		}
		int risk = Math.min(100, e.flags.stream().mapToInt(f -> f.weight).sum());
		if (s.safeMode && risk > s.maxRisk) {
			return null;
		}
		double capital = e.costPerCraft * e.craftsPerHour * holdHours;
		double costPerItem = e.costPerCraft / plan.outCount;
		double revenuePerItem = e.revenuePerCraft / plan.outCount;

		List<String> details = new ArrayList<>();
		details.add(s.mode.label + ": " + e.route);
		details.addAll(e.lines);
		details.add(String.format(Locale.ROOT, "Profit %s/item (%.1f%% ROI) × %s items/h = %s/h",
				Coins.compact(profitPerCraft / plan.outCount), 100 * profitPerCraft / e.costPerCraft,
				Coins.compact(itemsPerHour), Coins.compact(profitPerHour)));
		details.add("Limited by " + e.limitedBy + " · capital needed " + Coins.compact(capital)
				+ (plan.durationSec > 0 ? " · forge time " + formatDuration(plan.durationSec) : ""));
		if (plan.requirement != null && !plan.requirement.isBlank()) {
			details.add(plan.requirement);
		}
		for (Flag f : e.flags) {
			details.add("⚠ " + f.label + ": " + f.description);
		}

		return new FlipResult(s.mode, plan.outputId, name(plan.outputId), e.route, costPerItem, revenuePerItem,
				profitPerCraft / plan.outCount, itemsPerHour, profitPerHour, capital, profitPerCraft / e.costPerCraft,
				risk, e.flags, e.limitedBy, List.copyOf(details));
	}

	private static final class Evaluation {
		double costPerCraft;
		double revenuePerCraft;
		double marketRate = Double.MAX_VALUE;
		String marketLimitedBy = "market volume";
		double craftsPerHour;
		String limitedBy = "";
		String route;
		final EnumSet<Flag> flags = EnumSet.noneOf(Flag.class);
		final List<String> lines = new ArrayList<>();
	}

	private Evaluation price(Plan plan, double craftsPerHour, double tax) {
		Evaluation e = new Evaluation();
		e.costPerCraft = plan.coinsPerCraft;
		String buyRoute = null;
		for (Input in : plan.inputs) {
			double unit;
			if (in.source == Source.NPC) {
				unit = in.npcUnitCost;
				buyRoute = buyRoute == null ? "NPC shop" : buyRoute;
				e.lines.add(String.format(Locale.ROOT, "Buy %s× %s from NPC @ %s", fmtCount(in.perCraft), name(in.id), Coins.full(unit)));
				if (s.mode == FlipMode.NPC_TO_BAZAAR) {
					e.flags.add(Flag.NPC_LIMIT);
				}
			} else {
				Product p = bazaar.get(in.id);
				if (p == null || MarketGuard.acquireWeekVolume(p, s.buyMethod) < s.minWeeklyVolume) {
					return null;
				}
				MarketGuard.Quote q = guard.acquire(p, s.buyMethod, craftsPerHour * in.perCraft);
				if (q == null) {
					return null;
				}
				unit = q.unitPrice();
				e.flags.addAll(q.flags());
				double rate = q.ratePerHour() / in.perCraft;
				if (rate < e.marketRate) {
					e.marketRate = rate;
					e.marketLimitedBy = "market volume of " + name(in.id) + " (" + fmtShare() + ")";
				}
				buyRoute = buyRoute == null ? (s.buyMethod == TradeMethod.ORDER ? "Buy order" : "Instabuy") : buyRoute;
				e.lines.add(String.format(Locale.ROOT, "Buy %s× %s: %s", fmtCount(in.perCraft), name(in.id), q.description()));
			}
			e.costPerCraft += unit * in.perCraft;
		}
		if (plan.coinsPerCraft > 0) {
			e.lines.add("Coin cost per craft: " + Coins.full(plan.coinsPerCraft));
		}
		if (!(e.costPerCraft > 0)) {
			return null;
		}

		String sellRoute;
		if (plan.sink == Source.NPC) {
			e.revenuePerCraft = plan.npcSellPrice * plan.outCount;
			sellRoute = "Sell to NPC";
			e.lines.add("Sell to NPC @ " + Coins.full(plan.npcSellPrice) + " (no tax)");
		} else {
			Product out = bazaar.get(plan.outputId);
			if (out == null || MarketGuard.disposeWeekVolume(out, s.sellMethod) < s.minWeeklyVolume) {
				return null;
			}
			MarketGuard.Quote q = guard.dispose(out, s.sellMethod, craftsPerHour * plan.outCount);
			if (q == null) {
				return null;
			}
			e.flags.addAll(q.flags());
			e.revenuePerCraft = q.unitPrice() * (1 - tax) * plan.outCount;
			double rate = q.ratePerHour() / plan.outCount;
			if (rate < e.marketRate) {
				e.marketRate = rate;
				e.marketLimitedBy = "market volume of " + name(plan.outputId) + " (" + fmtShare() + ")";
			}
			sellRoute = s.sellMethod == TradeMethod.ORDER ? "Sell offer" : "Instasell";
			e.lines.add(String.format(Locale.ROOT, "Sell %s× %s: %s, %s after %s%% tax",
					fmtCount(plan.outCount), name(plan.outputId), q.description(),
					Coins.full(q.unitPrice() * (1 - tax)), trimNum(s.taxPercent)));
			e.lines.add("Weekly volume: " + Coins.compact(out.instaBuyWeek()) + " instabuys · "
					+ Coins.compact(out.instaSellWeek()) + " instasells · history: " + historyText(out));
		}
		String middle = switch (s.mode) {
			case CRAFT -> " → craft";
			case FORGE -> " → forge";
			default -> "";
		};
		e.route = buyRoute + middle + " → " + sellRoute;
		return e;
	}

	private String historyText(Product p) {
		PriceHistory.Window w = guard.window(p);
		if (w.samples() == 0) {
			return "none yet";
		}
		return w.samples() + " samples / " + formatDuration(w.spanSec());
	}

	private String fmtShare() {
		return trimNum(s.marketSharePercent) + "% share";
	}

	private static String fmtCount(double n) {
		return n == Math.rint(n) ? Long.toString((long) n) : String.format(Locale.ROOT, "%.2f", n);
	}

	private static String trimNum(double v) {
		return v == Math.rint(v) ? Long.toString((long) v) : String.format(Locale.ROOT, "%s", v);
	}

	public static String formatDuration(long sec) {
		if (sec < 60) {
			return sec + "s";
		}
		if (sec < 3600) {
			return (sec / 60) + "m";
		}
		if (sec < 86400) {
			long h = sec / 3600;
			long m = (sec % 3600) / 60;
			return m == 0 ? h + "h" : h + "h " + m + "m";
		}
		long d = sec / 86400;
		long h = (sec % 86400) / 3600;
		return h == 0 ? d + "d" : d + "d " + h + "h";
	}
}
