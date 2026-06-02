package com.stellarcompact.api.galaxy;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Serves the thin, dynamic active-state overlay
 * {@code GET /api/galaxy/{gameId}/overlay?bbox=&sinceTick=} (rest-api spec). This
 * is the small layer composited on top of the immutable star tiles: ownership,
 * contested, fleets, routes, blockades inside a bbox, scoped to what changed since
 * a tick. It is NOT cacheable (game state changes every tick) and carries NO
 * scenery/star data - keeping the heavy tiles cacheable and this layer thin
 * (architecture 02 sections 3 and 5).
 *
 * <p>The live data wiring (a running match) is E6-01/E6-04; here the source is the
 * {@link GalaxyStateSource} seam, currently backed by an in-memory stub. The
 * endpoint contract (bbox + sinceTick scoping, thin shape) is correct regardless.
 */
@RestController
@RequestMapping("/api/galaxy")
public class OverlayController {

    private final GalaxyStateSource stateSource;

    public OverlayController(GalaxyStateSource stateSource) {
        this.stateSource = stateSource;
    }

    @GetMapping("/{gameId}/overlay")
    public ResponseEntity<OverlayPayload> overlay(
            @PathVariable("gameId") String gameId,
            @RequestParam("bbox") String bbox,
            @RequestParam(name = "sinceTick", defaultValue = "0") long sinceTick) {

        TileGrid.Bbox box = parseBbox(bbox);
        if (sinceTick < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "sinceTick must be >= 0");
        }
        OverlayPayload payload = stateSource.overlay(gameId, box, sinceTick);
        // Live state: never cache.
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(payload);
    }

    /** Parse "minX,minY,maxX,maxY"; 400 on malformed or inverted boxes. */
    private static TileGrid.Bbox parseBbox(String raw) {
        String[] parts = raw.split(",");
        if (parts.length != 4) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "bbox must be 'minX,minY,maxX,maxY'");
        }
        try {
            double minX = Double.parseDouble(parts[0].trim());
            double minY = Double.parseDouble(parts[1].trim());
            double maxX = Double.parseDouble(parts[2].trim());
            double maxY = Double.parseDouble(parts[3].trim());
            if (maxX <= minX || maxY <= minY) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "bbox max must exceed min on both axes");
            }
            return new TileGrid.Bbox(minX, minY, maxX, maxY);
        } catch (NumberFormatException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "bbox components must be numbers");
        }
    }
}
