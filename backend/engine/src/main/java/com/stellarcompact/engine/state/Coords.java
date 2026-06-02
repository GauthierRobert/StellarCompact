package com.stellarcompact.engine.state;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Integer galaxy coordinates of a star/system in the procedural catalog.
 *
 * <p>The galaxy is generated from {@code gameSeed}; a star at a given
 * {@code (x, y)} is derived by hash (see game-design 01 and the data-model
 * spec's {@code seed_coords}). Coordinates are therefore a stable, seed-relative
 * address into the procedural catalog, not a floating-point position - integers
 * keep them exactly reproducible and free of platform float drift, which matters
 * for the determinism contract.
 *
 * <p>Kept deliberately tiny and dependency-free; the renderer derives screen
 * positions from these, the engine only ever compares/identifies by them.
 */
@JsonIgnoreProperties(ignoreUnknown = false)
public record Coords(long x, long y) {
}
