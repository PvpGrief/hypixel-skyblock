package com.bazaarflipper.core.calc;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;

import com.bazaarflipper.core.Coins;
import com.bazaarflipper.core.Stats;
import com.bazaarflipper.core.market.BookMath;
import com.bazaarflipper.core.market.PriceHistory;
import com.bazaarflipper.core.model.Product;

/**
 * Turns raw Bazaar data into conservative, manipulation-resistant prices.
 *
 * <p>Principles:
 * <ol>
 *     <li><b>What you pay instantly</b> comes from walking the real order book for the amount you would buy
 *     (an hour's worth), so a single cheap order cannot make a whole flip look profitable.</li>
 *     <li><b>What you receive later</b> (sell offers, and anything sold after a wait) is capped at the recent
 *     median plus a tolerance, so a pumped price is never trusted.</li>
 *     <li><b>What you bid with buy orders</b> is never assumed to be below the recent median minus the
 *     tolerance, so a temporary dump does not create fake margins.</li>
 *     <li>Tiny outlier ("bait") orders at the top of the book are ignored when reading best prices.</li>
 *     <li>Without enough history, the Hypixel {@code quick_status} averages (top 2% of volume) are used as
 *     the reference, and a missing history is shown as a risk flag.</li>
 * </ol>
 */
public final class MarketGuard {
	public static final double TICK = 0.1;
	static final double HOURS_PER_WEEK = 168.0;
	static final int MIN_ORDERS = 3;
	static final int CROWDED_ORDERS = 15;
	static final double VOLATILE_CV = 0.10;

	/** A price for one leg of a flip, with the rate at which it can be done and any risk flags. */
	public record Quote(double unitPrice, double ratePerHour, EnumSet<Flag> flags, String description) {
	}

	private final PriceHistory history;
	private final FlipSettings settings;
	private final long nowSec;
	private final Map<String, PriceHistory.Window> windows = new HashMap<>();

	public MarketGuard(PriceHistory history, FlipSettings settings, long nowSec) {
		this.history = history;
		this.settings = settings;
		this.nowSec = nowSec;
	}

	private double share() {
		return settings.marketSharePercent / 100.0;
	}

	private double tol() {
		return settings.guardTolerancePercent / 100.0;
	}

	public PriceHistory.Window window(Product p) {
		return windows.computeIfAbsent(p.id(), id ->
				history == null ? PriceHistory.Window.EMPTY
						: history.window(id, nowSec, (long) (settings.historyWindowHours * 3600)));
	}

	private boolean hasHistory(PriceHistory.Window w) {
		return w.samples() >= settings.minHistorySamples;
	}

	/** Items per hour flowing through the buy-order side (instasells). */
	public static double instaSellFlow(Product p) {
		return p.instaSellWeek() / HOURS_PER_WEEK;
	}

	/** Items per hour flowing through the sell-offer side (instabuys). */
	public static double instaBuyFlow(Product p) {
		return p.instaBuyWeek() / HOURS_PER_WEEK;
	}

	/** Weekly volume that limits buying {@code p} with {@code method}. */
	public static double acquireWeekVolume(Product p, TradeMethod method) {
		return method == TradeMethod.ORDER ? p.instaSellWeek() : p.instaBuyWeek();
	}

	/** Weekly volume that limits selling {@code p} with {@code method}. */
	public static double disposeWeekVolume(Product p, TradeMethod method) {
		return method == TradeMethod.ORDER ? p.instaBuyWeek() : p.instaSellWeek();
	}

	private EnumSet<Flag> commonFlags(Product p) {
		EnumSet<Flag> f = EnumSet.noneOf(Flag.class);
		PriceHistory.Window w = window(p);
		if (!hasHistory(w)) {
			f.add(Flag.NEW);
		} else if ((Stats.isUsable(w.bidCv()) && w.bidCv() > VOLATILE_CV) || (Stats.isUsable(w.askCv()) && w.askCv() > VOLATILE_CV)) {
			f.add(Flag.VOLATILE);
		}
		return f;
	}

	/** Reference buy-order price: history median, or the quick_status average when history is missing. */
	private double refBid(Product p) {
		PriceHistory.Window w = window(p);
		if (hasHistory(w) && Stats.isUsable(w.medianBid())) {
			return w.medianBid();
		}
		return p.instaSellAvg();
	}

	/** Reference sell-offer price: history median, or the quick_status average when history is missing. */
	private double refAsk(Product p) {
		PriceHistory.Window w = window(p);
		if (hasHistory(w) && Stats.isUsable(w.medianAsk())) {
			return w.medianAsk();
		}
		return p.instaBuyAvg();
	}

	/**
	 * Price and rate for buying {@code p} on the Bazaar.
	 *
	 * @param qtyPerHour how many you would buy per hour (used to walk the book for instant buys)
	 * @return null when the item cannot be bought this way
	 */
	public Quote acquire(Product p, TradeMethod method, double qtyPerHour) {
		EnumSet<Flag> flags = commonFlags(p);
		double rate = acquireWeekVolume(p, method) / HOURS_PER_WEEK * share();
		if (method == TradeMethod.ORDER) {
			BookMath.Best bid = BookMath.effectiveBest(p.buyOrders(), instaSellFlow(p), true);
			if (!bid.present()) {
				return null;
			}
			if (bid.skipped() > 0) {
				flags.add(Flag.BAIT);
			}
			double price = bid.price() + TICK;
			double ask = p.lowestSellOffer();
			if (Stats.isUsable(ask) && price >= ask) {
				price = bid.price();
			}
			double ref = refBid(p);
			if (Stats.isUsable(ref) && price < ref * (1 - tol())) {
				flags.add(Flag.DUMP);
				if (settings.safeMode) {
					price = ref * (1 - tol());
				}
			}
			if (p.buyOrderCount() < MIN_ORDERS) {
				flags.add(Flag.FEW_ORDERS);
			}
			if (BookMath.ordersNearTop(p.buyOrders(), 0.01, true) >= CROWDED_ORDERS) {
				flags.add(Flag.CROWDED);
			}
			return new Quote(price, rate, flags, "buy order @ " + Coins.full(price));
		}
		if (p.sellOffers().isEmpty()) {
			return null;
		}
		double want = Math.max(1, qtyPerHour);
		BookMath.Fill fill = BookMath.walk(p.sellOffers(), want);
		double price = fill.avgPrice();
		if (fill.filled() < want) {
			flags.add(Flag.THIN);
			// The rest has to be bought later, at no better than the current last level or the usual price.
			double later = Math.max(fill.lastPrice(), Stats.isUsable(refAsk(p)) ? refAsk(p) : fill.lastPrice());
			price = (fill.avgPrice() * fill.filled() + later * (want - fill.filled())) / want;
		}
		if (p.sellOfferCount() < MIN_ORDERS) {
			flags.add(Flag.FEW_ORDERS);
		}
		return new Quote(price, rate, flags, "instabuy @ avg " + Coins.full(price));
	}

	/**
	 * Price (before tax) and rate for selling {@code p} on the Bazaar.
	 *
	 * @return null when the item cannot be sold this way
	 */
	public Quote dispose(Product p, TradeMethod method, double qtyPerHour) {
		EnumSet<Flag> flags = commonFlags(p);
		double rate = disposeWeekVolume(p, method) / HOURS_PER_WEEK * share();
		PriceHistory.Window w = window(p);
		if (method == TradeMethod.ORDER) {
			BookMath.Best ask = BookMath.effectiveBest(p.sellOffers(), instaBuyFlow(p), false);
			double price;
			if (ask.present()) {
				if (ask.skipped() > 0) {
					flags.add(Flag.BAIT);
				}
				price = ask.price() - TICK;
				double bid = p.topBuyOrder();
				if (Stats.isUsable(bid) && price <= bid) {
					price = ask.price();
				}
			} else if (hasHistory(w) && Stats.isUsable(w.medianAsk())) {
				price = w.medianAsk();
				flags.add(Flag.THIN);
			} else {
				return null;
			}
			double ref = refAsk(p);
			if (Stats.isUsable(ref) && price > ref * (1 + tol())) {
				flags.add(Flag.SPIKE);
				if (settings.safeMode) {
					price = ref * (1 + tol());
				}
			}
			double bid = BookMath.effectiveBest(p.buyOrders(), instaSellFlow(p), true).price();
			if (Stats.isUsable(bid) && ask.present()) {
				double spread = ask.price() / bid;
				double usual = w.medianSpreadRatio();
				boolean wide = hasHistory(w) && Stats.isUsable(usual)
						? spread > 1.1 && spread > usual * 1.5
						: spread > 2.0;
				if (wide) {
					flags.add(Flag.SPREAD);
					if (!hasHistory(w)) {
						// No history to cap the price with: a very wide spread is exactly what a buy-out-and-relist
						// pump looks like, so treat it as a spike until history proves the spread is normal.
						flags.add(Flag.SPIKE);
					}
				}
			}
			if (p.sellOfferCount() < MIN_ORDERS) {
				flags.add(Flag.FEW_ORDERS);
			}
			if (BookMath.ordersNearTop(p.sellOffers(), 0.01, false) >= CROWDED_ORDERS) {
				flags.add(Flag.CROWDED);
			}
			return new Quote(price, rate, flags, "sell offer @ " + Coins.full(price));
		}
		if (p.buyOrders().isEmpty()) {
			return null;
		}
		double want = Math.max(1, qtyPerHour);
		BookMath.Fill fill = BookMath.walk(p.buyOrders(), want);
		double price = fill.avgPrice();
		if (fill.filled() < want) {
			flags.add(Flag.THIN);
			double later = Math.min(fill.lastPrice(), Stats.isUsable(refBid(p)) ? refBid(p) : fill.lastPrice());
			price = (fill.avgPrice() * fill.filled() + later * (want - fill.filled())) / want;
		}
		double ref = refBid(p);
		if (Stats.isUsable(ref) && price > ref * (1 + tol())) {
			flags.add(Flag.SPIKE);
			if (settings.safeMode) {
				price = ref * (1 + tol());
			}
		}
		if (p.buyOrderCount() < MIN_ORDERS) {
			flags.add(Flag.FEW_ORDERS);
		}
		return new Quote(price, rate, flags, "instasell @ avg " + Coins.full(price));
	}
}
