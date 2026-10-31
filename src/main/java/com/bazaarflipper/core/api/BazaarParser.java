package com.bazaarflipper.core.api;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import com.bazaarflipper.core.model.BazaarSnapshot;
import com.bazaarflipper.core.model.OrderLevel;
import com.bazaarflipper.core.model.Product;

/**
 * Parses {@code GET https://api.hypixel.net/v2/skyblock/bazaar}.
 *
 * <p>Everything is validated: non-finite, zero or negative numbers are dropped, order books are re-sorted
 * (never trusted to arrive sorted) and crossed books are repaired, so a malformed or tampered response
 * cannot produce fake profits.
 */
public final class BazaarParser {
	/** Upper bound for any single price; anything above is treated as garbage. */
	static final double MAX_PRICE = 1e11;

	private BazaarParser() {
	}

	public static BazaarSnapshot parse(String json, long fetchedAt) throws ApiException {
		JsonObject root;
		try {
			root = JsonParser.parseString(json).getAsJsonObject();
		} catch (JsonParseException | IllegalStateException e) {
			throw new ApiException("Bazaar response is not valid JSON", e);
		}
		if (!root.has("success") || !root.get("success").getAsBoolean()) {
			String cause = root.has("cause") ? root.get("cause").getAsString() : "unknown";
			throw new ApiException("Bazaar API returned success=false (" + cause + ")");
		}
		long lastUpdated = getLong(root, "lastUpdated");
		JsonElement productsEl = root.get("products");
		if (productsEl == null || !productsEl.isJsonObject()) {
			throw new ApiException("Bazaar response has no products");
		}
		Map<String, Product> products = new HashMap<>();
		for (Map.Entry<String, JsonElement> e : productsEl.getAsJsonObject().entrySet()) {
			if (!e.getValue().isJsonObject()) {
				continue;
			}
			Product p = parseProduct(e.getKey(), e.getValue().getAsJsonObject());
			if (p != null) {
				products.put(p.id(), p);
			}
		}
		if (products.isEmpty()) {
			throw new ApiException("Bazaar response contained no usable products");
		}
		return new BazaarSnapshot(lastUpdated, fetchedAt, Collections.unmodifiableMap(products));
	}

	static Product parseProduct(String id, JsonObject o) {
		if (id == null || id.isBlank() || id.length() > 128) {
			return null;
		}
		// sell_summary = buy orders (instasell side), best = highest price
		List<OrderLevel> buyOrders = parseLevels(o.get("sell_summary"));
		buyOrders.sort(Comparator.comparingDouble(OrderLevel::price).reversed());
		// buy_summary = sell offers (instabuy side), best = lowest price
		List<OrderLevel> sellOffers = parseLevels(o.get("buy_summary"));
		sellOffers.sort(Comparator.comparingDouble(OrderLevel::price));

		// A crossed book (best buy order >= best sell offer) cannot exist on the real Bazaar; it means
		// stale/garbage levels. Drop crossing buy-order levels so we never assume an impossible spread.
		if (!buyOrders.isEmpty() && !sellOffers.isEmpty()) {
			double ask = sellOffers.getFirst().price();
			buyOrders.removeIf(l -> l.price() >= ask);
		}

		JsonObject qs = o.has("quick_status") && o.get("quick_status").isJsonObject()
				? o.getAsJsonObject("quick_status") : new JsonObject();
		return new Product(
				id,
				List.copyOf(buyOrders),
				List.copyOf(sellOffers),
				price(qs, "sellPrice"),
				price(qs, "buyPrice"),
				nonNegLong(qs, "sellVolume"),
				nonNegLong(qs, "buyVolume"),
				(int) Math.min(Integer.MAX_VALUE, nonNegLong(qs, "sellOrders")),
				(int) Math.min(Integer.MAX_VALUE, nonNegLong(qs, "buyOrders")),
				nonNegLong(qs, "sellMovingWeek"),
				nonNegLong(qs, "buyMovingWeek")
		);
	}

	private static List<OrderLevel> parseLevels(JsonElement el) {
		List<OrderLevel> out = new ArrayList<>();
		if (el == null || !el.isJsonArray()) {
			return out;
		}
		JsonArray arr = el.getAsJsonArray();
		for (JsonElement item : arr) {
			if (!item.isJsonObject()) {
				continue;
			}
			JsonObject lvl = item.getAsJsonObject();
			double price = getDouble(lvl, "pricePerUnit");
			long amount = getLong(lvl, "amount");
			long orders = getLong(lvl, "orders");
			if (!(price > 0) || price > MAX_PRICE || Double.isInfinite(price) || amount <= 0) {
				continue;
			}
			out.add(new OrderLevel(price, amount, (int) Math.max(1, Math.min(Integer.MAX_VALUE, orders))));
		}
		return out;
	}

	private static double price(JsonObject o, String key) {
		double v = getDouble(o, key);
		return (v > 0 && v <= MAX_PRICE) ? v : Double.NaN;
	}

	private static long nonNegLong(JsonObject o, String key) {
		return Math.max(0, getLong(o, key));
	}

	static double getDouble(JsonObject o, String key) {
		try {
			JsonElement e = o.get(key);
			if (e == null || !e.isJsonPrimitive()) {
				return Double.NaN;
			}
			double v = e.getAsDouble();
			return Double.isFinite(v) ? v : Double.NaN;
		} catch (NumberFormatException | UnsupportedOperationException e) {
			return Double.NaN;
		}
	}

	static long getLong(JsonObject o, String key) {
		double d = getDouble(o, key);
		if (Double.isNaN(d)) {
			return 0;
		}
		return (long) Math.max(Long.MIN_VALUE, Math.min(Long.MAX_VALUE, d));
	}
}
