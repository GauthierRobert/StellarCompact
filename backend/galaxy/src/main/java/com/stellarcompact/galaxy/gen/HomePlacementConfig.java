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
 * @param minHomePlanetCount       the E10-05 starting-economy floor on the home
 *                                 system's own planet count (3-agent-sim F4: the sim
 *                                 dealt a 1-planet home against a 7-planet home, a 7x
 *                                 economic gap from the seed alone). A cradle candidate
 *                                 whose own system carries fewer than this many planets
 *                                 is rejected, so a starved 1-planet home can never be
 *                                 dealt. Layered ON TOP of the cradle + tolerance guards
 *                                 (a survivor still has to fall in the fairness band).
 *                                 Must be {@code >= 1}; {@code 1} is inert (no floor)
 * @param minHomeBiomeYield        the E10-05 starting-economy floor on the home
 *                                 system's aggregate base biome yield (F4): the sum,
 *                                 over every planet in the candidate's own system, of
 *                                 that planet's {@link Planet#baseYields()} across all
 *                                 four {@link Resource}s. A candidate below this is
 *                                 rejected so a home that is multi-planet but near-barren
 *                                 (e.g. toxic rubble) is also excluded. Must be
 *                                 {@code >= 0}; {@code 0} is inert (no floor)
 */
public record HomePlacementConfig(
        int factionCount,
        int minSeparationHops,
        int neighbourhoodHops,
        double qualityToleranceFraction,
        Biome homeBiome,
        int minHomePlanetCount,
        double minHomeBiomeYield
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
        if (minHomePlanetCount < 1) {
            throw new IllegalArgumentException(
                    "minHomePlanetCount must be >= 1: " + minHomePlanetCount);
        }
        if (minHomeBiomeYield < 0.0) {
            throw new IllegalArgumentException(
                    "minHomeBiomeYield must be >= 0: " + minHomeBiomeYield);
        }
    }

    /**
     * Backwards-compatible five-arg constructor predating the E10-05 starting-economy
     * floor: delegates with the inert floor ({@code minHomePlanetCount = 1} = any cradle
     * passes the planet-count gate, {@code minHomeBiomeYield = 0.0} = no yield gate). Lets
     * pre-E10-05 callers and fixtures that build a config positionally (through
     * {@code homeBiome}) keep compiling unchanged, and keeps placement byte-identical
     * for them.
     */
    public HomePlacementConfig(
            int factionCount,
            int minSeparationHops,
            int neighbourhoodHops,
            double qualityToleranceFraction,
            Biome homeBiome) {
        this(factionCount, minSeparationHops, neighbourhoodHops, qualityToleranceFraction,
                homeBiome, 1, 0.0);
    }
}
