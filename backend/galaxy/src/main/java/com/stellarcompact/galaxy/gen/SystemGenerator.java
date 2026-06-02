package com.stellarcompact.galaxy.gen;

import java.util.ArrayList;
import java.util.List;

/**
 * The pure, deterministic function {@code (gameSeed, systemId) -> StarSystem}:
 * E2-02's system &amp; planet roster generator, layered on E2-01's star field
 * (procedural-galaxy skill: "only system stars carry planets; generate biome,
 * orbit, size deterministically from the star's seed"; game-design 01 sections
 * 1-2).
 *
 * <p><strong>Determinism contract (principle 1).</strong> Generation reads only
 * its arguments. No I/O, no Spring, no wall-clock, no {@code Math.random}, no
 * shared mutable RNG, no mutable statics. Every random-looking quantity is a
 * closed-form {@link SeedHash} mix of the system base hash
 * {@code (gameSeed XOR systemId)} and a per-quantity {@link Salt}. Therefore the
 * same {@code (gameSeed, systemId)} yields a byte-identical {@link StarSystem} on
 * every call, from any (stateless) generator, on any JVM.
 *
 * <p><strong>Seeding the sub-streams.</strong> The class is the
 * {@code gameSeed XOR coords} leg; {@code systemId} is the star's stable
 * E2-01-derived id ({@link Star#id()}). Each derived value draws an independent,
 * decorrelated {@code [0,1)} stream off the system base hash keyed by a {@link
 * Salt} appended in this card (SPECTRAL_CLASS, BRIGHTNESS, STAR_SIZE,
 * PLANET_COUNT, BIOME, PLANET_SIZE, SLOT_COUNT). Each planet additionally folds
 * its orbit index into a per-planet sub-hash so planet <em>i</em>'s rolls are
 * independent of planet <em>j</em>'s.
 *
 * <p><strong>Distributions</strong> (rationale documented on the enums /
 * constants they live on): spectral class is weighted M-dominant, O-rare
 * ({@link SpectralClass}); brightness and size are sampled inside the chosen
 * class's monotonic band so hotter stars are brighter and bigger; planet count
 * is uniform in {@code [MIN_PLANETS, MAX_PLANETS]}; biome is weighted toward
 * inhospitable worlds with cradle biomes a minority
 * ({@link GalaxyConstants#BIOME_WEIGHTS}); slot count is bounded by planet size
 * ({@link PlanetSize}), with ground slots forced to zero for the orbital-only Gas
 * giant.
 */
public final class SystemGenerator {

    private SystemGenerator() {
    }

    /**
     * Generates the system for a star.
     *
     * @param gameSeed the per-match galaxy seed
     * @param systemId the star's stable id ({@link Star#id()})
     * @return the deterministic, immutable {@link StarSystem}; never {@code null}
     */
    public static StarSystem generate(long gameSeed, long systemId) {
        long base = SeedHash.combine(gameSeed, systemId);

        SpectralClass spectral =
                SpectralClass.fromRoll(SeedHash.unit(base, Salt.SPECTRAL_CLASS.value()));
        double brightness = lerp(spectral.minBrightness(), spectral.maxBrightness(),
                SeedHash.unit(base, Salt.BRIGHTNESS.value()));
        double size = lerp(spectral.minSize(), spectral.maxSize(),
                SeedHash.unit(base, Salt.STAR_SIZE.value()));

        int planetCount = planetCount(base);
        List<Planet> planets = new ArrayList<>(planetCount);
        for (int orbit = 0; orbit < planetCount; orbit++) {
            planets.add(generatePlanet(base, orbit));
        }

        return new StarSystem(systemId, spectral, brightness, size, planets);
    }

    /**
     * Convenience overload: derive a system directly from a placed {@link Star}
     * (its {@link Star#id()} is the systemId seed).
     */
    public static StarSystem generate(long gameSeed, Star star) {
        return generate(gameSeed, star.id());
    }

    /** Uniform planet count in {@code [MIN_PLANETS, MAX_PLANETS]} inclusive. */
    private static int planetCount(long base) {
        double u = SeedHash.unit(base, Salt.PLANET_COUNT.value());
        int span = GalaxyConstants.MAX_PLANETS - GalaxyConstants.MIN_PLANETS + 1;
        int n = GalaxyConstants.MIN_PLANETS + (int) (u * span);
        if (n > GalaxyConstants.MAX_PLANETS) {
            n = GalaxyConstants.MAX_PLANETS; // guard roll == ~1.0
        }
        return n;
    }

    /** One planet at a given orbit, all rolls decorrelated per-orbit. */
    private static Planet generatePlanet(long systemBase, int orbit) {
        // Per-planet sub-hash: fold the orbit index so planet i != planet j.
        long pBase = SeedHash.combine(systemBase, orbit);

        Biome biome = pickBiome(SeedHash.unit(pBase, Salt.BIOME.value()));
        PlanetSize psize = PlanetSize.fromRoll(SeedHash.unit(pBase, Salt.PLANET_SIZE.value()));

        // Slot count sampled inside the size's monotonic band.
        int bandSlots = slotsInBand(psize, SeedHash.unit(pBase, Salt.SLOT_COUNT.value()));

        int ground;
        int orbital;
        if (biome.orbitalOnly()) {
            // Gas giant: no ground slots; the size band drives orbital capacity.
            ground = 0;
            orbital = bandSlots;
        } else {
            ground = bandSlots;
            orbital = 0;
        }

        return new Planet(orbit, biome, psize, ground, orbital, biome.favouredYields());
    }

    /** Weighted biome pick over {@link GalaxyConstants#BIOME_WEIGHTS}. */
    private static Biome pickBiome(double roll) {
        Biome[] all = Biome.values();
        int[] w = GalaxyConstants.BIOME_WEIGHTS;
        int total = 0;
        for (int x : w) {
            total += x;
        }
        double target = roll * total;
        double acc = 0;
        for (int i = 0; i < all.length; i++) {
            acc += w[i];
            if (target < acc) {
                return all[i];
            }
        }
        return all[all.length - 1]; // floating-point guard
    }

    /** Inclusive uniform slot count within a size's {@code [min,max]} band. */
    private static int slotsInBand(PlanetSize size, double roll) {
        int span = size.maxSlots() - size.minSlots() + 1;
        int s = size.minSlots() + (int) (roll * span);
        if (s > size.maxSlots()) {
            s = size.maxSlots(); // guard roll == ~1.0
        }
        return s;
    }

    /** Linear interpolation in {@code [lo, hi]} for a {@code [0,1)} roll. */
    private static double lerp(double lo, double hi, double t) {
        return lo + (hi - lo) * t;
    }
}
