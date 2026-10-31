package com.bazaarflipper.core.model;

/** Static item data from the Hypixel items resource. {@code npcSellPrice} is 0 when the item cannot be sold to NPCs. */
public record ItemInfo(String id, String name, double npcSellPrice) {
}
