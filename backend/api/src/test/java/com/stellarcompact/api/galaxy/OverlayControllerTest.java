package com.stellarcompact.api.galaxy;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.hamcrest.Matchers;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * MockMvc tests for the overlay endpoint: bbox scoping, sinceTick diff scoping,
 * the thin shape (active state only, no scenery), no-store caching, and
 * bad-request handling. The data source is the in-memory stub (real wiring is
 * E6-01/E6-04). Standalone MockMvc setup (Spring Boot 4 dropped {@code @WebMvcTest}).
 */
class OverlayControllerTest {

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders
                .standaloneSetup(new OverlayController(new InMemoryGalaxyStateSource()))
                .build();
    }

    @Test
    void overlayIsBboxScoped() throws Exception {
        // Box around the origin contains stub systems 1001 (0,0) and 1002 (120,60)
        // but excludes 1003 (-300,200).
        mvc.perform(get("/api/galaxy/{g}/overlay", "game-1")
                        .param("bbox", "-50,-50,200,100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.gameId").value("game-1"))
                .andExpect(jsonPath("$.systems[*].systemId",
                        Matchers.hasItems(1001, 1002)))
                .andExpect(jsonPath("$.systems[*].systemId",
                        Matchers.not(Matchers.hasItem(1003))));
    }

    @Test
    void overlayExcludesSystemsOutsideBbox() throws Exception {
        // Tiny box near 1003 only.
        mvc.perform(get("/api/galaxy/{g}/overlay", "game-1")
                        .param("bbox", "-320,180,-280,220"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.systems[*].systemId",
                        Matchers.hasItem(1003)))
                .andExpect(jsonPath("$.systems[*].systemId",
                        Matchers.not(Matchers.hasItem(1001))));
    }

    @Test
    void sinceTickFiltersToChangedSystemsOnly() throws Exception {
        // Stub change ticks: 1001@5, 1002@12, 1003@3. sinceTick=5 -> only 1002.
        mvc.perform(get("/api/galaxy/{g}/overlay", "game-1")
                        .param("bbox", "-500,-500,500,500")
                        .param("sinceTick", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sinceTick").value(5))
                .andExpect(jsonPath("$.systems.length()").value(1))
                .andExpect(jsonPath("$.systems[0].systemId").value(1002));
    }

    @Test
    void overlayCarriesThinActiveStateAndBlockades() throws Exception {
        mvc.perform(get("/api/galaxy/{g}/overlay", "game-1")
                        .param("bbox", "-500,-500,500,500"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.asOfTick").value(12))
                // contested + blockade present for system 1002
                .andExpect(jsonPath("$.blockades[*].systemId", Matchers.hasItem(1002)))
                .andExpect(jsonPath("$.systems[?(@.systemId==1002)].contested")
                        .value(Matchers.hasItem(true)))
                // thin: no scenery/star fields leak into the overlay
                .andExpect(jsonPath("$.stars").doesNotExist())
                .andExpect(jsonPath("$.systems[0].spectral").doesNotExist());
    }

    @Test
    void overlayIsNotCached() throws Exception {
        mvc.perform(get("/api/galaxy/{g}/overlay", "game-1")
                        .param("bbox", "-500,-500,500,500"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL,
                        Matchers.containsString("no-store")));
    }

    @Test
    void malformedBboxIs400() throws Exception {
        mvc.perform(get("/api/galaxy/{g}/overlay", "game-1")
                        .param("bbox", "1,2,3"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void invertedBboxIs400() throws Exception {
        mvc.perform(get("/api/galaxy/{g}/overlay", "game-1")
                        .param("bbox", "100,100,50,50"))
                .andExpect(status().isBadRequest());
    }
}
