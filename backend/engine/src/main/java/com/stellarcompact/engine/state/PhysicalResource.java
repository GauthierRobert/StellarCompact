package com.stellarcompact.engine.state;

/**
 * The four <em>physical</em>, tradeable resources (economy 02 section 1).
 * Influence is deliberately excluded: it is political capital that accrues and
 * is granted via treaty terms, never posted on the open market. A
 * {@link MarketOrder} therefore trades only one of these.
 */
public enum PhysicalResource {
    ENERGY,
    MINERALS,
    FOOD,
    TECH
}
