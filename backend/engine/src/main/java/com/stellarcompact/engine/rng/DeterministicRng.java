package com.stellarcompact.engine.rng;

/**
 * The single source of randomness in the deterministic engine: a small,
 * fully-controlled SplitMix64 generator created per {@code (gameSeed, tick,
 * localSalt)} use-site.
 *
 * <p><b>Why our own SplitMix64 and not {@code java.util.Random} /
 * {@code RandomGenerator}?</b> Replay stability is sacred (skill
 * {@code game-engine-determinism}): a stored {@code (gameSeed, action-log)} must
 * reproduce every tick byte-for-byte, today and on any future JVM. We therefore
 * pin a tiny algorithm we own end-to-end rather than depend on a platform
 * generator whose exact bit-output we do not control. SplitMix64 is the right fit:
 * a single 64-bit additive counter advanced by the golden-ratio constant and run
 * through a strong avalanche finaliser. It has no rejected states, passes standard
 * statistical batteries, is trivially seedable from one long, and -- crucially --
 * its output is a closed-form function of an explicit 64-bit state, so it is
 * identical across JVMs and architectures. {@code java.util.Random} is rejected
 * for being implicit/legacy; {@code java.util.random.RandomGenerator} is rejected
 * because its concrete algorithms and their guarantees are not pinned by us.
 *
 * <p><b>No shared mutable static state.</b> Each generator is an ordinary instance
 * holding a private {@code long state}. There are no static mutable fields and no
 * global generator. Resolution is single-threaded (the orchestrator owns all
 * concurrency), but even so this type is free of shared mutable statics, so two
 * call sites can never interfere. It is <em>not</em> internally synchronised and
 * is deliberately not thread-safe: a generator belongs to exactly one resolve
 * step on one thread.
 *
 * <p><b>Purity.</b> No I/O, no wall-clock, no {@code Math.random}. The only way to
 * obtain a seed is to pass one in, normally via
 * {@link SeedDerivation#rng(long, long, SaltDomain, long)}.
 */
public final class DeterministicRng {

    /** SplitMix64 additive step: the 64-bit golden-ratio odd constant. */
    private static final long GOLDEN = 0x9E3779B97F4A7C15L;
    private static final long MIX_A = 0xBF58476D1CE4E5B9L;
    private static final long MIX_B = 0x94D049BB133111EBL;

    /** Top 53 bits scaled into a unit double: 2^-53. */
    private static final double UNIT_DOUBLE_SCALE = 0x1.0p-53;

    /** The sole mutable field -- per instance, never static. */
    private long state;

    private DeterministicRng(long seed) {
        this.state = seed;
    }

    /**
     * Creates a generator from an already-derived 64-bit stream seed. Prefer
     * {@link SeedDerivation#rng(long, long, SaltDomain, long)} at call sites so the
     * {@code gameSeed XOR tick XOR localSalt} derivation is applied consistently;
     * use this directly only when you already hold a derived seed.
     *
     * @param seed the 64-bit stream seed
     * @return a fresh generator positioned at the start of that stream
     */
    public static DeterministicRng fromSeed(long seed) {
        return new DeterministicRng(seed);
    }

    /**
     * Advances the stream and returns the next uniformly distributed 64-bit value.
     * This is the SplitMix64 core: bump the additive counter, then avalanche it.
     *
     * @return the next 64-bit draw (full range, including negatives)
     */
    public long nextLong() {
        long z = (state += GOLDEN);
        z = (z ^ (z >>> 30)) * MIX_A;
        z = (z ^ (z >>> 27)) * MIX_B;
        return z ^ (z >>> 31);
    }

    /**
     * Returns the next 32-bit value (the high 32 bits of {@link #nextLong()},
     * which carry the strongest avalanche).
     *
     * @return a 32-bit draw (full range, including negatives)
     */
    public int nextInt() {
        return (int) (nextLong() >>> 32);
    }

    /**
     * Returns a uniformly distributed {@code int} in {@code [0, bound)} with
     * <strong>no modulo bias</strong>. Uses Lemire's multiply-high method with a
     * rejection fallback on the rare biased window, drawing fresh 32-bit words from
     * the stream as needed. Determinism is preserved because the rejection path is
     * itself a deterministic function of the stream.
     *
     * @param bound exclusive upper bound; must be {@code > 0}
     * @return a uniform value in {@code [0, bound)}
     * @throws IllegalArgumentException if {@code bound <= 0}
     */
    public int nextInt(int bound) {
        if (bound <= 0) {
            throw new IllegalArgumentException("bound must be positive: " + bound);
        }
        long m = (next32() & 0xFFFF_FFFFL) * bound;
        long low = m & 0xFFFF_FFFFL;
        if (low < bound) {
            // Reject the low window that would otherwise be over-represented.
            long threshold = Integer.toUnsignedLong(-bound) % bound;
            while (low < threshold) {
                m = (next32() & 0xFFFF_FFFFL) * bound;
                low = m & 0xFFFF_FFFFL;
            }
        }
        return (int) (m >>> 32);
    }

    /**
     * Returns a uniformly distributed {@code long} in {@code [0, bound)} with no
     * modulo bias, via rejection sampling over the largest unbiased multiple of
     * {@code bound}.
     *
     * @param bound exclusive upper bound; must be {@code > 0}
     * @return a uniform value in {@code [0, bound)}
     * @throws IllegalArgumentException if {@code bound <= 0}
     */
    public long nextLong(long bound) {
        if (bound <= 0) {
            throw new IllegalArgumentException("bound must be positive: " + bound);
        }
        // Largest multiple of bound that fits in the unsigned 64-bit range.
        long unbiasedLimit = -Long.remainderUnsigned(-bound, bound);
        long bits;
        do {
            bits = nextLong(); // compared as unsigned via Long.compareUnsigned
        } while (Long.compareUnsigned(bits, unbiasedLimit) >= 0);
        return Long.remainderUnsigned(bits, bound);
    }

    /**
     * Returns a uniformly distributed {@code double} in {@code [0.0, 1.0)}. Uses
     * the top 53 bits (a {@code double}'s mantissa width), so every result is
     * exactly representable and free of rounding bias.
     *
     * @return a uniform value in {@code [0.0, 1.0)}
     */
    public double nextDouble() {
        return (nextLong() >>> 11) * UNIT_DOUBLE_SCALE;
    }

    /**
     * Returns a uniformly distributed {@code double} in {@code [lo, hi)} (or
     * exactly {@code lo} when {@code lo == hi}). This is the variance-band helper
     * combat uses: pass {@code Combat.varianceBand()} as {@code [lo, hi]} to draw
     * the bounded roll described in game-design 05 section 2.
     *
     * @param lo inclusive lower bound
     * @param hi exclusive upper bound; must be {@code >= lo}
     * @return a uniform value in {@code [lo, hi)}
     * @throws IllegalArgumentException if {@code hi < lo} or either bound is NaN
     */
    public double nextInRange(double lo, double hi) {
        if (Double.isNaN(lo) || Double.isNaN(hi)) {
            throw new IllegalArgumentException("range bounds must not be NaN");
        }
        if (hi < lo) {
            throw new IllegalArgumentException(
                    "range hi (" + hi + ") must be >= lo (" + lo + ")");
        }
        return lo + (hi - lo) * nextDouble();
    }

    /** Next unsigned 32-bit word as the low 32 bits of a long (helper). */
    private long next32() {
        return nextLong() >>> 32;
    }
}
