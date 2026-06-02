package com.stellarcompact.galaxy.gen;

/**
 * A star's continuous position in galaxy space, in the same units as the PoC
 * (galactic centre at the origin, radius bounded by
 * {@link GalaxyConstants#R_MAX}).
 *
 * <p>Galaxy-local on purpose. The engine has its own integer
 * {@code Coords(long x, long y)} used as a seed-relative catalog address; the
 * galaxy module is kept independent of engine (see this card's report), and star
 * placement needs continuous spiral positions, so this is a separate, tiny,
 * dependency-free record. The renderer derives screen positions from these; no
 * Jackson here (the galaxy module carries no JSON dependency).
 *
 * <p>Determinism note: positions are {@code double}s produced by the same closed
 * form everywhere, so they are reproducible. They are deliberately not used as
 * map keys or for equality-sensitive logic in the engine, which addresses stars
 * by id, not float position.
 */
public record StarCoords(double x, double y) {
}
