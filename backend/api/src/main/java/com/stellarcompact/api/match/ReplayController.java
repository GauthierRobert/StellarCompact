package com.stellarcompact.api.match;

import com.stellarcompact.engine.config.BalanceProfile;
import com.stellarcompact.engine.config.BalanceProfileLoader;
import com.stellarcompact.engine.resolve.PublicEvent;
import com.stellarcompact.engine.resolve.Scoring;
import com.stellarcompact.engine.state.FactionId;
import com.stellarcompact.engine.state.GameState;
import com.stellarcompact.engine.state.SystemId;
import com.stellarcompact.orchestrator.match.MatchReplay;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The deterministic-replay REST surface (board card E9-02; game-design 07 section 5; rest-api
 * spec, section Replay). Server-side re-resolution behind two thin endpoints, so the client
 * stays thin and the engine stays authoritative (the action log is never shipped to the
 * browser; the server re-resolves it):
 *
 * <ul>
 *   <li>{@code GET /api/games/{id}/replay} -&gt; {@link ReplayManifest}: the scrub-bar
 *       metadata (seekable tick range, frame count, seed/profile).</li>
 *   <li>{@code GET /api/games/{id}/replay/{tick}} -&gt; {@link ReplayFrame}: the fog-free
 *       public projection of that tick (events + leaderboard + reputations + status), shaped
 *       to feed the same spectator stores the live STOMP path feeds.</li>
 * </ul>
 *
 * <p><b>One resolution path.</b> The timeline comes from {@link MatchService#replayTimeline},
 * which builds a {@link MatchReplay} by re-folding the recorded {@code (seed, action log)}
 * through the same pure {@code Resolver} the live run used (and re-asserting each tick's
 * canonical hash). This controller only <em>projects</em> the resulting frames into public
 * DTOs - it never resolves anything itself.
 *
 * <p><b>Fog boundary (principle 2).</b> A replay frame is a spectator projection: it carries
 * only common-knowledge state (the public event stream, the config-weighted leaderboard which
 * leaks no hidden state by construction, and the public reputation ledger). The authoritative
 * {@link GameState} inside a frame is never serialised; no faction's private state leaves the
 * server.
 *
 * <p><b>Not cacheable.</b> Like every game-state read, frames carry {@code Cache-Control:
 * no-store} (rest-api Conventions); only the immutable tile endpoint is cacheable. (The replay
 * <em>is</em> reproducible, but keeping the convention uniform avoids a shared cache ever
 * serving a stale manifest while a match is still recording ticks.)
 *
 * <p>Blocking handlers on virtual threads (Spring Boot 4 / Java 25), the same model as
 * {@link MatchController}.
 */
@RestController
@RequestMapping("/api/games")
public class ReplayController {

    private final MatchService matches;

    public ReplayController(MatchService matches) {
        this.matches = matches;
    }

    /** The replay manifest: seekable range + frame count + seed/profile for the scrub bar. */
    @GetMapping("/{gameId}/replay")
    public ResponseEntity<ReplayManifest> manifest(@PathVariable("gameId") String gameId) {
        MatchReplay replay = matches.replayTimeline(gameId);
        ReplayManifest manifest = new ReplayManifest(
                gameId, replay.gameSeed(), profileName(gameId, replay),
                replay.firstTick(), replay.lastTick(), replay.tickCount());
        return noStore(ResponseEntity.ok()).body(manifest);
    }

    /**
     * Seek to {@code tick}: the fog-free public projection of that replayed tick. Out-of-range
     * ticks clamp to the nearest end (a scrubbing client can drag freely), so this only 404s
     * on an unknown match or an empty (never-resolved) timeline.
     */
    @GetMapping("/{gameId}/replay/{tick}")
    public ResponseEntity<ReplayFrame> frame(
            @PathVariable("gameId") String gameId,
            @PathVariable("tick") long tick) {

        MatchReplay replay = matches.replayTimeline(gameId);
        MatchReplay.Frame frame = replay.seek(tick);
        if (frame == null) {
            throw new MatchNotFoundException("match " + gameId + " has no resolved ticks to replay");
        }
        return noStore(ResponseEntity.ok()).body(project(gameId, frame));
    }

    // ===== projection ==========================================================

    /**
     * Project a replay {@link MatchReplay.Frame} into the public {@link ReplayFrame} DTO:
     * the tick's events in wire shape, the engine config-weighted leaderboard at this tick's
     * snapshot, and the public reputation ledger - exactly the shapes the live spectator
     * stores consume. The frame's profile is loaded once for {@code Scoring.rank}.
     */
    private ReplayFrame project(String gameId, MatchReplay.Frame frame) {
        GameState state = frame.state();
        BalanceProfile profile = loadProfile(state.balanceProfileName());

        List<EventsPage.Event> events = new ArrayList<>();
        int seq = 0;
        for (PublicEvent e : frame.events()) {
            List<String> parties = e.parties().stream().map(FactionId::value).toList();
            String systemId = e.systemId().map(SystemId::value).orElse(null);
            events.add(new EventsPage.Event(frame.tick(), seq++, e.type(), parties, systemId));
        }

        List<LeaderboardResponse.Entry> leaderboard = new ArrayList<>();
        int rank = 1;
        for (Scoring.FactionScore fs : Scoring.rank(state, profile)) {
            leaderboard.add(new LeaderboardResponse.Entry(rank++, fs.faction().value(), fs.score()));
        }

        List<SpectatorView.Reputation> reputations = state.factions().values().stream()
                .sorted(Comparator.comparing(f -> f.id().value()))
                .map(f -> new SpectatorView.Reputation(f.id().value(), f.reputation()))
                .toList();

        return new ReplayFrame(gameId, frame.tick(), state.status().name(),
                events, leaderboard, reputations);
    }

    /** The profile name of the timeline (from its first frame), for the manifest. */
    private static String profileName(String gameId, MatchReplay replay) {
        if (replay.tickCount() == 0) {
            throw new MatchNotFoundException("match " + gameId + " has no resolved ticks to replay");
        }
        return replay.frames().getFirst().state().balanceProfileName();
    }

    /** Load a shipped balance profile from the classpath (rule 6: numbers live in config). */
    private static BalanceProfile loadProfile(String name) {
        String path = "/balance/" + name + ".json";
        try (InputStream in = ReplayController.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("missing balance profile on the classpath: " + name);
            }
            return BalanceProfileLoader.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // ===== error mapping =======================================================

    /** Unknown match / empty timeline -&gt; 404. */
    @ExceptionHandler(MatchNotFoundException.class)
    public ResponseEntity<String> notFound(MatchNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(e.getMessage());
    }

    private static ResponseEntity.BodyBuilder noStore(ResponseEntity.BodyBuilder builder) {
        return builder.cacheControl(CacheControl.noStore());
    }
}
