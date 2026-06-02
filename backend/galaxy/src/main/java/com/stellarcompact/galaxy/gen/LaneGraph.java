package com.stellarcompact.galaxy.gen;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * An immutable natural-lane graph over a bounded playable region (game-design 01
 * section 3): the static travel skeleton E1-09 movement and pathfinding consume.
 *
 * <p><strong>Shape.</strong> Nodes are star ids ({@link Star#id()}); edges are
 * {@link Lane}s (undirected, weighted by travel ticks). The graph is built by
 * {@link LaneGraphGenerator#generate} as a pure function of {@code (seed,
 * region)} and is connected over its region (see that class), so any region
 * system can reach any other.
 *
 * <p><strong>Immutability.</strong> All collections handed in are defensively
 * copied into unmodifiable views in the constructor; {@code lanes()} and
 * {@code lanesFrom(..)} return unmodifiable lists. The graph holds no mutable
 * state, so it is safe to share across threads and reproducible to fold for
 * golden tests.
 *
 * <p><strong>Separation.</strong> This is the data structure only. Shortest-path
 * lives in {@link LanePathfinder} so the graph and the pathfinder stay cleanly
 * separated (the graph is reused across many path queries).
 *
 * <p><strong>Determinism of iteration.</strong> {@link #starIds()} and
 * {@link #lanes()} preserve the deterministic order the generator produced
 * (insertion order of a {@link LinkedHashMap}), so a canonical fold over the
 * graph is stable per seed.
 */
public final class LaneGraph {

    /** star id -> its incident lanes, in deterministic order; unmodifiable. */
    private final Map<Long, List<Lane>> adjacency;

    /** every lane once, in canonical (a<b) form, deterministic order; unmodifiable. */
    private final List<Lane> lanes;

    /**
     * @param adjacency star id -> incident lanes (a star with no lanes maps to an
     *                  empty list); copied defensively
     * @param lanes     each lane once in canonical {@code a<b} orientation; copied
     */
    LaneGraph(Map<Long, List<Lane>> adjacency, List<Lane> lanes) {
        // Defensive, order-preserving deep copy into unmodifiable views.
        Map<Long, List<Lane>> adj = new LinkedHashMap<>(adjacency.size() * 2);
        for (Map.Entry<Long, List<Lane>> e : adjacency.entrySet()) {
            adj.put(e.getKey(), List.copyOf(e.getValue()));
        }
        this.adjacency = Collections.unmodifiableMap(adj);
        this.lanes = List.copyOf(lanes);
    }

    /** @return the number of stars (nodes) in the region graph. */
    public int starCount() {
        return adjacency.size();
    }

    /** @return the number of lanes (undirected edges) in the graph. */
    public int laneCount() {
        return lanes.size();
    }

    /** @return true if {@code starId} is a node of this region graph. */
    public boolean contains(long starId) {
        return adjacency.containsKey(starId);
    }

    /**
     * @return the region's star ids, in the deterministic order the generator
     *         produced; unmodifiable.
     */
    public List<Long> starIds() {
        return List.copyOf(adjacency.keySet());
    }

    /**
     * @return every lane once (canonical {@code a<b} orientation), in deterministic
     *         order; unmodifiable. The natural-lane skeleton as a flat edge list.
     */
    public List<Lane> lanes() {
        return lanes;
    }

    /**
     * Adjacency lookup: the lanes incident to a star (the jump options out of that
     * system). This is the primitive E1-09 walks during pathfinding.
     *
     * @param starId a star id
     * @return its incident lanes in deterministic order (empty if the star has no
     *         lanes); unmodifiable. Never {@code null} even for unknown ids.
     */
    public List<Lane> lanesFrom(long starId) {
        List<Lane> ls = adjacency.get(starId);
        return ls == null ? List.of() : ls;
    }

    /**
     * @param from a star id
     * @return the neighbouring star ids reachable in one lane hop, deterministic
     *         order; unmodifiable.
     */
    public List<Long> neighbours(long from) {
        List<Lane> ls = lanesFrom(from);
        List<Long> out = new ArrayList<>(ls.size());
        for (Lane l : ls) {
            out.add(l.other(from));
        }
        return List.copyOf(out);
    }
}
