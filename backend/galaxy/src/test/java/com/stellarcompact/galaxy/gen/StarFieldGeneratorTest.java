package com.stellarcompact.galaxy.gen;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Determinism + density-character tests for {@link StarFieldGenerator}.
 *
 * <p>Determinism is asserted both structurally (repeated and independent calls
 * return equal lists) and via a canonical fold of the output (a local
 * golden-style hash, since the galaxy module has no GoldenStateHash util). The
 * golden hash is pinned so accidental changes to the placement maths fail loudly.
 *
 * <p>"Matches the PoC density character" is asserted statistically: cells in the
 * bulge are denser than halo cells, and arm cells are denser than inter-arm
 * cells at the same radius. These mirror the spiral-arm + bulge + halo shape of
 * {@code poc/galaxy-navigator.html}.
 */
class StarFieldGeneratorTest {

    private static final long SEED = 0xC0FFEEL;

    /** Pinned on first green run (printed by goldenPatchHash failure). */
    private static final long GOLDEN_PATCH_HASH = 8405097635591554367L;

    // --- Determinism ---

    @Test
    @DisplayName("same (seed, cell) returns an identical list on repeated calls")
    void repeatedCallsAreIdentical() {
        Cell cell = new Cell(3, -2);
        List<Star> a = StarFieldGenerator.generate(SEED, cell);
        List<Star> b = StarFieldGenerator.generate(SEED, cell);
        assertEquals(a, b, "repeated generate() must be structurally identical");
        assertEquals(foldHash(a), foldHash(b), "canonical hashes must match");
    }

    @Test
    @DisplayName("no hidden state: independent call sequences agree across many cells")
    void noHiddenState() {
        // Interleave a noise call sequence to perturb any (illegal) shared
        // state, then assert the target cell still matches a clean computation.
        Cell target = new Cell(1, 1);
        List<Star> clean = StarFieldGenerator.generate(SEED, target);

        long acc = 0;
        for (int i = 0; i < 50; i++) {
            acc ^= foldHash(StarFieldGenerator.generate(SEED ^ i, new Cell(i, -i)));
        }
        // Touch acc so the noise loop cannot be elided.
        assertTrue(acc != 0 || acc == 0);

        List<Star> again = StarFieldGenerator.generate(SEED, target);
        assertEquals(clean, again, "interleaving other calls must not change output");
    }

    @Test
    @DisplayName("generate returns an immutable list")
    void outputIsImmutable() {
        List<Star> stars = StarFieldGenerator.generate(SEED, new Cell(0, 0));
        // List.copyOf yields an unmodifiable list whose copy is itself.
        assertSame(stars, List.copyOf(stars));
    }

    @Test
    @DisplayName("different seeds produce different fields")
    void differentSeedsDiffer() {
        long hashA = patchHash(SEED);
        long hashB = patchHash(SEED + 1);
        assertNotEquals(hashA, hashB, "distinct seeds must yield distinct fields");
    }

    @Test
    @DisplayName("golden: pinned canonical hash of a fixed patch")
    void goldenPatchHash() {
        long actual = patchHash(SEED);
        // Pinned on first green run; a change here means placement maths moved.
        assertEquals(GOLDEN_PATCH_HASH, actual,
                "placement output changed; if intentional, re-pin GOLDEN_PATCH_HASH");
    }

    // --- Density character (matches PoC: bulge + arms + halo) ---

    @Test
    @DisplayName("bulge cells are far denser than halo cells")
    void bulgeDenserThanHalo() {
        int bulge = countNear(0.0, 0.0);
        int halo = countNear(850.0, 0.0);
        assertTrue(bulge > halo * 3,
                "bulge should dominate halo; bulge=" + bulge + " halo=" + halo);
    }

    @Test
    @DisplayName("arm cells are denser than inter-arm cells at the same radius")
    void armDenserThanInterArm() {
        double r = 450.0;
        int armTotal = 0;
        int interTotal = 0;
        int interSamples = 0;
        int armSamples = 0;
        for (int deg = 0; deg < 360; deg += 5) {
            double th = Math.toRadians(deg);
            double x = Math.cos(th) * r;
            double y = Math.sin(th) * r;
            int c = countNear(x, y);
            if (onArmCrest(x, y, r)) {
                armTotal += c;
                armSamples++;
            } else {
                interTotal += c;
                interSamples++;
            }
        }
        double armAvg = armTotal / (double) armSamples;
        double interAvg = interTotal / (double) interSamples;
        assertTrue(armAvg > interAvg,
                "arm crests must be denser than inter-arm at r=" + r
                        + "; armAvg=" + armAvg + " interAvg=" + interAvg);
    }

    @Test
    @DisplayName("no stars are placed beyond the galaxy radius")
    void noStarsBeyondRadius() {
        int cellsAcross = (int) Math.ceil((GalaxyConstants.R_MAX * 1.4)
                / GalaxyConstants.CELL_SIZE);
        for (int cx = -cellsAcross; cx <= cellsAcross; cx += 3) {
            for (int cy = -cellsAcross; cy <= cellsAcross; cy += 3) {
                for (Star s : StarFieldGenerator.generate(SEED, new Cell(cx, cy))) {
                    double rr = Math.hypot(s.pos().x(), s.pos().y());
                    assertTrue(rr <= GalaxyConstants.R_MAX + 1e-9,
                            "star outside R_MAX at r=" + rr);
                }
            }
        }
    }

    // --- helpers ---

    /** Total stars in the 3x3 block of cells around a world position. */
    private static int countNear(double x, double y) {
        int cx = (int) Math.floor(x / GalaxyConstants.CELL_SIZE);
        int cy = (int) Math.floor(y / GalaxyConstants.CELL_SIZE);
        int total = 0;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                total += StarFieldGenerator.generate(SEED,
                        new Cell(cx + dx, cy + dy)).size();
            }
        }
        return total;
    }

    /** Whether (x,y) lies within ~half an arm-width of the nearest arm crest. */
    private static boolean onArmCrest(double x, double y, double r) {
        double phase = GalaxyConstants.WIND
                * Math.log(Math.max(r, 1.0) / GalaxyConstants.R_MAX
                        * GalaxyConstants.ARM_LOG_SCALE + GalaxyConstants.ARM_LOG_BIAS);
        double theta = Math.atan2(y, x);
        double spacing = 2.0 * Math.PI / GalaxyConstants.ARMS;
        double delta = theta - phase;
        delta = delta - spacing * Math.floor(delta / spacing + 0.5);
        return Math.abs(delta) < GalaxyConstants.ARM_HALF_WIDTH * 0.6;
    }

    /** Canonical order-sensitive fold of a star list (local golden hash). */
    private static long foldHash(List<Star> stars) {
        long h = 1125899906842597L;
        for (Star s : stars) {
            h = h * 31 + s.id();
            h = h * 31 + Double.doubleToLongBits(s.pos().x());
            h = h * 31 + Double.doubleToLongBits(s.pos().y());
        }
        return h;
    }

    /** Fold over a fixed patch of cells for seed-sensitivity / golden tests. */
    private static long patchHash(long seed) {
        long h = 0;
        for (int cx = -5; cx <= 5; cx++) {
            for (int cy = -5; cy <= 5; cy++) {
                h = h * 1099511628211L
                        + foldHash(StarFieldGenerator.generate(seed, new Cell(cx, cy)));
            }
        }
        return h;
    }
}
