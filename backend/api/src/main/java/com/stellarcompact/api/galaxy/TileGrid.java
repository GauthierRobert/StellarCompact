package com.stellarcompact.api.galaxy;

import com.stellarcompact.galaxy.gen.GalaxyConstants;

/**
 * Quadtree tile addressing over the galaxy square, and the aggregate-vs-star-list
 * level split (E6-03, the small-tier slice of the lod-tiling scheme; the full
 * Hilbert/quadtree machinery is E8-04).
 *
 * <p>The whole galaxy lives in the square {@code [-R_MAX, R_MAX]} on each axis
 * (galaxy units; same space the procedural generators place stars in). Level
 * {@code L} dices that square into {@code 2^L} tiles per axis, so tile
 * {@code (x, y)} at level {@code L} covers, on each axis,
 * {@code [-R_MAX + i*side, -R_MAX + (i+1)*side]} with
 * {@code side = 2*R_MAX / 2^L}. This is slippy-map {@code z/x/y} in spirit
 * (architecture 02 section 3).
 *
 * <p><strong>LOD split.</strong> Coarse levels (below
 * {@link #STAR_LIST_MIN_LEVEL}) summarise their region as a density aggregate
 * (the galaxy glow/arms); fine levels list the actual procedural stars in the
 * tiny region. This is the "detail is a function of zoom" rule.
 *
 * <p>Pure and stateless: addressing is a closed-form function of the address, so
 * a tile's bbox is reproducible on every call.
 */
public final class TileGrid {

    /**
     * Highest quadtree level served in this small tier. At
     * {@code level = MAX_LEVEL} a tile spans {@code 2*R_MAX / 2^MAX_LEVEL} galaxy
     * units per axis. Six levels gives a smallest tile of
     * {@code 2000 / 64 ~= 31.25} units - finer than one placement
     * {@link GalaxyConstants#CELL_SIZE} (50) so fine tiles hold a handful of
     * stars, matching the "hundreds-few thousand" target. The full deep-zoom
     * descent (floating origin, many more levels) is E8-04.
     */
    public static final int MAX_LEVEL = 6;

    /**
     * First level that returns a {@code StarListTile}. Levels {@code 0..2} are
     * aggregates (the galaxy can't resolve individual stars there); levels
     * {@code 3..MAX_LEVEL} list stars. At level 3 a tile spans
     * {@code 2000 / 8 = 250} units, small enough to enumerate without listing the
     * whole catalog.
     */
    public static final int STAR_LIST_MIN_LEVEL = 3;

    private TileGrid() {
    }

    /** @return number of tiles per axis at {@code level} ({@code 2^level}). */
    public static int tilesPerAxis(int level) {
        return 1 << level;
    }

    /** @return side length (galaxy units) of a tile at {@code level}. */
    public static double tileSide(int level) {
        return (2.0 * GalaxyConstants.R_MAX) / tilesPerAxis(level);
    }

    /** Whether {@code level} (and the {@code x,y} pair) is a legal address. */
    public static boolean isValid(int level, int x, int y) {
        if (level < 0 || level > MAX_LEVEL) {
            return false;
        }
        int n = tilesPerAxis(level);
        return x >= 0 && x < n && y >= 0 && y < n;
    }

    /** Whether {@code level} should be served as a star list (vs an aggregate). */
    public static boolean isStarListLevel(int level) {
        return level >= STAR_LIST_MIN_LEVEL;
    }

    /** The world-space bounding box of a tile address. */
    public static Bbox bbox(int level, int x, int y) {
        double side = tileSide(level);
        double minX = -GalaxyConstants.R_MAX + x * side;
        double minY = -GalaxyConstants.R_MAX + y * side;
        return new Bbox(minX, minY, minX + side, minY + side);
    }

    /** An axis-aligned bounding box in galaxy units. */
    public record Bbox(double minX, double minY, double maxX, double maxY) {

        /** Inclusive-min, exclusive-max containment (so adjacent tiles tile cleanly). */
        public boolean contains(double px, double py) {
            return px >= minX && px < maxX && py >= minY && py < maxY;
        }
    }
}
