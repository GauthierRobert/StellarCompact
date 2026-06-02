package com.stellarcompact.galaxy.gen;

/**
 * Pure, stateless deterministic mixing for the procedural galaxy.
 *
 * <p>Every random-looking quantity in the generator (a cell's star count, each
 * star's offset within the cell, its derived id) is a closed-form function of
 * {@code (gameSeed, cellX, cellY, cellZ, localSalt)}. There is no shared mutable
 * RNG, no statics holding state, no {@code Math.random}, no wall-clock - so the
 * same inputs yield byte-identical output on every call and across JVMs
 * (principle 1: determinism is sacred; seed RNG from gameSeed XOR coords XOR
 * salt).
 *
 * <p>The mixer is the SplitMix64 finaliser. It is a strong avalanching 64-bit
 * hash: flipping any input bit scrambles roughly half the output bits, so
 * neighbouring cells and adjacent salts decorrelate cleanly. The PoC uses a
 * 32-bit murmur-style finaliser ({@code imul(i ^ 0x9e3779b9, 2246822519)} then
 * an xorshift); we keep the same family and the same golden-ratio constant
 * {@code 0x9E3779B97F4A7C15} but widen it to 64 bits for headroom against
 * collisions across a large cell grid.
 */
final class SeedHash {

    /** SplitMix64 increment: the 64-bit golden-ratio odd constant. */
    private static final long GOLDEN = 0x9E3779B97F4A7C15L;
    private static final long MIX_A = 0xBF58476D1CE4E5B9L;
    private static final long MIX_B = 0x94D049BB133111EBL;

    private SeedHash() {
    }

    /**
     * SplitMix64 finaliser: avalanches a single 64-bit word into a well
     * distributed 64-bit hash. Pure; no state.
     */
    static long mix(long z) {
        z = (z ^ (z >>> 30)) * MIX_A;
        z = (z ^ (z >>> 27)) * MIX_B;
        return z ^ (z >>> 31);
    }

    /**
     * Folds an ordered list of inputs into one hash by chaining the finaliser,
     * advancing a SplitMix64-style cursor by {@link #GOLDEN} per input so that
     * argument order and position both matter (i.e. {@code combine(a, b)} differs
     * from {@code combine(b, a)}).
     *
     * @param values the ordered inputs (seed, coords, salt, ...)
     * @return a deterministic 64-bit hash of the inputs
     */
    static long combine(long... values) {
        long h = GOLDEN;
        for (long v : values) {
            h += GOLDEN;
            h = mix(h ^ mix(v));
        }
        return mix(h);
    }

    /**
     * Maps a 64-bit hash to a uniform double in {@code [0, 1)}. Uses the top 53
     * bits (the mantissa width of a {@code double}) so the result is exactly
     * representable and free of rounding bias.
     *
     * @param hash any 64-bit hash
     * @return a uniform value in {@code [0.0, 1.0)}
     */
    static double toUnitDouble(long hash) {
        return (hash >>> 11) * 0x1.0p-53;
    }

    /**
     * Derives an independent uniform {@code [0,1)} stream value for a given salt,
     * keyed off a base hash. Pure convenience for "give me the n-th independent
     * roll from this cell's hash".
     *
     * @param baseHash the cell/star base hash
     * @param salt     a small disambiguating constant (a {@link Salt} ordinal)
     * @return a uniform value in {@code [0.0, 1.0)}
     */
    static double unit(long baseHash, long salt) {
        return toUnitDouble(combine(baseHash, salt));
    }
}
