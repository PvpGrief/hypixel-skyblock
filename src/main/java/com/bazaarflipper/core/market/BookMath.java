package com.bazaarflipper.core.market;

import java.util.List;

import com.bazaarflipper.core.model.OrderLevel;

/** Order-book helpers: bait-order filtering and volume-weighted fills. */
public final class BookMath {
	/** A top level smaller than this share of one hour of traded volume counts as "tiny". */
	static final double BAIT_FLOW_SHARE = 0.02;
	/** ...and is only skipped if it sits at least this far (relative) from the next level. */
	static final double BAIT_GAP = 0.05;
	/** Never skip more than this many levels. */
	static final int MAX_BAIT_LEVELS = 3;

	private BookMath() {
	}

	/** The best price after skipping tiny outlier ("bait") levels, plus how many levels were skipped. */
	public record Best(double price, int skipped) {
		public static final Best NONE = new Best(Double.NaN, 0);

		public boolean present() {
			return !Double.isNaN(price);
		}
	}

	/** Result of walking the book for {@code wanted} items. */
	public record Fill(double avgPrice, double filled, double lastPrice) {
	}

	/**
	 * Returns the best price of a book side, ignoring bait orders: a level is skipped when it holds very few
	 * items (relative to an hour of real trading) <i>and</i> is priced far away from the next level. Such
	 * orders are a classic way to make a spread look different than it really is.
	 *
	 * @param levels     book side, best first (buy orders: highest first, sell offers: lowest first)
	 * @param hourlyFlow items traded per hour on this side
	 * @param descending true for buy orders (better = higher), false for sell offers (better = lower)
	 */
	public static Best effectiveBest(List<OrderLevel> levels, double hourlyFlow, boolean descending) {
		if (levels.isEmpty()) {
			return Best.NONE;
		}
		double tiny = Math.max(2.0, hourlyFlow * BAIT_FLOW_SHARE);
		int skipped = 0;
		for (int i = 0; i < levels.size() - 1 && skipped < MAX_BAIT_LEVELS; i++) {
			OrderLevel cur = levels.get(i);
			OrderLevel next = levels.get(i + 1);
			double gap = descending
					? (cur.price() - next.price()) / next.price()
					: (next.price() - cur.price()) / next.price();
			if (cur.amount() < tiny && gap > BAIT_GAP) {
				skipped++;
			} else {
				break;
			}
		}
		return new Best(levels.get(skipped).price(), skipped);
	}

	/** Walks the book (best first) and returns the average price for up to {@code wanted} items. */
	public static Fill walk(List<OrderLevel> levels, double wanted) {
		if (levels.isEmpty() || !(wanted > 0)) {
			double p = levels.isEmpty() ? Double.NaN : levels.getFirst().price();
			return new Fill(p, 0, p);
		}
		double filled = 0;
		double cost = 0;
		double last = levels.getFirst().price();
		for (OrderLevel l : levels) {
			double take = Math.min(l.amount(), wanted - filled);
			cost += take * l.price();
			filled += take;
			last = l.price();
			if (filled >= wanted) {
				break;
			}
		}
		return new Fill(filled > 0 ? cost / filled : last, filled, last);
	}

	/** Number of orders within {@code pct} of the best price (a measure of competition). */
	public static int ordersNearTop(List<OrderLevel> levels, double pct, boolean descending) {
		if (levels.isEmpty()) {
			return 0;
		}
		double best = levels.getFirst().price();
		int n = 0;
		for (OrderLevel l : levels) {
			boolean near = descending ? l.price() >= best * (1 - pct) : l.price() <= best * (1 + pct);
			if (!near) {
				break;
			}
			n += l.orders();
		}
		return n;
	}
}
