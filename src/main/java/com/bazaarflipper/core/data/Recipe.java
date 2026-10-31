package com.bazaarflipper.core.data;

import java.util.Map;

/**
 * A crafting or Forge recipe.
 *
 * @param output      Bazaar product id of the result
 * @param outputCount items produced per craft
 * @param inputs      ingredient id -> amount per craft
 * @param coins       extra coin cost per craft (Forge recipes sometimes have one)
 * @param durationSec Forge duration in seconds (0 for normal crafting)
 * @param requirement unlock requirement text, e.g. "Requires: Diamond IV" (may be empty)
 */
public record Recipe(String output, double outputCount, Map<String, Double> inputs, double coins, int durationSec, String requirement) {
}
