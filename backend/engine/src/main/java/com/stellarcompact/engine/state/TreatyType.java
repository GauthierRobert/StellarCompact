package com.stellarcompact.engine.state;

/**
 * The closed set of treaty types the engine enforces (game-design 04 section 2,
 * action ProposeTreaty in 03). Per-type enforcement weights live in the balance
 * profile's {@code diplomacy.treatyEnforcement}.
 */
public enum TreatyType {
    /** Suspends war for a duration; Attack between parties illegal. */
    CEASEFIRE,
    /** Attack/DeclareWar between parties illegal until broken. */
    NON_AGGRESSION,
    /** Enables preferential cross-faction routes and pricing between parties. */
    TRADE_PACT,
    /** Signatories may (and are expected to) join an attacked partner's defence. */
    DEFENSIVE_PACT,
    /** Full pact: non-aggression + shared border vision + allied routes + pooled votes. */
    ALLIANCE,
    /** Vassal pays recurring tribute and cannot declare war independently. */
    VASSALAGE
}
