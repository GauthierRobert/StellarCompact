package com.stellarcompact.api.faction;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stellarcompact.agentruntime.chat.AgentRuntimeProperties;
import com.stellarcompact.agentruntime.chat.ModelTier;
import com.stellarcompact.agentruntime.prompt.ActionSchemaCatalog;
import com.stellarcompact.agentruntime.prompt.AssembledPrompt;
import com.stellarcompact.agentruntime.prompt.PromptAssembler;
import com.stellarcompact.api.match.CreateGameRequest;
import com.stellarcompact.api.match.InMemoryMatchService;
import com.stellarcompact.api.ws.InMemoryFactionOwnershipRegistry;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * MockMvc tests for the Sovereign-config REST surface (E6-02). The service is the real
 * {@link InMemoryFactionConfigService} over a real {@link InMemoryMatchService},
 * {@link InMemoryFactionOwnershipRegistry} and {@link PromptAssembler}, so the owner
 * binding, redaction, the between-matches gate and the prompt-assembly link are exercised
 * end to end with no mocks. Standalone MockMvc (Spring Boot 4 dropped {@code @WebMvcTest}).
 *
 * <p>The owner principal is supplied through the documented {@code X-Owner-Token} header
 * stand-in (see {@link FactionConfigController}); an authenticated {@code Principal} would
 * resolve identically.
 */
class FactionConfigControllerTest {

    private static final String OWNER = FactionConfigController.OWNER_HEADER;

    private MockMvc mvc;
    private InMemoryMatchService matchService;
    private InMemoryFactionConfigService configService;
    private final ObjectMapper json = new ObjectMapper();

    @BeforeEach
    void setUp() {
        matchService = new InMemoryMatchService();
        InMemoryFactionOwnershipRegistry ownership = new InMemoryFactionOwnershipRegistry();
        PromptAssembler assembler = new PromptAssembler(
                AgentRuntimeProperties.of(ModelTier.SMALL, Map.of()), new ActionSchemaCatalog());
        configService = new InMemoryFactionConfigService(matchService, ownership, assembler);
        mvc = MockMvcBuilders.standaloneSetup(new FactionConfigController(configService)).build();
    }

    /** Create a CREATED match directly through the service and return its id. */
    private String createGame() {
        return matchService.create(new CreateGameRequest(777L, null, 2, null, null, null, null)).gameId();
    }

    /** Attach a Sovereign as {@code owner}; return the seat handle. */
    private String attach(String gameId, String owner, String body) throws Exception {
        String resp = mvc.perform(post("/api/games/{id}/factions", gameId)
                        .header(OWNER, owner)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, Matchers.containsString("no-store")))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(resp).get("factionId").asText();
    }

    // ===== persistence + feeds prompt assembly =================================

    @Test
    void configPersistsAndIsRetrievableByOwner() throws Exception {
        String game = createGame();
        String handle = attach(game, "alice", """
                {"customPersona":"A cautious trade republic",
                 "goals":["Grow the economy","Avoid war"],
                 "hardConstraints":["Never strike first"],
                 "modelTier":"LARGE"}""");

        mvc.perform(get("/api/factions/{id}", handle).header(OWNER, "alice"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.owner").value(true))
                .andExpect(jsonPath("$.persona").value("A cautious trade republic"))
                .andExpect(jsonPath("$.goals", Matchers.hasItems("Grow the economy", "Avoid war")))
                .andExpect(jsonPath("$.hardConstraints", Matchers.hasItem("Never strike first")))
                .andExpect(jsonPath("$.modelTier").value("LARGE"));
    }

    @Test
    void storedConfigFeedsPromptAssembly() throws Exception {
        String game = createGame();
        String handle = attach(game, "alice", """
                {"customPersona":"A cautious trade republic that prizes commerce",
                 "goals":["Grow the economy"],
                 "hardConstraints":["Never strike first"]}""");

        // The exact SovereignConfig stored here is what the agent-runtime PromptAssembler
        // (E4-02) consumes - assert the assembled system prompt reflects stored persona/goals.
        AssembledPrompt prompt = configService.assembledPromptFor(handle, Map.of("tick", 1));
        String sys = prompt.systemMessage();
        assertTrue(sys.contains("A cautious trade republic that prizes commerce"),
                "assembled prompt must carry the stored persona");
        assertTrue(sys.contains("Grow the economy"), "assembled prompt must carry the stored goal");
        assertTrue(sys.contains("Never strike first"),
                "assembled prompt must carry the stored hard constraint");
    }

    // ===== the security crux: owner-only redaction ============================

    @Test
    void nonOwnerGetsRedactedView() throws Exception {
        String game = createGame();
        String handle = attach(game, "alice", """
                {"customPersona":"SECRET DOCTRINE: feign peace then betray",
                 "goals":["Backstab faction-2"],"hardConstraints":["Trust no one"]}""");

        // A different principal: identity is public, but every sensitive field is stripped.
        String body = mvc.perform(get("/api/factions/{id}", handle).header(OWNER, "mallory"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.owner").value(false))
                .andExpect(jsonPath("$.factionId").value(handle))
                .andExpect(jsonPath("$.persona").doesNotExist())
                .andExpect(jsonPath("$.goals").isEmpty())
                .andExpect(jsonPath("$.hardConstraints").isEmpty())
                .andExpect(jsonPath("$.modelTier").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        // Hard assertion: not one byte of the owner's secret strategy leaks to a non-owner.
        assertFalse(body.contains("SECRET DOCTRINE"),
                "redaction leak: persona text present in non-owner view: " + body);
        assertFalse(body.contains("Backstab"),
                "redaction leak: goal text present in non-owner view: " + body);
    }

    @Test
    void unauthenticatedReadIsRedacted() throws Exception {
        String game = createGame();
        String handle = attach(game, "alice", "{\"customPersona\":\"hidden\"}");

        // No principal and no owner token => default to redaction (never the owner view).
        mvc.perform(get("/api/factions/{id}", handle))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.owner").value(false))
                .andExpect(jsonPath("$.persona").doesNotExist());
    }

    @Test
    void unknownFactionIs404() throws Exception {
        mvc.perform(get("/api/factions/{id}", "no-such-game:faction-1").header(OWNER, "alice"))
                .andExpect(status().isNotFound());
    }

    @Test
    void attachToUnknownMatchIs404() throws Exception {
        mvc.perform(post("/api/games/{id}/factions", "does-not-exist")
                        .header(OWNER, "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"customPersona\":\"x\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void attachWithoutOwnerPrincipalIsForbidden() throws Exception {
        String game = createGame();
        mvc.perform(post("/api/games/{id}/factions", game)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"customPersona\":\"x\"}"))
                .andExpect(status().isForbidden());
    }

    // ===== between-matches gate on directive PATCH ============================

    @Test
    void ownerMayPatchDirectivesBetweenMatches() throws Exception {
        String game = createGame(); // status CREATED (between matches)
        String handle = attach(game, "alice", "{\"customPersona\":\"old\"}");

        mvc.perform(patch("/api/factions/{id}", handle)
                        .header(OWNER, "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"customPersona\":\"new doctrine\",\"goals\":[\"Expand\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.owner").value(true))
                .andExpect(jsonPath("$.persona").value("new doctrine"))
                .andExpect(jsonPath("$.goals", Matchers.hasItem("Expand")));
    }

    @Test
    void patchWhileRunningIsRejected() throws Exception {
        String game = createGame();
        String handle = attach(game, "alice", "{\"customPersona\":\"old\"}");
        matchService.start(game);   // -> RUNNING
        matchService.pause(game);   // stop the background loop; status is PAUSED now
        matchService.resume(game);  // -> RUNNING again (loop running)
        try {
            mvc.perform(patch("/api/factions/{id}", handle)
                            .header(OWNER, "alice")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"customPersona\":\"mid-match cheat\"}"))
                    .andExpect(status().isConflict());
        } finally {
            matchService.pause(game); // leave the loop stopped
        }
    }

    @Test
    void nonOwnerPatchIsForbidden() throws Exception {
        String game = createGame();
        String handle = attach(game, "alice", "{\"customPersona\":\"old\"}");

        mvc.perform(patch("/api/factions/{id}", handle)
                        .header(OWNER, "mallory")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"customPersona\":\"hijack\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void twoOwnersGetSeparateSeatsAndCannotSeeEachOther() throws Exception {
        String game = createGame();
        String aliceSeat = attach(game, "alice", "{\"customPersona\":\"alice secret\"}");
        String bobSeat = attach(game, "bob", "{\"customPersona\":\"bob secret\"}");

        org.junit.jupiter.api.Assertions.assertNotEquals(aliceSeat, bobSeat,
                "distinct owners must be bound to distinct seats");

        // Bob reading Alice's seat is redacted (and vice versa).
        mvc.perform(get("/api/factions/{id}", aliceSeat).header(OWNER, "bob"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.owner").value(false))
                .andExpect(jsonPath("$.persona").doesNotExist());
        mvc.perform(get("/api/factions/{id}", aliceSeat).header(OWNER, "alice"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.persona").value("alice secret"));
    }
}
