package com.bazaarflipper.core.model;

/** One price level of the Bazaar order book: {@code amount} items across {@code orders} orders at {@code price}. */
public record OrderLevel(double price, long amount, int orders) {
}
