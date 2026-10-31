package com.bazaarflipper.core.data;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Static SkyBlock data bundled with the mod (generated from the NotEnoughUpdates item repository by
 * {@code tools/gen_skyblock_data.py}): crafting recipes, Forge recipes, NPC shop prices and display names.
 * No prices from third parties are used; this file only describes <i>what</i> can be crafted or bought.
 */
public final class SkyblockData {
	public record NpcOffer(String id, double unitCost, String npc) {
	}

	private final List<Recipe> crafts;
	private final List<Recipe> forge;
	private final Map<String, NpcOffer> npcShop;
	private final Map<String, String> names;
	private final String source;

	public SkyblockData(List<Recipe> crafts, List<Recipe> forge, Map<String, NpcOffer> npcShop, Map<String, String> names, String source) {
		this.crafts = List.copyOf(crafts);
		this.forge = List.copyOf(forge);
		this.npcShop = Collections.unmodifiableMap(new HashMap<>(npcShop));
		this.names = Collections.unmodifiableMap(new HashMap<>(names));
		this.source = source;
	}

	public static SkyblockData empty() {
		return new SkyblockData(List.of(), List.of(), Map.of(), Map.of(), "none");
	}

	public static SkyblockData load(InputStream in) throws IOException {
		try (Reader r = new InputStreamReader(in, StandardCharsets.UTF_8)) {
			JsonObject root = JsonParser.parseReader(r).getAsJsonObject();
			List<Recipe> crafts = parseRecipes(root, "crafts");
			List<Recipe> forge = parseRecipes(root, "forge");
			Map<String, NpcOffer> npc = new HashMap<>();
			if (root.has("npcShop")) {
				for (JsonElement el : root.getAsJsonArray("npcShop")) {
					JsonObject o = el.getAsJsonObject();
					double cost = o.get("coins").getAsDouble();
					if (cost > 0 && Double.isFinite(cost)) {
						String id = o.get("id").getAsString();
						npc.put(id, new NpcOffer(id, cost, o.has("npc") ? o.get("npc").getAsString() : ""));
					}
				}
			}
			Map<String, String> names = new HashMap<>();
			if (root.has("names")) {
				for (Map.Entry<String, JsonElement> e : root.getAsJsonObject("names").entrySet()) {
					names.put(e.getKey(), e.getValue().getAsString());
				}
			}
			String source = root.has("source") ? root.get("source").getAsString() : "unknown";
			return new SkyblockData(crafts, forge, npc, names, source);
		} catch (RuntimeException e) {
			throw new IOException("Invalid skyblock data file", e);
		}
	}

	private static List<Recipe> parseRecipes(JsonObject root, String key) {
		List<Recipe> out = new ArrayList<>();
		if (!root.has(key)) {
			return out;
		}
		for (JsonElement el : root.getAsJsonArray(key)) {
			JsonObject o = el.getAsJsonObject();
			Map<String, Double> inputs = new LinkedHashMap<>();
			for (Map.Entry<String, JsonElement> in : o.getAsJsonObject("in").entrySet()) {
				double n = in.getValue().getAsDouble();
				if (n > 0) {
					inputs.merge(in.getKey(), n, Double::sum);
				}
			}
			double count = o.has("n") ? o.get("n").getAsDouble() : 1;
			if (inputs.isEmpty() || !(count > 0)) {
				continue;
			}
			out.add(new Recipe(
					o.get("out").getAsString(),
					count,
					Collections.unmodifiableMap(inputs),
					o.has("coins") ? o.get("coins").getAsDouble() : 0,
					o.has("sec") ? o.get("sec").getAsInt() : 0,
					o.has("req") ? o.get("req").getAsString() : ""));
		}
		return out;
	}

	public List<Recipe> crafts() {
		return crafts;
	}

	public List<Recipe> forge() {
		return forge;
	}

	public Map<String, NpcOffer> npcShop() {
		return npcShop;
	}

	public String source() {
		return source;
	}

	/** Bundled display name, or null. */
	public String bundledName(String id) {
		return names.get(id);
	}

	/** Readable fallback name built from a product id: ENCHANTMENT_ULTIMATE_WISE_5 -> "Ultimate Wise V". */
	public static String prettyId(String id) {
		String s = id;
		String suffix = "";
		if (s.startsWith("ENCHANTMENT_")) {
			s = s.substring("ENCHANTMENT_".length());
			int us = s.lastIndexOf('_');
			if (us > 0) {
				try {
					suffix = " " + roman(Integer.parseInt(s.substring(us + 1)));
					s = s.substring(0, us);
				} catch (NumberFormatException ignored) {
					// not a level suffix
				}
			}
			if (s.startsWith("ULTIMATE_") && !s.equals("ULTIMATE_WISE") && !s.equals("ULTIMATE_JERRY")) {
				s = s.substring("ULTIMATE_".length());
			}
		}
		StringBuilder b = new StringBuilder();
		for (String part : s.split("[_:]")) {
			if (part.isEmpty()) {
				continue;
			}
			if (!b.isEmpty()) {
				b.append(' ');
			}
			b.append(part.charAt(0)).append(part.substring(1).toLowerCase(Locale.ROOT));
		}
		return b + suffix;
	}

	static String roman(int n) {
		if (n <= 0 || n > 3999) {
			return Integer.toString(n);
		}
		int[] v = {1000, 900, 500, 400, 100, 90, 50, 40, 10, 9, 5, 4, 1};
		String[] r = {"M", "CM", "D", "CD", "C", "XC", "L", "XL", "X", "IX", "V", "IV", "I"};
		StringBuilder b = new StringBuilder();
		for (int i = 0; i < v.length; i++) {
			while (n >= v[i]) {
				n -= v[i];
				b.append(r[i]);
			}
		}
		return b.toString();
	}
}
