package com.stellarcompact.engine.state;

/**
 * The closed set of buildings that occupy planet slots (game-design 02
 * section 6 build economy table). Costs, build times and effects are config
 * (balance profile), keyed by {@link #configKey()}.
 */
public enum BuildingType {
    /** +Minerals. */
    MINE("mine"),
    /** +Energy. */
    SOLAR_ARRAY("solarArray"),
    /** +Food. */
    FARM("farm"),
    /** +Tech. */
    RESEARCH_LAB("researchLab"),
    /** Enables an order book (market) at this system; +trade capacity. */
    MARKET_HUB("marketHub"),
    /** Enables fleet construction here. */
    SHIPYARD("shipyard"),
    /** System defensive bonus in combat. */
    DEFENSE_PLATFORM("defensePlatform"),
    /** Large one-time + ongoing Influence (prestige play). */
    MONUMENT("monument"),
    /** Slowly upgrades a hostile biome toward habitable. */
    TERRAFORMER("terraformer"),

    // --- E12 Kardashev megastructures (append-only) ---------------------------
    // Built via the ordinary Build pipeline (cost in construction.costs, time in
    // construction.buildTimes) and tech-gated via tech.unlocks. They produce NO
    // ordinary economy resource (EconomyResolution.outputOf -> null); their value
    // is captured watts (kardashev.wattsPerBuilding), the Kardashev climb (E12).
    /** Type-I assist: orbital solar power lattice. */
    ORBITAL_LATTICE("orbitalLattice"),
    /** Type-II: collectors shrouding the star (Dyson swarm). */
    DYSON_SWARM("dysonSwarm"),
    /** Type-II: star-lifting / Shkadov stellar engine. */
    STELLAR_ENGINE("stellarEngine"),
    /** Type-II: nested Dyson computer turning starlight into thought. */
    MATRIOSHKA_BRAIN("matrioshkaBrain"),
    /** Type-III: Penrose/Blandford–Znajek black-hole power tap. */
    BLACK_HOLE_TAP("blackHoleTap");

    private final String configKey;

    BuildingType(String configKey) {
        this.configKey = configKey;
    }

    /** Stable key under which this building's tunables appear in the balance profile. */
    public String configKey() {
        return configKey;
    }

    /**
     * True for the E12 Kardashev megastructures. They are built via the ordinary
     * Build pipeline but contribute captured watts (the Kardashev climb) rather
     * than an ordinary economy resource. Pure; used by the Kardashev calculator.
     */
    public boolean isMegastructure() {
        return switch (this) {
            case ORBITAL_LATTICE, DYSON_SWARM, STELLAR_ENGINE, MATRIOSHKA_BRAIN, BLACK_HOLE_TAP -> true;
            case MINE, SOLAR_ARRAY, FARM, RESEARCH_LAB, MARKET_HUB, SHIPYARD,
                 DEFENSE_PLATFORM, MONUMENT, TERRAFORMER -> false;
        };
    }
}
