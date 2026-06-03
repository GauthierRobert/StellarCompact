package com.stellarcompact.api.auth;

import com.stellarcompact.api.match.CreateGameRequest;
import com.stellarcompact.api.match.EventsPage;
import com.stellarcompact.api.match.GameSummary;
import com.stellarcompact.api.match.LeaderboardResponse;
import com.stellarcompact.api.match.MatchNotFoundException;
import com.stellarcompact.api.match.MatchService;
import com.stellarcompact.api.match.SpectatorView;
import com.stellarcompact.api.me.MeController;
import com.stellarcompact.api.security.SecurityConfig;
import com.stellarcompact.api.ws.InMemoryFactionOwnershipRegistry;
import com.stellarcompact.engine.state.FactionId;
import com.stellarcompact.engine.state.GameStatus;
import com.stellarcompact.orchestrator.match.MatchReplay;
import com.stellarcompact.orchestrator.sovereign.WorldView;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

import java.util.List;

/**
 * Slim Spring Boot context for the dev-auth flow test ({@link AuthFlowIntegrationTest}). It
 * boots the real {@link SecurityConfig} filter chain + token beans, the auth/me controllers,
 * the real ownership registry, and a tiny canned {@link MatchService}. This exercises the
 * actual Bearer-token path end to end (401 without a token; mint → me → my-games with one).
 */
@SpringBootConfiguration
@EnableAutoConfiguration
@Import({SecurityConfig.class, AuthController.class, DevTokenService.class, MeController.class,
        InMemoryFactionOwnershipRegistry.class})
public class AuthTestApp {

    /** The single game the canned service knows about (status RUNNING, tick 7). */
    public static final String KNOWN_GAME = "game-auth-1";

    @Bean
    MatchService matchService() {
        return new CannedMatchService();
    }

    /** Answers {@code summary(KNOWN_GAME)} only; everything else is out of scope for this test. */
    private static final class CannedMatchService implements MatchService {
        @Override
        public GameSummary summary(String gameId) {
            if (!KNOWN_GAME.equals(gameId)) {
                throw new MatchNotFoundException("no such match " + gameId);
            }
            return new GameSummary(gameId, 42L, GameStatus.RUNNING, 7L, "small-default",
                    List.of("faction-1", "faction-2"));
        }

        @Override
        public GameSummary create(CreateGameRequest request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<GameSummary> list() {
            throw new UnsupportedOperationException();
        }

        @Override
        public GameSummary start(String gameId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public GameSummary pause(String gameId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public GameSummary resume(String gameId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public WorldView stateFor(String gameId, FactionId requester) {
            throw new UnsupportedOperationException();
        }

        @Override
        public SpectatorView spectatorState(String gameId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public EventsPage events(String gameId, long fromTick, int limit) {
            throw new UnsupportedOperationException();
        }

        @Override
        public LeaderboardResponse leaderboard(String gameId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public MatchReplay replayTimeline(String gameId) {
            throw new UnsupportedOperationException();
        }
    }
}
