package com.stellarcompact.engine.resolve;

/**
 * The fixed, eleven-step resolution order of game-design 03 ("Resolution order"),
 * lowest ordinal first. This is the determinism contract the resolver enforces
 * every tick (see {@code .claude/skills/game-engine-determinism}, rule 3): within
 * a tick the engine walks these steps in declaration order, never in any other.
 *
 * <p><b>Why a separate enum from {@link com.stellarcompact.engine.action.ActionCategory}.</b>
 * {@code ActionCategory} classifies a single agent {@code Action} into the bucket
 * whose <em>actions</em> drive it; it collapses the design's eleven steps into the
 * fewer buckets that actions actually trigger (e.g. construction + colonisation are
 * one {@code DEVELOPMENT} action bucket) and adds soft-diplomacy / no-op buckets
 * that are not gameplay-resolution steps. This enum, by contrast, is the canonical
 * <em>resolution schedule</em>: it lists all eleven steps including the three
 * <em>passive</em> ones (production/upkeep, influence, public events) that no action
 * triggers but that still must run in this exact slot. The resolver iterates this
 * enum; it maps each action's {@code ActionCategory} onto the step it feeds.
 *
 * <p>The ordinal order is load-bearing and part of the replay contract: never
 * reorder these constants.
 */
public enum ResolutionStep {

    /** 1. Treaty break/accept &amp; war declarations - state changes resolve first. */
    DIPLOMATIC_STATE,

    /** 2. Espionage operations. */
    ESPIONAGE,

    /** 3. Fleet movement &amp; interception. */
    MOVEMENT,

    /** 4. Combat (attacks, assaults). */
    COMBAT,

    /** 5. Blockade / raid effects on routes. */
    INTERDICTION,

    /** 6. Construction, research, terraform progress. */
    DEVELOPMENT,

    /** 7. Colonisation. */
    COLONISATION,

    /** 8. Market matching &amp; escrow settlement. */
    MARKET,

    /** 9. Production, upkeep, population, attrition (passive - no triggering action). */
    PRODUCTION,

    /** 10. Influence accrual &amp; decay (passive - no triggering action). */
    INFLUENCE,

    /** 11. Public event emission (passive - no triggering action). */
    EVENTS
}
