package com.bazaarflipper.core.calc;

import java.util.List;
import java.util.Set;

/**
 * One ranked flip. All "per item" values are per output item.
 *
 * @param route          short description, e.g. "Buy order -> Sell offer"
 * @param costPerItem    coins spent per output item (ingredients + coin costs)
 * @param revenuePerItem coins received per output item after tax
 * @param itemsPerHour   sustainable output items per hour (after volume, budget and cap limits)
 * @param capitalNeeded  coins tied up to run this flip at {@code itemsPerHour}
 * @param limitedBy      what limits {@code itemsPerHour}
 * @param details        human-readable breakdown for the UI
 */
public record FlipResult(
		FlipMode mode,
		String id,
		String name,
		String route,
		double costPerItem,
		double revenuePerItem,
		double profitPerItem,
		double itemsPerHour,
		double profitPerHour,
		double capitalNeeded,
		double roi,
		int risk,
		Set<Flag> flags,
		String limitedBy,
		List<String> details
) {
}
