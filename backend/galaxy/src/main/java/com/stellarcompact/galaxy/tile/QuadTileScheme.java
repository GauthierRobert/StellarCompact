package com.stellarcompact.galaxy.tile;

import com.stellarcompact.galaxy.gen.GalaxyConstants;

/**
 * The full quadtree LOD tiling scheme over the galaxy square (E8-04; lod-tiling
 * skill; architecture 02 sections 3 and 5). This is the pure, framework-free heart
 * of the tile service: all addressing, the level -> payload-kind boundary, the
 * world-space footprint of a tile, and the Hilbert key used for cache locality.
 * The api module's HTTP layer is a thin adapter over this.
 *
 * <h2>Addressing</h2>
 * The whole galaxy lives in the square {@code [-R_MAX, R_MAX]} on each axis
 * (galaxy units; the same space the procedural generators place stars in). Level
 * {@code L} dices that square into {@code 2^L} tiles per axis, so tile
 * {@code (x, y)} covers, on each axis,
 * {@code [-R_MAX + i*side, -R_MAX + (i+1)*side]} with {@code side = 2*R_MAX/2^L}.
 * This is slippy-map {@code z/x/y} in spirit.
 *
 * <h2>LOD split (detail is a function of zoom)</h2>
 * Coarse levels (below {@link #STAR_LIST_MIN_LEVEL}) summarise their region as a
 * density aggregate (the galaxy glow/arms - not individual stars). Fine levels
 * ({@code >= STAR_LIST_MIN_LEVEL}) list the actual procedural stars in the tiny
 * region. {@link #isStarListLevel(int)} is the single source of truth for that
 * boundary, asserted by tests.
 *
 * <h2>Deep zoom</h2>
 * {@link #MAX_LEVEL} extends well past the small tier (E6-03 served only 6 levels)
 * so the client can keep descending: at the deepest level a tile spans
 * {@code 2*R_MAX / 2^MAX_LEVEL} galaxy units, far smaller than one placement
 * {@link GalaxyConstants#CELL_SIZE}, so a deep tile holds at most a handful of
 * stars. Per-tile work stays bounded by the (tiny) tile footprint, never by the
 * catalog size (O(visible)). Floating-origin precision handling at extreme zoom is
 * the client's concern (E8-05); the address space here is integer and exact.
 *
 * <p>Pure and stateless (determinism sacred): closed-form math, no I/O, no Spring,
 * no randomness; reproducible on every JVM.
 */
public final class QuadTileScheme {

    private QuadTileScheme() {
    }

    /**
     * Deepest quadtree level served. At {@code MAX_LEVEL} a tile spans
     * {@code 2*R_MAX / 2^MAX_LEVEL} galaxy units per axis. With {@code R_MAX = 1000}
     * and {@code level = 20} that is {@code 2000 / 1048576 ~= 0.0019} units - far
     * below the {@code CELL_SIZE} of 50, i.e. an individual-star scale. Twenty
     * levels is the full deep-zoom descent the billion-star view needs; the small
     * tier (E6-03) used 6. The Hilbert index at this level fits comfortably in a
     * {@code long} ({@code 4^20 = 2^40}).
     */
    public static final int MAX_LEVEL = 20;

    /**
     * First level that returns a star list (vs an aggregate). Below this the galaxy
     * cannot resolve individual stars, so tiles are density aggregates; at and
     * above it the tile footprint is small enough to enumerate stars without
     * listing the catalog. At level {@code 3} a tile spans {@code 2000/8 = 250}
     * units. Kept identical to the E6-03 small-tier boundary so existing client
     * expectations and the rest-api contract are unchanged.
     */
    public static final int STAR_LIST_MIN_LEVEL = 3;

    /** @return number of tiles per axis at {@code level} ({@code 2^level}). */
    public static long tilesPerAxis(int level) {
        return 1L << level;
    }

    /** @return side length (galaxy units) of a tile at {@code level}. */
    public static double tileSide(int level) {
        return (2.0 * GalaxyConstants.R_MAX) / tilesPerAxis(level);
    }

    /** Whether {@code (level, x, y)} is a legal address in this scheme. */
    public static boolean isValid(int level, long x, long y) {
        if (level < 0 || level > MAX_LEVEL) {
            return false;
        }
        long n = tilesPerAxis(level);
        return x >= 0 && x < n && y >= 0 && y < n;
    }

    /** Whether {@code level} should be served as a star list (vs an aggregate). */
    public static boolean isStarListLevel(int level) {
        return level >= STAR_LIST_MIN_LEVEL;
    }

    /** The world-space bounding box of a tile address (caller validates first). */
    public static TileBounds bounds(int level, long x, long y) {
        double side = tileSide(level);
        double minX = -GalaxyConstants.R_MAX + x * side;
        double minY = -GalaxyConstants.R_MAX + y * side;
        return new TileBounds(minX, minY, minX + side, minY + side);
    }

    /**
     * The Hilbert index of a tile within its level - the locality-preserving 1-D
     * key used for cache addressing and CDN/prefix ordering (lod-tiling skill).
     * Spatially adjacent tiles get nearby Hilbert indices, so a viewport maps to a
     * few short contiguous key runs.
     */
    public static long hilbertIndex(int level, long x, long y) {
        return HilbertCurve.index(level, x, y);
    }
}
