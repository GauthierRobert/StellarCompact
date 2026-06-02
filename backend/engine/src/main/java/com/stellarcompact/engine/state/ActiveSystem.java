package com.stellarcompact.engine.state;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;
import java.util.Optional;

/**
 * A system that has diverged from the procedural baseline and is therefore
 * persisted as live game state (game-design 01 section 2; data-model
 * {@code active_system(seed_coords, name, owner_faction_id?, population,
 * loyalty)} + its {@code planet} rows).
 *
 * <p>Only active systems exist as records - the vast procedural catalog is never
 * materialised (rule 3, scale discipline). A system is the unit of ownership;
 * {@code owner} is {@link Optional} because a system can be neutral/contested
 * frontier with no owner.
 *
 * <p>{@code coords} is the seed-relative address tying this active system back to
 * its procedural star. {@code loyalty} (0..1) drops on capture and gates
 * occupation cost/unrest (game-design 05 section 6); the concrete penalties are
 * config.
 */
@JsonIgnoreProperties(ignoreUnknown = false)
public record ActiveSystem(
        SystemId id,
        String name,
        Coords coords,
        Optional<FactionId> owner,
        List<Planet> planets,
        long population,
        double loyalty
) {
    public ActiveSystem {
        if (id == null) {
            throw new IllegalArgumentException("ActiveSystem.id must be set");
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("ActiveSystem.name must be non-blank");
        }
        if (coords == null) {
            throw new IllegalArgumentException("ActiveSystem.coords must be set");
        }
        if (owner == null) {
            throw new IllegalArgumentException("ActiveSystem.owner must be set (use Optional.empty())");
        }
        if (population < 0) {
            throw new IllegalArgumentException("ActiveSystem.population must be >= 0");
        }
        if (loyalty < 0.0 || loyalty > 1.0) {
            throw new IllegalArgumentException("ActiveSystem.loyalty must be in [0,1]");
        }
        planets = List.copyOf(planets);
    }
}
