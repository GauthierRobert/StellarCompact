package com.stellarcompact.engine.state;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Typed identifier for a resting market order.
 *
 * <p>A thin wrapper around a {@code String} value so ids are type-safe (a
 * {@code FactionId} can never be passed where a {@code SystemId} is expected)
 * yet serialise as a bare JSON string via {@link JsonValue}. This keeps the
 * {@code GameState} maps keyed by a stable, human-readable id while preventing
 * id-type mix-ups across the many later engine cards.
 */
public record MarketOrderId(@JsonValue String value) {

    public MarketOrderId {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("MarketOrderId value must be non-blank");
        }
    }

    /** Jackson factory: deserialises a bare JSON string into this typed id. */
    @JsonCreator
    public static MarketOrderId of(String value) {
        return new MarketOrderId(value);
    }
}
