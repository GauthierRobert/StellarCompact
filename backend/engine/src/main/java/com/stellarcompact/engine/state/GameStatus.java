package com.stellarcompact.engine.state;

/**
 * Match lifecycle status (game-design 07 section 4):
 * {@code CREATED -> LOBBY -> RUNNING -> (PAUSED <-> RUNNING) -> CONCLUDED -> ARCHIVED}.
 *
 * <p>Carried on {@link GameState} so a snapshot self-describes whether ticks may
 * advance. The engine resolver only ever progresses a {@code RUNNING} game; the
 * orchestrator owns transitions between these states.
 */
public enum GameStatus {
    /** A galaxy is seeded with parameters but not yet open to factions. */
    CREATED,
    /** Open for factions to join before the first tick. */
    LOBBY,
    /** The tick loop is advancing; resolution happens here. */
    RUNNING,
    /** Loop halted, state frozen, resumable. */
    PAUSED,
    /** A victory condition fired; no further ticks. */
    CONCLUDED,
    /** State + full event log retained for replay/analysis. */
    ARCHIVED
}
