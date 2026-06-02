package com.stellarcompact.engine.state;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * The guarded match-lifecycle state machine (board card E1-15; game-design 07 section 4):
 *
 * <pre>
 *   CREATED -&gt; LOBBY -&gt; RUNNING -&gt; (PAUSED &lt;-&gt; RUNNING) -&gt; CONCLUDED -&gt; ARCHIVED
 * </pre>
 *
 * <p><b>Why a dedicated guard.</b> {@link GameStatus} is just the enum; this class is the
 * single authority on which {@link GameStatus} transitions are legal. The orchestrator
 * drives most transitions (CREATED-&gt;LOBBY-&gt;RUNNING, pause/resume, archival); the engine
 * itself only ever fires the one transition victory/tick-limit triggers
 * (RUNNING-&gt;CONCLUDED, via {@code VictoryEvaluation}). Both go through {@link #transition}
 * so an illegal jump (e.g. CONCLUDED-&gt;RUNNING, or skipping LOBBY) is rejected, never
 * silently applied.
 *
 * <p><b>Pure, framework-free, deterministic.</b> Lives in the state module (no Spring, no
 * I/O, no clock): the adjacency map below is the whole rulebook, fixed at class load. A
 * transition is a pure function of (from, to).
 */
public final class LifecycleTransitions {

    /**
     * The legal forward (and pause/resume) edges of the lifecycle. A status maps to the
     * set of statuses it may transition <em>to</em>. Terminal {@link GameStatus#ARCHIVED}
     * has no outgoing edge (an archived match is immutable history).
     */
    private static final Map<GameStatus, Set<GameStatus>> ALLOWED = Map.of(
            GameStatus.CREATED, EnumSet.of(GameStatus.LOBBY),
            GameStatus.LOBBY, EnumSet.of(GameStatus.RUNNING),
            // RUNNING may pause, conclude (victory / tick limit), and nothing else.
            GameStatus.RUNNING, EnumSet.of(GameStatus.PAUSED, GameStatus.CONCLUDED),
            // PAUSED resumes to RUNNING (the only resume), or is concluded outright
            // (e.g. an admin ends a paused, abandoned match).
            GameStatus.PAUSED, EnumSet.of(GameStatus.RUNNING, GameStatus.CONCLUDED),
            GameStatus.CONCLUDED, EnumSet.of(GameStatus.ARCHIVED),
            GameStatus.ARCHIVED, EnumSet.noneOf(GameStatus.class));

    private LifecycleTransitions() {
    }

    /**
     * @return {@code true} iff a match may move directly from {@code from} to {@code to}.
     * A self-transition ({@code from == to}) is <em>not</em> legal (no-op moves are a
     * caller bug, surfaced rather than swallowed). {@code null} either side is illegal.
     */
    public static boolean canTransition(GameStatus from, GameStatus to) {
        if (from == null || to == null) {
            return false;
        }
        return ALLOWED.getOrDefault(from, Set.of()).contains(to);
    }

    /**
     * Apply a guarded lifecycle transition to a snapshot.
     *
     * @param state the snapshot whose {@link GameState#status()} is {@code from}
     * @param to    the target lifecycle status
     * @return a new snapshot with {@code status == to}
     * @throws IllegalArgumentException if {@code state} is {@code null} or the transition
     *                                  {@code state.status() -> to} is not a legal edge
     */
    public static GameState transition(GameState state, GameStatus to) {
        if (state == null) {
            throw new IllegalArgumentException("transition: state must be set");
        }
        GameStatus from = state.status();
        if (!canTransition(from, to)) {
            throw new IllegalArgumentException(
                    "illegal lifecycle transition: " + from + " -> " + to);
        }
        return state.withStatus(to);
    }
}
