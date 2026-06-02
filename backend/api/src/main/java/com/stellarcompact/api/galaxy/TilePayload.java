package com.stellarcompact.api.galaxy;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.annotation.JsonSubTypes;

import java.util.List;

/**
 * The LOD tile payload union (rest-api spec, Galaxy tiles): an immutable,
 * procedural tile is either an {@link AggregateTile} (coarse zoom: a density
 * summary of the region, the galaxy glow/arms) or a {@link StarListTile} (fine
 * zoom: the actual stars in the small region). Both are pure functions of
 * {@code (gameSeed, level, x, y)} - nothing here is stored.
 *
 * <p>Sealed so the choice is closed and exhaustively switchable. Jackson emits a
 * {@code "kind"} discriminator so the client can tell the two apart; the
 * production WebGL2 renderer composites them by zoom (lod-tiling skill).
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.EXISTING_PROPERTY,
        property = "kind")
@JsonSubTypes({
        @JsonSubTypes.Type(value = TilePayload.AggregateTile.class, name = "aggregate"),
        @JsonSubTypes.Type(value = TilePayload.StarListTile.class, name = "starlist")
})
public sealed interface TilePayload
        permits TilePayload.AggregateTile, TilePayload.StarListTile {

    /** Stable discriminator value, also emitted in the JSON body. */
    @JsonProperty("kind")
    String kind();

    int level();

    int x();

    int y();

    TileGrid.Bbox bbox();

    /** Bumped whenever the tile payload shape or generation changes (ETag input). */
    int schemaVersion();

    /**
     * Coarse-zoom aggregate: a handful of representative density "impostors" and
     * summary colour/density stats over the tile region. This renders the galaxy's
     * glow and arm structure without listing millions of stars (architecture 02
     * section 3, lod-tiling skill).
     *
     * @param impostors  representative weighted points sampled over the region
     * @param colorStats summary density statistics for the region
     */
    record AggregateTile(int level, int x, int y, TileGrid.Bbox bbox,
                         List<Impostor> impostors, ColorStats colorStats,
                         int schemaVersion) implements TilePayload {

        public AggregateTile {
            impostors = List.copyOf(impostors);
        }

        @Override
        public String kind() {
            return "aggregate";
        }

        /** A representative density sample point used to paint the aggregate glow. */
        public record Impostor(double x, double y, double weight) {
        }

        /** Region density summary (drives the aggregate brightness/tint). */
        public record ColorStats(double avgDensity, double peakDensity, int sampleCount) {
        }
    }

    /**
     * Fine-zoom star list: the procedural stars whose positions fall inside the
     * tile bbox, each carrying the seed-derived spectral/brightness/size so the
     * client can render it without a second round-trip. {@code activeSystemId} is
     * set only for stars promoted to a live persisted system (E2-05/E6-01);
     * procedural scenery leaves it null.
     */
    record StarListTile(int level, int x, int y, TileGrid.Bbox bbox,
                        List<StarDto> stars, int schemaVersion) implements TilePayload {

        public StarListTile {
            stars = List.copyOf(stars);
        }

        @Override
        public String kind() {
            return "starlist";
        }

        /**
         * One scenery/active star in a fine tile.
         *
         * @param localId        the star's stable seed-derived id ({@code Star#id})
         * @param x              galaxy-space x
         * @param y              galaxy-space y
         * @param spectral       Morgan-Keenan class name (O..M)
         * @param brightness     relative luminosity (Sol G ~ 1.0)
         * @param size           relative stellar radius (Sol G ~ 1.0)
         * @param activeSystemId live system id if promoted, else null (scenery)
         */
        public record StarDto(long localId, double x, double y, String spectral,
                             double brightness, double size, Long activeSystemId) {
        }
    }
}
