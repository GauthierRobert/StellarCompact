package com.stellarcompact.galaxy.gen;

/**
 * Per-quantity local salts so each derived value draws from an independent,
 * decorrelated hash stream off the same cell base hash.
 *
 * <p>This is the "localSalt" leg of the {@code gameSeed XOR tick XOR localSalt}
 * seeding discipline applied to spatial generation: the cell base hash plays the
 * role of {@code gameSeed XOR coords}, and each {@link Salt} keeps two values
 * derived from the same cell from being correlated (e.g. a star's x-offset must
 * be independent of its y-offset).
 *
 * <p>Ordinals are part of the determinism contract: reordering or renumbering
 * them changes generated output. Append new salts at the end only.
 */
enum Salt {
    /** Number of candidate stars in a cell. */
    CELL_COUNT,
    /** A candidate star's x offset within its cell. */
    STAR_X,
    /** A candidate star's y offset within its cell. */
    STAR_Y,
    /** Acceptance roll comparing the local density field to a uniform. */
    DENSITY_ACCEPT,
    /** Base hash for an accepted star (its stable id seed). */
    STAR_ID,

    // --- E2-02 system & planet roster sub-streams (append-only) ---

    /** A star's spectral class roll (O..M weighted distribution). */
    SPECTRAL_CLASS,
    /** A star's brightness (luminosity) roll within its spectral class band. */
    BRIGHTNESS,
    /** A star's physical size (radius) roll within its spectral class band. */
    STAR_SIZE,
    /** Number of planets in a system. */
    PLANET_COUNT,
    /** A planet's biome roll (weighted, mostly-inhospitable distribution). */
    BIOME,
    /** A planet's physical size class roll (drives its slot count). */
    PLANET_SIZE,
    /** A planet's slot-count roll within the band its size permits. */
    SLOT_COUNT,

    // --- E2-03 lane graph deterministic tiebreak (append-only) ---

    /**
     * Tiebreak key for the lane graph: when two candidate lanes are equal in
     * distance (neighbour selection) or equal in bridging cost (connectivity
     * pass), this seeds a deterministic, seed-stable ordering so the generated
     * graph is reproducible without relying on float-equality or insertion order.
     */
    LANE_TIEBREAK,

    // --- E2-04 home placement deterministic tiebreak (append-only) ---

    /**
     * Tiebreak key for home placement: when two candidate home systems are equal
     * in neighbourhood quality (and the id discriminator below also ties - which it
     * cannot, ids are unique - this is only ever reached on quality ties), this
     * seeds a deterministic, seed-stable ordering so the chosen home set is
     * reproducible without relying on float-equality or list order. Different seeds
     * can therefore pick different (equally fair) homes out of a tie.
     */
    HOME_TIEBREAK;

    /** @return the stable salt value mixed into the hash. */
    long value() {
        // Offset away from 0 so the first ordinal still perturbs the mix.
        return 0x51A1L + ordinal();
    }
}
