package com.stellarcompact.engine.state;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * A resting limit order in a trade hub's order book (economy 02 section 4;
 * data-model {@code market_order(hub_system_id, side, resource, qty, price,
 * faction_id, placed_tick, status)}).
 *
 * <p>Matching is price-time priority: {@code placedTick} (with submission order
 * as a tiebreak resolved upstream) gives the time priority, {@code price} the
 * price priority. Only a {@link PhysicalResource} can be traded - Influence is
 * never market-traded (economy 02 section 1).
 */
@JsonIgnoreProperties(ignoreUnknown = false)
public record MarketOrder(
        MarketOrderId id,
        SystemId hubSystem,
        FactionId faction,
        MarketSide side,
        PhysicalResource resource,
        double quantity,
        double price,
        long placedTick,
        OrderStatus status
) {
    public MarketOrder {
        if (id == null) {
            throw new IllegalArgumentException("MarketOrder.id must be set");
        }
        if (hubSystem == null) {
            throw new IllegalArgumentException("MarketOrder.hubSystem must be set");
        }
        if (faction == null) {
            throw new IllegalArgumentException("MarketOrder.faction must be set");
        }
        if (side == null) {
            throw new IllegalArgumentException("MarketOrder.side must be set");
        }
        if (resource == null) {
            throw new IllegalArgumentException("MarketOrder.resource must be set");
        }
        if (quantity < 0) {
            throw new IllegalArgumentException("MarketOrder.quantity must be >= 0");
        }
        if (price < 0) {
            throw new IllegalArgumentException("MarketOrder.price must be >= 0");
        }
        if (status == null) {
            throw new IllegalArgumentException("MarketOrder.status must be set");
        }
    }
}
