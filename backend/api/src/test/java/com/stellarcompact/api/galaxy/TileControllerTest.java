package com.stellarcompact.api.galaxy;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.hamcrest.Matchers;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * MockMvc tests for the tile endpoint: payload shape (aggregate vs star-list by
 * level), ETag presence + 304 on If-None-Match, ETag determinism, payload
 * determinism, and address validation.
 *
 * <p>Standalone MockMvc setup (Spring Framework, no Boot context): Spring Boot 4
 * dropped the {@code @WebMvcTest} slice annotation, so the controller is wired
 * directly against the real {@link TileGenerator} with the framework's MVC
 * infrastructure (Jackson auto-registered, default exception resolver mapping
 * {@code ResponseStatusException} to the right status).
 */
class TileControllerTest {

    private MockMvc mvc;
    private TileCache cache;

    @BeforeEach
    void setUp() {
        cache = new TileCache(
                new TileGenerator(), new ActiveSystemIndex.NoActiveSystems());
        mvc = MockMvcBuilders
                .standaloneSetup(new TileController(cache, new TilePrebakeService(cache)))
                .build();
    }

    @Test
    void coarseLevelReturnsAggregateTile() throws Exception {
        mvc.perform(get("/api/galaxy/{seed}/tile/{l}/{x}/{y}", 42L, 0, 0, 0))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kind").value("aggregate"))
                .andExpect(jsonPath("$.level").value(0))
                .andExpect(jsonPath("$.impostors").isArray())
                .andExpect(jsonPath("$.colorStats.sampleCount").isNumber())
                .andExpect(jsonPath("$.schemaVersion").value(TileGenerator.SCHEMA_VERSION));
    }

    @Test
    void fineLevelReturnsStarListTile() throws Exception {
        // Level 3 is the first star-list level; tile (4,4) straddles the centre
        // (galaxy origin) where the bulge guarantees stars.
        mvc.perform(get("/api/galaxy/{seed}/tile/{l}/{x}/{y}", 42L, 3, 4, 4))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kind").value("starlist"))
                .andExpect(jsonPath("$.level").value(3))
                .andExpect(jsonPath("$.stars").isArray());
    }

    @Test
    void tileResponseCarriesStrongEtagAndImmutableCacheControl() throws Exception {
        mvc.perform(get("/api/galaxy/{seed}/tile/{l}/{x}/{y}", 7L, 3, 4, 4))
                .andExpect(status().isOk())
                .andExpect(header().exists(HttpHeaders.ETAG))
                .andExpect(header().string(HttpHeaders.ETAG,
                        Matchers.matchesPattern("\"[0-9a-f]+\"")))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL,
                        Matchers.containsString("max-age=31536000")))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL,
                        Matchers.containsString("public")))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL,
                        Matchers.containsString("immutable")));
    }

    @Test
    void ifNoneMatchYields304NotModified() throws Exception {
        MvcResult first = mvc.perform(
                        get("/api/galaxy/{seed}/tile/{l}/{x}/{y}", 7L, 1, 0, 0))
                .andExpect(status().isOk())
                .andReturn();
        String etag = first.getResponse().getHeader(HttpHeaders.ETAG);
        assertThat(etag).isNotNull();

        mvc.perform(get("/api/galaxy/{seed}/tile/{l}/{x}/{y}", 7L, 1, 0, 0)
                        .header(HttpHeaders.IF_NONE_MATCH, etag))
                .andExpect(status().isNotModified())
                .andExpect(header().string(HttpHeaders.ETAG, etag));
    }

    @Test
    void etagIsDeterministicAcrossRequests() throws Exception {
        String a = mvc.perform(get("/api/galaxy/{seed}/tile/{l}/{x}/{y}", 99L, 2, 1, 1))
                .andReturn().getResponse().getHeader(HttpHeaders.ETAG);
        String b = mvc.perform(get("/api/galaxy/{seed}/tile/{l}/{x}/{y}", 99L, 2, 1, 1))
                .andReturn().getResponse().getHeader(HttpHeaders.ETAG);
        assertThat(a).isNotNull().isEqualTo(b);
    }

    @Test
    void differentSeedYieldsDifferentEtag() throws Exception {
        String a = mvc.perform(get("/api/galaxy/{seed}/tile/{l}/{x}/{y}", 99L, 2, 1, 1))
                .andReturn().getResponse().getHeader(HttpHeaders.ETAG);
        String b = mvc.perform(get("/api/galaxy/{seed}/tile/{l}/{x}/{y}", 100L, 2, 1, 1))
                .andReturn().getResponse().getHeader(HttpHeaders.ETAG);
        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void tilePayloadIsDeterministicForSameAddress() throws Exception {
        String a = mvc.perform(get("/api/galaxy/{seed}/tile/{l}/{x}/{y}", 12345L, 4, 8, 8))
                .andReturn().getResponse().getContentAsString();
        String b = mvc.perform(get("/api/galaxy/{seed}/tile/{l}/{x}/{y}", 12345L, 4, 8, 8))
                .andReturn().getResponse().getContentAsString();
        assertThat(a).isEqualTo(b);
    }

    @Test
    void outOfRangeLevelIs400() throws Exception {
        mvc.perform(get("/api/galaxy/{seed}/tile/{l}/{x}/{y}", 1L, 99, 0, 0))
                .andExpect(status().isBadRequest());
    }

    @Test
    void outOfRangeCoordIs400() throws Exception {
        // level 1 has 2 tiles per axis, so x=5 is invalid.
        mvc.perform(get("/api/galaxy/{seed}/tile/{l}/{x}/{y}", 1L, 1, 5, 0))
                .andExpect(status().isBadRequest());
    }

    // ---- pre-bake / CDN warming (E8-07) ----

    @Test
    void prebakeWarmsCoarseLevelsAndActiveRegionAndReportsCounts() throws Exception {
        mvc.perform(post("/api/galaxy/{seed}/prebake", 555L)
                        .param("level", "4")
                        .param("minX", "-100").param("minY", "-100")
                        .param("maxX", "100").param("maxY", "100"))
                .andExpect(status().isOk())
                // 21 coarse tiles for STAR_LIST_MIN_LEVEL=3 (1+4+16).
                .andExpect(jsonPath("$.coarseTiles").value(21))
                .andExpect(jsonPath("$.activeTiles").value(Matchers.greaterThan(0)))
                .andExpect(jsonPath("$.totalTiles").value(Matchers.greaterThan(21)))
                // Not a cacheable artifact: warming reports are volatile.
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL,
                        Matchers.containsString("no-store")));
    }

    @Test
    void afterPrebakeSubsequentTileRequestsAreCacheHits() throws Exception {
        // A coarse tile is cold before warming...
        assertThat(cache.isCached(555L, 0, 0, 0)).isFalse();
        mvc.perform(post("/api/galaxy/{seed}/prebake", 555L)
                        .param("level", "4")
                        .param("minX", "-50").param("minY", "-50")
                        .param("maxX", "50").param("maxY", "50"))
                .andExpect(status().isOk());
        // ...and warm afterwards (the common views are hot -> CDN/cache hits).
        assertThat(cache.isCached(555L, 0, 0, 0)).isTrue();
        // The subsequent live GET still serves the immutable/ETag CDN headers.
        mvc.perform(get("/api/galaxy/{seed}/tile/{l}/{x}/{y}", 555L, 0, 0, 0))
                .andExpect(status().isOk())
                .andExpect(header().exists(HttpHeaders.ETAG))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL,
                        Matchers.containsString("immutable")));
    }

    @Test
    void prebakeRejectsACoarseLevelWith400() throws Exception {
        // Level 2 is a coarse aggregate level; the active region must be fine.
        mvc.perform(post("/api/galaxy/{seed}/prebake", 1L)
                        .param("level", "2")
                        .param("minX", "0").param("minY", "0")
                        .param("maxX", "1").param("maxY", "1"))
                .andExpect(status().isBadRequest());
    }
}
