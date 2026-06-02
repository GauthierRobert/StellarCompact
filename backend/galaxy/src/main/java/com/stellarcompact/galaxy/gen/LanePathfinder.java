package com.stellarcompact.galaxy.gen;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.PriorityQueue;

/**
 * Deterministic shortest-path over a {@link LaneGraph} by summed lane ticks: the
 * pathfinder E1-09 (Movement &amp; interception) consumes to compute fleet travel
 * ETAs and routes.
 *
 * <p><strong>Separated from the graph on purpose.</strong> {@link LaneGraph} is
 * the immutable data structure; this is a stateless helper that runs many queries
 * against it. Keeping them apart means the graph is built once per (seed, region)
 * and reused across every path query.
 *
 * <p><strong>Determinism.</strong> Dijkstra over non-negative integer lane
 * lengths. The frontier is a {@link PriorityQueue} ordered by
 * {@code (distance, node id)} so equal-cost nodes are popped in a fixed order, and
 * neighbours are relaxed in the graph's deterministic adjacency order. Ties in
 * total path cost are therefore broken identically on every call and JVM - the
 * returned path is a pure function of {@code (graph, from, to)}. No I/O, no
 * wall-clock, no randomness; the only mutable state is local scratch.
 */
public final class LanePathfinder {

    private LanePathfinder() {
    }

    /**
     * Result of a shortest-path query: the ordered list of star ids from origin to
     * destination (inclusive of both) and the total travel cost in ticks (the sum
     * of the traversed lanes' lengths).
     *
     * @param nodes      ordered path {@code [from, ..., to]}; immutable
     * @param totalTicks summed lane ticks along the path ({@code 0} when
     *                   {@code from == to})
     */
    public record Path(List<Long> nodes, long totalTicks) {
        public Path {
            nodes = List.copyOf(nodes);
        }

        /** @return number of lane hops (edges) along the path. */
        public int hops() {
            return Math.max(0, nodes.size() - 1);
        }
    }

    /**
     * Computes the minimum-travel-tick path between two region systems.
     *
     * @param graph the lane graph (connected over its region, so a path exists for
     *              any two member stars)
     * @param from  origin star id (must be a node of {@code graph})
     * @param to    destination star id (must be a node of {@code graph})
     * @return the shortest {@link Path}, or {@link Optional#empty()} if either
     *         endpoint is not in the graph or (defensively) no path exists
     */
    public static Optional<Path> shortestPath(LaneGraph graph, long from, long to) {
        if (!graph.contains(from) || !graph.contains(to)) {
            return Optional.empty();
        }
        if (from == to) {
            return Optional.of(new Path(List.of(from), 0L));
        }

        Map<Long, Long> dist = new HashMap<>();
        Map<Long, Long> prev = new HashMap<>();
        // Frontier ordered by (tentative distance, node id) for a stable, seed-free
        // deterministic pop order on equal-cost ties.
        PriorityQueue<long[]> frontier = new PriorityQueue<>(
                Comparator.<long[]>comparingLong(e -> e[1]).thenComparingLong(e -> e[0]));

        dist.put(from, 0L);
        frontier.add(new long[]{from, 0L});

        while (!frontier.isEmpty()) {
            long[] top = frontier.poll();
            long node = top[0];
            long d = top[1];
            if (d > dist.getOrDefault(node, Long.MAX_VALUE)) {
                continue; // a stale, superseded frontier entry
            }
            if (node == to) {
                break; // reached the destination with its final distance
            }
            // Relax neighbours in the graph deterministic adjacency order.
            for (Lane lane : graph.lanesFrom(node)) {
                long next = lane.other(node);
                long nd = d + lane.lengthTicks();
                if (nd < dist.getOrDefault(next, Long.MAX_VALUE)) {
                    dist.put(next, nd);
                    prev.put(next, node);
                    frontier.add(new long[]{next, nd});
                }
            }
        }

        Long total = dist.get(to);
        if (total == null) {
            return Optional.empty(); // unreachable (should not happen on a connected region)
        }

        // Reconstruct the path back-to-front, then reverse.
        List<Long> path = new ArrayList<>();
        for (Long cur = to; cur != null; cur = prev.get(cur)) {
            path.add(cur);
        }
        Collections.reverse(path);
        return Optional.of(new Path(path, total));
    }
}
