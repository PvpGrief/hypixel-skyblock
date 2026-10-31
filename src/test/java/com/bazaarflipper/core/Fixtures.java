package com.bazaarflipper.core;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import com.bazaarflipper.core.api.ApiException;
import com.bazaarflipper.core.api.BazaarParser;
import com.bazaarflipper.core.model.BazaarSnapshot;

/** Builds Hypixel-format Bazaar JSON for tests. */
public final class Fixtures {
	private Fixtures() {
	}

	public static final class P {
		final String id;
		final List<double[]> buyOrders = new ArrayList<>(); // price, amount, orders
		final List<double[]> sellOffers = new ArrayList<>();
		long instaBuyWeek = 168_000;
		long instaSellWeek = 168_000;
		int buyOrderCount = 50;
		int sellOfferCount = 50;

		P(String id) {
			this.id = id;
		}

		/** Adds a buy order level (someone wants to buy {@code amount} at {@code price}). */
		public P bid(double price, long amount, int orders) {
			buyOrders.add(new double[]{price, amount, orders});
			return this;
		}

		public P bid(double price, long amount) {
			return bid(price, amount, 1);
		}

		/** Adds a sell offer level. */
		public P ask(double price, long amount, int orders) {
			sellOffers.add(new double[]{price, amount, orders});
			return this;
		}

		public P ask(double price, long amount) {
			return ask(price, amount, 1);
		}

		public P weekly(long instaBuys, long instaSells) {
			instaBuyWeek = instaBuys;
			instaSellWeek = instaSells;
			return this;
		}

		public P counts(int buyOrders, int sellOffers) {
			buyOrderCount = buyOrders;
			sellOfferCount = sellOffers;
			return this;
		}
	}

	public static P product(String id) {
		return new P(id);
	}

	public static String json(long lastUpdated, P... products) {
		JsonObject root = new JsonObject();
		root.addProperty("success", true);
		root.addProperty("lastUpdated", lastUpdated);
		JsonObject ps = new JsonObject();
		for (P p : products) {
			JsonObject o = new JsonObject();
			o.addProperty("product_id", p.id);
			o.add("sell_summary", levels(p.buyOrders));
			o.add("buy_summary", levels(p.sellOffers));
			JsonObject qs = new JsonObject();
			qs.addProperty("productId", p.id);
			qs.addProperty("sellPrice", avg(p.buyOrders));
			qs.addProperty("buyPrice", avg(p.sellOffers));
			qs.addProperty("sellVolume", sum(p.buyOrders));
			qs.addProperty("buyVolume", sum(p.sellOffers));
			qs.addProperty("sellMovingWeek", p.instaSellWeek);
			qs.addProperty("buyMovingWeek", p.instaBuyWeek);
			qs.addProperty("sellOrders", p.buyOrderCount);
			qs.addProperty("buyOrders", p.sellOfferCount);
			o.add("quick_status", qs);
			ps.add(p.id, o);
		}
		root.add("products", ps);
		return root.toString();
	}

	public static BazaarSnapshot snapshot(long lastUpdated, P... products) {
		try {
			return BazaarParser.parse(json(lastUpdated, products), lastUpdated);
		} catch (ApiException e) {
			throw new AssertionError(e);
		}
	}

	private static JsonArray levels(List<double[]> ls) {
		JsonArray a = new JsonArray();
		for (double[] l : ls) {
			JsonObject o = new JsonObject();
			o.addProperty("amount", (long) l[1]);
			o.addProperty("pricePerUnit", l[0]);
			o.addProperty("orders", (int) l[2]);
			a.add(o);
		}
		return a;
	}

	private static double avg(List<double[]> ls) {
		double c = 0;
		double n = 0;
		for (double[] l : ls) {
			c += l[0] * l[1];
			n += l[1];
		}
		return n == 0 ? 0 : c / n;
	}

	private static long sum(List<double[]> ls) {
		long n = 0;
		for (double[] l : ls) {
			n += (long) l[1];
		}
		return n;
	}

	public static Map<String, Double> map(Object... kv) {
		Map<String, Double> m = new HashMap<>();
		for (int i = 0; i < kv.length; i += 2) {
			m.put((String) kv[i], ((Number) kv[i + 1]).doubleValue());
		}
		return m;
	}
}
