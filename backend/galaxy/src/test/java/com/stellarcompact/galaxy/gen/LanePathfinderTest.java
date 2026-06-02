package com.stellarcompact.galaxy.gen;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Deterministic shortest-path tests for {@link LanePathfinder} (the helper E1-09
 * consumes). Covers correctness on a hand-authored graph, reproducibility, the
 * connected-region guarantee (a path always exists), and that path cost equals the
 * summed lane ticks.
 */
class LanePathfinderTest {

    private static final long SEED = 0xC0FFEEL;

    // --- Hand-authored graph: a known shortest path ---

    /**
     * A small diamond where the lower route is cheaper:
     * <pre>
     *        (1)---2---(2)
     *       /             \
     *     5                 1
     *     /                  \
     *   (0)                  (3)
     *     \                  /
     *     1                 1
     *       \             /
     *        (4)---1---(5)
     * </pre>
     * Top route 0-1-2-3 costs 5+2+1=8; bottom 0-4-5-3 costs 1+1+1=3.
     */
    private static LaneGraph diamond() {
        List<Star> stars = new ArrayList<>();
        for (int i = 0; i <= 5; i++) {
            stars.add(new Star(i, new StarCoords(i, 0)));
        }
        // Bypass the proximity generator: assemble the exact topology directly so
        // the shortest-path assertion is independent of placement maths.
        return TestGraphs.of(stars,
                new Lane(0, 1, 5),
                new Lane(1, 2, 2),
                new Lane(2, 3, 1),
                new Lane(0, 4, 1),
                new Lane(4, 5, 1),
                new Lane(3, 5, 1));
    }

    @Test
    @DisplayName("finds the minimum-tick path, not merely the fewest hops")
    void picksCheapestByTicks() {
        LaneGraph g = diamond();
        Optional<LanePathfinder.Path> p = LanePathfinder.shortestPath(g, 0, 3);
        assertTrue(p.isPresent());
        assertEquals(List.of(0L, 4L, 5L, 3L), p.get().nodes(), "should take the cheaper bottom route");
        assertEquals(3L, p.get().totalTicks(), "cost = 1+1+1");
        assertEquals(3, p.get().hops());
    }

    @Test
    @DisplayName("total cost equals the summed lengths of the traversed lanes")
    void costEqualsSummedLaneTicks() {
        LaneGraph g = diamond();
        LanePathfinder.Path p = LanePathfinder.shortestPath(g, 0, 3).orElseThrow();
        long summed = 0;
        for (int i = 0; i + 1 < p.nodes().size(); i++) {
            summed += laneBetween(g, p.nodes().get(i), p.nodes().get(i + 1)).lengthTicks();
        }
        assertEquals(summed, p.totalTicks());
    }

    @Test
    @DisplayName("path from a node to itself is trivial with zero cost")
    void selfPath() {
        LaneGraph g = diamond();
        LanePathfinder.Path p = LanePathfinder.shortestPath(g, 2, 2).orElseThrow();
        assertEquals(List.of(2L), p.nodes());
        assertEquals(0L, p.totalTicks());
        assertEquals(0, p.hops());
    }

    @Test
    @DisplayName("unknown endpoints yield empty")
    void unknownEndpoints() {
        LaneGraph g = diamond();
        assertTrue(LanePathfinder.shortestPath(g, 0, 999).isEmpty());
        assertTrue(LanePathfinder.shortestPath(g, 999, 0).isEmpty());
    }

    @Test
    @DisplayName("shortest path is deterministic across repeated calls")
    void deterministic() {
        LaneGraph g = diamond();
        LanePathfinder.Path a = LanePathfinder.shortestPath(g, 0, 3).orElseThrow();
        LanePathfinder.Path b = LanePathfinder.shortestPath(g, 0, 3).orElseThrow();
        assertEquals(a.nodes(), b.nodes());
        assertEquals(a.totalTicks(), b.totalTicks());
    }

    // --- On a real generated, connected region: a path always exists ---

    @Test
    @DisplayName("on the connected playable region a path exists between any pair")
    void pathExistsOnRegion() {
        List<Star> region = new ArrayList<>();
        int span = (int) Math.ceil(GalaxyConstants.BULGE_RADIUS / GalaxyConstants.CELL_SIZE);
        for (int cx = -span; cx <= span; cx++) {
            for (int cy = -span; cy <= span; cy++) {
                region.addAll(StarFieldGenerator.generate(SEED, new Cell(cx, cy)));
            }
        }
        LaneGraph g = LaneGraphGenerator.generate(SEED, region);
        List<Long> ids = g.starIds();
        long src = ids.get(0);
        // Sample several destinations across the id-ordered node list.
        for (int k = 0; k < ids.size(); k += Math.max(1, ids.size() / 25)) {
            long dst = ids.get(k);
            Optional<LanePathfinder.Path> p = LanePathfinder.shortestPath(g, src, dst);
            assertTrue(p.isPresent(), "no path to " + dst + " on a connected region");
            assertEquals(src, p.get().nodes().get(0));
            assertEquals(dst, p.get().nodes().get(p.get().nodes().size() - 1));
            if (src != dst) {
                assertTrue(p.get().totalTicks() >= GalaxyConstants.LANE_MIN_TICKS);
                assertFalse(p.get().nodes().isEmpty());
            }
        }
    }

    private static Lane laneBetween(LaneGraph g, long u, long v) {
        for (Lane l : g.lanesFrom(u)) {
            if (l.touches(v)) {
                return l;
            }
        }
        throw new AssertionError("no lane between " + u + " and " + v);
    }
}
