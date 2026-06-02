package com.stellarcompact.engine.state;

/**
 * Operational status of a {@link Route}. A route can be throttled by a blockade
 * or interrupted entirely while remaining a known game object (game-design 05
 * section 5 sub-war pressure).
 */
public enum RouteStatus {
    /** Carrying its configured throughput normally. */
    ACTIVE,
    /** Throughput choked by a hostile blockade. */
    BLOCKADED,
    /** Suspended (e.g. an endpoint lost / treaty lapsed) but not deleted. */
    SUSPENDED
}
