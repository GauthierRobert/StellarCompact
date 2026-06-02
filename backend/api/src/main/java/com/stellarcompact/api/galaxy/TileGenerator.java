package com.stellarcompact.api.galaxy;

import com.stellarcompact.galaxy.gen.CatalogGenerator;
import com.stellarcompact.galaxy.gen.CatalogStar;
import com.stellarcompact.galaxy.gen.Cell;
import com.stellarcompact.galaxy.gen.GalaxyConstants;
import com.stellarcompact.galaxy.gen.SpiralDensityField;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Generates an immutable LOD {@link TilePayload} on demand from
 * {@code (gameSeed, level, x, y)} by calling the framework-free galaxy generators
 * (architecture 02 section 5; lod-tiling skill "generate on miss"). Tiles are
 * NEVER stored - they are pure functions of the seed and the address, so the same
 * inputs yield a byte-identical payload on every call and JVM. The HTTP layer is
 * the only place caching headers (ETag, long TTL) are applied.
 *
 * <p>Read-only against galaxy: this calls {@link CatalogGenerator} (E8-03's
 * shared per-cell facade) and {@link SpiralDensityField}; it does not modify them.
 *
 * <p>Active-system promotion ({@code activeSystemId}) is the concern of E2-05/
 * E6-01; until that is wired, every scenery star reports {@code null}. The thin
 * active overlay is served separately (see {@code OverlayController}) and is never
 * baked into these cacheable star tiles.
 */
@Component
public class TileGenerator {

    /**
     * Tile payload schema version. Part of the ETag key
     * {@code (seed, level, x, y, schemaVersion)}; bump it whenever the payload
     * shape or the generation output changes so stale cached/CDN tiles are
     * invalidated.
     */
    public static final int SCHEMA_VERSION = 1;

    /** Density samples per axis when summarising a coarse tile region. */
    private static final int AGG_SAMPLES_PER_AXIS = 8;

    /** Cap on impostors emitted per aggregate tile (bounded payload). */
    private static final int MAX_IMPOSTORS = 64;

    /** Generate the tile for an address. Caller must have validated the address. */
    public TilePayload generate(long gameSeed, int level, int x, int y) {
        TileGrid.Bbox bbox = TileGrid.bbox(level, x, y);
        if (TileGrid.isStarListLevel(level)) {
            return starList(gameSeed, level, x, y, bbox);
        }
        return aggregate(level, x, y, bbox);
    }

    /**
     * Coarse aggregate: sample the pure {@link SpiralDensityField} on a grid over
     * the region for colour stats, and keep the densest sample points as
     * impostors. Independent of the seed (the field is the galaxy's fixed shape),
     * but still keyed by the address so the payload is deterministic.
     */
    private TilePayload aggregate(int level, int x, int y, TileGrid.Bbox bbox) {
        double w = bbox.maxX() - bbox.minX();
        double h = bbox.maxY() - bbox.minY();
        double stepX = w / AGG_SAMPLES_PER_AXIS;
        double stepY = h / AGG_SAMPLES_PER_AXIS;

        List<TilePayload.AggregateTile.Impostor> impostors = new ArrayList<>();
        double sum = 0.0;
        double peak = 0.0;
        int n = 0;
        for (int iy = 0; iy < AGG_SAMPLES_PER_AXIS; iy++) {
            for (int ix = 0; ix < AGG_SAMPLES_PER_AXIS; ix++) {
                double sx = bbox.minX() + (ix + 0.5) * stepX;
                double sy = bbox.minY() + (iy + 0.5) * stepY;
                double d = SpiralDensityField.densityAt(sx, sy);
                sum += d;
                n++;
                if (d > peak) {
                    peak = d;
                }
                if (d > 0.0) {
                    impostors.add(
                            new TilePayload.AggregateTile.Impostor(sx, sy, d));
                }
            }
        }
        double avg = n == 0 ? 0.0 : sum / n;

        // Keep the brightest impostors, bounded; order is deterministic (stable
        // sort on a deterministic input list).
        impostors.sort((a, b) -> Double.compare(b.weight(), a.weight()));
        if (impostors.size() > MAX_IMPOSTORS) {
            impostors = new ArrayList<>(impostors.subList(0, MAX_IMPOSTORS));
        }

        var stats = new TilePayload.AggregateTile.ColorStats(avg, peak, n);
        return new TilePayload.AggregateTile(level, x, y, bbox, impostors, stats,
                SCHEMA_VERSION);
    }

    /**
     * Fine star list: enumerate the procedural-grid cells overlapping the tile
     * bbox, generate their fully-derived catalog stars via the shared
     * {@link CatalogGenerator} (E8-03) and keep those whose position lands inside
     * the bbox. Using the same per-cell facade the TypeScript client mirrors keeps
     * tile output parity-locked to client-side scenery (one join, no drift).
     * Bounded by the tile size (fine tiles cover a tiny region), so this never
     * iterates the catalog.
     */
    private TilePayload starList(long gameSeed, int level, int x, int y,
                                 TileGrid.Bbox bbox) {
        double cs = GalaxyConstants.CELL_SIZE;
        int cMinX = (int) Math.floor(bbox.minX() / cs);
        int cMaxX = (int) Math.floor((bbox.maxX() - 1e-9) / cs);
        int cMinY = (int) Math.floor(bbox.minY() / cs);
        int cMaxY = (int) Math.floor((bbox.maxY() - 1e-9) / cs);

        List<TilePayload.StarListTile.StarDto> stars = new ArrayList<>();
        for (int cy = cMinY; cy <= cMaxY; cy++) {
            for (int cx = cMinX; cx <= cMaxX; cx++) {
                List<CatalogStar> cellStars =
                        CatalogGenerator.generateCell(gameSeed, new Cell(cx, cy));
                for (CatalogStar s : cellStars) {
                    if (!bbox.contains(s.x(), s.y())) {
                        continue;
                    }
                    stars.add(new TilePayload.StarListTile.StarDto(
                            s.id(), s.x(), s.y(), s.spectral().name(),
                            s.brightness(), s.size(),
                            // E2-05/E6-01 promotion not yet wired: scenery only.
                            null));
                }
            }
        }
        // Deterministic order independent of cell iteration: sort by id.
        stars.sort((a, b) -> Long.compareUnsigned(a.localId(), b.localId()));
        return new TilePayload.StarListTile(level, x, y, bbox, stars, SCHEMA_VERSION);
    }
}
