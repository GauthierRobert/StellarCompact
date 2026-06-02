package com.stellarcompact.galaxy.gen;

/**
 * The tunables E2-04 home placement reads (game-design 01 section 6: "Home
 * systems are placed by the engine with a minimum separation and balanced local
 * resource potential, so no Sovereign begins boxed-in or starved").
 *
 * <p><strong>Why this lives in the galaxy module as a plain record (rule 6
 * nuance).</strong> The galaxy module is deliberately framework-free and carries
 * no dependency on the engine (see {@code pom.xml}'s ban + {@code GalaxyPurityTest}),
 * so it cannot read the engine's authoritative {@code BalanceProfile}. These are
 * genuine gameplay numbers, so the <em>canonical</em> values live in
 * {@code BalanceProfile.homePlacement} and the two balance JSONs; the orchestrator /
 * promotion boundary reads them from the active profile and constructs this mirror
 * record to hand to {@link HomePlacementGenerator}. This is the same pattern by
 * which {@link Biome} / {@link Resource} mirror their engine twins by name without a
 * compile-time dependency. Keeping placement a pure function of an explicit config
 * object (not a hidden constant) preserves the determinism contract and keeps the
 * numbers tunable per profile.
 *
 * @param factionCount             number of homes to place (one per faction); must
 *                                 be {@code >= 1}
 * @param minSeparationHops        the minimum number of lane hops required between
 *                                 any two homes (no two factions cramped together);
 *                                 must be {@code >= 1}
 * @param neighbourhoodHops        the hop radius K that defines a home's local
 *                                 neighbourhood for the balance/quality measure
 *                                 (habitable &amp; colonisable bodies within K hops);
 *                                 must be {@code >= 0}
 * @param qualityToleranceFraction the maximum allowed relative spread of
 *                                 neighbourhood quality across the chosen homes
 *                                 (balanced within tolerance): all chosen homes'
 *                                 quality scores must fall inside a band of width
 *                                 {@code tolerance x maxChosenQuality}. Must be in
 *                                 {@code [0, 1]}; smaller = stricter fairness
 * @param homeBiome                the cradle {@link Biome} a candidate home system
 *                                 must contain at least one of (the colonised home
 *                                 world the faction starts on); never {@code null}
 */
public record HomePlacementConfig(
        int factionCount,
        int minSeparationHops,
        int neighbourhoodHops,
        double qualityToleranceFraction,
        Biome homeBiome
) {

    public HomePlacementConfig {
        if (factionCount < 1) {
            throw new IllegalArgumentException("factionCount must be >= 1: " + factionCount);
        }
        if (minSeparationHops < 1) {
            throw new IllegalArgumentException(
                    "minSeparationHops must be >= 1: " + minSeparationHops);
        }
        if (neighbourhoodHops < 0) {
            throw new IllegalArgumentException(
                    "neighbourhoodHops must be >= 0: " + neighbourhoodHops);
        }
        if (qualityToleranceFraction < 0.0 || qualityToleranceFraction > 1.0) {
            throw new IllegalArgumentException(
                    "qualityToleranceFraction must be in [0,1]: " + qualityToleranceFraction);
        }
        if (homeBiome == null) {
            throw new IllegalArgumentException("homeBiome must not be null");
        }
    }
}
