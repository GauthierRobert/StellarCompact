package com.stellarcompact.api.match;

import com.stellarcompact.engine.state.FactionId;
import com.stellarcompact.orchestrator.match.MatchReplay;
import com.stellarcompact.orchestrator.sovereign.WorldView;

/**
 * The application-service seam behind the match-lifecycle REST endpoints (board card
 * E6-01; rest-api spec, section Match lifecycle). It owns the match registry and drives
 * the {@code TickOrchestrator} (start kicks the tick loop; pause/resume toggle it), and
 * exposes the state/event/leaderboard reads the controller serves.
 *
 * <p><b>Why an interface (the E6-03 pattern).</b> Like {@code GalaxyStateSource}, this is
 * a seam: {@link InMemoryMatchService} is the current backing (an in-process registry +
 * background virtual-thread tick loops). A later card swaps in a persistence-backed
 * implementation that commits each tick through {@code TickCommitService} and resumes
 * from the event log, without touching the controller or its DTOs.
 *
 * <p><b>Fog boundary (non-negotiable, principle 2).</b> {@link #stateFor} routes every
 * per-faction state read through the authoritative server-side {@code WorldViewBuilder}
 * so a requester only ever receives what its fog-of-war permits; a spectator (no
 * requester) receives only strictly-public state via {@link #spectatorState}. Hidden
 * faction state is never placed into a returned view.
 *
 * <p><b>Guarded transitions (principle from E1-15).</b> {@link #start}, {@link #pause}
 * and {@link #resume} go through the engine's {@code LifecycleTransitions} guard; an
 * illegal edge throws {@link IllegalTransitionException} (mapped to 4xx), never silently
 * applied.
 */
public interface MatchService {

    /** Create a match in {@code CREATED} status; returns its public summary. */
    GameSummary create(CreateGameRequest request);

    /**
     * List all known matches (public summaries, in ascending gameId order). Used by the
     * spectator UI to discover a running demo match without knowing its id in advance.
     */
    java.util.List<GameSummary> list();

    /**
     * @return the match's public summary.
     * @throws MatchNotFoundException if no such match
     */
    GameSummary summary(String gameId);

    /**
     * Transition the match to {@code RUNNING} and start its tick loop.
     *
     * @throws MatchNotFoundException    if no such match
     * @throws IllegalTransitionException if the current status cannot start
     */
    GameSummary start(String gameId);

    /**
     * Pause a running match (halt its tick loop, freeze state).
     *
     * @throws MatchNotFoundException    if no such match
     * @throws IllegalTransitionException if the match is not running
     */
    GameSummary pause(String gameId);

    /**
     * Resume a paused match (restart its tick loop from the frozen state).
     *
     * @throws MatchNotFoundException    if no such match
     * @throws IllegalTransitionException if the match is not paused
     */
    GameSummary resume(String gameId);

    /**
     * The fog-filtered {@link WorldView} for {@code requester} - own state in full,
     * everyone else fog-limited (default-deny). Built server-side via the authoritative
     * {@code WorldViewBuilder}.
     *
     * @throws MatchNotFoundException if no such match, or {@code requester} is not a seat
     */
    WorldView stateFor(String gameId, FactionId requester);

    /**
     * The strictly-public spectator view (no requester): lifecycle + tick + public
     * reputation ledger only. No private faction state.
     *
     * @throws MatchNotFoundException if no such match
     */
    SpectatorView spectatorState(String gameId);

    /**
     * A page of the public event log with {@code tick >= fromTick}, tick-then-seq ordered,
     * capped at {@code limit} events.
     *
     * @throws MatchNotFoundException if no such match
     */
    EventsPage events(String gameId, long fromTick, int limit);

    /**
     * The current config-weighted standings (engine {@code Scoring.rank}) at the last
     * resolved snapshot.
     *
     * @throws MatchNotFoundException if no such match
     */
    LeaderboardResponse leaderboard(String gameId);

    /**
     * Build the deterministic replay timeline for {@code gameId} from its recorded
     * {@code (seed, action log)} (board card E9-02). The match is re-resolved tick-by-tick
     * through the same pure {@code Resolver} the live run used (one resolution path), so any
     * tick can be sought. The returned {@link MatchReplay} is server-built and authoritative;
     * the {@code ReplayController} projects per-tick frames from it (fog-free public state),
     * keeping the client thin.
     *
     * @throws MatchNotFoundException if no such match
     */
    MatchReplay replayTimeline(String gameId);
}
