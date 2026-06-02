package com.stellarcompact.galaxy.gen;

/**
 * The starting set of planet biomes (game-design 01 section 2 "Biomes" table).
 *
 * <p><strong>Intentional mirror of the engine.</strong> This is a galaxy-local
 * copy of {@code com.stellarcompact.engine.state.Biome}. The galaxy module is
 * deliberately independent of the engine (it carries no engine dependency), but
 * the names and declaration order here are kept <em>exactly</em> in lock-step
 * with the engine enum:
 * {@code OCEANIC, TERRAN, ARID, DESERT, VOLCANIC, FROZEN, TOXIC, GAS_GIANT}.
 * That lets the promotion boundary (E2-05), where a procedural system is turned
 * into a persisted {@code ActiveSystem}, map galaxy {@code Biome} -> engine
 * {@code Biome} one-to-one by {@code name()} with no translation table. If you
 * change either enum, change both.
 *
 * <p>This enum carries only the closed set of biome identities and the
 * generator-facing facts the procedural roster needs: which physical resources a
 * biome favours (the source of base yields, game-design 01 section 2), whether
 * it admits ground slots, and a generation weight expressing how common the
 * biome is in the galaxy. The favoured yields here are the procedural
 * <em>baseline</em>; authoritative gameplay tunables still live in the engine's
 * {@code BalanceProfile} (rule 6) and are layered on at promotion time.
 */
public enum Biome {
    /** Comfortable cradle world; favours Food; low colonise difficulty. Rare. */
    OCEANIC(false, ResourceYield.of(Resource.FOOD, 3)),
    /** Generalist, balanced yields; low colonise difficulty. Rare. */
    TERRAN(false, ResourceYield.balanced(2)),
    /** Favours Minerals; medium colonise difficulty. */
    ARID(false, ResourceYield.of(Resource.MINERALS, 3)),
    /** Favours Energy (solar); medium colonise difficulty. */
    DESERT(false, ResourceYield.of(Resource.ENERGY, 3)),
    /** Hostile, high reward; favours Minerals + Energy; high difficulty. */
    VOLCANIC(false, ResourceYield.of(Resource.MINERALS, 3).plus(Resource.ENERGY, 2)),
    /** Sparse but valuable; favours Tech (research stations); high difficulty. */
    FROZEN(false, ResourceYield.of(Resource.TECH, 3)),
    /** Yields nothing until terraformed; very high colonise difficulty. */
    TOXIC(false, ResourceYield.none()),
    /** No ground slots, orbital only (skimming Energy); special colonisation. */
    GAS_GIANT(true, ResourceYield.of(Resource.ENERGY, 4));

    private final boolean orbitalOnly;
    private final ResourceYield favouredYields;

    Biome(boolean orbitalOnly, ResourceYield favouredYields) {
        this.orbitalOnly = orbitalOnly;
        this.favouredYields = favouredYields;
    }

    /**
     * Whether this biome has no ground slots and is colonised via orbital
     * infrastructure only (game-design 01 section 2: Gas giant). The roster
     * generator uses this to force a gas giant's ground-slot count to zero.
     */
    public boolean orbitalOnly() {
        return orbitalOnly;
    }

    /**
     * The biome's favoured base yields per resource (game-design 01 section 2),
     * expressed as small integer weights. These are the procedural baseline a
     * colonised planet starts from; the engine balance profile is authoritative
     * for the live economy.
     */
    public ResourceYield favouredYields() {
        return favouredYields;
    }
}
