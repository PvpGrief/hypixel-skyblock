package com.bazaarflipper.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.bazaarflipper.core.api.ApiException;
import com.bazaarflipper.core.api.BazaarParser;
import com.bazaarflipper.core.model.BazaarSnapshot;
import com.bazaarflipper.core.model.Product;

class BazaarParserTest {
	@Test
	void mapsHypixelSidesToBuyOrdersAndSellOffers() {
		BazaarSnapshot s = Fixtures.snapshot(1000,
				Fixtures.product("WHEAT").bid(5, 100).bid(6, 10).ask(8, 50).ask(7, 20).weekly(1_000, 2_000));
		Product p = s.get("WHEAT");
		// sell_summary are buy orders, re-sorted highest first
		assertEquals(6, p.topBuyOrder());
		// buy_summary are sell offers, re-sorted lowest first
		assertEquals(7, p.lowestSellOffer());
		assertEquals(1_000, p.instaBuyWeek());
		assertEquals(2_000, p.instaSellWeek());
	}

	@Test
	void dropsInvalidLevelsAndCrossedOrders() {
		String json = Fixtures.json(1, Fixtures.product("X")
				.bid(-5, 10).bid(0, 10).bid(100, 0).bid(10, 5).bid(30, 5) // 30 crosses the ask (20)
				.ask(20, 5).ask(25, 5));
		BazaarSnapshot s;
		try {
			s = BazaarParser.parse(json, 1);
		} catch (ApiException e) {
			throw new AssertionError(e);
		}
		Product p = s.get("X");
		assertEquals(1, p.buyOrders().size());
		assertEquals(10, p.topBuyOrder());
		assertEquals(2, p.sellOffers().size());
	}

	@Test
	void rejectsUnsuccessfulOrMalformedResponses() {
		assertThrows(ApiException.class, () -> BazaarParser.parse("{\"success\":false,\"cause\":\"x\"}", 0));
		assertThrows(ApiException.class, () -> BazaarParser.parse("not json", 0));
		assertThrows(ApiException.class, () -> BazaarParser.parse("{\"success\":true,\"products\":{}}", 0));
	}

	@Test
	void nonNumericFieldsDoNotCrash() {
		String json = "{\"success\":true,\"lastUpdated\":5,\"products\":{\"A\":{\"sell_summary\":[{\"amount\":\"x\",\"pricePerUnit\":\"NaN\",\"orders\":1}],"
				+ "\"buy_summary\":[{\"amount\":3,\"pricePerUnit\":2.5,\"orders\":1}],\"quick_status\":{\"buyMovingWeek\":\"oops\"}}}}";
		BazaarSnapshot s = assertDoesNotThrowParse(json);
		Product p = s.get("A");
		assertTrue(p.buyOrders().isEmpty());
		assertEquals(2.5, p.lowestSellOffer());
		assertEquals(0, p.instaBuyWeek());
	}

	private static BazaarSnapshot assertDoesNotThrowParse(String json) {
		try {
			return BazaarParser.parse(json, 0);
		} catch (ApiException e) {
			throw new AssertionError(e);
		}
	}
}
