package com.stellarcompact.api.match;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * MockMvc tests for the deterministic-replay REST surface (E9-02): the replay manifest and
 * per-tick seek frame served by re-resolving the recorded {@code (seed, action log)}
 * server-side through the pure resolver. The service is the real {@link InMemoryMatchService}
 * (no DB) so the recording + replay path is exercised end to end through the orchestrator +
 * engine. Standalone MockMvc (Spring Boot 4 dropped {@code @WebMvcTest}).
 */
class ReplayControllerTest {

    private MockMvc mvc;
    private InMemoryMatchService service;
    private final ObjectMapper json = new ObjectMapper();

    @BeforeEach
    void setUp() {
        service = new InMemoryMatchService();
        mvc = MockMvcBuilders
                .standaloneSetup(new ReplayController(service), new MatchController(service))
                .build();
    }

    /** Create a 2-faction match, run a few ticks synchronously, return its id. */
    private String archivedGame(int ticks) throws Exception {
        String body = mvc.perform(post("/api/games")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"seed\":4242,\"factionCount\":2}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String id = json.readTree(body).get("gameId").asText();
        service.readyForTest(id);          // RUNNING without the racy background loop
        service.advanceForTest(id, ticks); // resolve exactly `ticks` ticks, recording the log
        return id;
    }

    // ===== manifest ============================================================

    @Test
    void manifestExposesTheSeekableTickRange() throws Exception {
        String id = archivedGame(6);
        mvc.perform(get("/api/games/{id}/replay", id))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, Matchers.containsString("no-store")))
                .andExpect(jsonPath("$.gameSeed").value(4242))
                .andExpect(jsonPath("$.firstTick").value(0))
                .andExpect(jsonPath("$.lastTick").value(5))
                .andExpect(jsonPath("$.tickCount").value(6))
                .andExpect(jsonPath("$.profile").value("small-default"));
    }

    @Test
    void manifestForUnknownMatchIs404() throws Exception {
        mvc.perform(get("/api/games/{id}/replay", "does-not-exist"))
                .andExpect(status().isNotFound());
    }

    @Test
    void manifestForAMatchWithNoResolvedTicksIs404() throws Exception {
        // Created but never started/advanced -> empty timeline -> 404 (nothing to replay).
        String body = mvc.perform(post("/api/games")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"seed\":1,\"factionCount\":2}"))
                .andReturn().getResponse().getContentAsString();
        String id = json.readTree(body).get("gameId").asText();
        mvc.perform(get("/api/games/{id}/replay", id))
                .andExpect(status().isNotFound());
    }

    // ===== seek frame: tick-identical, public-only =============================

    @Test
    void seekingToATickReturnsThatTicksPublicFrame() throws Exception {
        String id = archivedGame(6);
        mvc.perform(get("/api/games/{id}/replay/{tick}", id, 3))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, Matchers.containsString("no-store")))
                .andExpect(jsonPath("$.tick").value(3))
                // The frame feeds the same stores as live: status heartbeat, leaderboard, reputations.
                .andExpect(jsonPath("$.status").exists())
                .andExpect(jsonPath("$.leaderboard[*].factionId",
                        Matchers.hasItems("faction-1", "faction-2")))
                .andExpect(jsonPath("$.reputations[*].factionId",
                        Matchers.hasItems("faction-1", "faction-2")))
                .andExpect(jsonPath("$.events").isArray());
    }

    @Test
    void replayFrameLeaksNoHiddenFactionState() throws Exception {
        String id = archivedGame(5);
        String body = mvc.perform(get("/api/games/{id}/replay/{tick}", id, 2))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        // A replay frame is a public spectator projection: no private systems/fleets/stockpiles.
        JsonNode frame = json.readTree(body);
        Assertions.assertFalse(frame.has("self"), "frame must not carry a self WorldView");
        Assertions.assertFalse(frame.has("ownSystems"), "frame must not carry private systems");
        Assertions.assertFalse(frame.has("fleets"), "frame must not carry private fleets");
        Assertions.assertFalse(body.contains("stockpile"), "frame must not carry private stockpiles");
    }

    @Test
    void seekingTheSameTickTwiceYieldsTheIdenticalFrameDeterministic() throws Exception {
        String id = archivedGame(6);
        String a = mvc.perform(get("/api/games/{id}/replay/{tick}", id, 4))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String b = mvc.perform(get("/api/games/{id}/replay/{tick}", id, 4))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        Assertions.assertEquals(a, b,
                "seeking the same tick is deterministic and order-independent");
    }

    @Test
    void seekingAdjacentTicksYieldsDistinctFramesAsTheMatchProgresses() throws Exception {
        String id = archivedGame(8);
        // The leaderboard score moves as economy grows, so two well-separated ticks differ.
        double early = scoreOfFirstEntry(id, 1);
        double late = scoreOfFirstEntry(id, 7);
        Assertions.assertNotEquals(early, late,
                "the replayed state evolves across ticks (frames are real per-tick snapshots)");
    }

    @Test
    void seekOutOfRangeClampsRatherThan404() throws Exception {
        String id = archivedGame(6);
        // Above the last recorded tick clamps to the last frame (a scrubbing client can drag freely).
        mvc.perform(get("/api/games/{id}/replay/{tick}", id, 9999))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tick").value(5));
    }

    @Test
    void frameForUnknownMatchIs404() throws Exception {
        mvc.perform(get("/api/games/{id}/replay/{tick}", "nope", 0))
                .andExpect(status().isNotFound());
    }

    private double scoreOfFirstEntry(String id, long tick) throws Exception {
        String body = mvc.perform(get("/api/games/{id}/replay/{tick}", id, tick))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("leaderboard").get(0).get("score").asDouble();
    }
}
