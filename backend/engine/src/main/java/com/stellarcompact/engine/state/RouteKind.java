package com.stellarcompact.engine.state;

/**
 * The kind of an established {@link Route} (game-design 01 section 3 /
 * economy 02 section 5). The renderer colours routes by this; the engine uses it
 * for protection/eligibility rules (e.g. {@code ALLIED} requires an alliance
 * between endpoints).
 */
public enum RouteKind {
    /** Intra-alliance, protected. */
    ALLIED,
    /** Cross-faction trade pact. */
    COMMERCIAL,
    /** Runs through disputed space; vulnerable. */
    CONTESTED
}
