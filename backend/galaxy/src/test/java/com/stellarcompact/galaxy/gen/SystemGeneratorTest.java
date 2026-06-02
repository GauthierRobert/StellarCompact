package com.stellarcompact.galaxy.gen;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Determinism + distribution tests for {@link SystemGenerator} (E2-02).
 *
 * <p>Determinism is asserted structurally (repeated and independent calls agree)
 * and via a pinned canonical golden hash so any accidental change to the roster
 * maths fails loudly. Distributions (spectral, biome, planet-count, slots) are
 * asserted statistically over a large seeded sample with documented thresholds.
 */
class SystemGeneratorTest {

    private static final long SEED = 0xC0FFEEL;

    /** Pinned on first green run (printed by goldenSystemHash failure). */
    private static final long GOLDEN_SYSTEM_HASH = -7505506005924917595L;

    // --- Determinism ---

    @Test
    @DisplayName("same (seed, systemId) returns an identical system on repeated calls")
    void repeatedCallsAreIdentical() {
        StarSystem a = SystemGenerator.generate(SEED, 12345L);
        StarSystem b = SystemGenerator.generate(SEED, 12345L);
        assertEquals(a, b, "repeated generate() must be structurally identical");
        assertEquals(foldHash(a), foldHash(b), "canonical hashes must match");
    }

    @Test
    @DisplayName("no hidden state: independent call sequences agree")
    void noHiddenState() {
        StarSystem clean = SystemGenerator.generate(SEED, 777L);
        long acc = 0;
        for (int i = 0; i < 100; i++) {
            acc ^= foldHash(SystemGenerator.generate(SEED ^ i, i * 31L));
        }
        assertTrue(acc != 0 || acc == 0);
        StarSystem again = SystemGenerator.generate(SEED, 777L);
        assertEquals(clean, again, "interleaving other calls must not change output");
    }

    @Test
    @DisplayName("the Star overload matches the explicit-id overload")
    void starOverloadMatchesIdOverload() {
        Star star = new Star(98765L, new StarCoords(10.0, -20.0));
        assertEquals(SystemGenerator.generate(SEED, star.id()),
                SystemGenerator.generate(SEED, star));
    }

    @Test
    @DisplayName("the planet roster is an immutable list")
    void rosterIsImmutable() {
        List<Planet> planets = SystemGenerator.generate(SEED, 1L).planets();
        assertSame(planets, List.copyOf(planets));
    }

    @Test
    @DisplayName("different seeds and different ids produce different systems")
    void differentInputsDiffer() {
        long h0 = foldHash(SystemGenerator.generate(SEED, 1L));
        long h1 = foldHash(SystemGenerator.generate(SEED + 1, 1L));
        long h2 = foldHash(SystemGenerator.generate(SEED, 2L));
        assertNotEquals(h0, h1, "distinct seeds must differ");
        assertNotEquals(h0, h2, "distinct ids must differ");
    }

    @Test
    @DisplayName("golden: pinned canonical hash over a fixed batch of systems")
    void goldenSystemHash() {
        long actual = batchHash(SEED);
        assertEquals(GOLDEN_SYSTEM_HASH, actual,
                "roster output changed; if intentional, re-pin GOLDEN_SYSTEM_HASH");
    }

    // --- Structural invariants ---

    @Test
    @DisplayName("planet count is always within the configured bounds")
    void planetCountBounded() {
        for (long id = 0; id < 5000; id++) {
            int n = SystemGenerator.generate(SEED, id).planetCount();
            assertTrue(n >= GalaxyConstants.MIN_PLANETS && n <= GalaxyConstants.MAX_PLANETS,
                    "planet count out of bounds: " + n);
        }
    }

    @Test
    @DisplayName("gas giants have zero ground slots; other biomes have zero orbital slots")
    void slotPlacementByBiome() {
        for (long id = 0; id < 5000; id++) {
            for (Planet p : SystemGenerator.generate(SEED, id).planets()) {
                if (p.biome() == Biome.GAS_GIANT) {
                    assertEquals(0, p.groundSlots(), "gas giant must have no ground slots");
                    assertTrue(p.orbitalSlots() > 0, "gas giant should have orbital slots");
                } else {
                    assertEquals(0, p.orbitalSlots(), "non-gas biomes carry ground slots only");
                    assertTrue(p.groundSlots() > 0, "planet must have at least one slot");
                }
            }
        }
    }

    @Test
    @DisplayName("slot counts stay within their planet-size band, and bands are monotonic")
    void slotsRespectSizeBand() {
        for (long id = 0; id < 8000; id++) {
            for (Planet p : SystemGenerator.generate(SEED, id).planets()) {
                int slots = p.totalSlots();
                assertTrue(slots >= p.size().minSlots() && slots <= p.size().maxSlots(),
                        "slots " + slots + " outside band for " + p.size());
            }
        }
        assertTrue(PlanetSize.SMALL.maxSlots() < PlanetSize.MEDIUM.minSlots());
        assertTrue(PlanetSize.MEDIUM.maxSlots() < PlanetSize.LARGE.minSlots());
        assertTrue(PlanetSize.LARGE.maxSlots() < PlanetSize.HUGE.minSlots());
    }

    @Test
    @DisplayName("base yields reflect the biome favoured resource")
    void yieldsMatchBiome() {
        boolean found = false;
        outer:
        for (long id = 0; id < 5000; id++) {
            for (Planet p : SystemGenerator.generate(SEED, id).planets()) {
                if (p.biome() == Biome.DESERT) {
                    assertTrue(p.baseYields().get(Resource.ENERGY) > 0,
                            "Desert should favour Energy");
                    found = true;
                    break outer;
                }
            }
        }
        assertTrue(found, "expected at least one Desert planet in the sample");
    }

    // --- Distributions ---

    @Test
    @DisplayName("spectral distribution sane: M common, O rare, monotone toward M")
    void spectralDistributionSane() {
        EnumMap<SpectralClass, Integer> counts = new EnumMap<>(SpectralClass.class);
        for (SpectralClass c : SpectralClass.values()) {
            counts.put(c, 0);
        }
        int total = 100000;
        for (long id = 0; id < total; id++) {
            counts.merge(SystemGenerator.generate(SEED, id).spectral(), 1, Integer::sum);
        }
        double m = counts.get(SpectralClass.M) / (double) total;
        double o = counts.get(SpectralClass.O) / (double) total;
        assertTrue(m > 0.40, "M should dominate; was " + m);
        assertTrue(o < 0.01, "O should be rare; was " + o);
        assertTrue(counts.get(SpectralClass.O) > 0, "O should still appear");
        SpectralClass[] order = SpectralClass.values();
        for (int i = 1; i < order.length; i++) {
            assertTrue(counts.get(order[i]) > counts.get(order[i - 1]),
                    "expected monotone counts at " + order[i]);
        }
    }

    @Test
    @DisplayName("hotter spectral classes are brighter and bigger")
    void brightnessSizeCorrelateWithClass() {
        EnumMap<SpectralClass, double[]> sums = new EnumMap<>(SpectralClass.class);
        EnumMap<SpectralClass, Integer> n = new EnumMap<>(SpectralClass.class);
        for (long id = 0; id < 100000; id++) {
            StarSystem s = SystemGenerator.generate(SEED, id);
            sums.computeIfAbsent(s.spectral(), k -> new double[2]);
            double[] acc = sums.get(s.spectral());
            acc[0] += s.brightness();
            acc[1] += s.size();
            n.merge(s.spectral(), 1, Integer::sum);
        }
        double oBright = sums.get(SpectralClass.O)[0] / n.get(SpectralClass.O);
        double mBright = sums.get(SpectralClass.M)[0] / n.get(SpectralClass.M);
        double oSize = sums.get(SpectralClass.O)[1] / n.get(SpectralClass.O);
        double mSize = sums.get(SpectralClass.M)[1] / n.get(SpectralClass.M);
        assertTrue(oBright > mBright, "O must be brighter than M");
        assertTrue(oSize > mSize, "O must be bigger than M");
    }

    @Test
    @DisplayName("biome distribution: inhospitable dominates, cradle a minority but present")
    void biomeDistributionPlausible() {
        EnumMap<Biome, Integer> counts = new EnumMap<>(Biome.class);
        for (Biome b : Biome.values()) {
            counts.put(b, 0);
        }
        int totalPlanets = 0;
        for (long id = 0; id < 50000; id++) {
            for (Planet p : SystemGenerator.generate(SEED, id).planets()) {
                counts.merge(p.biome(), 1, Integer::sum);
                totalPlanets++;
            }
        }
        double cradle = (counts.get(Biome.OCEANIC) + counts.get(Biome.TERRAN))
                / (double) totalPlanets;
        assertTrue(cradle < 0.20, "cradle worlds should be a minority; was " + cradle);
        assertTrue(cradle > 0.03, "cradle worlds should still appear; was " + cradle);
        assertTrue(counts.get(Biome.OCEANIC) > 0 && counts.get(Biome.TERRAN) > 0,
                "both cradle biomes must appear");
        double inhospitable = (counts.get(Biome.VOLCANIC) + counts.get(Biome.FROZEN)
                + counts.get(Biome.TOXIC) + counts.get(Biome.GAS_GIANT))
                / (double) totalPlanets;
        assertTrue(inhospitable > cradle * 3,
                "inhospitable biomes should dominate; inhospitable="
                        + inhospitable + " cradle=" + cradle);
        int toxic = counts.get(Biome.TOXIC);
        for (Biome b : Biome.values()) {
            if (b != Biome.TOXIC) {
                assertTrue(toxic >= counts.get(b), "Toxic should be the most common biome");
            }
        }
    }

    @Test
    @DisplayName("planet count distribution covers the whole configured range")
    void planetCountSpread() {
        boolean sawMin = false;
        boolean sawMax = false;
        for (long id = 0; id < 20000; id++) {
            int n = SystemGenerator.generate(SEED, id).planetCount();
            if (n == GalaxyConstants.MIN_PLANETS) {
                sawMin = true;
            }
            if (n == GalaxyConstants.MAX_PLANETS) {
                sawMax = true;
            }
        }
        assertTrue(sawMin, "expected at least one minimum-size system");
        assertTrue(sawMax, "expected at least one maximum-size system");
    }

    // --- helpers ---

    /** Canonical order-sensitive fold of a system (local golden hash). */
    private static long foldHash(StarSystem s) {
        long h = 1125899906842597L;
        h = h * 31 + s.systemId();
        h = h * 31 + s.spectral().ordinal();
        h = h * 31 + Double.doubleToLongBits(s.brightness());
        h = h * 31 + Double.doubleToLongBits(s.size());
        for (Planet p : s.planets()) {
            h = h * 31 + p.orbitIndex();
            h = h * 31 + p.biome().ordinal();
            h = h * 31 + p.size().ordinal();
            h = h * 31 + p.groundSlots();
            h = h * 31 + p.orbitalSlots();
            for (Resource r : Resource.values()) {
                h = h * 31 + p.baseYields().get(r);
            }
        }
        return h;
    }

    /** Fold over a fixed batch of system ids for the golden / seed test. */
    private static long batchHash(long seed) {
        long h = 0;
        for (long id = 0; id < 256; id++) {
            h = h * 1099511628211L + foldHash(SystemGenerator.generate(seed, id));
        }
        return h;
    }
}
