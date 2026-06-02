package com.stellarcompact.galaxy.gen;

/**
 * A single procedurally placed star: the minimal output of E2-01.
 *
 * <p>Deliberately minimal. This card places stars (stable id + position) only.
 * Spectral class, brightness, size and planet rosters are E2-02 and are
 * intentionally omitted here rather than stubbed; they will be derived from the
 * same {@code seed} that produced this star ({@link #id} is that per-star seed),
 * so E2-02 can extend the catalog without changing how positions are generated.
 *
 * <p>The {@link #id} is a stable 64-bit hash derived from
 * {@code (gameSeed, cell, candidateIndex)}, so the same star keeps the same id
 * across regenerations and JVMs and can be used as a persistent address (e.g.
 * the promotion/demotion boundary in E2-05). It is also the per-star seed E2-02
 * will mix from.
 *
 * @param id  stable, seed-derived identity / per-star seed
 * @param pos continuous position in galaxy space
 */
public record Star(long id, StarCoords pos) {
}
