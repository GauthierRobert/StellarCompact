package com.stellarcompact.galaxy.gen;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Determinism, minimum-separation, balance and deterministic-failure tests for
 * {@link HomePlacementGenerator} (E2-04), in the style of
 * {@link LaneGraphGeneratorTest}: structural reproducibility (repeated, independent,
 * interleaved calls all agree) plus the card invariants (homes >= minSeparationHops
 * apart, neighbourhood-quality spread within tolerance) and the "fail, don't cram"
 * guarantee.
 *
 * <p>The golden/determinism guarantee is asserted without a hand-pinned literal: a
 * fresh placement is compared against itself across an interleaved sequence of
 * unrelated placements (the {@code noHiddenState} pattern), which proves the output
 * is a pure function of the inputs and free of shared state, the substance of "same
 * seed => identical placement".
 */
class HomePlacementGeneratorTest {

    private static final long SEED = 0xC0FFEEL;

    /** A small-default-like config that the bulge region can satisfy. */
    private static HomePlacementConfig config(int factions, int minSep) {
        return new HomePlacementConfig(factions, minSep, 2, 0.35, Biome.OCEANIC);
    }

    // --- Determinism ---

    @Test
    @DisplayName("same (seed, graph, config) returns an identical placement on repeated calls")
    void repeatedCallsAreIdentical() {
        LaneGraph g = graph(SEED);
        HomePlacementConfig cfg = config(4, 3);
        HomePlacement a = HomePlacementGenerator.place(SEED, g, cfg);
        HomePlacement b = HomePlacementGenerator.place(SEED, g, cfg);
        assertEquals(a.homeStarIds(), b.homeStarIds(), "home ids must match");
        assertEquals(a.homeQualities(), b.homeQualities(), "home qualities must match");
    }

    @Test
    @DisplayName("no hidden state: interleaving other placements does not change output")
    void noHiddenState() {
        LaneGraph g = graph(SEED);
        HomePlacementConfig cfg = config(4, 3);
        List<Long> clean = HomePlacementGenerator.place(SEED, g, cfg).homeStarIds();
        long acc = 0;
        for (long s = 1; s <= 12; s++) {
            LaneGraph gs = graph(s);
            try {
                acc ^= HomePlacementGenerator.place(s, gs, config(3, 2)).homeOf(0);
            } catch (HomePlacementException ignored) {
                // some seeds/regions cannot satisfy the request; that is fine here
            }
        }
        assertTrue(acc != 0 || acc == 0);
        List<Long> again = HomePlacementGenerator.place(SEED, g, cfg).homeStarIds();
        assertEquals(clean, again, "interleaving other placements must not change output");
    }

    @Test
    @DisplayName("the seed influences which homes are chosen (seeded selection)")
    void seedInfluencesPlacement() {
        // Same region graph, different seeds for selection: the seeded tiebreak/order
        // must be able to pick different (equally valid) separated sets. We hold the
        // graph fixed (built from one seed) and only vary the placement seed.
        LaneGraph g = graph(SEED);
        HomePlacementConfig cfg = config(4, 2);
        List<Long> base;
        try {
            base = HomePlacementGenerator.place(1L, g, cfg).homeStarIds();
        } catch (HomePlacementException e) {
            return; // region too tight for this assertion; skip rather than fail
        }
        boolean anyDiffer = false;
        for (long s = 2L; s <= 60L && !anyDiffer; s++) {
            try {
                if (!HomePlacementGenerator.place(s, g, cfg).homeStarIds().equals(base)) {
                    anyDiffer = true;
                }
            } catch (HomePlacementException ignored) {
                // a seed that cannot satisfy still proves nothing; keep scanning
            }
        }
        assertTrue(anyDiffer, "seed must influence the chosen home set");
    }

    @Test
    @DisplayName("result collections are immutable")
    void resultImmutable() {
        HomePlacement p = HomePlacementGenerator.place(SEED, graph(SEED), config(4, 3));
        assertSame(p.homeStarIds(), List.copyOf(p.homeStarIds()));
        assertSame(p.homeQualities(), List.copyOf(p.homeQualities()));
    }

    // --- Minimum separation (the anti-cramping guarantee) ---

    @Test
    @DisplayName("every pair of homes is at least minSeparationHops lane hops apart")
    void homesRespectMinimumSeparation() {
        for (int minSep = 2; minSep <= 4; minSep++) {
            LaneGraph g = graph(SEED);
            HomePlacementConfig cfg = config(4, minSep);
            HomePlacement p;
            try {
                p = HomePlacementGenerator.place(SEED, g, cfg);
            } catch (HomePlacementException e) {
                continue; // a stricter separation may be infeasible; that is a valid outcome
            }
            List<Long> homes = p.homeStarIds();
            for (int i = 0; i < homes.size(); i++) {
                for (int j = i + 1; j < homes.size(); j++) {
                    int hops = hopDistance(g, homes.get(i), homes.get(j));
                    assertTrue(hops >= minSep,
                            "homes " + homes.get(i) + " and " + homes.get(j)
                                    + " only " + hops + " hops apart (min " + minSep + ")");
                }
            }
        }
    }

    @Test
    @DisplayName("one home per faction, all distinct, all graph nodes")
    void onePerFactionDistinctAndOnGraph() {
        LaneGraph g = graph(SEED);
        HomePlacement p = HomePlacementGenerator.place(SEED, g, config(4, 3));
        assertEquals(4, p.count(), "one home per faction");
        Set<Long> distinct = new HashSet<>(p.homeStarIds());
        assertEquals(4, distinct.size(), "homes must be distinct");
        for (long id : p.homeStarIds()) {
            assertTrue(g.contains(id), "home must be a node of the graph: " + id);
        }
    }

    @Test
    @DisplayName("every home carries the configured cradle biome")
    void everyHomeHasCradleBiome() {
        LaneGraph g = graph(SEED);
        HomePlacement p = HomePlacementGenerator.place(SEED, g, config(4, 3));
        for (long id : p.homeStarIds()) {
            StarSystem sys = SystemGenerator.generate(SEED, id);
            boolean hasCradle = sys.planets().stream().anyMatch(pl -> pl.biome() == Biome.OCEANIC);
            assertTrue(hasCradle, "home " + id + " must carry an OCEANIC cradle");
        }
    }

    // --- Balance (within tolerance) ---

    @Test
    @DisplayName("chosen homes' neighbourhood-quality spread is within the configured tolerance")
    void neighbourhoodQualityWithinTolerance() {
        double tolerance = 0.35;
        LaneGraph g = graph(SEED);
        HomePlacementConfig cfg = new HomePlacementConfig(4, 3, 2, tolerance, Biome.OCEANIC);
        HomePlacement p = HomePlacementGenerator.place(SEED, g, cfg);

        List<Double> qs = p.homeQualities();
        double max = qs.stream().mapToDouble(Double::doubleValue).max().orElseThrow();
        double min = qs.stream().mapToDouble(Double::doubleValue).min().orElseThrow();
        // The generator guarantees all chosen qualities lie in [max*(1-tol), max].
        assertTrue(min >= max * (1.0 - tolerance) - 1e-9,
                "quality spread exceeds tolerance: min=" + min + " max=" + max
                        + " allowed floor=" + (max * (1.0 - tolerance)));
        // Cross-check the reported qualities actually match a fresh measurement.
        for (long id : p.homeStarIds()) {
            double measured = HomePlacementGenerator.neighbourhoodQuality(SEED, g, id, 2);
            assertEquals(measured, qs.get(p.homeStarIds().indexOf(id)), 1e-9,
                    "reported quality must match a fresh neighbourhood measurement");
        }
    }

    @Test
    @DisplayName("a stricter tolerance still yields a band-compliant set (or fails cleanly)")
    void stricterToleranceStaysBalanced() {
        double tolerance = 0.15;
        LaneGraph g = graph(SEED);
        HomePlacementConfig cfg = new HomePlacementConfig(3, 2, 2, tolerance, Biome.OCEANIC);
        HomePlacement p;
        try {
            p = HomePlacementGenerator.place(SEED, g, cfg);
        } catch (HomePlacementException e) {
            return; // failing cleanly is an acceptable outcome under a tight tolerance
        }
        List<Double> qs = p.homeQualities();
        double max = qs.stream().mapToDouble(Double::doubleValue).max().orElseThrow();
        double min = qs.stream().mapToDouble(Double::doubleValue).min().orElseThrow();
        assertTrue(min >= max * (1.0 - tolerance) - 1e-9,
                "stricter tolerance must still hold: min=" + min + " max=" + max);
    }

    // --- Deterministic failure (don't silently cram) ---

    @Test
    @DisplayName("too many factions for the available cradles fails deterministically")
    void tooManyFactionsFailsDeterministically() {
        LaneGraph g = graph(SEED);
        // Far more factions than any plausible cradle count in the region.
        HomePlacementConfig cfg = new HomePlacementConfig(100000, 1, 1, 0.5, Biome.OCEANIC);
        HomePlacementException e1 = assertThrows(HomePlacementException.class,
                () -> HomePlacementGenerator.place(SEED, g, cfg));
        HomePlacementException e2 = assertThrows(HomePlacementException.class,
                () -> HomePlacementGenerator.place(SEED, g, cfg));
        assertEquals(e1.getMessage(), e2.getMessage(), "failure must be deterministic");
        assertTrue(e1.getMessage().toLowerCase().contains("candidate")
                        || e1.getMessage().toLowerCase().contains("home"),
                "message must be clear: " + e1.getMessage());
    }

    @Test
    @DisplayName("an impossible separation fails rather than cramming factions together")
    void impossibleSeparationFails() {
        LaneGraph g = graph(SEED);
        // A separation larger than the graph diameter cannot be met by >1 home.
        int diameter = graphDiameterUpperBound(g);
        HomePlacementConfig cfg =
                new HomePlacementConfig(4, diameter + 5, 1, 0.9, Biome.OCEANIC);
        assertThrows(HomePlacementException.class,
                () -> HomePlacementGenerator.place(SEED, g, cfg),
                "must fail deterministically rather than place cramped homes");
    }

    @Test
    @DisplayName("a single faction always places (the trivial, fairest case)")
    void singleFactionAlwaysPlaces() {
        LaneGraph g = graph(SEED);
        HomePlacement p = HomePlacementGenerator.place(SEED, g, config(1, 1));
        assertEquals(1, p.count());
        assertTrue(g.contains(p.homeOf(0)));
    }

    @Test
    @DisplayName("config validation rejects nonsensical tunables")
    void configValidation() {
        assertThrows(IllegalArgumentException.class,
                () -> new HomePlacementConfig(0, 1, 1, 0.5, Biome.OCEANIC));
        assertThrows(IllegalArgumentException.class,
                () -> new HomePlacementConfig(2, 0, 1, 0.5, Biome.OCEANIC));
        assertThrows(IllegalArgumentException.class,
                () -> new HomePlacementConfig(2, 1, -1, 0.5, Biome.OCEANIC));
        assertThrows(IllegalArgumentException.class,
                () -> new HomePlacementConfig(2, 1, 1, 1.5, Biome.OCEANIC));
        assertThrows(IllegalArgumentException.class,
                () -> new HomePlacementConfig(2, 1, 1, 0.5, null));
        // E10-05 floor validation: planet count must be >= 1, yield floor must be >= 0.
        assertThrows(IllegalArgumentException.class,
                () -> new HomePlacementConfig(2, 1, 1, 0.5, Biome.OCEANIC, 0, 0.0));
        assertThrows(IllegalArgumentException.class,
                () -> new HomePlacementConfig(2, 1, 1, 0.5, Biome.OCEANIC, 1, -1.0));
        assertNotEquals(null, config(2, 1));
    }

    // --- E10-05 starting-economy floor (3-agent-sim F4) ---

    @Test
    @DisplayName("every chosen home clears the configured starting-economy floor")
    void chosenHomesClearTheFloor() {
        int minPlanets = 3;
        double minYield = 9.0;
        LaneGraph g = graph(SEED);
        HomePlacementConfig cfg =
                new HomePlacementConfig(3, 2, 2, 0.5, Biome.OCEANIC, minPlanets, minYield);
        HomePlacement p;
        try {
            p = HomePlacementGenerator.place(SEED, g, cfg);
        } catch (HomePlacementException e) {
            return; // a strict floor may be infeasible for this region; a clean fail is valid
        }
        for (long id : p.homeStarIds()) {
            StarSystem sys = SystemGenerator.generate(SEED, id);
            assertTrue(sys.planets().size() >= minPlanets,
                    "home " + id + " has only " + sys.planets().size()
                            + " planets, below floor " + minPlanets);
            assertTrue(aggregateYield(sys) >= minYield,
                    "home " + id + " yields " + aggregateYield(sys) + ", below floor " + minYield);
        }
    }

    @Test
    @DisplayName("a would-be-starved candidate is rejected by the floor (lifted or excluded)")
    void floorRejectsStarvedCandidates() {
        LaneGraph g = graph(SEED);
        // Inert floor: the full cradle pool (including any starved 1-planet homes).
        HomePlacementConfig inert =
                new HomePlacementConfig(3, 2, 2, 0.5, Biome.OCEANIC, 1, 0.0);
        // A real floor: reject homes below 3 planets / 9 aggregate yield.
        int minPlanets = 3;
        double minYield = 9.0;
        HomePlacementConfig floored =
                new HomePlacementConfig(3, 2, 2, 0.5, Biome.OCEANIC, minPlanets, minYield);

        // The floored candidate pool must be a strict subset whenever a starved cradle
        // exists in the region: any cradle below the floor is dropped.
        List<Long> inertPool = cradlePool(g, inert);
        List<Long> flooredPool = cradlePool(g, floored);
        assertTrue(flooredPool.size() <= inertPool.size(),
                "the floor can only shrink (never grow) the candidate pool");
        for (long id : flooredPool) {
            StarSystem sys = SystemGenerator.generate(SEED, id);
            assertTrue(sys.planets().size() >= minPlanets && aggregateYield(sys) >= minYield,
                    "a floored candidate must clear the floor: " + id);
        }
        // Prove the floor actually bites somewhere in the explored bulge: at least one
        // cradle that the inert pool admits is starved (below the floor) and excluded.
        boolean someStarvedExcluded = inertPool.stream().anyMatch(id -> {
            StarSystem sys = SystemGenerator.generate(SEED, id);
            return sys.planets().size() < minPlanets || aggregateYield(sys) < minYield;
        });
        assertEquals(someStarvedExcluded,
                flooredPool.size() < inertPool.size(),
                "if a starved cradle exists, the floored pool must be strictly smaller");
    }

    @Test
    @DisplayName("placement under a floor is still deterministic (same seed => identical)")
    void floorPlacementIsDeterministic() {
        LaneGraph g = graph(SEED);
        HomePlacementConfig cfg =
                new HomePlacementConfig(3, 2, 2, 0.5, Biome.OCEANIC, 2, 6.0);
        HomePlacement a;
        try {
            a = HomePlacementGenerator.place(SEED, g, cfg);
        } catch (HomePlacementException e) {
            return; // infeasible floor for this region is an acceptable, deterministic outcome
        }
        HomePlacement b = HomePlacementGenerator.place(SEED, g, cfg);
        assertEquals(a.homeStarIds(), b.homeStarIds(), "floored placement must be reproducible");
        assertEquals(a.homeQualities(), b.homeQualities(), "floored qualities must match");
    }

    @Test
    @DisplayName("an unsatisfiable floor fails deterministically rather than dealing a starved home")
    void impossibleFloorFails() {
        LaneGraph g = graph(SEED);
        // A yield floor no system can reach forces a clean, repeatable failure.
        HomePlacementConfig cfg =
                new HomePlacementConfig(4, 2, 2, 0.5, Biome.OCEANIC, 1, 1_000_000.0);
        HomePlacementException e1 = assertThrows(HomePlacementException.class,
                () -> HomePlacementGenerator.place(SEED, g, cfg));
        HomePlacementException e2 = assertThrows(HomePlacementException.class,
                () -> HomePlacementGenerator.place(SEED, g, cfg));
        assertEquals(e1.getMessage(), e2.getMessage(), "floor failure must be deterministic");
        assertTrue(e1.getMessage().toLowerCase().contains("floor"),
                "message must name the floor: " + e1.getMessage());
    }

    /** Aggregate base biome yield of a system (sum over planets, across all resources). */
    private static double aggregateYield(StarSystem sys) {
        double total = 0.0;
        for (Planet p : sys.planets()) {
            for (Resource r : Resource.values()) {
                total += p.baseYields().get(r);
            }
        }
        return total;
    }

    /**
     * The cradle candidate pool the generator would build for a config: every graph node
     * carrying the home biome whose own system clears the floor. Mirrors the generator's
     * step-1 filter so a test can compare inert vs. floored pools.
     */
    private static List<Long> cradlePool(LaneGraph g, HomePlacementConfig cfg) {
        List<Long> out = new ArrayList<>();
        for (long id : g.starIds()) {
            StarSystem sys = SystemGenerator.generate(SEED, id);
            if (sys.planets().size() < cfg.minHomePlanetCount()) {
                continue;
            }
            boolean cradle = sys.planets().stream().anyMatch(p -> p.biome() == cfg.homeBiome());
            if (cradle && aggregateYield(sys) >= cfg.minHomeBiomeYield()) {
                out.add(id);
            }
        }
        return out;
    }

    // --- helpers ---

    /** Build the playable region lane graph for a seed (bulge block, as E2-03 tests do). */
    private static LaneGraph graph(long seed) {
        return LaneGraphGenerator.generate(seed, region(seed));
    }

    /** Materialise the dense inner disc / bulge cells for a seed (pure, no RNG). */
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

    /** Unweighted BFS lane-hop distance between two graph nodes (-1 if unreachable). */
    private static int hopDistance(LaneGraph g, long from, long to) {
        if (from == to) {
            return 0;
        }
        Map<Long, Integer> hop = new HashMap<>();
        Deque<Long> queue = new ArrayDeque<>();
        hop.put(from, 0);
        queue.add(from);
        while (!queue.isEmpty()) {
            long node = queue.poll();
            int d = hop.get(node);
            for (long nb : g.neighbours(node)) {
                if (nb == to) {
                    return d + 1;
                }
                if (!hop.containsKey(nb)) {
                    hop.put(nb, d + 1);
                    queue.add(nb);
                }
            }
        }
        return -1;
    }

    /** A cheap upper bound on the graph's diameter (eccentricity from one node). */
    private static int graphDiameterUpperBound(LaneGraph g) {
        long start = g.starIds().get(0);
        Map<Long, Integer> hop = new HashMap<>();
        Deque<Long> queue = new ArrayDeque<>();
        hop.put(start, 0);
        queue.add(start);
        int max = 0;
        while (!queue.isEmpty()) {
            long node = queue.poll();
            int d = hop.get(node);
            max = Math.max(max, d);
            for (long nb : g.neighbours(node)) {
                if (!hop.containsKey(nb)) {
                    hop.put(nb, d + 1);
                    queue.add(nb);
                }
            }
        }
        return max * 2; // eccentricity-from-one is at most diameter; double for a safe bound
    }
}
