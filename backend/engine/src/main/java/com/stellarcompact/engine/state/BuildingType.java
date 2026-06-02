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
    TERRAFORMER("terraformer");

    private final String configKey;

    BuildingType(String configKey) {
        this.configKey = configKey;
    }

    /** Stable key under which this building's tunables appear in the balance profile. */
    public String configKey() {
        return configKey;
    }
}
