package com.stellarcompact.api.galaxy;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

import java.time.Duration;

/**
 * Serves the scale-critical, immutable, procedural galaxy tiles
 * {@code GET /api/galaxy/{gameSeed}/tile/{level}/{x}/{y}} (rest-api spec; lod-tiling
 * skill; architecture 02 sections 3 and 5).
 *
 * <p>A tile is a pure function of {@code (gameSeed, level, x, y)} served via the
 * generate-on-miss {@link TileCache} - never persisted; the cache is a pure
 * optimisation keyed by the Hilbert-ordered quadkey (E8-04). The response is
 * heavily cacheable downstream too: a strong {@code ETag} keyed by
 * {@code (seed, level, x, y, schemaVersion)} plus
 * {@code Cache-Control: public, max-age=1y, immutable}. {@code If-None-Match}
 * short-circuits to {@code 304 Not Modified} without generating (or even touching
 * the cache for) the payload - sound precisely because the content is a pure
 * function of that address.
 *
 * <p>Blocking handlers on purpose: under Spring Boot 4 / Java 25 virtual threads
 * each request runs on its own carrier-cheap virtual thread, so straight-line
 * blocking generation is the simplest correct model.
 */
@RestController
@RequestMapping("/api/galaxy")
public class TileController {

    private final TileCache tileCache;

    public TileController(TileCache tileCache) {
        this.tileCache = tileCache;
    }

    @GetMapping("/{gameSeed}/tile/{level}/{x}/{y}")
    public ResponseEntity<TilePayload> tile(
            @PathVariable("gameSeed") long gameSeed,
            @PathVariable("level") int level,
            @PathVariable("x") int x,
            @PathVariable("y") int y,
            @RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false)
            String ifNoneMatch) {

        if (!TileGrid.isValid(level, x, y)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "tile address out of range: level=" + level + " x=" + x + " y=" + y);
        }

        String etag = TileEtag.of(gameSeed, level, x, y, TileGenerator.SCHEMA_VERSION);
        CacheControl cache = CacheControl
                .maxAge(Duration.ofDays(365))
                .cachePublic()
                .immutable();

        // Conditional GET: honour If-None-Match (may be a comma-separated list).
        if (ifNoneMatch != null && matches(ifNoneMatch, etag)) {
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED)
                    .eTag(etag)
                    .cacheControl(cache)
                    .build();
        }

        TilePayload payload = tileCache.get(gameSeed, level, x, y);
        return ResponseEntity.ok()
                .eTag(etag)
                .cacheControl(cache)
                .body(payload);
    }

    /** True if any token in the If-None-Match header equals the tile's ETag (or "*"). */
    private static boolean matches(String header, String etag) {
        for (String token : header.split(",")) {
            String t = token.trim();
            if (t.equals("*") || t.equals(etag)) {
                return true;
            }
        }
        return false;
    }
}
