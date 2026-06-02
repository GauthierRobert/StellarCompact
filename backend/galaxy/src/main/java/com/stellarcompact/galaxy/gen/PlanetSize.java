package com.stellarcompact.galaxy.gen;

/**
 * A planet's physical size class, which bounds how many build slots it offers
 * (game-design 01 section 2: "planets have a limited number of slots (by size)";
 * "bigger planets -> more slots").
 *
 * <p>Each size declares an inclusive {@code [minSlots, maxSlots]} band for ground
 * slots; {@link SystemGenerator} samples within the band with a second seeded
 * roll, so two same-size planets can differ but a larger planet always has at
 * least as many slots as a smaller one (the bands are monotonic and
 * non-overlapping). Gas giants have no <em>ground</em> slots regardless of size
 * (orbital only) - the generator zeroes ground slots for orbital-only biomes; the
 * size band still drives their orbital capacity.
 */
public enum PlanetSize {
    /** Tiny / dwarf body. */
    SMALL(1, 2),
    /** Mid-size world. */
    MEDIUM(3, 4),
    /** Large world. */
    LARGE(5, 6),
    /** Massive world / giant. */
    HUGE(7, 9);

    private final int minSlots;
    private final int maxSlots;

    PlanetSize(int minSlots, int maxSlots) {
        this.minSlots = minSlots;
        this.maxSlots = maxSlots;
    }

    /** Inclusive lower bound on slots a planet of this size offers. */
    public int minSlots() {
        return minSlots;
    }

    /** Inclusive upper bound on slots a planet of this size offers. */
    public int maxSlots() {
        return maxSlots;
    }

    /**
     * Picks a size from a uniform {@code [0,1)} roll. Uniform over the four
     * sizes; pure and deterministic for a given roll.
     */
    public static PlanetSize fromRoll(double roll) {
        PlanetSize[] all = values();
        int idx = (int) (roll * all.length);
        if (idx >= all.length) {
            idx = all.length - 1; // guard against roll == ~1.0
        }
        return all[idx];
    }
}
