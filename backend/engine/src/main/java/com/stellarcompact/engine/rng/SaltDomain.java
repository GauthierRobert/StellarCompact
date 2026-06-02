package com.stellarcompact.engine.rng;

import java.nio.charset.StandardCharsets;

/**
 * The closed set of <em>local-salt domains</em> in the deterministic engine.
 *
 * <p>Every seeded roll is keyed by {@code (gameSeed, tick, localSalt)} (skill
 * {@code game-engine-determinism}, rule 2). The {@code localSalt} is what makes
 * two rolls within the same tick independent: combat for battle A must draw a
 * different stream than combat for battle B, which must differ again from an
 * espionage roll or a market tie-break. To guarantee those streams never collide
 * by accident, every call site first picks a {@code SaltDomain} (the <em>kind</em>
 * of roll) and then mixes in a per-instance key (a battle id, op id, ...).
 *
 * <p>The domain ordinal is folded into the salt via {@link #domainTag()} so that,
 * say, {@code COMBAT} with key {@code 7} and {@code ESPIONAGE} with key {@code 7}
 * still produce well-separated streams. Each tag is a fixed odd 64-bit constant
 * (a distinct stride from the SplitMix64 golden ratio) rather than the bare
 * ordinal, so flipping the domain avalanches roughly half the seed bits instead of
 * one low bit. <strong>The constants below are part of the replay contract: never
 * reorder or renumber them.</strong> Adding a new domain appends a new constant.
 *
 * <p>This enum is pure and immutable: no I/O, no wall-clock, no mutable state.
 */
public enum SaltDomain {

    /** Combat variance roll, keyed by battle id (game-design 05 section 2). */
    COMBAT(0x51F3C9A7E2B14D6BL),

    /** Mid-transit interception checks, keyed by fleet/lane id (05 section 4). */
    INTERCEPTION(0x9A6B4F18D3E7205FL),

    /** Espionage / sabotage success rolls, keyed by operation id (05 section 5). */
    ESPIONAGE(0xC4D9A03B5E81762DL),

    /** Route-raid shipment-steal rolls, keyed by route id (05 section 5). */
    RAID(0x2E7158BC9F4A06D3L),

    /** Market price/time tie-break ordering, keyed by hub/order id (economy 02). */
    MARKET_TIEBREAK(0x7B3E1C5A9D02486FL),

    /** Tick-level public-event selection, keyed by an event group id. */
    EVENT(0xF1A4D78E36C9520BL);

    private final long tag;

    SaltDomain(long tag) {
        this.tag = tag;
    }

    /**
     * The fixed 64-bit avalanche tag for this domain. Combined with a call-site key
     * by {@link #salt(long)} to form the {@code localSalt}.
     *
     * @return this domain's stable tag constant
     */
    public long domainTag() {
        return tag;
    }

    /**
     * Builds a {@code localSalt} for this domain from a numeric call-site key (a
     * battle id, op id, route id, order id, ...). Two different keys in the same
     * domain, and the same key in two different domains, both yield well-separated
     * salts (and therefore independent streams).
     *
     * @param key the per-instance disambiguator (e.g. {@code battleId})
     * @return a 64-bit {@code localSalt} ready for
     *         {@link SeedDerivation#derive(long, long, long)}
     */
    public long salt(long key) {
        // Mix the key with the domain tag through the finaliser so neither the
        // raw key nor the raw tag survives as a low-entropy region of the salt.
        return SeedDerivation.mix(tag ^ SeedDerivation.mix(key));
    }

    /**
     * Builds a {@code localSalt} for this domain from a textual call-site key
     * (e.g. a string battle id or operation name). The string is folded to a long
     * first, then treated exactly like a numeric key. Pure: hashes the UTF-8 bytes
     * with the same avalanche mixer (NOT {@link String#hashCode()}, which is only
     * 32-bit and weakly mixed), so the result is stable across JVMs.
     *
     * @param key the textual disambiguator; must not be {@code null}
     * @return a 64-bit {@code localSalt}
     */
    public long salt(String key) {
        return salt(hashString(key));
    }

    /**
     * Pure, cross-JVM-stable 64-bit hash of a string: an FNV-1a-style fold over the
     * UTF-8 bytes finished with the SplitMix64 avalanche. Used so a textual id can
     * become a numeric salt key without leaning on the platform's 32-bit
     * {@link String#hashCode()}.
     *
     * @param key the string to hash; must not be {@code null}
     * @return a well-distributed 64-bit hash
     */
    public static long hashString(String key) {
        if (key == null) {
            throw new IllegalArgumentException("salt key string must not be null");
        }
        long h = 0xCBF29CE484222325L; // 64-bit FNV offset basis
        for (byte b : key.getBytes(StandardCharsets.UTF_8)) {
            h ^= (b & 0xFFL);
            h *= 0x100000001B3L;      // 64-bit FNV prime
        }
        return SeedDerivation.mix(h);
    }
}
