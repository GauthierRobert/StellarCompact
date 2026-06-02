package com.stellarcompact.galaxy.gen;

/**
 * A fully-derived procedural catalog star: position + the visual attributes the
 * renderer needs (spectral class, brightness, size), all derived from the same
 * seed+coords on the server and the client (E8-03; see
 * {@code docs/specs/procedural-catalog-algorithm.md}).
 *
 * <p>This is the unit of the server/client parity contract. It is the join of
 * E2-01 placement ({@link Star}: stable {@link #id} + position) and the visual
 * slice of E2-02 ({@link StarSystem}: spectral / brightness / size) - exactly
 * the fields the TypeScript client regenerates so it can render scenery it was
 * never sent. Planet rosters are deliberately excluded: they are zoom-gated
 * detail derived from the same {@code id} when needed, not part of the
 * star-for-star parity proof.
 *
 * @param id         stable 64-bit seed-derived identity (same on both sides)
 * @param x          galaxy-space x (origin = galactic centre)
 * @param y          galaxy-space y
 * @param spectral   Morgan-Keenan spectral class
 * @param brightness relative luminosity (Sol G ~ 1.0)
 * @param size       relative stellar radius (Sol G ~ 1.0)
 */
public record CatalogStar(long id,
                          double x,
                          double y,
                          SpectralClass spectral,
                          double brightness,
                          double size) {
}
