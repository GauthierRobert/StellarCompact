package com.stellarcompact.engine.rng;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Determinism, decorrelation and no-leakage guarantees for the engine RNG.
 *
 * <p>These tests defend the replay contract (skill game-engine-determinism):
 * identical (gameSeed, tick, salt) must yield byte-identical streams, and the
 * generator must carry no wall-clock / global-state dependency.
 */
class DeterministicRngTest {

    private static final long SEED = 0xABCDEF0123456789L;

    private static long[] drawLongs(DeterministicRng rng, int n) {
        long[] out = new long[n];
        for (int i = 0; i < n; i++) {
            out[i] = rng.nextLong();
        }
        return out;
    }

    @Test
    void sameTripleProducesByteIdenticalStream() {
        long battleId = 42L;
        DeterministicRng a = SeedDerivation.rng(SEED, 100L, SaltDomain.COMBAT, battleId);
        DeterministicRng b = SeedDerivation.rng(SEED, 100L, SaltDomain.COMBAT, battleId);

        assertArrayEqualsLong(drawLongs(a, 64), drawLongs(b, 64),
                "two independently constructed RNGs with the same triple must match");
    }

    @Test
    void differentSaltKeyDivergesWithinSameDomainAndTick() {
        DeterministicRng a = SeedDerivation.rng(SEED, 100L, SaltDomain.COMBAT, 1L);
        DeterministicRng b = SeedDerivation.rng(SEED, 100L, SaltDomain.COMBAT, 2L);
        assertStreamsDiffer(a, b);
    }

    @Test
    void differentDomainDivergesWithSameKeyAndTick() {
        DeterministicRng a = SeedDerivation.rng(SEED, 100L, SaltDomain.COMBAT, 7L);
        DeterministicRng b = SeedDerivation.rng(SEED, 100L, SaltDomain.ESPIONAGE, 7L);
        assertStreamsDiffer(a, b);
    }

    @Test
    void differentTickDivergesWithSameDomainAndKey() {
        DeterministicRng a = SeedDerivation.rng(SEED, 100L, SaltDomain.COMBAT, 7L);
        DeterministicRng b = SeedDerivation.rng(SEED, 101L, SaltDomain.COMBAT, 7L);
        assertStreamsDiffer(a, b);
    }

    @Test
    void differentGameSeedDiverges() {
        DeterministicRng a = SeedDerivation.rng(SEED, 100L, SaltDomain.COMBAT, 7L);
        DeterministicRng b = SeedDerivation.rng(SEED ^ 1L, 100L, SaltDomain.COMBAT, 7L);
        assertStreamsDiffer(a, b);
    }

    @Test
    void adjacentTriplesAreDecorrelated() {
        Set<Long> firstDraws = new HashSet<>();
        int collisions = 0;
        for (long tick = 0; tick < 64; tick++) {
            for (long key = 0; key < 64; key++) {
                long v = SeedDerivation.rng(SEED, tick, SaltDomain.COMBAT, key).nextLong();
                if (!firstDraws.add(v)) {
                    collisions++;
                }
            }
        }
        assertEquals(0, collisions, "adjacent triples produced colliding first draws");
    }

    @Test
    void reconstructedRngGivesIdenticalOutputAtDifferentWallClockTimes() throws Exception {
        DeterministicRng first = SeedDerivation.rng(SEED, 7L, SaltDomain.ESPIONAGE, 99L);
        long[] before = drawLongs(first, 32);

        Thread.sleep(15L);

        DeterministicRng second = SeedDerivation.rng(SEED, 7L, SaltDomain.ESPIONAGE, 99L);
        long[] after = drawLongs(second, 32);

        assertArrayEqualsLong(before, after,
                "RNG output must not depend on wall-clock or any global mutable state");
    }

    @Test
    void rngClassesHaveNoMutableStaticState() {
        for (Class<?> type : List.of(
                DeterministicRng.class, SeedDerivation.class, SaltDomain.class)) {
            for (Field f : type.getDeclaredFields()) {
                if (f.isSynthetic() || f.isEnumConstant()) {
                    continue;
                }
                if (Modifier.isStatic(f.getModifiers())) {
                    assertTrue(Modifier.isFinal(f.getModifiers()),
                            type.getSimpleName() + "." + f.getName()
                                    + " is a non-final static field (forbidden global state)");
                }
            }
        }
    }

    @Test
    void noForbiddenWallClockOrGlobalRandomnessInPackageSource() throws Exception {
        Path pkg = Path.of("src", "main", "java", "com", "stellarcompact", "engine", "rng");
        assertTrue(Files.isDirectory(pkg), "rng source package not found at " + pkg.toAbsolutePath());
        List<String> banned = List.of(
                "Math.random", "System.currentTimeMillis", "System.nanoTime",
                "Instant.now", "new Random", "ThreadLocalRandom");
        try (Stream<Path> files = Files.walk(pkg)) {
            files.filter(p -> p.toString().endsWith(".java")).forEach(p -> {
                String src;
                try {
                    src = Files.readString(p);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
                // Strip block comments and line comments first: Javadoc legitimately
                // names these tokens to explain WHY they are rejected (e.g. "not
                // Math.random"). We guard against actual code use, not prose.
                String code = stripComments(src);
                for (String needle : banned) {
                    assertFalse(code.contains(needle),
                            p.getFileName() + " contains forbidden token in code: " + needle);
                }
            });
        }
    }

    @Test
    void nextIntIsBoundedAndRejectsNonPositiveBound() {
        DeterministicRng rng = SeedDerivation.rng(SEED, 1L, SaltDomain.MARKET_TIEBREAK, 0L);
        for (int i = 0; i < 10_000; i++) {
            int v = rng.nextInt(6);
            assertTrue(v >= 0 && v < 6, "nextInt(6) out of range: " + v);
        }
        assertThrows(IllegalArgumentException.class, () -> rng.nextInt(0));
        assertThrows(IllegalArgumentException.class, () -> rng.nextInt(-3));
    }

    @Test
    void nextLongBoundedIsInRangeAndRejectsNonPositiveBound() {
        DeterministicRng rng = SeedDerivation.rng(SEED, 2L, SaltDomain.RAID, 5L);
        for (int i = 0; i < 10_000; i++) {
            long v = rng.nextLong(1_000_000_007L);
            assertTrue(v >= 0 && v < 1_000_000_007L, "nextLong(bound) out of range: " + v);
        }
        assertThrows(IllegalArgumentException.class, () -> rng.nextLong(0L));
    }

    @Test
    void nextDoubleIsUnitInterval() {
        DeterministicRng rng = SeedDerivation.rng(SEED, 3L, SaltDomain.EVENT, 1L);
        for (int i = 0; i < 100_000; i++) {
            double d = rng.nextDouble();
            assertTrue(d >= 0.0 && d < 1.0, "nextDouble out of [0,1): " + d);
        }
    }

    @Test
    void nextInRangeRespectsBandAndValidatesBounds() {
        DeterministicRng rng = SeedDerivation.rng(SEED, 4L, SaltDomain.COMBAT, 123L);
        double lo = 0.85;
        double hi = 1.15;
        for (int i = 0; i < 100_000; i++) {
            double r = rng.nextInRange(lo, hi);
            assertTrue(r >= lo && r < hi, "roll outside variance band: " + r);
        }
        assertEquals(2.0, rng.nextInRange(2.0, 2.0), 0.0, "lo==hi must return lo exactly");
        assertThrows(IllegalArgumentException.class, () -> rng.nextInRange(1.0, 0.0));
        assertThrows(IllegalArgumentException.class, () -> rng.nextInRange(Double.NaN, 1.0));
    }

    @Test
    void stringSaltKeyIsStableAndDistinct() {
        long a = SaltDomain.ESPIONAGE.salt("operation-omega");
        long b = SaltDomain.ESPIONAGE.salt("operation-omega");
        long c = SaltDomain.ESPIONAGE.salt("operation-sigma");
        assertEquals(a, b, "string salt must be stable for equal keys");
        assertNotEquals(a, c, "different string keys must produce different salts");
    }

    /** Removes block comments and single-line comments so guards scan code only. */
    private static String stripComments(String src) {
        String noBlocks = src.replaceAll("(?s)/\\*.*?\\*/", "");
        return noBlocks.replaceAll("(?m)//.*$", "");
    }

    private static void assertStreamsDiffer(DeterministicRng a, DeterministicRng b) {
        long[] sa = drawLongs(a, 16);
        long[] sb = drawLongs(b, 16);
        boolean anyDifferent = false;
        for (int i = 0; i < sa.length; i++) {
            if (sa[i] != sb[i]) {
                anyDifferent = true;
                break;
            }
        }
        assertTrue(anyDifferent, "streams expected to diverge but were identical");
    }

    private static void assertArrayEqualsLong(long[] expected, long[] actual, String msg) {
        assertEquals(expected.length, actual.length, msg);
        for (int i = 0; i < expected.length; i++) {
            assertEquals(expected[i], actual[i], msg + " @ index " + i);
        }
    }
}
