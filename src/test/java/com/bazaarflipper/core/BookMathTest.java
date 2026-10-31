package com.bazaarflipper.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.bazaarflipper.core.market.BookMath;
import com.bazaarflipper.core.model.OrderLevel;

class BookMathTest {
	@Test
	void skipsTinyOutlierAtTop() {
		List<OrderLevel> offers = List.of(new OrderLevel(50, 1, 1), new OrderLevel(120, 5000, 10), new OrderLevel(121, 5000, 10));
		BookMath.Best best = BookMath.effectiveBest(offers, 1000, false);
		assertEquals(120, best.price());
		assertEquals(1, best.skipped());
	}

	@Test
	void keepsLargeOrCloseTopLevels() {
		// Large top order: real liquidity, not bait.
		List<OrderLevel> big = List.of(new OrderLevel(50, 10_000, 1), new OrderLevel(120, 5000, 10));
		assertEquals(50, BookMath.effectiveBest(big, 1000, false).price());
		// Tiny but close to the next level: normal undercut, not bait.
		List<OrderLevel> close = List.of(new OrderLevel(119.9, 1, 1), new OrderLevel(120, 5000, 10));
		assertEquals(119.9, BookMath.effectiveBest(close, 1000, false).price());
	}

	@Test
	void buyOrderBaitIsSkippedToo() {
		List<OrderLevel> bids = List.of(new OrderLevel(150, 1, 1), new OrderLevel(100, 5000, 10));
		assertEquals(100, BookMath.effectiveBest(bids, 1000, true).price());
	}

	@Test
	void walkComputesVolumeWeightedAverage() {
		List<OrderLevel> offers = List.of(new OrderLevel(10, 100, 1), new OrderLevel(20, 100, 1));
		BookMath.Fill f = BookMath.walk(offers, 150);
		assertEquals(150, f.filled());
		assertEquals((10 * 100 + 20 * 50) / 150.0, f.avgPrice(), 1e-9);
		assertEquals(20, f.lastPrice());
		assertEquals(200, BookMath.walk(offers, 1000).filled());
	}
}
