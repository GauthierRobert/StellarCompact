package com.stellarcompact.engine.state;

/**
 * Lifecycle of a {@link MarketOrder}. Orders rest in the book until matched by
 * price-time priority, fully filled, or cancelled (economy 02 section 4).
 */
public enum OrderStatus {
    /** Resting in the book, unmatched. */
    OPEN,
    /** Partially matched; remaining quantity still rests. */
    PARTIALLY_FILLED,
    /** Fully matched and settled. */
    FILLED,
    /** Withdrawn before being fully filled. */
    CANCELLED
}
