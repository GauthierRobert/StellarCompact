package com.stellarcompact.galaxy.gen;

/**
 * Thrown by {@link HomePlacementGenerator#place} when the galaxy cannot satisfy the
 * requested placement deterministically - e.g. there are too few cradle-biome home
 * candidates for the faction count, or no set of {@code factionCount} candidates can
 * simultaneously honour the minimum lane-hop separation and stay inside the quality
 * tolerance band.
 *
 * <p>The card requires this to <em>fail deterministically with a clear error</em>
 * rather than silently cram factions together or hand out unfair starts. The failure
 * is a pure function of {@code (gameSeed, graph, config)}: the same inputs always
 * throw (or always succeed) with the same message.
 */
public final class HomePlacementException extends RuntimeException {

    public HomePlacementException(String message) {
        super(message);
    }
}
