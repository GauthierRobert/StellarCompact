package com.stellarcompact.galaxy.gen;

import java.util.List;

/**
 * A fully derived procedural system: a star (spectral class, brightness, size)
 * plus its planet roster (game-design 01 section 2 hierarchy
 * Galaxy -> Region -> System -> Planet -> Slot). The atomic unit of ownership.
 *
 * <p>Pure, immutable, regenerable: {@link SystemGenerator#generate(long, long)}
 * produces the same {@code StarSystem} byte-for-byte for the same
 * {@code (gameSeed, systemId)} on every call and across JVMs. The galaxy never
 * stores systems; the renderer and (at the E2-05 promotion boundary) the
 * simulation regenerate them on demand from the seed.
 *
 * @param systemId   the stable per-star seed id (the {@link Star#id()} of E2-01)
 * @param spectral   the star's spectral class
 * @param brightness relative luminosity (Sol G ~ 1.0)
 * @param size       relative stellar radius (Sol G ~ 1.0)
 * @param planets    the planet roster, ordered innermost-first; immutable
 */
public record StarSystem(long systemId,
                         SpectralClass spectral,
                         double brightness,
                         double size,
                         List<Planet> planets) {

    public StarSystem {
        planets = List.copyOf(planets); // defensive immutable copy
    }

    /** Number of planets in the system. */
    public int planetCount() {
        return planets.size();
    }
}
