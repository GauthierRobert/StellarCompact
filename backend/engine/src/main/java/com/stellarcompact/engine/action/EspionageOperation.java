package com.stellarcompact.engine.action;

/**
 * The closed set of espionage operations a Sovereign may order
 * (game-design 03 section C, {@code Espionage}).
 *
 * <p>Each is probabilistic (seeded) at resolution time; the per-operation cost,
 * success curve and effect magnitude live in the balance profile, never here
 * (rule 6). This enum only fixes the legal operation kinds so the agent I/O
 * schema stays closed.
 */
public enum EspionageOperation {
    /** Reveal hidden details about the target (fog-piercing reconnaissance). */
    SCOUT,
    /** Attempt to steal a tech or resources from the target. */
    STEAL_INTEL,
    /** Attempt to damage a building in the target's territory. */
    SABOTAGE,
    /** Attempt to reduce a target colony's population/loyalty. */
    INCITE_UNREST
}
