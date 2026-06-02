package com.stellarcompact.api.ws;

import com.stellarcompact.api.galaxy.InMemoryGalaxyStateSource;
import com.stellarcompact.api.galaxy.OverlayController;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Documents and exercises the E6-04 reconnect-resync contract (skill rule 1; spec Notes):
 * the live socket carries only a thin {@link LiveMessages.OverlayDelta} pointer (which
 * tick changed, optionally which systems), and a just-reconnected or lagging client
 * resyncs the heavy overlay detail over the existing REST {@code overlay?sinceTick=}
 * endpoint (E6-03) - never over the socket. This test asserts the two halves line up:
 * the {@code asOfTick} a client last saw on the socket is exactly what it passes as
 * {@code sinceTick} to the REST endpoint to fetch only what changed after it.
 */
class ReconnectResyncTest {

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders
                .standaloneSetup(new OverlayController(new InMemoryGalaxyStateSource()))
                .build();
    }

    @Test
    void socketDeltaIsThinAndCarriesAsOfTick() {
        // The socket payload is a tiny pointer: a tick plus changed-system ids. No heavy
        // per-system detail, routes or scenery travel over the socket.
        LiveMessages.OverlayDelta delta = new LiveMessages.OverlayDelta(5, List.of(1001L));
        assertEquals(5, delta.asOfTick());
        assertEquals(List.of(1001L), delta.changedSystems());
    }

    @Test
    void clientResyncsHeavyOverlayViaRestSinceTick() throws Exception {
        // Pretend the client last observed asOfTick=5 on the socket, then reconnected.
        long lastSeen = 5;
        // It resyncs full overlay detail for its bbox over REST, sinceTick = lastSeen.
        mvc.perform(get("/api/galaxy/{g}/overlay", "game-1")
                        .param("bbox", "-500,-500,500,500")
                        .param("sinceTick", String.valueOf(lastSeen)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sinceTick").value(5))
                // Only systems changed strictly after tick 5 come back (the diff resync).
                .andExpect(jsonPath("$.systems.length()").value(1))
                .andExpect(jsonPath("$.systems[0].systemId").value(1002));
    }
}
