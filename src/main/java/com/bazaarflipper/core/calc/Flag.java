package com.bazaarflipper.core.calc;

/** Risk / manipulation flags attached to a flip. {@code weight} adds to the 0-100 risk score. */
public enum Flag {
	SPIKE("Spike", 35, "Sell price is far above its recent median (possible manipulation); revenue capped."),
	DUMP("Dump", 35, "Buy price is far below its recent median (possible dump or bait); cost raised."),
	SPREAD("Spread", 25, "Spread is much wider than usual for this item."),
	NEW("New", 15, "Not enough price history yet; only live-book checks applied."),
	VOLATILE("Volatile", 20, "Price moved a lot over the history window."),
	THIN("Thin", 15, "The order book cannot fill one hour of this trade at the shown price."),
	FEW_ORDERS("Few orders", 20, "Fewer than 3 orders on one side; easy to manipulate."),
	CROWDED("Crowded", 10, "Many competing orders at the top; fills will be slower."),
	BAIT("Bait", 5, "Tiny outlier orders at the top of the book were ignored."),
	NPC_LIMIT("NPC limit", 0, "NPC shops may limit how many you can buy per day.");

	public final String label;
	public final int weight;
	public final String description;

	Flag(String label, int weight, String description) {
		this.label = label;
		this.weight = weight;
		this.description = description;
	}
}
