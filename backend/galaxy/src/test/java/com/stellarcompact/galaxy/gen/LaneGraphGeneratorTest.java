package com.stellarcompact.galaxy.gen;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Determinism, connectivity and lane-length tests for {@link LaneGraphGenerator}
 * (E2-03), in the style of {@link StarFieldGeneratorTest} /
 * {@link SystemGeneratorTest}: structural reproducibility plus a pinned canonical
 * golden hash, then the card's invariants (connected playable region, lane length
 * derived from and monotonic with distance).
 */
class LaneGraphGeneratorTest {

    private static final long SEED = 0xC0FFEEL;

    /** Pinned on first green run (printed by goldenGraphHash failure). */
    private static final long GOLDEN_GRAPH_HASH = 3784755403516210428L;

    // --- Determinism ---

    @Test
    @DisplayName("same (seed, region) returns an identical graph on repeated calls")
    void repeatedCallsAreIdentical() {
        List<Star> region = region(SEED);
        LaneGraph a = LaneGraphGenerator.generate(SEED, region);
        LaneGraph b = LaneGraphGenerator.generate(SEED, region);
        assertEquals(foldHash(a), foldHash(b), "canonical hashes must match");
        assertEquals(a.lanes(), b.lanes(), "lane lists must be structurally identical");
    }

    @Test
    @DisplayName("input list order does not affect the graph")
    void inputOrderIndependent() {
        List<Star> region = region(SEED);
        long clean = foldHash(LaneGraphGenerator.generate(SEED, region));

        List<Star> shuffled = new ArrayList<>(region);
        // A fixed, deterministic reordering (reverse) - not RNG - to prove the
        // builder canonicalises node order itself.
        Collections.reverse(shuffled);
        long reordered = foldHash(LaneGraphGenerator.generate(SEED, shuffled));
        assertEquals(clean, reordered, "caller list order must not change the graph");
    }

    @Test
    @DisplayName("no hidden state: interleaving other builds does not change output")
    void noHiddenState() {
        List<Star> region = region(SEED);
        long clean = foldHash(LaneGraphGenerator.generate(SEED, region));
        long acc = 0;
        for (int i = 0; i < 20; i++) {
            acc ^= foldHash(LaneGraphGenerator.generate(SEED ^ i, region(SEED ^ i)));
        }
        assertTrue(acc != 0 || acc == 0);
        long again = foldHash(LaneGraphGenerator.generate(SEED, region));
        assertEquals(clean, again, "interleaving other calls must not change output");
    }

    @Test
    @DisplayName("seed feeds the tiebreak: under exact-distance ties the seed decides the edge")
    void seedBreaksDistanceTies() {
        // A symmetric cross where the centre star is equidistant to four arms, all
        // ties within LANE_MAX_RADIUS, and the budget is smaller than the number of
        // equidistant candidates. With no float ordering possible, the seeded
        // tiebreak is the ONLY thing that can decide which lanes are kept - so two
        // seeds must be able to produce different graphs.
        double d = GalaxyConstants.LANE_MAX_RADIUS * 0.4;
        List<Star> cross = new ArrayList<>();
        cross.add(new Star(0L, new StarCoords(0, 0)));     // centre
        cross.add(new Star(1L, new StarCoords(d, 0)));
        cross.add(new Star(2L, new StarCoords(-d, 0)));
        cross.add(new Star(3L, new StarCoords(0, d)));
        cross.add(new Star(4L, new StarCoords(0, -d)));
        cross.add(new Star(5L, new StarCoords(d, d)));     // a few more equidistant rings
        cross.add(new Star(6L, new StarCoords(-d, -d)));
        cross.add(new Star(7L, new StarCoords(d, -d)));
        cross.add(new Star(8L, new StarCoords(-d, d)));

        boolean anyDiffer = false;
        long base = foldHash(LaneGraphGenerator.generate(1L, cross));
        for (long s = 2L; s <= 40L && !anyDiffer; s++) {
            if (foldHash(LaneGraphGenerator.generate(s, cross)) != base) {
                anyDiffer = true;
            }
        }
        assertTrue(anyDiffer, "seed must influence the graph when distances tie");
    }

    @Test
    @DisplayName("golden: pinned canonical hash of a fixed region graph")
    void goldenGraphHash() {
        long actual = foldHash(LaneGraphGenerator.generate(SEED, region(SEED)));
        assertEquals(GOLDEN_GRAPH_HASH, actual,
                "lane graph output changed; if intentional, re-pin GOLDEN_GRAPH_HASH");
    }

    // --- Structure & immutability ---

    @Test
    @DisplayName("graph node set equals the region star set")
    void nodesMatchRegion() {
        List<Star> region = region(SEED);
        LaneGraph g = LaneGraphGenerator.generate(SEED, region);
        Set<Long> ids = new HashSet<>();
        for (Star s : region) {
            ids.add(s.id());
        }
        assertEquals(ids.size(), g.starCount(), "every region star must be a node");
        for (long id : ids) {
            assertTrue(g.contains(id), "missing node " + id);
        }
    }

    @Test
    @DisplayName("adjacency lookups are immutable")
    void adjacencyImmutable() {
        LaneGraph g = LaneGraphGenerator.generate(SEED, region(SEED));
        long id = g.starIds().get(0);
        List<Lane> ls = g.lanesFrom(id);
        assertSame(ls, List.copyOf(ls));
        assertSame(g.lanes(), List.copyOf(g.lanes()));
    }

    @Test
    @DisplayName("lanes are undirected and consistent: each appears in both endpoints adjacency")
    void lanesUndirected() {
        LaneGraph g = LaneGraphGenerator.generate(SEED, region(SEED));
        for (Lane lane : g.lanes()) {
            assertTrue(lane.a() < lane.b(), "lanes stored canonically a<b: " + lane);
            assertTrue(g.lanesFrom(lane.a()).contains(lane), "missing from a-side: " + lane);
            assertTrue(g.lanesFrom(lane.b()).contains(lane), "missing from b-side: " + lane);
        }
    }

    @Test
    @DisplayName("degree is bounded near the proximity neighbour budget (plus bridges)")
    void degreeBounded() {
        LaneGraph g = LaneGraphGenerator.generate(SEED, region(SEED));
        // Proximity caps each star at LANE_MAX_NEIGHBOURS edges it initiates, but a
        // star can also be *chosen* by others and receive bridge lanes; assert a
        // sane loose upper bound rather than an exact degree.
        for (long id : g.starIds()) {
            int deg = g.lanesFrom(id).size();
            assertTrue(deg >= 1, "no isolated node allowed; " + id + " deg=" + deg);
            assertTrue(deg <= GalaxyConstants.LANE_MAX_NEIGHBOURS * 4,
                    "degree implausibly high for " + id + ": " + deg);
        }
    }

    // --- Connectivity (the card guarantee) ---

    @Test
    @DisplayName("the playable region graph is connected: no isolated star")
    void regionIsConnected() {
        List<Star> region = region(SEED);
        LaneGraph g = LaneGraphGenerator.generate(SEED, region);
        assertConnected(g);
    }

    @Test
    @DisplayName("connectivity holds across many seeds and region densities")
    void connectivityRobustAcrossSeeds() {
        for (long s = 1; s <= 12; s++) {
            List<Star> region = region(s);
            if (region.size() < 2) {
                continue;
            }
            assertConnected(LaneGraphGenerator.generate(s, region));
        }
    }

    @Test
    @DisplayName("a sparse, scattered region with no proximity edges is still bridged connected")
    void sparseRegionBridged() {
        // Stars deliberately spaced far beyond LANE_MAX_RADIUS so the proximity pass
        // produces zero edges; the MST bridging pass must still connect them.
        List<Star> sparse = new ArrayList<>();
        double gap = GalaxyConstants.LANE_MAX_RADIUS * 3.0;
        for (int i = 0; i < 6; i++) {
            sparse.add(new Star(1000L + i, new StarCoords(i * gap, 0.0)));
        }
        LaneGraph g = LaneGraphGenerator.generate(SEED, sparse);
        assertConnected(g);
        // A 6-node connected graph needs at least 5 edges.
        assertTrue(g.laneCount() >= 5, "expected a spanning set of bridges");
    }

    // --- Lane length derived from / monotonic with distance ---

    @Test
    @DisplayName("lane length = round(distance * ticksPerUnit), min 1 tick, monotonic")
    void travelTicksMonotonic() {
        int prev = LaneGraphGenerator.travelTicks(0.0);
        assertEquals(GalaxyConstants.LANE_MIN_TICKS, prev, "zero distance floors to min ticks");
        for (double d = 0.0; d <= 500.0; d += 0.5) {
            int t = LaneGraphGenerator.travelTicks(d);
            assertTrue(t >= GalaxyConstants.LANE_MIN_TICKS, "below min at d=" + d);
            assertTrue(t >= prev, "ticks must be non-decreasing in distance at d=" + d);
            prev = t;
        }
        // Exact-formula spot check well above the min floor.
        double d = 200.0;
        long expected = Math.round(d * GalaxyConstants.LANE_TICKS_PER_UNIT);
        assertEquals((int) expected, LaneGraphGenerator.travelTicks(d));
    }

    @Test
    @DisplayName("each lane length matches the travel-tick formula over its endpoint distance")
    void laneLengthsDerivedFromDistance() {
        List<Star> region = region(SEED);
        LaneGraph g = LaneGraphGenerator.generate(SEED, region);
        for (Lane lane : g.lanes()) {
            StarCoords pa = coordsOf(region, lane.a());
            StarCoords pb = coordsOf(region, lane.b());
            double dist = Math.hypot(pa.x() - pb.x(), pa.y() - pb.y());
            assertEquals(LaneGraphGenerator.travelTicks(dist), lane.lengthTicks(),
                    "lane length must equal the distance-derived travel ticks: " + lane);
            assertTrue(lane.lengthTicks() >= GalaxyConstants.LANE_MIN_TICKS);
        }
    }

    // --- helpers ---

    /**
     * Materialise a playable region: the block of E2-01 cells over the dense inner
     * disc / bulge for a seed. Pure (no RNG); the star set is itself deterministic.
     */
    private static List<Star> region(long seed) {
        List<Star> out = new ArrayList<>();
        int span = (int) Math.ceil(GalaxyConstants.BULGE_RADIUS / GalaxyConstants.CELL_SIZE);
        for (int cx = -span; cx <= span; cx++) {
            for (int cy = -span; cy <= span; cy++) {
                out.addAll(StarFieldGenerator.generate(seed, new Cell(cx, cy)));
            }
        }
        return out;
    }

    private static StarCoords coordsOf(List<Star> region, long id) {
        for (Star s : region) {
            if (s.id() == id) {
                return s.pos();
            }
        }
        throw new AssertionError("id not in region: " + id);
    }

    /** BFS from an arbitrary node must reach every node. */
    private static void assertConnected(LaneGraph g) {
        List<Long> ids = g.starIds();
        if (ids.isEmpty()) {
            return;
        }
        Set<Long> seen = new HashSet<>();
        Deque<Long> queue = new ArrayDeque<>();
        long start = ids.get(0);
        seen.add(start);
        queue.add(start);
        while (!queue.isEmpty()) {
            long node = queue.poll();
            for (long nb : g.neighbours(node)) {
                if (seen.add(nb)) {
                    queue.add(nb);
                }
            }
        }
        assertFalse(seen.size() < ids.size(),
                "region not connected: reached " + seen.size() + " of " + ids.size());
        assertEquals(ids.size(), seen.size(), "every node must be reachable");
    }

    /** Canonical order-sensitive fold of a graph (local golden hash). */
    private static long foldHash(LaneGraph g) {
        long h = 1125899906842597L;
        for (long id : g.starIds()) {
            h = h * 31 + id;
        }
        for (Lane lane : g.lanes()) {
            h = h * 1099511628211L + lane.a();
            h = h * 1099511628211L + lane.b();
            h = h * 1099511628211L + lane.lengthTicks();
        }
        return h;
    }
}
