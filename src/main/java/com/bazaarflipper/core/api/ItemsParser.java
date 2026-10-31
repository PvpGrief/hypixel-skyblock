package com.bazaarflipper.core.api;

import java.util.HashMap;
import java.util.Map;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import com.bazaarflipper.core.model.ItemInfo;

/** Parses {@code GET https://api.hypixel.net/v2/resources/skyblock/items} (names and NPC sell prices). */
public final class ItemsParser {
	private ItemsParser() {
	}

	public static Map<String, ItemInfo> parse(String json) throws ApiException {
		JsonObject root;
		try {
			root = JsonParser.parseString(json).getAsJsonObject();
		} catch (JsonParseException | IllegalStateException e) {
			throw new ApiException("Items response is not valid JSON", e);
		}
		if (!root.has("success") || !root.get("success").getAsBoolean()) {
			throw new ApiException("Items API returned success=false");
		}
		JsonElement items = root.get("items");
		if (items == null || !items.isJsonArray()) {
			throw new ApiException("Items response has no items array");
		}
		Map<String, ItemInfo> out = new HashMap<>();
		for (JsonElement el : items.getAsJsonArray()) {
			if (!el.isJsonObject()) {
				continue;
			}
			JsonObject o = el.getAsJsonObject();
			if (!o.has("id")) {
				continue;
			}
			String id = o.get("id").getAsString();
			String name = o.has("name") ? stripColors(o.get("name").getAsString()) : id;
			double npc = BazaarParser.getDouble(o, "npc_sell_price");
			if (!(npc > 0) || npc > BazaarParser.MAX_PRICE) {
				npc = 0;
			}
			out.put(id, new ItemInfo(id, name, npc));
		}
		if (out.isEmpty()) {
			throw new ApiException("Items response contained no items");
		}
		return out;
	}

	public static String stripColors(String s) {
		return s == null ? "" : s.replaceAll("§.", "").trim();
	}
}
