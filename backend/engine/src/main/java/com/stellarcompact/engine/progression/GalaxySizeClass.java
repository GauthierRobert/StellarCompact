package com.stellarcompact.engine.progression;

/**
 * The two galaxy tiers of the small-&gt;large progression loop (E9-01; game-design 07
 * section 6).
 *
 * <ul>
 *   <li>{@link #SMALL} - the funnel: cheap, fast, ephemeral matches where a Sovereign
 *       (and its human's configuration) proves itself. Completing one earns standing.</li>
 *   <li>{@link #LARGE} - the persistent campaign: expensive, slow, high-value. Entry is
 *       gated by standing earned in small galaxies; a Sovereign carries only its
 *       identity and reputation in, never material advantage.</li>
 * </ul>
 *
 * <p>Derived from a profile's {@code progression.sizeClass} string (rule 6 - the tier is
 * config, not hardcoded). Unknown / blank values fall back to {@link #SMALL}, the
 * open-entry tier, so a partial profile degrades to the safest (no-gating) default.
 */
public enum GalaxySizeClass {
    SMALL,
    LARGE;

    /**
     * @return the tier named by {@code raw} (case-insensitive), or {@link #SMALL} when
     * {@code raw} is null, blank or unrecognised. Mirrors the lenient normalisation in
     * {@link com.stellarcompact.engine.config.BalanceProfile.Progression}.
     */
    public static GalaxySizeClass fromConfig(String raw) {
        if (raw == null || raw.isBlank()) {
            return SMALL;
        }
        return switch (raw.trim().toUpperCase()) {
            case "LARGE" -> LARGE;
            default -> SMALL;
        };
    }
}
