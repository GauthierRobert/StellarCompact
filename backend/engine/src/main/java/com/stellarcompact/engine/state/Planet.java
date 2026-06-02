package com.stellarcompact.engine.state;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * A colonisable body within an {@link ActiveSystem} (game-design 01 section 2;
 * data-model {@code planet(biome, slots_total, population)}).
 *
 * <p>The {@link #biome()} fixes base yields and colonisation difficulty (looked
 * up in config). {@code slotsTotal} caps how many {@link Building}s the planet
 * can host (Gas giants have zero ground slots - orbital only). {@code population}
 * scales production and the Influence soft-power base (economy 02 section 3).
 *
 * <p>{@code buildings} is defensively copied to an unmodifiable list; ordering is
 * preserved but the canonical state hash does not depend on it being sorted
 * (lists hash in order - the resolver keeps buildings in a stable slot order).
 */
@JsonIgnoreProperties(ignoreUnknown = false)
public record Planet(
        PlanetId id,
        Biome biome,
        int slotsTotal,
        long population,
        List<Building> buildings
) {
    public Planet {
        if (id == null) {
            throw new IllegalArgumentException("Planet.id must be set");
        }
        if (biome == null) {
            throw new IllegalArgumentException("Planet.biome must be set");
        }
        if (slotsTotal < 0) {
            throw new IllegalArgumentException("Planet.slotsTotal must be >= 0");
        }
        if (population < 0) {
            throw new IllegalArgumentException("Planet.population must be >= 0");
        }
        buildings = List.copyOf(buildings);
    }
}
