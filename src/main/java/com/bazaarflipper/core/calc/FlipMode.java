package com.bazaarflipper.core.calc;

public enum FlipMode {
	BAZAAR_FLIP("Bazaar Flip", "Buy on the Bazaar and sell the same item back on the Bazaar."),
	CRAFT("Craft", "Buy the ingredients, craft, sell the result on the Bazaar."),
	FORGE("Forge", "Buy the ingredients, forge them (Dwarven Mines), sell the result."),
	BAZAAR_TO_NPC("Bazaar → NPC", "Buy on the Bazaar and sell to an NPC merchant."),
	NPC_TO_BAZAAR("NPC → Bazaar", "Buy from an NPC shop and sell on the Bazaar.");

	public final String label;
	public final String description;

	FlipMode(String label, String description) {
		this.label = label;
		this.description = description;
	}
}
