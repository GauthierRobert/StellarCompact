package com.stellarcompact.engine;

/**
 * Placeholder marker for the pure, deterministic engine module.
 *
 * <p>This module is intentionally framework-free: no Spring, no {@code java.io},
 * no {@code java.net}, no wall-clock and no unseeded randomness. Those
 * constraints are enforced at build time by the maven-enforcer banned-dependency
 * rule (Spring artifacts) and by {@code EnginePurityTest} (forbidden imports).
 *
 * <p>Real domain types (state records, the sealed {@code Action} hierarchy and
 * the resolver) arrive in later cards (E1-xx).
 */
public final class EngineModule {

    private EngineModule() {
    }

    /** @return the stable module name, used only as a placeholder anchor. */
    public static String name() {
        return "engine";
    }
}
