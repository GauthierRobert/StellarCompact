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
    SCOUT("scout"),
    /** Attempt to steal a tech or resources from the target. */
    STEAL_INTEL("stealIntel"),
    /** Attempt to damage a building in the target's territory. */
    SABOTAGE("sabotage"),
    /** Attempt to reduce a target colony's population/loyalty. */
    INCITE_UNREST("inciteUnrest");

    private final String configKey;

    EspionageOperation(String configKey) {
        this.configKey = configKey;
    }

    /**
     * Stable key under which this operation's tunables (success/detection base
     * odds, cost, effect magnitudes) appear in the balance profile
     * ({@code espionage.successBase[key]}, ...). Kept here so the config keys are a
     * single source of truth, never a hardcoded string at the resolver call site.
     */
    public String configKey() {
        return configKey;
    }
}
