package com.bazaarflipper.core.calc;

/** All user settings. Public fields so Gson can (de)serialize the config file directly. */
public final class FlipSettings {
	public FlipMode mode = FlipMode.BAZAAR_FLIP;
	public TradeMethod buyMethod = TradeMethod.ORDER;
	public TradeMethod sellMethod = TradeMethod.ORDER;
	public SortOrder sort = SortOrder.PROFIT_PER_HOUR;

	/** Max coins tied up at once. */
	public double budget = 10_000_000;
	/** Max items you want to handle per hour (0 = unlimited). */
	public double maxItemsPerHour = 50_000;
	/** Ignore items whose relevant weekly volume is below this. */
	public double minWeeklyVolume = 20_000;
	/** Share of the market's hourly volume you expect to capture (competition). */
	public double marketSharePercent = 5.0;
	/** Bazaar tax on sales: 1.25 by default, 1.125 / 1.0 with Bazaar Flipper account upgrades. */
	public double taxPercent = 1.25;
	/** Hide results below this profit per hour. */
	public double minProfitPerHour = 0;

	/** Safe mode applies the manipulation guards to prices and hides results above {@link #maxRisk}. */
	public boolean safeMode = true;
	public int maxRisk = 50;
	/** How far (percent) a live price may move away from its recent median before it is capped. */
	public double guardTolerancePercent = 15;
	/** History samples needed before history-based guards are used. */
	public int minHistorySamples = 5;
	/** Hours of history used for medians. */
	public double historyWindowHours = 6;

	public int forgeSlots = 2;
	/** Allow buying craft ingredients from NPC shops when that is cheaper (or the item is not on the Bazaar). */
	public boolean npcIngredients = true;
	public String search = "";

	public int refreshSeconds = 30;

	public boolean hudEnabled = true;
	public int hudLines = 5;
	public int hudX = 4;
	public int hudY = 4;

	public boolean alertsEnabled = false;
	public double alertMinProfitPerHour = 5_000_000;

	/** Clamps every value into a sane range (protects against a hand-edited or corrupt config). */
	public FlipSettings sanitize() {
		if (mode == null) {
			mode = FlipMode.BAZAAR_FLIP;
		}
		if (buyMethod == null) {
			buyMethod = TradeMethod.ORDER;
		}
		if (sellMethod == null) {
			sellMethod = TradeMethod.ORDER;
		}
		if (sort == null) {
			sort = SortOrder.PROFIT_PER_HOUR;
		}
		budget = clamp(budget, 1, 1e13, 10_000_000);
		maxItemsPerHour = clamp(maxItemsPerHour, 0, 1e9, 50_000);
		minWeeklyVolume = clamp(minWeeklyVolume, 0, 1e12, 20_000);
		marketSharePercent = clamp(marketSharePercent, 0.1, 100, 5);
		taxPercent = clamp(taxPercent, 0, 10, 1.25);
		minProfitPerHour = clamp(minProfitPerHour, 0, 1e12, 0);
		maxRisk = (int) clamp(maxRisk, 0, 100, 50);
		guardTolerancePercent = clamp(guardTolerancePercent, 1, 100, 15);
		minHistorySamples = (int) clamp(minHistorySamples, 1, 200, 5);
		historyWindowHours = clamp(historyWindowHours, 0.5, 24, 6);
		forgeSlots = (int) clamp(forgeSlots, 1, 7, 2);
		if (search == null) {
			search = "";
		}
		refreshSeconds = (int) clamp(refreshSeconds, 20, 600, 30);
		hudLines = (int) clamp(hudLines, 1, 15, 5);
		hudX = (int) clamp(hudX, 0, 4000, 4);
		hudY = (int) clamp(hudY, 0, 4000, 4);
		alertMinProfitPerHour = clamp(alertMinProfitPerHour, 0, 1e12, 5_000_000);
		return this;
	}

	private static double clamp(double v, double min, double max, double fallback) {
		if (Double.isNaN(v) || Double.isInfinite(v)) {
			return fallback;
		}
		return Math.max(min, Math.min(max, v));
	}

	public FlipSettings copy() {
		FlipSettings c = new FlipSettings();
		c.mode = mode;
		c.buyMethod = buyMethod;
		c.sellMethod = sellMethod;
		c.sort = sort;
		c.budget = budget;
		c.maxItemsPerHour = maxItemsPerHour;
		c.minWeeklyVolume = minWeeklyVolume;
		c.marketSharePercent = marketSharePercent;
		c.taxPercent = taxPercent;
		c.minProfitPerHour = minProfitPerHour;
		c.safeMode = safeMode;
		c.maxRisk = maxRisk;
		c.guardTolerancePercent = guardTolerancePercent;
		c.minHistorySamples = minHistorySamples;
		c.historyWindowHours = historyWindowHours;
		c.forgeSlots = forgeSlots;
		c.npcIngredients = npcIngredients;
		c.search = search;
		c.refreshSeconds = refreshSeconds;
		c.hudEnabled = hudEnabled;
		c.hudLines = hudLines;
		c.hudX = hudX;
		c.hudY = hudY;
		c.alertsEnabled = alertsEnabled;
		c.alertMinProfitPerHour = alertMinProfitPerHour;
		return c;
	}
}
