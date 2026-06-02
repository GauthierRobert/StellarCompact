package com.stellarcompact.api.galaxy;

import org.springframework.stereotype.Service;

/**
 * Tile pre-bake / cache-warming routine (E8-07; architecture 02 section 5
 * "Pre-generation (optional): for a launched galaxy, coarse tiles and the active
 * region's fine tiles can be pre-baked so the common views are warm").
 *
 * <p>Warming a tile is just a {@link TileCache#get} against the cacheable
 * generate-on-miss cache: a miss generates+retains the tile, a hit is a no-op.
 * So after a warm the subsequent live {@code GET .../tile/...} (and the CDN behind
 * it) is a cache hit. The pre-bake adds NOTHING to the served payload or its
 * headers - it only populates the same cache the controller already reads, so the
 * long-TTL/immutable/ETag CDN semantics from {@link TileController} are unchanged.
 *
 * <h2>Bounded, never iterates the catalog (principle 3: scale discipline)</h2>
 * Two warm sets, both bounded and tiny relative to a billion-star catalog:
 * <ul>
 *   <li><b>Coarse aggregate levels</b> {@code 0..STAR_LIST_MIN_LEVEL-1}: these are
 *       the zoomed-out galaxy-glow tiles every session opens on. Their total count
 *       is fixed and small ({@code sum 4^L} over the coarse levels - 21 tiles for
 *       the default {@code STAR_LIST_MIN_LEVEL = 3}).</li>
 *   <li><b>The active region's fine tiles</b> at one fine level: only the tiles
 *       whose bbox overlaps the supplied active-region world bbox (where the
 *       Sovereigns actually are), hard-capped by {@link #MAX_ACTIVE_TILES} so a
 *       pathological bbox can never warm an unbounded set.</li>
 * </ul>
 * Everything else stays cold and is generated on demand - exactly the Google-Maps
 * model (only common/active views are warm).
 *
 * <p>Determinism preserved: warming only triggers the same deterministic
 * generation a live request would, never mutating the galaxy. The galaxy module
 * stays pure; this service is api-layer orchestration over {@link TileCache}.
 */
@Service
public class TilePrebakeService {

    /**
     * Upper bound on fine tiles warmed for an active region, so a huge bbox cannot
     * trigger unbounded generation. A region wider than this many tiles is warmed
     * only at its near corner up to the cap (the rest stays generate-on-miss).
     */
    public static final int MAX_ACTIVE_TILES = 1024;

    private final TileCache tileCache;

    public TilePrebakeService(TileCache tileCache) {
        this.tileCache = tileCache;
    }

    /**
     * Warm the coarse aggregate levels for a seed (the always-loaded zoomed-out
     * views). Bounded: the coarse levels hold a fixed, small number of tiles.
     *
     * @return the number of tiles warmed.
     */
    public int warmCoarse(long gameSeed) {
        int warmed = 0;
        for (int level = 0; level < TileGrid.STAR_LIST_MIN_LEVEL; level++) {
            long n = TileGrid.tilesPerAxis(level);
            for (long y = 0; y < n; y++) {
                for (long x = 0; x < n; x++) {
                    tileCache.get(gameSeed, level, (int) x, (int) y);
                    warmed++;
                }
            }
        }
        return warmed;
    }

    /**
     * Warm the fine (star-list) tiles covering an active-region world bbox at
     * {@code level}, so the views around where the Sovereigns operate are hot.
     * Bounded by {@link #MAX_ACTIVE_TILES}; {@code level} must be a star-list level.
     *
     * @return the number of tiles warmed.
     * @throws IllegalArgumentException if {@code level} is not a fine/star-list level.
     */
    public int warmActiveRegion(long gameSeed, int level,
                                double minX, double minY, double maxX, double maxY) {
        if (!TileGrid.isStarListLevel(level)) {
            throw new IllegalArgumentException(
                    "active-region warm requires a star-list level (>= "
                            + TileGrid.STAR_LIST_MIN_LEVEL + "), got " + level);
        }
        long x0 = TileGrid.tileIndexForWorld(level, Math.min(minX, maxX));
        long x1 = TileGrid.tileIndexForWorld(level, Math.max(minX, maxX));
        long y0 = TileGrid.tileIndexForWorld(level, Math.min(minY, maxY));
        long y1 = TileGrid.tileIndexForWorld(level, Math.max(minY, maxY));

        int warmed = 0;
        for (long y = y0; y <= y1; y++) {
            for (long x = x0; x <= x1; x++) {
                if (warmed >= MAX_ACTIVE_TILES) {
                    return warmed;
                }
                tileCache.get(gameSeed, level, (int) x, (int) y);
                warmed++;
            }
        }
        return warmed;
    }

    /**
     * Warm a launched galaxy's common views in one call: the coarse aggregate
     * levels plus the active region's fine tiles. Returns the total tiles warmed.
     */
    public int warmLaunch(long gameSeed, int activeLevel,
                          double minX, double minY, double maxX, double maxY) {
        return warmCoarse(gameSeed)
                + warmActiveRegion(gameSeed, activeLevel, minX, minY, maxX, maxY);
    }
}
