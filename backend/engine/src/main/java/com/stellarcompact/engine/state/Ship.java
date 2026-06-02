package com.stellarcompact.engine.state;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * A homogeneous group of ships of one spec within a {@link Fleet}
 * (game-design 05 section 1; data-model {@code ship(spec, count)}).
 *
 * <p>Ships are modelled as a {@code (spec, count)} pair rather than individual
 * objects so a 200-ship stack is one record, not 200 - keeping state bounded.
 * The {@code spec} is a config-defined archetype key (e.g. {@code "scout"},
 * {@code "cruiser"}, {@code "capital"}, {@code "freighter"}); its attack,
 * defence, speed, tier and upkeep live in the balance profile, never here
 * (rule 6).
 */
@JsonIgnoreProperties(ignoreUnknown = false)
public record Ship(
        String spec,
        int count
) {
    public Ship {
        if (spec == null || spec.isBlank()) {
            throw new IllegalArgumentException("Ship.spec must be non-blank");
        }
        if (count < 0) {
            throw new IllegalArgumentException("Ship.count must be >= 0");
        }
    }
}
