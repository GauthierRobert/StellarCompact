package com.stellarcompact.api.galaxy;

import com.stellarcompact.galaxy.gen.GalaxyConstants;
import com.stellarcompact.galaxy.tile.QuadTileScheme;
import com.stellarcompact.galaxy.tile.TileBounds;

/**
 * Thin HTTP-side adapter over the pure {@link QuadTileScheme} (galaxy module),
 * which owns all the quadtree/Hilbert math (E8-04). This class keeps the api-local
 * {@link Bbox} DTO shape the tile/overlay payloads already serialise and forwards
 * every addressing decision to the framework-free scheme - so the math stays
 * unit-testable without Spring and is shared with any other caller.
 *
 * <p>The whole galaxy lives in the square {@code [-R_MAX, R_MAX]} on each axis;
 * level {@code L} dices it into {@code 2^L} tiles per axis. Coarse levels (below
 * {@link #STAR_LIST_MIN_LEVEL}) are density aggregates; fine levels list stars.
 * This is the "detail is a function of zoom" rule (architecture 02 section 3).
 *
 * <p>E6-03 served a 6-level small tier; E8-04 widened {@link #MAX_LEVEL} to the
 * full deep-zoom descent and moved the addressing/curve math into the galaxy
 * module. The aggregate-vs-star-list boundary ({@link #STAR_LIST_MIN_LEVEL}) is
 * unchanged so the rest-api contract is stable.
 */
public final class TileGrid {

    /** Deepest quadtree level served (full deep-zoom descent; see scheme). */
    public static final int MAX_LEVEL = QuadTileScheme.MAX_LEVEL;

    /** First level that returns a {@code StarListTile} (vs an aggregate). */
    public static final int STAR_LIST_MIN_LEVEL = QuadTileScheme.STAR_LIST_MIN_LEVEL;

    private TileGrid() {
    }

    /** @return number of tiles per axis at {@code level} ({@code 2^level}). */
    public static long tilesPerAxis(int level) {
        return QuadTileScheme.tilesPerAxis(level);
    }

    /** @return side length (galaxy units) of a tile at {@code level}. */
    public static double tileSide(int level) {
        return QuadTileScheme.tileSide(level);
    }

    /** Whether {@code level} (and the {@code x,y} pair) is a legal address. */
    public static boolean isValid(int level, long x, long y) {
        return QuadTileScheme.isValid(level, x, y);
    }

    /** Whether {@code level} should be served as a star list (vs an aggregate). */
    public static boolean isStarListLevel(int level) {
        return QuadTileScheme.isStarListLevel(level);
    }

    /**
     * The Hilbert index of a tile within its level - the locality-preserving cache
     * key (lod-tiling skill). Spatially adjacent tiles get nearby indices.
     */
    public static long hilbertIndex(int level, long x, long y) {
        return QuadTileScheme.hilbertIndex(level, x, y);
    }

    /** The world-space bounding box of a tile address. */
    public static Bbox bbox(int level, long x, long y) {
        TileBounds b = QuadTileScheme.bounds(level, x, y);
        return new Bbox(b.minX(), b.minY(), b.maxX(), b.maxY());
    }

    /**
     * The tile index on one axis containing world coordinate {@code w} at
     * {@code level} - the inverse of {@link #bbox} (the whole galaxy lives in
     * {@code [-R_MAX, R_MAX]}, diced into {@code 2^level} tiles per axis). Used by
     * the pre-bake/warm path (E8-07) to map an active-region bbox to the tile
     * addresses covering it. Clamped to {@code [0, tilesPerAxis-1]} so a query at
     * or just past the galaxy edge stays a legal address.
     */
    public static long tileIndexForWorld(int level, double w) {
        long n = tilesPerAxis(level);
        double side = tileSide(level);
        long i = (long) Math.floor((w + GalaxyConstants.R_MAX) / side);
        if (i < 0) {
            return 0;
        }
        if (i > n - 1) {
            return n - 1;
        }
        return i;
    }

    /** An axis-aligned bounding box in galaxy units (the api DTO shape). */
    public record Bbox(double minX, double minY, double maxX, double maxY) {

        /** Inclusive-min, exclusive-max containment (so adjacent tiles tile cleanly). */
        public boolean contains(double px, double py) {
            return px >= minX && px < maxX && py >= minY && py < maxY;
        }
    }
}
