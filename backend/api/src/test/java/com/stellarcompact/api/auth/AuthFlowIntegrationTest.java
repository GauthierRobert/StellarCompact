package com.stellarcompact.api.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stellarcompact.api.ws.InMemoryFactionOwnershipRegistry;
import com.stellarcompact.engine.state.FactionId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end test of the dev username-only JWT flow through the real {@link
 * com.stellarcompact.api.security.SecurityConfig} filter chain: login mints a token, the
 * token authenticates {@code /api/auth/me} and {@code /api/me/games}, and the latter is a
 * reverse lookup of the ownership registry (only the caller's owned games appear).
 *
 * <p>Spring Boot 4 dropped {@code @AutoConfigureMockMvc}, so MockMvc is built from the web
 * context and the security filter chain is applied via {@code springSecurity()} — this is
 * what makes the 401/200 assertions exercise the actual Bearer-token authentication.
 */
@SpringBootTest(classes = AuthTestApp.class)
class AuthFlowIntegrationTest {

    @Autowired
    WebApplicationContext context;

    @Autowired
    InMemoryFactionOwnershipRegistry ownership;

    private final ObjectMapper json = new ObjectMapper();

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(springSecurity())
                .build();
    }

    @Test
    void login_returnsBearerToken_forValidUsername() throws Exception {
        mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"alice\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("alice"))
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.token").isNotEmpty())
                .andExpect(jsonPath("$.expiresAt").isNotEmpty());
    }

    @Test
    void login_rejectsMalformedUsername_with400() throws Exception {
        mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"not a valid name!\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void me_isUnauthorized_withoutToken() throws Exception {
        mvc.perform(get("/api/auth/me"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void me_echoesPrincipal_withValidToken() throws Exception {
        String token = login("bob");
        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("bob"));
    }

    @Test
    void me_rejectsGarbageToken_with401() throws Exception {
        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer not.a.jwt"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void myGames_isUnauthorized_withoutToken() throws Exception {
        mvc.perform(get("/api/me/games"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void myGames_listsOnlyOwnedSeats_enrichedWithLiveStatus() throws Exception {
        // carol owns faction-1 in the known game; dave owns nothing here.
        ownership.bind("carol", AuthTestApp.KNOWN_GAME, new FactionId("faction-1"));

        String carol = login("carol");
        mvc.perform(get("/api/me/games").header("Authorization", "Bearer " + carol))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("carol"))
                .andExpect(jsonPath("$.games.length()").value(1))
                .andExpect(jsonPath("$.games[0].gameId").value(AuthTestApp.KNOWN_GAME))
                .andExpect(jsonPath("$.games[0].seatId").value("faction-1"))
                .andExpect(jsonPath("$.games[0].factionId").value(AuthTestApp.KNOWN_GAME + ":faction-1"))
                .andExpect(jsonPath("$.games[0].status").value("RUNNING"))
                .andExpect(jsonPath("$.games[0].tick").value(7))
                .andExpect(jsonPath("$.games[0].factionCount").value(2));

        String dave = login("dave");
        mvc.perform(get("/api/me/games").header("Authorization", "Bearer " + dave))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("dave"))
                .andExpect(jsonPath("$.games.length()").value(0));
    }

    /** Helper: mint a token for {@code username} via the real login endpoint. */
    private String login(String username) throws Exception {
        MvcResult result = mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode body = json.readTree(result.getResponse().getContentAsString());
        return body.get("token").asText();
    }
}
