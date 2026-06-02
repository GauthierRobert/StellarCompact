package com.stellarcompact.galaxy.tile;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Hilbert curve correctness: index<->(x,y) is an exact bijection per level
 * (the round-trip the lod-tiling skill / E8-04 require), plus the locality and
 * range properties the cache/CDN ordering depends on.
 */
class HilbertCurveTest {

    @Test
    void roundTripsForEveryTileAtSeveralLevels() {
        for (int level = 0; level <= 8; level++) {
            long n = 1L << level;
            for (long y = 0; y < n; y++) {
                for (long x = 0; x < n; x++) {
                    long d = HilbertCurve.index(level, x, y);
                    long[] back = HilbertCurve.coords(level, d);
                    assertEquals(x, back[0], "x round-trip at level " + level);
                    assertEquals(y, back[1], "y round-trip at level " + level);
                }
            }
        }
    }

    @Test
    void indexIsAPermutationOfZeroToNSquaredMinusOne() {
        for (int level = 0; level <= 6; level++) {
            long n = 1L << level;
            long total = n * n;
            Set<Long> seen = new HashSet<>();
            for (long y = 0; y < n; y++) {
                for (long x = 0; x < n; x++) {
                    long d = HilbertCurve.index(level, x, y);
                    assertTrue(d >= 0 && d < total, "index in range");
                    assertTrue(seen.add(d), "index " + d + " unique");
                }
            }
            assertEquals(total, seen.size());
        }
    }

    @Test
    void consecutiveIndicesAreSpatiallyAdjacent() {
        // The defining Hilbert property: stepping the index by 1 moves to a
        // 4-neighbour tile (Manhattan distance 1). This is what gives locality.
        for (int level = 1; level <= 8; level++) {
            long n = 1L << level;
            long total = n * n;
            for (long d = 1; d < total; d++) {
                long[] a = HilbertCurve.coords(level, d - 1);
                long[] b = HilbertCurve.coords(level, d);
                long manhattan = Math.abs(a[0] - b[0]) + Math.abs(a[1] - b[1]);
                assertEquals(1L, manhattan,
                        "adjacency at level " + level + " step " + d);
            }
        }
    }

    @Test
    void level0IsTheSingleTileAtIndexZero() {
        assertEquals(0L, HilbertCurve.index(0, 0, 0));
        long[] c = HilbertCurve.coords(0, 0);
        assertEquals(0L, c[0]);
        assertEquals(0L, c[1]);
    }

    @Test
    void rejectsOutOfRangeInputs() {
        assertThrows(IllegalArgumentException.class, () -> HilbertCurve.index(2, 4, 0));
        assertThrows(IllegalArgumentException.class, () -> HilbertCurve.index(-1, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> HilbertCurve.coords(2, 16));
    }
}
