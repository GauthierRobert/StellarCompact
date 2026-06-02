package com.stellarcompact.galaxy.gen;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Determinism + basic distribution checks for the seed mixer. */
class SeedHashTest {

    @Test
    void combineIsPureAndOrderSensitive() {
        assertEquals(SeedHash.combine(1, 2, 3), SeedHash.combine(1, 2, 3),
                "combine must be a pure function of its inputs");
        assertNotEquals(SeedHash.combine(1, 2, 3), SeedHash.combine(3, 2, 1),
                "argument order must matter");
    }

    @Test
    void unitDoublesStayInRange() {
        for (long i = 0; i < 10_000; i++) {
            double u = SeedHash.toUnitDouble(SeedHash.mix(i));
            assertTrue(u >= 0.0 && u < 1.0, "out of range: " + u);
        }
    }

    @Test
    void unitDoublesAreRoughlyUniform() {
        int[] buckets = new int[10];
        int n = 100_000;
        for (long i = 0; i < n; i++) {
            double u = SeedHash.toUnitDouble(SeedHash.combine(0xABCDEFL, i));
            buckets[(int) (u * 10)]++;
        }
        int expected = n / 10;
        for (int b : buckets) {
            assertTrue(Math.abs(b - expected) < expected * 0.1,
                    "bucket skew too high: " + b + " vs " + expected);
        }
    }
}
