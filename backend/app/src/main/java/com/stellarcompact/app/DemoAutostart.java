package com.stellarcompact.app;

import com.stellarcompact.api.match.CreateGameRequest;
import com.stellarcompact.api.match.GameSummary;
import com.stellarcompact.api.match.MatchService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Demo-match autostart (board card E11-07, finding P5).
 *
 * <p>When {@code stellar-compact.demo.autostart=true} (default {@code false}), this
 * {@link ApplicationRunner} fires once after the application context is fully ready and
 * creates + starts a single demo match so that a fresh {@code mvn spring-boot:run} (or
 * docker-compose up) immediately has a watchable game without any manual API calls.
 *
 * <p><b>How the frontend discovers it.</b> The new {@code GET /api/games} list endpoint
 * (also E11-07) returns all matches; the spectator view fetches that list on first load
 * and redirects to the first RUNNING match it finds.
 *
 * <p><b>Constraints.</b>
 * <ul>
 *   <li>Opt-in: default is {@code false} so all existing tests and production boots are
 *       unchanged. Add {@code stellar-compact.demo.autostart=true} to
 *       {@code application-demo.yml} or pass {@code -Dstellar-compact.demo.autostart=true}
 *       on the command line.</li>
 *   <li>This class only calls the public {@link MatchService} API; it never reaches into
 *       {@code InMemoryMatchService} internals.</li>
 *   <li>The demo match uses default parameters (no seed, no faction-count override, default
 *       balance profile). Override via the optional properties below.</li>
 * </ul>
 */
@Component
@ConditionalOnProperty(
        name  = "stellar-compact.demo.autostart",
        havingValue = "true",
        matchIfMissing = false
)
public class DemoAutostart implements ApplicationRunner {

    private static final Logger LOG = LoggerFactory.getLogger(DemoAutostart.class);

    private final MatchService matchService;
    private final DemoProperties props;

    public DemoAutostart(MatchService matchService, DemoProperties props) {
        this.matchService = matchService;
        this.props = props;
    }

    @Override
    public void run(ApplicationArguments args) {
        LOG.info("Demo autostart: creating match (factions={}, profile={})",
                props.factionCount(), props.balanceProfile());

        // CreateGameRequest(seed, size, factionCount, tickIntervalMs, victoryCondition, balanceProfile, seats)
        CreateGameRequest req = new CreateGameRequest(
                props.seed(),
                null,                  // size — informational only
                props.factionCount(),
                null,                  // tickIntervalMs — orchestration timing, never an engine input
                null,                  // victoryCondition — informational; lives in the balance profile
                props.balanceProfile(),
                null                   // seats — default all-scripted (E11-04 per-seat selection)
        );

        GameSummary created = matchService.create(req);
        GameSummary running = matchService.start(created.gameId());

        LOG.info("Demo match started: gameId={} seed={} status={}",
                running.gameId(), running.gameSeed(), running.status());
    }
}
