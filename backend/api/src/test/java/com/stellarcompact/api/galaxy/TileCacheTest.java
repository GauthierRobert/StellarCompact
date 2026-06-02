package com.stellarcompact.api.galaxy;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Generate-on-miss + cache behaviour (E8-04): a miss generates deterministically;
 * a hit returns the identical cached payload; the key is the Hilbert-ordered
 * quadkey; the bounded LRU evicts; and active-system pointers are merged at fine
 * levels.
 */
class TileCacheTest {

    private TileCache newCache() {
        return new TileCache(new TileGenerator(), new ActiveSystemIndex.NoActiveSystems());
    }

    @Test
    void missGeneratesThenHitReturnsTheSameInstance() {
        TileCache cache = newCache();
        assertThat(cache.isCached(42L, 4, 8, 8)).isFalse();

        TilePayload first = cache.get(42L, 4, 8, 8);
        assertThat(cache.isCached(42L, 4, 8, 8)).isTrue();

        TilePayload second = cache.get(42L, 4, 8, 8);
        // Cache hit: same immutable instance, not a regeneration.
        assertThat(second).isSameAs(first);
    }

    @Test
    void payloadIsDeterministicAcrossSeparateCaches() {
        TilePayload a = newCache().get(777L, 4, 8, 8);
        TilePayload b = newCache().get(777L, 4, 8, 8);
        assertThat(a).isEqualTo(b); // records: value equality == byte-identical
    }

    @Test
    void boundedLruEvictsBeyondCapacity() {
        TileCache cache = new TileCache(
                new TileGenerator(), new ActiveSystemIndex.NoActiveSystems(), 4);
        // Fill past capacity with distinct fine-level tiles.
        for (int i = 0; i < 10; i++) {
            cache.get(1L, 4, i, 0);
        }
        assertThat(cache.size()).isEqualTo(4);
    }

    @Test
    void distinctAddressesGetDistinctHilbertKeysSoBothCache() {
        TileCache cache = newCache();
        cache.get(1L, 4, 0, 0);
        cache.get(1L, 4, 1, 0);
        cache.get(1L, 4, 0, 1);
        // Three spatially-distinct tiles -> three cache entries (no key collision).
        assertThat(cache.size()).isEqualTo(3);
        assertThat(cache.isCached(1L, 4, 0, 0)).isTrue();
        assertThat(cache.isCached(1L, 4, 1, 0)).isTrue();
        assertThat(cache.isCached(1L, 4, 0, 1)).isTrue();
    }

    @Test
    void hilbertKeysOfDistinctTilesDoNotCollide() {
        // The cache keys on hilbertIndex(level,x,y); a per-level bijection means no
        // two tiles share a key. Verify the index is unique across a level.
        int level = 4;
        long n = TileGrid.tilesPerAxis(level);
        Set<Long> indices = new HashSet<>();
        for (long y = 0; y < n; y++) {
            for (long x = 0; x < n; x++) {
                assertThat(indices.add(TileGrid.hilbertIndex(level, x, y))).isTrue();
            }
        }
        assertThat(indices).hasSize((int) (n * n));
    }

    @Test
    void activeSystemPointerIsMergedAtFineLevels() {
        // A stub index that promotes every star to id starId+1_000_000.
        ActiveSystemIndex promoteAll = (seed, starId) -> starId + 1_000_000L;
        TileCache cache = new TileCache(new TileGenerator(), promoteAll);

        var tile = (TilePayload.StarListTile) cache.get(42L, 4, 8, 8);
        assertThat(tile.stars()).isNotEmpty();
        for (var s : tile.stars()) {
            assertThat(s.activeSystemId()).isEqualTo(s.localId() + 1_000_000L);
        }
    }

    @Test
    void sceneryTilesHaveNullActiveSystemIdByDefault() {
        var tile = (TilePayload.StarListTile) newCache().get(42L, 4, 8, 8);
        assertThat(tile.stars()).allSatisfy(
                s -> assertThat(s.activeSystemId()).isNull());
    }
}
