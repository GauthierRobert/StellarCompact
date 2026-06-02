package com.stellarcompact.api.galaxy;

/**
 * The seam through which the overlay endpoint reads live active-game state, kept
 * bbox- and sinceTick-scoped so the api module never pulls a full snapshot
 * (principle 3: scale discipline; the simulation touches only active systems).
 *
 * <p>This is an interface on purpose: the real implementation wires to the live
 * match / engine state (E6-01) and is the same shape E6-04 streams over the
 * websocket. Until that lands, {@link InMemoryGalaxyStateSource} supplies a small
 * deterministic stub so the endpoint contract is correct and testable now.
 *
 * <p>The implementation MUST return only systems whose position falls inside
 * {@code bbox} and whose state changed at a tick {@code > sinceTick} - a thin
 * diff, not the whole game state.
 */
public interface GalaxyStateSource {

    /**
     * @param gameId    the live match id
     * @param bbox      the region of interest (galaxy units)
     * @param sinceTick changed-since lower bound (exclusive); 0 = full visible set
     * @return the thin, bbox-scoped, since-scoped overlay diff; never null
     */
    OverlayPayload overlay(String gameId, TileGrid.Bbox bbox, long sinceTick);
}
