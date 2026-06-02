package com.stellarcompact.galaxy.gen;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The pure, deterministic function {@code (gameSeed, region) -> LaneGraph}:
 * E2-03's natural-lane builder, layered on E2-01's star field (game-design 01
 * section 3 "natural lanes - exist from generation, based on proximity; the
 * static skeleton of the map").
 *
 * <p><strong>Determinism contract (principle 1).</strong> Generation reads only
 * its arguments. No I/O, no Spring, no wall-clock, no {@code Math.random}, no
 * shared mutable RNG, no mutable statics. Distances are closed-form over the
 * stars' {@link StarCoords}; every ordering decision is a total order with a
 * {@link SeedHash}-derived tiebreak, so the same {@code (gameSeed, region)} yields
 * a byte-identical {@link LaneGraph} on every call and across JVMs. The input star
 * list is consumed in a seed-stable canonical order (sorted by star id) so the
 * caller's list order cannot perturb the result.
 *
 * <h2>Algorithm</h2>
 * <ol>
 *   <li><strong>Proximity edges (k-nearest within a radius).</strong> Each star
 *       links to up to {@link GalaxyConstants#LANE_MAX_NEIGHBOURS} nearest other
 *       stars within {@link GalaxyConstants#LANE_MAX_RADIUS}. Candidates are
 *       ordered by squared distance, then by a deterministic seeded tiebreak so
 *       equal-distance ties resolve identically per seed (never by float equality
 *       or list order). Edges are undirected and de-duplicated (a link added from
 *       either endpoint counts once).</li>
 *   <li><strong>Connectivity (deterministic minimum spanning forest).</strong>
 *       Proximity edges alone can leave isolated stars or disconnected clusters
 *       (sparse frontier, or stars beyond every neighbour's radius). We then union
 *       the components and add the cheapest cross-component lane repeatedly (a
 *       Kruskal-style minimum spanning forest over a deterministically ordered
 *       candidate list, ties broken by the same seeded order) until the region is a
 *       single connected component. This guarantees E1-09 pathfinding always has a
 *       path between any two region systems.</li>
 * </ol>
 *
 * <p>Lane length is {@code round(distance * LANE_TICKS_PER_UNIT)} floored at
 * {@link GalaxyConstants#LANE_MIN_TICKS}, so it is monotonic with real distance
 * and never zero (game-design 01 section 3).
 *
 * <p><strong>Scale (principle 3).</strong> The builder operates on the bounded set
 * of region stars the caller materialised; it never iterates the whole catalog.
 * The proximity pass is O(n^2) over that region, which is fine for a playable
 * region (thousands of stars); the billion-star tiling is E8.
 */
public final class LaneGraphGenerator {

    private LaneGraphGenerator() {
    }

    /**
     * Builds the natural-lane graph over an explicit set of region stars.
     *
     * @param gameSeed the per-match galaxy seed
     * @param region   the stars of the playable region (materialised by the caller
     *                  from E2-01 cells); may be in any order
     * @return the deterministic, immutable, connected {@link LaneGraph}; never
     *         {@code null}
     */
    public static LaneGraph generate(long gameSeed, List<Star> region) {
        // Canonical, seed-stable node order: sort by star id. The caller list order
        // must not influence the graph (determinism contract).
        List<Star> stars = new ArrayList<>(region);
        stars.sort(Comparator.comparingLong(Star::id));

        // Seed the adjacency in canonical node order so iteration is deterministic.
        Map<Long, List<Lane>> adjacency = new LinkedHashMap<>(stars.size() * 2);
        for (Star s : stars) {
            adjacency.put(s.id(), new ArrayList<>(GalaxyConstants.LANE_MAX_NEIGHBOURS));
        }

        // A union-find over node ids tracks connectivity as we add edges.
        UnionFind components = new UnionFind(stars);

        List<Lane> lanes = new ArrayList<>();

        addProximityLanes(gameSeed, stars, adjacency, lanes, components);
        bridgeComponents(gameSeed, stars, adjacency, lanes, components);

        return new LaneGraph(adjacency, lanes);
    }

    // --- step 1: proximity (k-nearest within radius) -------------------------

    private static void addProximityLanes(long gameSeed,
                                          List<Star> stars,
                                          Map<Long, List<Lane>> adjacency,
                                          List<Lane> lanes,
                                          UnionFind components) {
        int n = stars.size();
        double maxR2 = GalaxyConstants.LANE_MAX_RADIUS * GalaxyConstants.LANE_MAX_RADIUS;
        for (int i = 0; i < n; i++) {
            Star from = stars.get(i);

            // Rank every other star by squared distance, then seeded tiebreak.
            List<Candidate> candidates = new ArrayList<>();
            for (int j = 0; j < n; j++) {
                if (j == i) {
                    continue;
                }
                Star to = stars.get(j);
                double d2 = dist2(from.pos(), to.pos());
                if (d2 > maxR2) {
                    continue; // outside the proximity radius
                }
                candidates.add(new Candidate(to.id(), d2,
                        tiebreak(gameSeed, from.id(), to.id())));
            }
            candidates.sort(CANDIDATE_ORDER);

            int linked = 0;
            for (Candidate c : candidates) {
                if (linked >= GalaxyConstants.LANE_MAX_NEIGHBOURS) {
                    break;
                }
                // De-dup: add the undirected lane once (canonical a<b). If it
                // already exists (added when the other endpoint chose us), skip but
                // still count it toward this star neighbour budget.
                if (hasLane(adjacency, from.id(), c.id)) {
                    linked++;
                    continue;
                }
                Lane lane = makeLane(from.id(), c.id, Math.sqrt(c.dist2));
                attach(adjacency, lanes, lane);
                components.union(lane.a(), lane.b());
                linked++;
            }
        }
    }

    // --- step 2: connectivity (deterministic minimum spanning forest) --------

    private static void bridgeComponents(long gameSeed,
                                         List<Star> stars,
                                         Map<Long, List<Lane>> adjacency,
                                         List<Lane> lanes,
                                         UnionFind components) {
        if (components.componentCount() <= 1) {
            return; // proximity pass already produced a single component
        }

        // Build every cross-pair as a bridging candidate, Kruskal-style: order by
        // distance then seeded tiebreak, then greedily union components. Ordering is
        // a total order so the spanning forest is reproducible per seed.
        List<Candidate> bridges = new ArrayList<>();
        int n = stars.size();
        for (int i = 0; i < n; i++) {
            long a = stars.get(i).id();
            for (int j = i + 1; j < n; j++) {
                long b = stars.get(j).id();
                double d2 = dist2(stars.get(i).pos(), stars.get(j).pos());
                bridges.add(new BridgeCandidate(a, b, d2, tiebreak(gameSeed, a, b)));
            }
        }
        bridges.sort(CANDIDATE_ORDER);

        for (Candidate raw : bridges) {
            if (components.componentCount() <= 1) {
                break;
            }
            BridgeCandidate c = (BridgeCandidate) raw;
            if (components.connected(c.a, c.b)) {
                continue; // already in the same component
            }
            if (hasLane(adjacency, c.a, c.b)) {
                components.union(c.a, c.b); // already linked by proximity; guard
                continue;
            }
            Lane lane = makeLane(c.a, c.b, Math.sqrt(c.dist2));
            attach(adjacency, lanes, lane);
            components.union(c.a, c.b);
        }
    }

    // --- lane construction & length ------------------------------------------

    /**
     * Lane length = travel ticks from real distance: {@code round(distance *
     * LANE_TICKS_PER_UNIT)}, floored at {@link GalaxyConstants#LANE_MIN_TICKS}.
     * {@link Math#round} is deterministic (round-half-up); monotonic with distance.
     */
    static int travelTicks(double distance) {
        long ticks = Math.round(distance * GalaxyConstants.LANE_TICKS_PER_UNIT);
        if (ticks < GalaxyConstants.LANE_MIN_TICKS) {
            ticks = GalaxyConstants.LANE_MIN_TICKS;
        }
        return (int) ticks;
    }

    /** Build a canonical (a<b) lane with travel-tick length for a distance. */
    private static Lane makeLane(long u, long v, double distance) {
        long a = Math.min(u, v);
        long b = Math.max(u, v);
        return new Lane(a, b, travelTicks(distance));
    }

    /** Register a lane in both endpoints adjacency and the flat edge list. */
    private static void attach(Map<Long, List<Lane>> adjacency,
                               List<Lane> lanes, Lane lane) {
        lanes.add(lane);
        adjacency.get(lane.a()).add(lane);
        adjacency.get(lane.b()).add(lane);
    }

    private static boolean hasLane(Map<Long, List<Lane>> adjacency, long u, long v) {
        for (Lane l : adjacency.get(u)) {
            if (l.touches(v)) {
                return true;
            }
        }
        return false;
    }

    // --- deterministic ordering ----------------------------------------------

    /** Squared Euclidean distance (avoids a sqrt during ranking). */
    private static double dist2(StarCoords p, StarCoords q) {
        double dx = p.x() - q.x();
        double dy = p.y() - q.y();
        return dx * dx + dy * dy;
    }

    /**
     * Seeded tiebreak for two endpoints, symmetric in the pair so {@code (a,b)} and
     * {@code (b,a)} agree. Used only to break exact distance ties deterministically.
     */
    private static long tiebreak(long gameSeed, long u, long v) {
        long lo = Math.min(u, v);
        long hi = Math.max(u, v);
        return SeedHash.combine(gameSeed, lo, hi, Salt.LANE_TIEBREAK.value());
    }

    /**
     * Total order over candidates: nearer first (by squared distance), then by the
     * seeded tiebreak, then by id keys as a final deterministic discriminator so no
     * two distinct candidates ever compare equal.
     */
    private static final Comparator<Candidate> CANDIDATE_ORDER =
            Comparator.<Candidate>comparingDouble(c -> c.dist2)
                    .thenComparingLong(c -> c.tiebreak)
                    .thenComparingLong(Candidate::primaryKey)
                    .thenComparingLong(Candidate::secondaryKey);

    /** A ranked neighbour candidate from a single source star. */
    private static sealed class Candidate permits BridgeCandidate {
        final long id;        // the other endpoint (for proximity ranking)
        final double dist2;
        final long tiebreak;

        Candidate(long id, double dist2, long tiebreak) {
            this.id = id;
            this.dist2 = dist2;
            this.tiebreak = tiebreak;
        }

        long primaryKey() {
            return id;
        }

        long secondaryKey() {
            return id;
        }
    }

    /** A cross-component bridging candidate carrying both endpoints. */
    private static final class BridgeCandidate extends Candidate {
        final long a;
        final long b;

        BridgeCandidate(long a, long b, double dist2, long tiebreak) {
            super(a, dist2, tiebreak);
            this.a = a;
            this.b = b;
        }

        @Override
        long primaryKey() {
            return a;
        }

        @Override
        long secondaryKey() {
            return b;
        }
    }

    /**
     * Tiny disjoint-set (union-find) over the region star ids, used only to track
     * connected components while bridging. Path-compression + union-by-size keep it
     * near-linear; it is local mutable scratch confined to a single generate() call,
     * not shared mutable state (the determinism contract bans shared/static mutable
     * state, not local scratch).
     */
    private static final class UnionFind {
        private final Map<Long, Long> parent;
        private final Map<Long, Integer> size;
        private int components;

        UnionFind(List<Star> stars) {
            parent = new LinkedHashMap<>(stars.size() * 2);
            size = new LinkedHashMap<>(stars.size() * 2);
            for (Star s : stars) {
                parent.put(s.id(), s.id());
                size.put(s.id(), 1);
            }
            components = stars.size();
        }

        long find(long x) {
            long root = x;
            while (parent.get(root) != root) {
                root = parent.get(root);
            }
            // Path compression.
            long cur = x;
            while (parent.get(cur) != root) {
                long next = parent.get(cur);
                parent.put(cur, root);
                cur = next;
            }
            return root;
        }

        boolean connected(long x, long y) {
            return find(x) == find(y);
        }

        void union(long x, long y) {
            long rx = find(x);
            long ry = find(y);
            if (rx == ry) {
                return;
            }
            // Union by size; smaller root id wins ties so the merge is deterministic.
            int sx = size.get(rx);
            int sy = size.get(ry);
            long root;
            long child;
            if (sx > sy || (sx == sy && rx < ry)) {
                root = rx;
                child = ry;
            } else {
                root = ry;
                child = rx;
            }
            parent.put(child, root);
            size.put(root, sx + sy);
            components--;
        }

        int componentCount() {
            return components;
        }
    }
}
