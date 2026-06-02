package com.stellarcompact.engine.map;

import com.stellarcompact.engine.state.SystemId;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The engine-side view of the natural-lane travel skeleton (game-design 01
 * section 3): an immutable, undirected, tick-weighted graph keyed by
 * {@link SystemId}, the primitive E1-09 movement, interception and the validator
 * adjacency/reachability checks consume.
 *
 * <p><b>Why the engine carries its own lane type and does not import the galaxy
 * module.</b> Both {@code engine} and {@code galaxy} are framework-free libraries
 * that must not depend on one another (the same separation the engine RNG keeps
 * from {@code galaxy.SeedHash}). The galaxy module owns {@code LaneGraph} keyed by a
 * numeric {@code starId}; the engine owns this {@code LaneNetwork} keyed by the
 * domain {@link SystemId}. The orchestrator (which legitimately depends on both)
 * projects the galaxy graph for the active region into a {@code LaneNetwork} via the
 * {@link Builder} and hands it to {@code resolve}/{@code validate} as a separate,
 * static-per-match input - exactly as the {@code BalanceProfile} is passed
 * separately rather than embedded in {@code GameState}. The heavy, static map never
 * enters the per-tick snapshot or its golden hash.
 *
 * <p><b>Determinism / purity.</b> Pure data plus pure graph lookups - no I/O, no
 * clock, no randomness. Adjacency lists preserve the deterministic order the builder
 * inserted neighbours in, so a fold over the network is replay-stable regardless of
 * map iteration order. Lane lengths are the travel cost in ticks, intrinsic to the
 * graph (derived from real distance at generation), so they belong here, not in the
 * balance profile (which holds gameplay tunables, not map geometry).
 *
 * <p>The {@link #EMPTY} network is the explicit "no lane graph supplied" value: it
 * has no systems and no lanes, so {@link #contains} is always false. The validator
 * treats an empty network as "adjacency/reachability cannot be proven here, fall
 * back to shape-only checks" (the pre-E1-09 behaviour), so legacy callers that pass
 * {@code EMPTY} keep working.
 */
public final class LaneNetwork {

    /** The explicit empty network: no systems, no lanes. */
    public static final LaneNetwork EMPTY = new LaneNetwork(Map.of());

    /** system id -> (neighbour id -> lane length in ticks), insertion-ordered, unmodifiable. */
    private final Map<SystemId, Map<SystemId, Integer>> adjacency;

    private LaneNetwork(Map<SystemId, Map<SystemId, Integer>> adjacency) {
        this.adjacency = adjacency; // already an unmodifiable deep copy from the builder
    }

    /** @return true iff {@code system} is a node of this network. */
    public boolean contains(SystemId system) {
        return adjacency.containsKey(system);
    }

    /** @return true iff this network has no nodes (the {@link #EMPTY} sentinel state). */
    public boolean isEmpty() {
        return adjacency.isEmpty();
    }

    /** @return the number of systems (nodes) in the network. */
    public int systemCount() {
        return adjacency.size();
    }

    /**
     * @return true iff {@code a} and {@code b} are joined by a direct lane (a single
     * hop) - the adjacency primitive the validator uses for {@code Explore} and for
     * checking each consecutive hop of a {@code MoveFleet} path is a real lane.
     */
    public boolean adjacent(SystemId a, SystemId b) {
        Map<SystemId, Integer> ns = adjacency.get(a);
        return ns != null && ns.containsKey(b);
    }

    /**
     * @return the travel cost in ticks of the direct lane between {@code a} and
     * {@code b}, or empty if no such lane exists.
     */
    public Optional<Integer> lengthTicks(SystemId a, SystemId b) {
        Map<SystemId, Integer> ns = adjacency.get(a);
        if (ns == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(ns.get(b));
    }

    /**
     * @return the neighbouring system ids of {@code a} reachable in one lane hop, in
     * the deterministic order the builder inserted them; unmodifiable, never null.
     */
    public List<SystemId> neighbours(SystemId a) {
        Map<SystemId, Integer> ns = adjacency.get(a);
        return ns == null ? List.of() : List.copyOf(ns.keySet());
    }

    /**
     * Validate that {@code path} (the ordered systems a fleet traverses, NOT
     * including the origin - per the {@code MoveFleet.path} contract) is a sequence
     * of real lane hops starting from {@code origin}, and sum its travel ticks.
     *
     * <p>The total is the summed lengths of {@code origin->path[0]},
     * {@code path[0]->path[1]}, ... This is the deterministic ETA E1-09 uses. If any
     * consecutive pair is not a direct lane, the result is empty (the path is not
     * traversable) - the validator turns that into {@code NO_PATH}.
     *
     * @param origin the fleet current location (path entries are the hops away from it)
     * @param path   the ordered destinations after the origin (non-empty)
     * @return the summed travel ticks of the whole walk, or empty if a hop is not a lane
     */
    public Optional<Long> pathTicks(SystemId origin, List<SystemId> path) {
        if (origin == null || path == null || path.isEmpty()) {
            return Optional.empty();
        }
        long total = 0;
        SystemId from = origin;
        for (SystemId to : path) {
            Optional<Integer> hop = lengthTicks(from, to);
            if (hop.isEmpty()) {
                return Optional.empty();
            }
            total += hop.get();
            from = to;
        }
        return Optional.of(total);
    }

    /** @return a fresh builder for assembling a network lane by lane. */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Mutable assembler for a {@link LaneNetwork}. The orchestrator drives this when
     * projecting the galaxy {@code LaneGraph} for the active region into engine
     * terms; tests drive it to author small hand-made graphs. Each
     * {@link #addLane} adds an undirected, tick-weighted edge in both directions.
     */
    public static final class Builder {
        private final Map<SystemId, Map<SystemId, Integer>> adjacency = new LinkedHashMap<>();

        private Builder() {
        }

        /**
         * Add an undirected lane {@code a <-> b} of {@code lengthTicks} travel cost.
         * Both endpoints become nodes; both directions get the same length.
         *
         * @param a           one endpoint
         * @param b           the other endpoint (must differ from {@code a})
         * @param lengthTicks travel cost in ticks (must be {@code > 0})
         * @return this builder
         */
        public Builder addLane(SystemId a, SystemId b, int lengthTicks) {
            if (a == null || b == null) {
                throw new IllegalArgumentException("lane endpoints must both be set");
            }
            if (a.equals(b)) {
                throw new IllegalArgumentException("a lane cannot connect a system to itself: " + a.value());
            }
            if (lengthTicks <= 0) {
                throw new IllegalArgumentException("lane lengthTicks must be > 0 but was " + lengthTicks);
            }
            adjacency.computeIfAbsent(a, k -> new LinkedHashMap<>()).put(b, lengthTicks);
            adjacency.computeIfAbsent(b, k -> new LinkedHashMap<>()).put(a, lengthTicks);
            return this;
        }

        /** @return the immutable {@link LaneNetwork}; do not reuse the builder after. */
        public LaneNetwork build() {
            Map<SystemId, Map<SystemId, Integer>> copy = new LinkedHashMap<>(adjacency.size() * 2);
            for (Map.Entry<SystemId, Map<SystemId, Integer>> e : adjacency.entrySet()) {
                copy.put(e.getKey(), Collections.unmodifiableMap(new LinkedHashMap<>(e.getValue())));
            }
            return new LaneNetwork(Collections.unmodifiableMap(copy));
        }
    }
}
