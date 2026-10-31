package com.bazaarflipper.core.calc;

/** How you trade on the Bazaar: place an order and wait, or fill existing orders instantly. */
public enum TradeMethod {
	ORDER("Order"),
	INSTANT("Instant");

	public final String label;

	TradeMethod(String label) {
		this.label = label;
	}

	public TradeMethod next() {
		return this == ORDER ? INSTANT : ORDER;
	}
}
