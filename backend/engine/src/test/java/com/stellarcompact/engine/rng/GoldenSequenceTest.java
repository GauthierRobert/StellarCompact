package com.stellarcompact.engine.rng;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pinned golden vectors for the engine RNG.
 *
 * <p>These freeze the exact bit-output of the SplitMix64 generator and the
 * (gameSeed, tick, localSalt) derivation for one fixed input. Any change to the
 * mixing constants, the derivation fold, the SplitMix64 step, the bounded-int /
 * unbiased-long sampling, or the salt-domain tags will flip a value here and fail
 * the build. That is the point: the replay contract (skill
 * game-engine-determinism) requires that stored games reproduce byte-for-byte, so
 * the algorithm must never silently drift. If you intend to change the algorithm,
 * you are also invalidating every persisted replay -- update these vectors
 * deliberately and bump the engine's replay/format version alongside.
 *
 * <p>Captured at real release 25 on JDK 25.
 */
class GoldenSequenceTest {

    private static final long SEED = 0x0123456789ABCDEFL;
    private static final long TICK = 7L;
    private static final long BATTLE_ID = 42L;

    @Test
    void derivedSeedIsPinned() {
        long derived = SeedDerivation.derive(SEED, TICK, SaltDomain.COMBAT, BATTLE_ID);
        assertEquals(-8243800925824656221L, derived,
                "seed derivation drifted; replay stability broken");
    }

    @Test
    void saltDomainTagsArePinned() {
        assertEquals(6344424578226636946L, SaltDomain.COMBAT.salt(BATTLE_ID),
                "COMBAT numeric salt drifted");
        assertEquals(-1749494118225809640L, SaltDomain.ESPIONAGE.salt("op-omega"),
                "ESPIONAGE string salt drifted");
    }

    @Test
    void nextLongStreamIsPinned() {
        DeterministicRng rng = SeedDerivation.rng(SEED, TICK, SaltDomain.COMBAT, BATTLE_ID);
        long[] expected = {
                -4962272454591951932L,
                4242014577349865135L,
                6256270439307340287L,
                -2040857705179847881L,
                3713115842709432122L,
                -1511657905734095737L,
                1234403013987929230L,
                -2959415954543086673L,
        };
        long[] actual = new long[expected.length];
        for (int i = 0; i < actual.length; i++) {
            actual[i] = rng.nextLong();
        }
        assertArrayEquals(expected, actual, "nextLong stream drifted");
    }

    @Test
    void nextIntStreamIsPinned() {
        DeterministicRng rng = SeedDerivation.rng(SEED, TICK, SaltDomain.COMBAT, BATTLE_ID);
        int[] expected = {73, 22, 33, 88, 20, 91, 6, 83};
        int[] actual = new int[expected.length];
        for (int i = 0; i < actual.length; i++) {
            actual[i] = rng.nextInt(100);
        }
        assertArrayEquals(expected, actual, "nextInt(100) stream drifted");
    }

    @Test
    void nextDoubleAndBandArePinned() {
        DeterministicRng rng = SeedDerivation.rng(SEED, TICK, SaltDomain.COMBAT, BATTLE_ID);
        assertEquals(0.7309946712133214d, rng.nextDouble(), 0.0, "nextDouble drifted");
        assertEquals(0.9189880213071685d, rng.nextInRange(0.85, 1.15), 0.0,
                "nextInRange variance band drifted");
    }
}
