package com.bazaarflipper.core.calc;

public enum SortOrder {
	PROFIT_PER_HOUR("Profit/h"),
	PROFIT_PER_ITEM("Profit/item"),
	ROI("ROI %"),
	SAFEST("Safest");

	public final String label;

	SortOrder(String label) {
		this.label = label;
	}

	public SortOrder next() {
		return values()[(ordinal() + 1) % values().length];
	}
}
