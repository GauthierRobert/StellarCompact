package com.stellarcompact.engine.state;

/**
 * Side of a {@link MarketOrder} in a hub's order book (economy 02 section 4).
 */
public enum MarketSide {
    /** A limit buy: BUY n resource at price <= p. */
    BUY,
    /** A limit sell: SELL n resource at price >= p. */
    SELL
}
