package com.stellarcompact.api.match;

import com.stellarcompact.engine.state.FactionId;
import com.stellarcompact.orchestrator.sovereign.WorldView;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The match-lifecycle REST surface (board card E6-01; rest-api spec, section Match
 * lifecycle). Thin controllers over the {@link MatchService} seam:
 *
 * <ul>
 *   <li>{@code POST /api/games} - create a match (seed/factionCount/profile) -&gt; CREATED.</li>
 *   <li>{@code GET  /api/games/{id}} - match metadata/status.</li>
 *   <li>{@code POST /api/games/{id}/start|pause|resume} - guarded lifecycle transitions
 *       (E1-15); an illegal transition is a {@code 409 Conflict}.</li>
 *   <li>{@code GET  /api/games/{id}/state?requester=} - fog-applied per requester (the
 *       authoritative {@code WorldViewBuilder}); no requester -&gt; strictly-public
 *       spectator view.</li>
 *   <li>{@code GET  /api/games/{id}/events?fromTick=&limit=} - the append-only public
 *       event log, paginated by tick.</li>
 *   <li>{@code GET  /api/games/{id}/leaderboard} - current config-weighted standings.</li>
 * </ul>
 *
 * <p><b>Not cacheable.</b> Every game-state read changes tick to tick, so all responses
 * carry {@code Cache-Control: no-store} (rest-api Conventions: game-state reads are not
 * cached). Only the immutable tile endpoint (E6-03) is cacheable.
 *
 * <p><b>No WebSocket here.</b> The live push of ticks/events/overlay is E6-04; this card
 * stays strictly REST so the two siblings do not collide.
 *
 * <p>Blocking handlers on purpose: under Spring Boot 4 / Java 25 each request runs on its
 * own virtual thread, so straight-line blocking is the simplest correct model.
 */
@RestController
@RequestMapping("/api/games")
public class MatchController {

    private final MatchService matches;

    public MatchController(MatchService matches) {
        this.matches = matches;
    }

    @PostMapping
    public ResponseEntity<GameSummary> create(@RequestBody(required = false) CreateGameRequest request) {
        GameSummary summary = matches.create(request);
        return noStore(ResponseEntity.status(HttpStatus.CREATED)).body(summary);
    }

    @GetMapping("/{gameId}")
    public ResponseEntity<GameSummary> summary(@PathVariable("gameId") String gameId) {
        return noStore(ResponseEntity.ok()).body(matches.summary(gameId));
    }

    @PostMapping("/{gameId}/start")
    public ResponseEntity<GameSummary> start(@PathVariable("gameId") String gameId) {
        return noStore(ResponseEntity.ok()).body(matches.start(gameId));
    }

    @PostMapping("/{gameId}/pause")
    public ResponseEntity<GameSummary> pause(@PathVariable("gameId") String gameId) {
        return noStore(ResponseEntity.ok()).body(matches.pause(gameId));
    }

    @PostMapping("/{gameId}/resume")
    public ResponseEntity<GameSummary> resume(@PathVariable("gameId") String gameId) {
        return noStore(ResponseEntity.ok()).body(matches.resume(gameId));
    }

    /**
     * Fog-applied state read. With a {@code requester} faction the response is that
     * faction's fog-filtered {@link WorldView} (own state full, everyone else fog-limited);
     * without one it is the strictly-public {@link SpectatorView}. The server enforces the
     * fog rule - a non-owner never receives another faction's hidden state.
     */
    @GetMapping("/{gameId}/state")
    public ResponseEntity<?> state(
            @PathVariable("gameId") String gameId,
            @RequestParam(name = "requester", required = false) String requester) {

        if (requester == null || requester.isBlank()) {
            return noStore(ResponseEntity.ok()).body(matches.spectatorState(gameId));
        }
        WorldView view = matches.stateFor(gameId, new FactionId(requester));
        return noStore(ResponseEntity.ok()).body(view);
    }

    @GetMapping("/{gameId}/events")
    public ResponseEntity<EventsPage> events(
            @PathVariable("gameId") String gameId,
            @RequestParam(name = "fromTick", defaultValue = "0") long fromTick,
            @RequestParam(name = "limit", defaultValue = "0") int limit) {

        return noStore(ResponseEntity.ok()).body(matches.events(gameId, fromTick, limit));
    }

    @GetMapping("/{gameId}/leaderboard")
    public ResponseEntity<LeaderboardResponse> leaderboard(@PathVariable("gameId") String gameId) {
        return noStore(ResponseEntity.ok()).body(matches.leaderboard(gameId));
    }

    // ===== error mapping =======================================================

    /** Unknown match / requester -&gt; 404. */
    @ExceptionHandler(MatchNotFoundException.class)
    public ResponseEntity<String> notFound(MatchNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(e.getMessage());
    }

    /** Illegal lifecycle transition -&gt; 409 Conflict (a 4xx; the card's "illegal -&gt; 4xx"). */
    @ExceptionHandler(IllegalTransitionException.class)
    public ResponseEntity<String> illegalTransition(IllegalTransitionException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(e.getMessage());
    }

    /** Malformed input (e.g. a blank faction id in the requester) -&gt; 400. */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<String> badRequest(IllegalArgumentException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(e.getMessage());
    }

    private static ResponseEntity.BodyBuilder noStore(ResponseEntity.BodyBuilder builder) {
        return builder.cacheControl(CacheControl.noStore());
    }
}
