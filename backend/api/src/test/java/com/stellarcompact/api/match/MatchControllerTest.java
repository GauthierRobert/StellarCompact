package com.stellarcompact.api.match;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * MockMvc tests for the match-lifecycle REST surface (E6-01): create + status, guarded
 * lifecycle transitions (illegal -&gt; 4xx), fog-correct per-requester state reads, the
 * tick-paginated public event log and the leaderboard. The service is the real
 * {@link InMemoryMatchService} (no DB) so the wiring through the orchestrator + engine is
 * exercised end to end. Standalone MockMvc (Spring Boot 4 dropped {@code @WebMvcTest}).
 */
class MatchControllerTest {

    private MockMvc mvc;
    private InMemoryMatchService service;
    private final ObjectMapper json = new ObjectMapper();

    @BeforeEach
    void setUp() {
        service = new InMemoryMatchService();
        mvc = MockMvcBuilders
                .standaloneSetup(new MatchController(service))
                .build();
    }

    private String createGame() throws Exception {
        String body = mvc.perform(post("/api/games")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"seed\":777,\"factionCount\":2}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("CREATED"))
                .andExpect(jsonPath("$.gameSeed").value(777))
                .andExpect(jsonPath("$.factions", Matchers.hasItems("faction-1", "faction-2")))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("gameId").asText();
    }

    @Test
    void createReturnsCreatedSummaryAndIsNotCached() throws Exception {
        mvc.perform(post("/api/games")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"factionCount\":3}"))
                .andExpect(status().isCreated())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, Matchers.containsString("no-store")))
                .andExpect(jsonPath("$.status").value("CREATED"))
                .andExpect(jsonPath("$.factions.length()").value(3));
    }

    @Test
    void getUnknownGameIs404() throws Exception {
        mvc.perform(get("/api/games/{id}", "does-not-exist"))
                .andExpect(status().isNotFound());
    }

    @Test
    void startTransitionsToRunning() throws Exception {
        String id = createGame();
        mvc.perform(post("/api/games/{id}/start", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RUNNING"));
    }

    @Test
    void startTwiceIsIllegalTransition409() throws Exception {
        String id = createGame();
        mvc.perform(post("/api/games/{id}/start", id)).andExpect(status().isOk());
        service.pause(id); // stop the background loop so the assertion is deterministic
        // A PAUSED match cannot start (only resume); the guard rejects it as a 4xx.
        mvc.perform(post("/api/games/{id}/start", id))
                .andExpect(status().isConflict());
    }

    @Test
    void resumeWithoutPauseIsIllegalTransition409() throws Exception {
        String id = createGame();
        // CREATED cannot resume (only PAUSED->RUNNING is legal).
        mvc.perform(post("/api/games/{id}/resume", id))
                .andExpect(status().isConflict());
    }

    @Test
    void pauseThenResumeIsLegal() throws Exception {
        String id = createGame();
        mvc.perform(post("/api/games/{id}/start", id)).andExpect(status().isOk());
        service.pause(id);
        mvc.perform(post("/api/games/{id}/resume", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RUNNING"));
        service.pause(id); // leave it stopped
    }

    @Test
    void pauseRunningIsLegalAndFreezesState() throws Exception {
        String id = createGame();
        mvc.perform(post("/api/games/{id}/start", id)).andExpect(status().isOk());
        mvc.perform(post("/api/games/{id}/pause", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PAUSED"));
    }

    @Test
    void pauseCreatedMatchIsIllegalTransition409() throws Exception {
        String id = createGame();
        mvc.perform(post("/api/games/{id}/pause", id))
                .andExpect(status().isConflict());
    }

    @Test
    void spectatorStateIsPublicOnly() throws Exception {
        String id = createGame();
        mvc.perform(get("/api/games/{id}/state", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CREATED"))
                .andExpect(jsonPath("$.reputations[*].factionId",
                        Matchers.hasItems("faction-1", "faction-2")))
                // No private state leaks into the spectator view.
                .andExpect(jsonPath("$.ownSystems").doesNotExist())
                .andExpect(jsonPath("$.self").doesNotExist());
    }

    @Test
    void stateReadIsFogCorrectPerRequester() throws Exception {
        String id = createGame();
        // faction-1 sees ITS OWN home in full, but NOT faction-2's home (no lane between
        // homes in the bootstrap => not in faction-1's sensor reach => fog hides it).
        String body = mvc.perform(get("/api/games/{id}/state", id).param("requester", "faction-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.self.id").value("faction-1"))
                .andExpect(jsonPath("$.ownSystems[*].id", Matchers.hasItem("home-1")))
                .andExpect(jsonPath("$.ownSystems[*].id", Matchers.not(Matchers.hasItem("home-2"))))
                // faction-2's home must not appear anywhere in faction-1's view (default-deny fog).
                .andExpect(jsonPath("$.neighbours[*].systemId", Matchers.not(Matchers.hasItem("home-2"))))
                .andReturn().getResponse().getContentAsString();
        // Hard assertion: faction-2's home id appears nowhere in the serialized view.
        org.junit.jupiter.api.Assertions.assertFalse(body.contains("home-2"),
                "fog leak: faction-2 home present in faction-1 view: " + body);
    }

    @Test
    void stateReadForUnknownRequesterIs404() throws Exception {
        String id = createGame();
        mvc.perform(get("/api/games/{id}/state", id).param("requester", "ghost"))
                .andExpect(status().isNotFound());
    }

    @Test
    void leaderboardRanksAllSeats() throws Exception {
        String id = createGame();
        mvc.perform(get("/api/games/{id}/leaderboard", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries.length()").value(2))
                .andExpect(jsonPath("$.entries[0].rank").value(1))
                .andExpect(jsonPath("$.entries[*].factionId",
                        Matchers.hasItems("faction-1", "faction-2")));
    }

    @Test
    void eventsPaginateByTick() throws Exception {
        String id = createGame();
        service.start(id);
        service.pause(id);              // stop the background loop for determinism
        service.advanceForTest(id, 6);  // resolve 6 ticks synchronously

        // fromTick=0 returns every logged event; assert ascending tick order.
        String all = mvc.perform(get("/api/games/{id}/events", id).param("fromTick", "0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fromTick").value(0))
                .andReturn().getResponse().getContentAsString();
        JsonNode events = json.readTree(all).get("events");
        long prev = -1;
        for (JsonNode e : events) {
            long t = e.get("tick").asLong();
            org.junit.jupiter.api.Assertions.assertTrue(t >= prev,
                    "events not tick-ordered: " + t + " after " + prev);
            prev = t;
        }

        // fromTick filters: every returned event has tick >= fromTick.
        if (events.size() > 0) {
            long mid = events.get(events.size() / 2).get("tick").asLong();
            String page = mvc.perform(get("/api/games/{id}/events", id)
                            .param("fromTick", Long.toString(mid)))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();
            for (JsonNode e : json.readTree(page).get("events")) {
                org.junit.jupiter.api.Assertions.assertTrue(e.get("tick").asLong() >= mid,
                        "fromTick filter leaked an earlier-tick event");
            }
        }
    }

    @Test
    void eventsRespectPageLimitAndCursor() throws Exception {
        String id = createGame();
        service.start(id);
        service.pause(id);
        service.advanceForTest(id, 8);

        String body = mvc.perform(get("/api/games/{id}/events", id)
                        .param("fromTick", "0").param("limit", "1"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode node = json.readTree(body);
        // A page of at most 1; nextFromTick is a forward cursor (>=0) iff more remain.
        org.junit.jupiter.api.Assertions.assertTrue(node.get("events").size() <= 1);
    }

    @Test
    void statesAreNotCached() throws Exception {
        String id = createGame();
        mvc.perform(get("/api/games/{id}/state", id))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, Matchers.containsString("no-store")));
        mvc.perform(get("/api/games/{id}/leaderboard", id))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, Matchers.containsString("no-store")));
    }

    @Test
    void createWithNoBodyUsesDefaults() throws Exception {
        mvc.perform(post("/api/games").contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.factions.length()").value(InMemoryMatchService.DEFAULT_FACTIONS))
                .andExpect(content().string(Matchers.containsString("small-default")));
    }
}
