package com.stellarcompact.api.galaxy;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure (no-Spring) tests for tile addressing, the LOD split, deterministic
 * generation and the ETag - the byte-identical-per-seed invariant the lod-tiling
 * skill requires.
 */
class TileGeneratorTest {

    private final TileGenerator gen = new TileGenerator();

    @Test
    void level0IsAggregateAndFineLevelsAreStarLists() {
        assertThat(TileGrid.isStarListLevel(0)).isFalse();
        assertThat(TileGrid.isStarListLevel(TileGrid.STAR_LIST_MIN_LEVEL)).isTrue();
        assertThat(gen.generate(1L, 0, 0, 0))
                .isInstanceOf(TilePayload.AggregateTile.class);
        assertThat(gen.generate(1L, TileGrid.STAR_LIST_MIN_LEVEL, 4, 4))
                .isInstanceOf(TilePayload.StarListTile.class);
    }

    @Test
    void level0SingleTileCoversWholeGalaxy() {
        TileGrid.Bbox b = TileGrid.bbox(0, 0, 0);
        assertThat(b.minX()).isEqualTo(-1000.0);
        assertThat(b.maxX()).isEqualTo(1000.0);
        assertThat(TileGrid.tilesPerAxis(0)).isEqualTo(1);
        assertThat(TileGrid.tilesPerAxis(3)).isEqualTo(8);
    }

    @Test
    void starsAreContainedInTheTileBbox() {
        var tile = (TilePayload.StarListTile) gen.generate(42L, 4, 8, 8);
        for (var s : tile.stars()) {
            assertThat(tile.bbox().contains(s.x(), s.y())).isTrue();
        }
    }

    @Test
    void generationIsByteIdenticalForSameAddress() {
        TilePayload a = gen.generate(777L, 4, 8, 8);
        TilePayload b = gen.generate(777L, 4, 8, 8);
        assertThat(a).isEqualTo(b); // records: value equality == identical payload
    }

    @Test
    void etagIsDeterministicAndAddressSensitive() {
        String e1 = TileEtag.of(1L, 3, 4, 4, 1);
        String e2 = TileEtag.of(1L, 3, 4, 4, 1);
        assertThat(e1).isEqualTo(e2).matches("\"[0-9a-f]+\"");
        assertThat(TileEtag.of(1L, 3, 4, 4, 1))
                .isNotEqualTo(TileEtag.of(2L, 3, 4, 4, 1));
        assertThat(TileEtag.of(1L, 3, 4, 4, 1))
                .isNotEqualTo(TileEtag.of(1L, 3, 4, 5, 1));
        assertThat(TileEtag.of(1L, 3, 4, 4, 1))
                .isNotEqualTo(TileEtag.of(1L, 3, 4, 4, 2)); // schemaVersion bumps it
    }

    @Test
    void invalidAddressesAreRejectedByGrid() {
        assertThat(TileGrid.isValid(-1, 0, 0)).isFalse();
        assertThat(TileGrid.isValid(TileGrid.MAX_LEVEL + 1, 0, 0)).isFalse();
        assertThat(TileGrid.isValid(1, 2, 0)).isFalse(); // level 1 -> x in [0,2)
        assertThat(TileGrid.isValid(1, 1, 1)).isTrue();
    }
}
