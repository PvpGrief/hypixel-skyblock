package com.bazaarflipper.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

import org.junit.jupiter.api.Test;

import com.bazaarflipper.core.market.PriceHistory;

class PriceHistoryTest {
	static void fill(PriceHistory h, long startSec, int n, double bid, double ask) {
		for (int i = 0; i < n; i++) {
			h.record(Fixtures.snapshot((startSec + i * 60L) * 1000,
					Fixtures.product("X").bid(bid, 5000).ask(ask, 5000)));
		}
	}

	@Test
	void respectsMinimumInterval() {
		PriceHistory h = new PriceHistory(60);
		assertTrue(h.record(Fixtures.snapshot(1_000_000, Fixtures.product("X").bid(1, 10).ask(2, 10))));
		assertFalse(h.record(Fixtures.snapshot(1_030_000, Fixtures.product("X").bid(1, 10).ask(2, 10))));
		assertTrue(h.record(Fixtures.snapshot(1_060_000, Fixtures.product("X").bid(1, 10).ask(2, 10))));
	}

	@Test
	void medianIgnoresAFewOutliers() {
		PriceHistory h = new PriceHistory(60);
		fill(h, 10_000, 9, 100, 120);
		h.record(Fixtures.snapshot((10_000 + 9 * 60L) * 1000, Fixtures.product("X").bid(100, 5000).ask(900, 5000)));
		PriceHistory.Window w = h.window("X", 10_000 + 600, 3600);
		assertEquals(10, w.samples());
		assertEquals(120, w.medianAsk(), 1e-6);
		assertEquals(100, w.medianBid(), 1e-6);
	}

	@Test
	void saveAndLoadRoundTrip() throws IOException {
		long now = 2_000_000;
		PriceHistory h = new PriceHistory(60);
		fill(h, now - 3600, 10, 100, 120);
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		h.save(out);
		PriceHistory loaded = new PriceHistory(60);
		loaded.load(new ByteArrayInputStream(out.toByteArray()), now);
		PriceHistory.Window w = loaded.window("X", now, 7200);
		assertEquals(10, w.samples());
		assertEquals(120, w.medianAsk(), 1e-6);
	}

	@Test
	void loadDropsSamplesOlderThanMaxAge() throws IOException {
		PriceHistory h = new PriceHistory(60);
		fill(h, 1_000, 5, 100, 120);
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		h.save(out);
		PriceHistory loaded = new PriceHistory(60);
		loaded.load(new ByteArrayInputStream(out.toByteArray()), 1_000 + PriceHistory.MAX_AGE_SEC * 2);
		assertEquals(0, loaded.productCount());
	}

	@Test
	void rejectsForeignFiles() {
		PriceHistory h = new PriceHistory(60);
		assertThrows(IOException.class, () -> h.load(new ByteArrayInputStream(new byte[]{1, 2, 3}), 0));
	}
}
