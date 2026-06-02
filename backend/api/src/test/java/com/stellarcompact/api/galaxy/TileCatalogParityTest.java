package com.stellarcompact.api.galaxy;

import com.stellarcompact.galaxy.gen.CatalogGenerator;
import com.stellarcompact.galaxy.gen.CatalogStar;
import com.stellarcompact.galaxy.gen.Cell;
import com.stellarcompact.galaxy.gen.GalaxyConstants;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Parity proof (E8-04 done-when): a fine-level star tile contains EXACTLY the
 * {@link CatalogGenerator} stars whose positions fall inside the tile bbox - the
 * tile service reuses the shared per-cell facade and never reimplements star
 * generation, so server tiles stay byte-identical to client-side scenery (E8-03).
 */
class TileCatalogParityTest {

    private final TileGenerator gen = new TileGenerator();

    @Test
    void fineTileStarsMatchCatalogGeneratorForCoveredCells() {
        long seed = 42L;
        int level = 4;
        int tx = 8;
        int ty = 8; // straddles the dense galactic centre

        var tile = (TilePayload.StarListTile) gen.generate(seed, level, tx, ty);
        TileGrid.Bbox bbox = TileGrid.bbox(level, tx, ty);

        // Independently enumerate the catalog for every cell the bbox overlaps and
        // keep the in-bbox stars. This is the reference the tile must match.
        double cs = GalaxyConstants.CELL_SIZE;
        int cMinX = (int) Math.floor(bbox.minX() / cs);
        int cMaxX = (int) Math.floor((bbox.maxX() - 1e-9) / cs);
        int cMinY = (int) Math.floor(bbox.minY() / cs);
        int cMaxY = (int) Math.floor((bbox.maxY() - 1e-9) / cs);

        List<CatalogStar> expected = new ArrayList<>();
        for (int cy = cMinY; cy <= cMaxY; cy++) {
            for (int cx = cMinX; cx <= cMaxX; cx++) {
                for (CatalogStar s : CatalogGenerator.generateCell(seed, new Cell(cx, cy))) {
                    if (bbox.contains(s.x(), s.y())) {
                        expected.add(s);
                    }
                }
            }
        }
        expected.sort((a, b) -> Long.compareUnsigned(a.id(), b.id()));

        assertThat(tile.stars()).hasSameSizeAs(expected);
        for (int i = 0; i < expected.size(); i++) {
            CatalogStar e = expected.get(i);
            var s = tile.stars().get(i);
            assertThat(s.localId()).isEqualTo(e.id());
            assertThat(s.x()).isEqualTo(e.x());
            assertThat(s.y()).isEqualTo(e.y());
            assertThat(s.spectral()).isEqualTo(e.spectral().name());
            assertThat(s.brightness()).isEqualTo(e.brightness());
            assertThat(s.size()).isEqualTo(e.size());
        }
        assertThat(tile.stars()).isNotEmpty();
    }
}
