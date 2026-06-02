package com.stellarcompact.engine.state;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.Optional;

/**
 * A resting limit order in a trade hub's order book (economy 02 section 4;
 * data-model {@code market_order(hub_system_id, side, resource, qty, price,
 * faction_id, placed_tick, status)}).
 *
 * <p>Matching is price-time priority (E1-07): {@code price} is the price
 * priority, then {@code placedTick} the time priority, then {@code id} the final
 * deterministic tie-break so two orders placed on the same tick keep a total,
 * replay-stable order (never {@code HashMap} order). Trades clear at the
 * <em>resting</em> (maker) order's {@code price}. Only a {@link PhysicalResource}
 * can be traded - Influence is never market-traded (economy 02 section 1), which
 * the type already forbids.
 *
 * <p><b>Open book order vs directed offer (E1-07 security prereq).</b> The same
 * record carries both shapes, distinguished by {@code addressee}:
 * <ul>
 *   <li><b>Open order</b> ({@code addressee} empty): rests in the hub's public
 *       order book and may be matched against any crossing counter-order by the
 *       matcher; nobody "accepts" it by id.</li>
 *   <li><b>Directed offer</b> ({@code addressee} present): a peer-to-peer offer
 *       that only the named faction may {@code AcceptTrade}/{@code DeclineTrade}
 *       (validator gate); a wrong addressee or an empty addressee is rejected.</li>
 * </ul>
 * {@code expiresTick} is the last tick on which the order is live: the offer is
 * expired once {@code currentTick > expiresTick} (rejected {@code OFFER_EXPIRED}),
 * and the matcher skips expired orders so a stale order never settles.
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
        long expiresTick,
        Optional<FactionId> addressee,
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
        if (addressee == null) {
            throw new IllegalArgumentException("MarketOrder.addressee must be set (use Optional.empty())");
        }
        if (status == null) {
            throw new IllegalArgumentException("MarketOrder.status must be set");
        }
    }

    /**
     * @return {@code true} iff this order has an addressee (a directed peer offer).
     * {@code @JsonIgnore} so Jackson does not treat this derived accessor as a bean
     * property (the strict {@code @JsonIgnoreProperties(ignoreUnknown = false)} would
     * otherwise see a phantom {@code "directed"} field on the round-trip).
     */
    @JsonIgnore
    public boolean isDirected() {
        return addressee.isPresent();
    }

    /** @return {@code true} iff {@code currentTick} is past {@link #expiresTick}. */
    @JsonIgnore
    public boolean isExpired(long currentTick) {
        return currentTick > expiresTick;
    }

    /** @return {@code true} iff this order may still match/settle (open or partially filled). */
    @JsonIgnore
    public boolean isLive() {
        return status == OrderStatus.OPEN || status == OrderStatus.PARTIALLY_FILLED;
    }

    /** Copy-on-write: this order with a new remaining {@code quantity} and {@code status}. */
    public MarketOrder withFill(double newQuantity, OrderStatus newStatus) {
        return new MarketOrder(id, hubSystem, faction, side, resource, newQuantity, price,
                placedTick, expiresTick, addressee, newStatus);
    }
}
