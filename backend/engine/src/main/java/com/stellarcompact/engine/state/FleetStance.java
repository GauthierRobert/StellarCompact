package com.stellarcompact.engine.state;

/**
 * Posture of a {@link Fleet}, feeding the {@code stanceMod} term of the combat
 * model (game-design 05 section 2). Concrete multipliers live in the balance
 * profile; this enum is the closed set of stances only.
 */
public enum FleetStance {
    /** Maximises attack contribution; weaker on defence. */
    AGGRESSIVE,
    /** Balanced posture (default). */
    BALANCED,
    /** Favours defence/holding; weaker on attack. */
    DEFENSIVE,
    /** Avoids combat where possible (e.g. escorting/relocating). */
    EVASIVE
}
