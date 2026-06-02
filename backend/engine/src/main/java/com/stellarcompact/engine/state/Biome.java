package com.stellarcompact.engine.state;

/**
 * The starting set of planet biomes (game-design 01 section 2 "Biomes" table).
 *
 * <p>A planet's biome determines its base resource yields and its colonisation
 * difficulty; the concrete numbers live in the active {@code BalanceProfile}
 * (rule 6: numbers in config, never hardcoded), keyed by the biome's
 * {@link #configKey()}. This enum carries only the closed set of biome
 * identities, no gameplay constants.
 *
 * <p>Ordering note: a {@code Terraformer} advances a hostile world one step
 * "toward habitable" (game-design 03 Terraform: Toxic -> Arid -> Terran ...).
 * The terraforming chain is a rule the resolver/economy card will model; this
 * enum intentionally does not bake an ordering into its declaration order so the
 * golden hash never depends on {@code ordinal()}. Hashing/serialisation uses the
 * stable enum {@code name()}.
 */
public enum Biome {
    /** Comfortable cradle world; favours Food; low colonise difficulty. */
    OCEANIC("oceanic"),
    /** Generalist, balanced yields; low colonise difficulty. */
    TERRAN("terran"),
    /** Favours Minerals; medium colonise difficulty. */
    ARID("arid"),
    /** Favours Energy (solar); medium colonise difficulty. */
    DESERT("desert"),
    /** Hostile, high reward; favours Minerals + Energy; high difficulty. */
    VOLCANIC("volcanic"),
    /** Sparse but valuable; favours Tech (research stations); high difficulty. */
    FROZEN("frozen"),
    /** Yields nothing until terraformed; very high colonise difficulty. */
    TOXIC("toxic"),
    /** No ground slots, orbital only (skimming Energy); special colonisation. */
    GAS_GIANT("gasGiant");

    private final String configKey;

    Biome(String configKey) {
        this.configKey = configKey;
    }

    /**
     * The stable key under which this biome's tunables (yields, difficulty)
     * appear in a {@code BalanceProfile} - e.g. {@code resources.biomeYields}.
     * Matches the camelCase keys used in the shipped balance profiles.
     */
    public String configKey() {
        return configKey;
    }
}
