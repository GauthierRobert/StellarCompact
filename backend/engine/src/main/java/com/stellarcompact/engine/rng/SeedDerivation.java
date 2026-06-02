package com.stellarcompact.engine.rng;

/**
 * Pure seed-derivation for the deterministic engine: folds
 * {@code (gameSeed, tick, localSalt)} into one 64-bit stream seed.
 *
 * <p>This is the single chokepoint named by the skill
 * {@code game-engine-determinism} (rule 2): <em>seed all RNG from
 * gameSeed&nbsp;XOR&nbsp;tick&nbsp;XOR&nbsp;localSalt</em>. A bare XOR alone is a
 * weak combiner -- adjacent ticks or neighbouring salts would share most of their
 * bits and produce visibly correlated streams. So each component is first run
 * through a strong avalanche mixer (the SplitMix64 finaliser) and the mixed words
 * are XOR-folded with distinct golden-ratio strides, then finalised once more.
 * Flipping any single input bit then scrambles roughly half the output bits, so
 * {@code (s, t, salt)} and {@code (s, t+1, salt)} yield well-decorrelated seeds.
 *
 * <p>This mirrors the mixing <em>family</em> used by the galaxy module's
 * {@code SeedHash} (same SplitMix64 finaliser and {@code 0x9E3779B97F4A7C15}
 * golden-ratio constant) for a consistent house style, but the engine keeps its
 * own independent copy: the two pure libraries must not depend on one another.
 *
 * <p>Purity: no I/O, no {@code java.util.Random}, no wall-clock, no mutable static
 * state. Every method is a closed-form function of its arguments, so the same
 * inputs give byte-identical output on every call and across JVMs.
 */
public final class SeedDerivation {

    /** SplitMix64 stream constant: the 64-bit golden-ratio odd word. */
    private static final long GOLDEN = 0x9E3779B97F4A7C15L;
    private static final long MIX_A = 0xBF58476D1CE4E5B9L;
    private static final long MIX_B = 0x94D049BB133111EBL;

    private SeedDerivation() {
    }

    /**
     * SplitMix64 finaliser: avalanches a single 64-bit word into a uniformly
     * distributed 64-bit hash. Pure and stateless.
     *
     * @param z any 64-bit word
     * @return the avalanched hash of {@code z}
     */
    public static long mix(long z) {
        z = (z ^ (z >>> 30)) * MIX_A;
        z = (z ^ (z >>> 27)) * MIX_B;
        return z ^ (z >>> 31);
    }

    /**
     * Derives the 64-bit stream seed for one use-site from the three components.
     *
     * <p>Each component is independently mixed and folded onto a SplitMix64 cursor
     * that advances by {@link #GOLDEN} per component, so component <em>position</em>
     * matters: {@code derive(a, b, c)} differs from any permutation of the same
     * three values. The accumulator is finalised once more before return.
     *
     * @param gameSeed  the per-match root seed (stable for the whole game)
     * @param tick      the current tick number (monotonic within a match)
     * @param localSalt the per-use-site salt; build it from a {@link SaltDomain}
     *                  via {@link SaltDomain#salt(long)} so distinct kinds of roll
     *                  (combat vs espionage vs market) get independent streams
     * @return the 64-bit seed to hand to {@link DeterministicRng#fromSeed(long)}
     */
    public static long derive(long gameSeed, long tick, long localSalt) {
        long h = GOLDEN;
        h += GOLDEN;
        h = mix(h ^ mix(gameSeed));
        h += GOLDEN;
        h = mix(h ^ mix(tick));
        h += GOLDEN;
        h = mix(h ^ mix(localSalt));
        return mix(h);
    }

    /**
     * Convenience overload: derive the stream seed directly from a typed salt
     * domain and a numeric call-site key.
     *
     * @param gameSeed the per-match root seed
     * @param tick     the current tick number
     * @param domain   the kind of roll (combat, espionage, ...)
     * @param key      the per-instance disambiguator (battle id, op id, ...)
     * @return the 64-bit stream seed
     */
    public static long derive(long gameSeed, long tick, SaltDomain domain, long key) {
        return derive(gameSeed, tick, domain.salt(key));
    }

    /**
     * Constructs a {@link DeterministicRng} for one use-site in a single step from
     * {@code (gameSeed, tick, domain, key)}. This is the call most resolver steps
     * use: {@code var rng = SeedDerivation.rng(seed, tick, SaltDomain.COMBAT, battleId);}
     *
     * @param gameSeed the per-match root seed
     * @param tick     the current tick number
     * @param domain   the kind of roll
     * @param key      the per-instance disambiguator
     * @return a fresh, independent generator for this use-site
     */
    public static DeterministicRng rng(long gameSeed, long tick, SaltDomain domain, long key) {
        return DeterministicRng.fromSeed(derive(gameSeed, tick, domain, key));
    }
}
