package com.stellarcompact.api.galaxy;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pre-bake / cache-warming behaviour (E8-07): warming the coarse levels + an
 * active region populates the same {@link TileCache} the controller reads (so
 * subsequent requests are hits), is bounded (never iterates the catalog), and is
 * deterministic. The galaxy stays untouched - warming only triggers the same
 * generation a live request would.
 */
class TilePrebakeServiceTest {

    private TileCache newCache() {
        return new TileCache(
                new TileGenerator(), new ActiveSystemIndex.NoActiveSystems(),
                100_000);
    }

    @Test
    void warmCoarseFillsEveryAggregateTileBelowTheStarListBoundary() {
        TileCache cache = newCache();
        TilePrebakeService svc = new TilePrebakeService(cache);

        int warmed = svc.warmCoarse(42L);

        // 1 + 4 + 16 = 21 tiles for STAR_LIST_MIN_LEVEL = 3.
        assertThat(warmed).isEqualTo(21);
        assertThat(cache.isCached(42L, 0, 0, 0)).isTrue();
        assertThat(cache.isCached(42L, 1, 1, 1)).isTrue();
        assertThat(cache.isCached(42L, 2, 3, 3)).isTrue();
    }

    @Test
    void warmActiveRegionMakesThoseFineTilesCacheHits() {
        TileCache cache = newCache();
        TilePrebakeService svc = new TilePrebakeService(cache);

        // Region around the galaxy centre at fine level 4.
        int warmed = svc.warmActiveRegion(7L, 4, -100, -100, 100, 100);
        assertThat(warmed).isGreaterThan(0);

        // The centre tile of that region is now warm.
        long cx = TileGrid.tileIndexForWorld(4, 0.0);
        long cy = TileGrid.tileIndexForWorld(4, 0.0);
        assertThat(cache.isCached(7L, 4, (int) cx, (int) cy)).isTrue();
    }

    @Test
    void warmActiveRegionIsBoundedByMaxActiveTiles() {
        TileCache cache = newCache();
        TilePrebakeService svc = new TilePrebakeService(cache);

        // A bbox spanning the whole galaxy at a deep level would address an
        // enormous tile grid; the warm must hard-cap so it never iterates it.
        int warmed = svc.warmActiveRegion(1L, 12, -1000, -1000, 1000, 1000);
        assertThat(warmed).isLessThanOrEqualTo(TilePrebakeService.MAX_ACTIVE_TILES);
    }

    @Test
    void warmActiveRegionRejectsCoarseLevels() {
        TilePrebakeService svc = new TilePrebakeService(newCache());
        assertThatThrownBy(() -> svc.warmActiveRegion(1L, 2, 0, 0, 1, 1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void warmingIsIdempotentAndCheapOnRepeat() {
        TileCache cache = newCache();
        TilePrebakeService svc = new TilePrebakeService(cache);

        svc.warmCoarse(99L);
        int sizeAfterFirst = cache.size();
        // Re-warming hits the cache; no new entries are created.
        svc.warmCoarse(99L);
        assertThat(cache.size()).isEqualTo(sizeAfterFirst);
    }

    @Test
    void warmLaunchWarmsCoarsePlusActiveInOneCall() {
        TileCache cache = newCache();
        TilePrebakeService svc = new TilePrebakeService(cache);

        int total = svc.warmLaunch(2024L, 4, -200, -200, 200, 200);
        assertThat(total).isGreaterThan(21);
        assertThat(cache.size()).isEqualTo(total);
    }
}
