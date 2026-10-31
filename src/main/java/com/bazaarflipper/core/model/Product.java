package com.bazaarflipper.core.model;

import java.util.List;

/**
 * One Bazaar product, with the confusing Hypixel field names translated into what they mean for a player.
 *
 * <p>Hypixel API mapping:
 * <ul>
 *     <li>{@code sell_summary} = open <b>buy orders</b> (what you get when you instasell), best (highest) first</li>
 *     <li>{@code buy_summary} = open <b>sell offers</b> (what you pay when you instabuy), best (lowest) first</li>
 *     <li>{@code quick_status.sellMovingWeek} = items instasold in the last 7 days (= buy orders filled)</li>
 *     <li>{@code quick_status.buyMovingWeek} = items instabought in the last 7 days (= sell offers filled)</li>
 * </ul>
 *
 * @param buyOrders       open buy orders, highest price first (top 30 levels only)
 * @param sellOffers      open sell offers, lowest price first (top 30 levels only)
 * @param instaSellAvg    quick_status.sellPrice: weighted average of the top 2% of buy-order volume
 * @param instaBuyAvg     quick_status.buyPrice: weighted average of the top 2% of sell-offer volume
 * @param buyOrderVolume  total items wanted by all buy orders
 * @param sellOfferVolume total items offered by all sell offers
 * @param buyOrderCount   number of buy orders
 * @param sellOfferCount  number of sell offers
 * @param instaSellWeek   items instasold in the last 7 days (buy orders filled per week)
 * @param instaBuyWeek    items instabought in the last 7 days (sell offers filled per week)
 */
public record Product(
		String id,
		List<OrderLevel> buyOrders,
		List<OrderLevel> sellOffers,
		double instaSellAvg,
		double instaBuyAvg,
		long buyOrderVolume,
		long sellOfferVolume,
		int buyOrderCount,
		int sellOfferCount,
		long instaSellWeek,
		long instaBuyWeek
) {
	public double topBuyOrder() {
		return buyOrders.isEmpty() ? Double.NaN : buyOrders.getFirst().price();
	}

	public double lowestSellOffer() {
		return sellOffers.isEmpty() ? Double.NaN : sellOffers.getFirst().price();
	}
}
