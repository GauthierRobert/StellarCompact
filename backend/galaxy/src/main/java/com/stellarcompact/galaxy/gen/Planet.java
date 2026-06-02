package com.stellarcompact.galaxy.gen;

/**
 * One procedurally generated planet in a {@link StarSystem} (game-design 01
 * section 2: "Planet carries a biome that determines base resource yields and
 * colonisation difficulty"; "Slot is where buildings go; planets have a limited
 * number of slots by size").
 *
 * <p>Immutable value type, deterministically derived from the system seed. The
 * {@code orbitIndex} is the planet's stable position in the roster (0 = innermost)
 * and doubles as its address within the system. {@code groundSlots} is bounded by
 * {@link #size} (bigger -> more) and is forced to zero for orbital-only biomes
 * (Gas giant), whose capacity is carried by {@code orbitalSlots} instead.
 *
 * @param orbitIndex   stable 0-based orbit / roster position (innermost first)
 * @param biome        the planet's biome (drives yields and colonise difficulty)
 * @param size         physical size class (bounds slot counts)
 * @param groundSlots  number of ground build slots (0 for orbital-only biomes)
 * @param orbitalSlots number of orbital build slots
 * @param baseYields   favoured base yields per resource (biome baseline)
 */
public record Planet(int orbitIndex,
                     Biome biome,
                     PlanetSize size,
                     int groundSlots,
                     int orbitalSlots,
                     ResourceYield baseYields) {

    public Planet {
        if (orbitIndex < 0) {
            throw new IllegalArgumentException("orbitIndex must be >= 0");
        }
        if (groundSlots < 0 || orbitalSlots < 0) {
            throw new IllegalArgumentException("slot counts must be >= 0");
        }
        if (biome.orbitalOnly() && groundSlots != 0) {
            throw new IllegalArgumentException(
                    "orbital-only biome " + biome + " must have zero ground slots");
        }
    }

    /** Total build capacity (ground + orbital). */
    public int totalSlots() {
        return groundSlots + orbitalSlots;
    }
}
