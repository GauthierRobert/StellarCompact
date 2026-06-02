package com.stellarcompact.engine.state;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Typed identifier for a Fleet (a stack of ships).
 *
 * <p>A thin wrapper around a {@code String} value so ids are type-safe (a
 * {@code FactionId} can never be passed where a {@code SystemId} is expected)
 * yet serialise as a bare JSON string via {@link JsonValue}. This keeps the
 * {@code GameState} maps keyed by a stable, human-readable id while preventing
 * id-type mix-ups across the many later engine cards.
 */
public record FleetId(@JsonValue String value) {

    public FleetId {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("FleetId value must be non-blank");
        }
    }

    /** Jackson factory: deserialises a bare JSON string into this typed id. */
    @JsonCreator
    public static FleetId of(String value) {
        return new FleetId(value);
    }
}
