package com.bazaarflipper.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class CoinsTest {
	@Test
	void parsesSuffixes() {
		assertEquals(10_000_000, Coins.parse("10m"));
		assertEquals(2_500_000_000.0, Coins.parse("2.5B"));
		assertEquals(750_000, Coins.parse(" 750k "));
		assertEquals(1_000_000, Coins.parse("1,000,000"));
		assertEquals(1_000_000, Coins.parse("1e6"));
	}

	@Test
	void rejectsGarbage() {
		assertTrue(Double.isNaN(Coins.parse("abc")));
		assertTrue(Double.isNaN(Coins.parse("-5")));
		assertTrue(Double.isNaN(Coins.parse("")));
		assertTrue(Double.isNaN(Coins.parse("NaN")));
		assertTrue(Double.isNaN(Coins.parse("Infinity")));
	}

	@Test
	void compactFormat() {
		assertEquals("1.5M", Coins.compact(1_500_000));
		assertEquals("12.3k", Coins.compact(12_345));
		assertEquals("999", Coins.compact(999));
		assertEquals("2.1B", Coins.compact(2_100_000_000.0));
		assertEquals("-4k", Coins.compact(-4000));
		assertEquals("0.5", Coins.compact(0.5));
	}
}
