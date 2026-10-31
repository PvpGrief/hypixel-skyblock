package com.bazaarflipper.core.model;

import java.util.Map;

/**
 * An immutable, validated copy of one Hypixel Bazaar API response.
 *
 * @param lastUpdated server-side timestamp (ms) of the data
 * @param fetchedAt   local time (ms) when the response was received
 */
public record BazaarSnapshot(long lastUpdated, long fetchedAt, Map<String, Product> products) {
	public Product get(String id) {
		return products.get(id);
	}
}
