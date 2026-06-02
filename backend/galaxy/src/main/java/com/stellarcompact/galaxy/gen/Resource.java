package com.stellarcompact.galaxy.gen;

/**
 * The four physical, produced resources a planet can yield (economy 02; mirrors
 * engine {@code com.stellarcompact.engine.state.PhysicalResource}).
 *
 * <p>Galaxy-local on purpose so the galaxy module stays free of any engine
 * dependency. Names and order are kept identical to the engine enum so the E2-05
 * promotion boundary can map them one-to-one by {@code name()}.
 */
public enum Resource {
    ENERGY,
    MINERALS,
    FOOD,
    TECH
}
