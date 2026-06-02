package com.stellarcompact.galaxy.tile;

import com.stellarcompact.galaxy.gen.GalaxyConstants;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The pure quadtree scheme: addressing, the level -> payload-kind boundary,
 * deep-zoom validity and tile footprints (E8-04).
 */
class QuadTileSchemeTest {

    @Test
    void levelToPayloadKindBoundaryIsExact() {
        for (int level = 0; level < QuadTileScheme.STAR_LIST_MIN_LEVEL; level++) {
            assertFalse(QuadTileScheme.isStarListLevel(level),
                    "level " + level + " is aggregate");
        }
        for (int level = QuadTileScheme.STAR_LIST_MIN_LEVEL;
             level <= QuadTileScheme.MAX_LEVEL; level++) {
            assertTrue(QuadTileScheme.isStarListLevel(level),
                    "level " + level + " is star list");
        }
    }

    @Test
    void level0SingleTileCoversWholeGalaxy() {
        assertEquals(1L, QuadTileScheme.tilesPerAxis(0));
        TileBounds b = QuadTileScheme.bounds(0, 0, 0);
        assertEquals(-GalaxyConstants.R_MAX, b.minX());
        assertEquals(GalaxyConstants.R_MAX, b.maxX());
        assertEquals(-GalaxyConstants.R_MAX, b.minY());
        assertEquals(GalaxyConstants.R_MAX, b.maxY());
    }

    @Test
    void tilesPerAxisAndSideHalveEachLevel() {
        assertEquals(8L, QuadTileScheme.tilesPerAxis(3));
        assertEquals(1024L, QuadTileScheme.tilesPerAxis(10));
        assertEquals(2.0 * GalaxyConstants.R_MAX, QuadTileScheme.tileSide(0));
        assertEquals(GalaxyConstants.R_MAX, QuadTileScheme.tileSide(1));
    }

    @Test
    void deepestLevelTileIsFinerThanAPlacementCell() {
        // O(visible): a deep tile spans far less than CELL_SIZE, so it holds at
        // most a handful of stars regardless of catalog size.
        double deepSide = QuadTileScheme.tileSide(QuadTileScheme.MAX_LEVEL);
        assertTrue(deepSide < GalaxyConstants.CELL_SIZE,
                "deep tile side " + deepSide + " < CELL_SIZE");
    }

    @Test
    void adjacentTilesTileThePlaneWithoutGapOrOverlap() {
        int level = 4;
        TileBounds t0 = QuadTileScheme.bounds(level, 0, 0);
        TileBounds t1 = QuadTileScheme.bounds(level, 1, 0);
        assertEquals(t0.maxX(), t1.minX());
        // a point on the shared edge belongs to exactly one tile (exclusive max)
        assertFalse(t0.contains(t0.maxX(), t0.minY()));
        assertTrue(t1.contains(t1.minX(), t1.minY()));
    }

    @Test
    void validityIsBoundedByLevelAndAxisCount() {
        assertFalse(QuadTileScheme.isValid(-1, 0, 0));
        assertFalse(QuadTileScheme.isValid(QuadTileScheme.MAX_LEVEL + 1, 0, 0));
        assertFalse(QuadTileScheme.isValid(1, 2, 0)); // level 1 -> x in [0,2)
        assertTrue(QuadTileScheme.isValid(1, 1, 1));
        long maxAtDeep = QuadTileScheme.tilesPerAxis(QuadTileScheme.MAX_LEVEL) - 1;
        assertTrue(QuadTileScheme.isValid(QuadTileScheme.MAX_LEVEL, maxAtDeep, maxAtDeep));
    }

    @Test
    void hilbertIndexDelegatesToTheCurve() {
        for (int level = 0; level <= 5; level++) {
            long n = 1L << level;
            for (long y = 0; y < n; y++) {
                for (long x = 0; x < n; x++) {
                    assertEquals(HilbertCurve.index(level, x, y),
                            QuadTileScheme.hilbertIndex(level, x, y));
                }
            }
        }
    }
}
