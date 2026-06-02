package com.stellarcompact.galaxy;

/**
 * Placeholder marker for the procedural galaxy + spatial-index module.
 *
 * <p>Like {@code engine}, this is a framework-free library: no Spring, no
 * {@code java.io}, no {@code java.net}. Enforced by the maven-enforcer
 * banned-dependency rule and by {@code GalaxyPurityTest}.
 *
 * <p>Real generation + spatial-index types arrive in later cards (E2-xx).
 */
public final class GalaxyModule {

    private GalaxyModule() {
    }

    /** @return the stable module name, used only as a placeholder anchor. */
    public static String name() {
        return "galaxy";
    }
}
