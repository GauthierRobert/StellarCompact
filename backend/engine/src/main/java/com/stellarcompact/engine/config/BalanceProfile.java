package com.stellarcompact.engine.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;
import java.util.Map;

/**
 * Immutable, externalised set of every gameplay tunable in Stellar Compact.
 *
 * <p>Rule 6 of the project principles: numbers live in config, never hardcoded.
 * The engine reads only from the active BalanceProfile; the design docs
 * (docs/game-design/02..07) are the source of these values and
 * docs/specs/balance-config.md the source of this shape.
 *
 * <p>Every member is a deeply-immutable record. Collections are defensively
 * copied in compact constructors so a profile, once parsed, cannot be mutated -
 * preserving determinism and reproducibility.
 *
 * <p>Purity / I/O seam. This module is pure (no java.io / java.net). Parsing
 * therefore happens through BalanceProfileLoader.parse(String), which consumes
 * a String the caller already holds in memory. The actual classpath/file read
 * that produces that String lives outside the engine (persistence/app modules,
 * or this module's own tests). Do not add a convenience loader here that reads
 * a stream - that would break engine purity.
 *
 * <p>A profile is versioned and named so it can be attached to a match and
 * replayed exactly.
 */
@JsonIgnoreProperties(ignoreUnknown = false)
public record BalanceProfile(
        String name,
        int version,
        Resources resources,
        Population population,
        Market market,
        Construction construction,
        Combat combat,
        Movement movement,
        Tech tech,
        Diplomacy diplomacy,
        Victory victory,
        Tick tick,
        // --- E2-04 home placement (append-only; see HomePlacement record below) ---
        HomePlacement homePlacement,
        // --- E1-13 espionage (append-only; see Espionage record below) ---
        Espionage espionage,
        // --- E1-14 influence accrual & decay (append-only; see Influence record below) ---
        Influence influence,
        // --- E9-01 small->large progression & identity/reputation-only carry-over ---
        Progression progression,
        // --- E9-03 tournament/season aggregation & leaderboard weights ---
        Season season
) {

    /**
     * Compact constructor. Movement (E1-09), home placement (E2-04), espionage
     * (E1-13), influence (E1-14) and progression (E9-01) are all additive; a profile
     * (or fixture) that omits any of them gets the inert defaults
     * ({@link Movement#defaults()} = free travel/interception off,
     * {@link HomePlacement#defaults()}, {@link Espionage#defaults()} = ops always fail /
     * are never detected, {@link Influence#defaults()} = no accrual, no decay,
     * {@link Progression#defaults()} = SMALL galaxy / open seat / no reputation carry) so
     * it stays loadable and deterministic (forward-compatible, like the tech DAG /
     * terraform chain defaults).
     */
    public BalanceProfile {
        movement = movement == null ? Movement.defaults() : movement;
        homePlacement = homePlacement == null ? HomePlacement.defaults() : homePlacement;
        espionage = espionage == null ? Espionage.defaults() : espionage;
        influence = influence == null ? Influence.defaults() : influence;
        progression = progression == null ? Progression.defaults() : progression;
        season = season == null ? Season.defaults() : season;
    }

    /**
     * Backwards-compatible constructor through {@code progression} (no E9-03
     * {@code season} block): delegates to the canonical constructor with
     * {@link Season#defaults()}. Lets pre-E9-03 fixtures/profiles that build a profile
     * positionally (through {@code progression}) keep compiling unchanged.
     */
    public BalanceProfile(
            String name,
            int version,
            Resources resources,
            Population population,
            Market market,
            Construction construction,
            Combat combat,
            Movement movement,
            Tech tech,
            Diplomacy diplomacy,
            Victory victory,
            Tick tick,
            HomePlacement homePlacement,
            Espionage espionage,
            Influence influence,
            Progression progression) {
        this(name, version, resources, population, market, construction, combat,
                movement, tech, diplomacy, victory, tick, homePlacement, espionage,
                influence, progression, Season.defaults());
    }

    /**
     * Backwards-compatible constructor through {@code influence} (no E9-01
     * {@code progression} block): delegates to the canonical constructor with
     * {@link Progression#defaults()}. Lets pre-E9-01 fixtures/profiles that build a
     * profile positionally (through {@code influence}) keep compiling unchanged.
     */
    public BalanceProfile(
            String name,
            int version,
            Resources resources,
            Population population,
            Market market,
            Construction construction,
            Combat combat,
            Movement movement,
            Tech tech,
            Diplomacy diplomacy,
            Victory victory,
            Tick tick,
            HomePlacement homePlacement,
            Espionage espionage,
            Influence influence) {
        this(name, version, resources, population, market, construction, combat,
                movement, tech, diplomacy, victory, tick, homePlacement, espionage,
                influence, Progression.defaults(), Season.defaults());
    }

    /**
     * Backwards-compatible constructor through {@code espionage} (no E1-14
     * {@code influence} block): delegates to the canonical constructor with
     * {@link Influence#defaults()}. Lets pre-E1-14 fixtures that build a profile
     * positionally (through {@code espionage}) keep compiling unchanged.
     */
    public BalanceProfile(
            String name,
            int version,
            Resources resources,
            Population population,
            Market market,
            Construction construction,
            Combat combat,
            Movement movement,
            Tech tech,
            Diplomacy diplomacy,
            Victory victory,
            Tick tick,
            HomePlacement homePlacement,
            Espionage espionage) {
        this(name, version, resources, population, market, construction, combat,
                movement, tech, diplomacy, victory, tick, homePlacement, espionage,
                Influence.defaults());
    }

    /**
     * Backwards-compatible constructor through {@code homePlacement} (no E1-13
     * {@code espionage} block): delegates to the canonical constructor with
     * {@link Espionage#defaults()}.
     */
    public BalanceProfile(
            String name,
            int version,
            Resources resources,
            Population population,
            Market market,
            Construction construction,
            Combat combat,
            Movement movement,
            Tech tech,
            Diplomacy diplomacy,
            Victory victory,
            Tick tick,
            HomePlacement homePlacement) {
        this(name, version, resources, population, market, construction, combat,
                movement, tech, diplomacy, victory, tick, homePlacement, Espionage.defaults());
    }

    /**
     * Backwards-compatible constructor through {@code tick} WITH a movement block (no
     * E2-04 {@code homePlacement}, no E1-13 {@code espionage}): delegates with both
     * inert defaults. Lets pre-E2-04 fixtures that build a profile positionally
     * (through {@code tick}) keep compiling unchanged.
     */
    public BalanceProfile(
            String name,
            int version,
            Resources resources,
            Population population,
            Market market,
            Construction construction,
            Combat combat,
            Movement movement,
            Tech tech,
            Diplomacy diplomacy,
            Victory victory,
            Tick tick) {
        this(name, version, resources, population, market, construction, combat,
                movement, tech, diplomacy, victory, tick,
                HomePlacement.defaults(), Espionage.defaults());
    }

    /**
     * Backwards-compatible constructor predating the E1-09 {@code movement}, E2-04
     * {@code homePlacement} and E1-13 {@code espionage} blocks: delegates with all
     * three inert defaults. Lets pre-E1-09 fixtures that build a profile positionally
     * (through {@code tick}, without a movement block) keep compiling; combat/economy
     * fixtures that do not care about travel get interception-off defaults.
     */
    public BalanceProfile(
            String name,
            int version,
            Resources resources,
            Population population,
            Market market,
            Construction construction,
            Combat combat,
            Tech tech,
            Diplomacy diplomacy,
            Victory victory,
            Tick tick) {
        this(name, version, resources, population, market, construction, combat,
                Movement.defaults(), tech, diplomacy, victory, tick,
                HomePlacement.defaults(), Espionage.defaults());
    }

    /** Per-tick resource economy. */
    public record Resources(
            Map<String, ResourceBundle> biomeYields,
            Map<String, ResourceBundle> upkeep,
            double deficitAttritionRate
    ) {
        public Resources {
            biomeYields = Map.copyOf(biomeYields);
            upkeep = Map.copyOf(upkeep);
        }
    }

    /** The five-resource bundle (Influence accrues but is never market-traded). */
    public record ResourceBundle(
            double energy,
            double minerals,
            double food,
            double tech,
            double influence
    ) {
    }

    /**
     * Population dynamics (economy 02 section 3).
     *
     * <ul>
     *   <li>{@code growthPerFoodSurplus} - population gained per unit of a planet's
     *       Food surplus per tick (a surplus world grows).</li>
     *   <li>{@code declinePerFoodDeficit} - population lost per unit of a planet's
     *       Food shortfall per tick when the colony cannot feed itself (famine).</li>
     *   <li>{@code productionPerPop} - the per-tick production multiplier added per
     *       unit of planet population; the population factor of the production
     *       formula is {@code 1 + population x productionPerPop}.</li>
     *   <li>{@code baseCap} - the population cap of a colony before any
     *       cap-raising buildings; the effective cap is {@code baseCap + sum of
     *       capByBuilding for the planet's active buildings}.</li>
     *   <li>{@code capByBuilding} - per-building population-cap contribution.</li>
     * </ul>
     */
    public record Population(
            double growthPerFoodSurplus,
            double declinePerFoodDeficit,
            double productionPerPop,
            long baseCap,
            Map<String, Integer> capByBuilding
    ) {
        public Population {
            capByBuilding = Map.copyOf(capByBuilding);
        }
    }

    /**
     * Market order-book policy and route-driven Influence.
     *
     * <ul>
     *   <li>{@code matchPolicy} - the matching discipline; the engine implements
     *       {@code "priceTimePriority"} (E1-07). An unrecognised policy is treated
     *       as price-time priority (the only one defined).</li>
     *   <li>{@code routeInfluencePerVolume} - Influence generated per unit of trade
     *       route volume (economy 02 section 5).</li>
     *   <li>{@code currency} - the {@link com.stellarcompact.engine.state.PhysicalResource}
     *       name (e.g. {@code "ENERGY"}) a market order's {@code price} is denominated
     *       in. Order-book prices are scalars; the matcher pays {@code qty x price}
     *       of this resource from the buyer to the seller, while the traded good
     *       moves the other way. Keeping the numeraire in config (rule 6) avoids
     *       hardcoding which resource is "money". A directed peer offer carries its
     *       own give/receive bundles and does not use this.</li>
     *   <li>{@code blockadeThroughputFactor} - E1-11: the fraction (0..1) of a route's
     *       throughput a hostile blockade removes (game-design 05 section 5 "throttle
     *       their economy"). A {@code 1.0} chokes the route completely; a {@code 0.0}
     *       (the inert default) is no effect. The INTERDICTION step flips the choked
     *       route to {@link com.stellarcompact.engine.state.RouteStatus#BLOCKADED} and
     *       this factor scales the throughput the economy/Influence steps then read.</li>
     *   <li>{@code raidStealFraction} - E1-11: the maximum fraction (0..1) of a route's
     *       per-cycle cargo a raid steals (game-design 05 section 5 "steal a shipment
     *       (seeded)"). The actual steal is this fraction scaled by a seeded roll in
     *       {@code [0,1)} (so a raid's haul is variable, not fixed), drawn from
     *       {@code gameSeed XOR tick XOR SaltDomain.RAID.salt(routeId)}. A {@code 0.0}
     *       (the inert default) steals nothing.</li>
     * </ul>
     */
    public record Market(
            String matchPolicy,
            double routeInfluencePerVolume,
            String currency,
            // --- E1-11 blockade & raid (append-only; inert defaults for older profiles) ---
            double blockadeThroughputFactor,
            double raidStealFraction
    ) {
        /**
         * Compact constructor clamping the E1-11 fractions into {@code [0,1]} so a
         * malformed profile cannot over-steal or over-choke. Both are additive; a
         * pre-E1-11 profile that omits them parses to {@code 0.0} (the inert default:
         * a blockade that removes nothing, a raid that steals nothing) and stays
         * loadable - forward-compatible like the other additive blocks.
         */
        public Market {
            blockadeThroughputFactor = clampFraction(blockadeThroughputFactor);
            raidStealFraction = clampFraction(raidStealFraction);
        }

        /**
         * Backwards-compatible three-arg constructor predating the E1-11 interdiction
         * knobs: delegates with both inert {@code 0.0} defaults. Lets pre-E1-11
         * fixtures/profiles that build a Market positionally keep compiling unchanged.
         */
        public Market(String matchPolicy, double routeInfluencePerVolume, String currency) {
            this(matchPolicy, routeInfluencePerVolume, currency, 0.0, 0.0);
        }

        private static double clampFraction(double v) {
            if (v < 0.0) {
                return 0.0;
            }
            return Math.min(v, 1.0);
        }
    }

    /**
     * Build times (ticks) and resource costs per building / terraform step.
     *
     * <ul>
     *   <li>{@code buildTimes} - ticks a queued building (keyed by its
     *       {@link com.stellarcompact.engine.state.BuildingType#configKey()}) takes
     *       to finish; also carries {@code "terraformerStep"}, the ticks one
     *       Terraform biome step takes (E1-08).</li>
     *   <li>{@code costs} - the Minerals(+Tech) cost to queue each building, keyed
     *       by building configKey.</li>
     *   <li>{@code terraformChain} - the biome-stepping DAG a Terraformer walks
     *       "toward habitable" (game-design 03 Terraform: Toxic-&gt;Arid-&gt;Terran...),
     *       keyed by a biome's {@link com.stellarcompact.engine.state.Biome#configKey()}
     *       to the configKey of the biome it advances to. A biome absent from this
     *       map is already habitable (no further step) and cannot be terraformed.
     *       Keeping the chain in config (rule 6) means the resolver hardcodes no
     *       biome ordering.</li>
     * </ul>
     */
    public record Construction(
            Map<String, Integer> buildTimes,
            Map<String, ResourceBundle> costs,
            Map<String, String> terraformChain
    ) {
        public Construction {
            buildTimes = Map.copyOf(buildTimes);
            costs = Map.copyOf(costs);
            // Additive in E1-08; an older/partial profile that omits the chain simply
            // has no terraform steps (defensive default, forward-compatible).
            terraformChain = terraformChain == null ? Map.of() : Map.copyOf(terraformChain);
        }
    }

    /**
     * Combat tunables (game-design 05 section 2; board card E1-10).
     *
     * <p>The power model is
     * {@code attackerPower = Sum(shipAttack x tierMult) x stanceAttackMod} and
     * {@code defenderPower = Sum(shipDefense x tierMult) x stanceDefenseMod x
     * terrainDefenseMod x defensePlatformBonus}; the {@code terrainDefenseMod} and
     * {@code defensePlatformBonus} apply only to a defended <em>system</em> assault,
     * not to a fleet-vs-fleet interception engagement.
     *
     * <ul>
     *   <li>{@code tierMultipliers} - per ship-spec tier/tech multiplier (a Capital
     *       outweighs a Corvette); a spec absent from the map contributes the neutral
     *       {@code 1.0}. A {@code 0.0} (e.g. freighter) means no combat value.</li>
     *   <li>{@code shipAttack} / {@code shipDefense} - per ship-spec base attack /
     *       defence strength; a spec absent from either map contributes {@code 0} on
     *       that axis (a non-combatant). Kept separate from {@code tierMultipliers} so
     *       a spec can be strong on one axis and weak on the other.</li>
     *   <li>{@code stanceAttackMod} / {@code stanceDefenseMod} - per
     *       {@link com.stellarcompact.engine.state.FleetStance} name (e.g.
     *       {@code "AGGRESSIVE"}) attack / defence multiplier; a stance absent from a
     *       map is the neutral {@code 1.0}.</li>
     *   <li>{@code terrainDefenseMod} - flat defender multiplier for a system assault
     *       (the garrison's home-ground advantage); {@code 1.0} is none. Does not apply
     *       to fleet-vs-fleet interception battles.</li>
     *   <li>{@code varianceBand} - {@code [lo, hi]} bounds of the seeded roll applied to
     *       the attacker (and its complement to the defender) so a stronger force
     *       usually but not always wins.</li>
     *   <li>{@code defensePlatformBonus} - defender multiplier when the assaulted system
     *       has an active {@code defensePlatform} building.</li>
     *   <li>{@code lossFractionWinner} / {@code lossFractionLoser} - fraction (0..1) of
     *       a side's ships destroyed when it wins / loses; the loser loses more, but the
     *       winner takes attrition too (no costless victory).</li>
     *   <li>{@code occupationLoyaltyPenalty} - loyalty lost (floored at 0) on a freshly
     *       captured system - the occupation/unrest brake on conquest.</li>
     *   <li>{@code warExhaustionPerLoss} - exhaustion accrued per ship lost (later cards
     *       surface it for war termination).</li>
     * </ul>
     */
    public record Combat(
            Map<String, Double> tierMultipliers,
            List<Double> varianceBand,
            double defensePlatformBonus,
            double occupationLoyaltyPenalty,
            double warExhaustionPerLoss,
            Map<String, Double> shipAttack,
            Map<String, Double> shipDefense,
            Map<String, Double> stanceAttackMod,
            Map<String, Double> stanceDefenseMod,
            double terrainDefenseMod,
            double lossFractionWinner,
            double lossFractionLoser
    ) {
        public Combat {
            tierMultipliers = Map.copyOf(tierMultipliers);
            varianceBand = List.copyOf(varianceBand);
            // E1-10 fields are additive over the original five; an older/partial profile
            // that omits them gets neutral defaults so it stays loadable and the combat
            // step degrades gracefully (forward-compatible, like the tech DAG / movement).
            shipAttack = shipAttack == null ? Map.of() : Map.copyOf(shipAttack);
            shipDefense = shipDefense == null ? Map.of() : Map.copyOf(shipDefense);
            stanceAttackMod = stanceAttackMod == null ? Map.of() : Map.copyOf(stanceAttackMod);
            stanceDefenseMod = stanceDefenseMod == null ? Map.of() : Map.copyOf(stanceDefenseMod);
            if (terrainDefenseMod <= 0.0) {
                terrainDefenseMod = 1.0;
            }
            // Loss fractions default to a sane proportional split if a legacy profile
            // omits them (loser annihilated, winner takes no attrition).
            if (lossFractionLoser <= 0.0) {
                lossFractionLoser = 1.0;
            }
            if (lossFractionWinner < 0.0) {
                lossFractionWinner = 0.0;
            }
        }

        /**
         * Five-arg constructor preserving the pre-E1-10 shape. Existing callers/tests
         * keep compiling; the E1-10 power/loss fields take their neutral defaults.
         */
        public Combat(Map<String, Double> tierMultipliers, List<Double> varianceBand,
                      double defensePlatformBonus, double occupationLoyaltyPenalty,
                      double warExhaustionPerLoss) {
            this(tierMultipliers, varianceBand, defensePlatformBonus, occupationLoyaltyPenalty,
                    warExhaustionPerLoss, Map.of(), Map.of(), Map.of(), Map.of(), 1.0, 0.0, 1.0);
        }
    }

    /**
     * Fleet movement and lane-interception tunables (E1-09; game-design 05 section 4,
     * 01 section 3). Map geometry (lane lengths in ticks) is NOT here - it is intrinsic
     * to the lane graph ({@code engine.map.LaneNetwork}); this record holds only the
     * gameplay <em>tunables</em> the resolver applies to that geometry.
     *
     * <ul>
     *   <li>{@code energyCostPerLaneTick} - Energy charged per tick of lane travel,
     *       summed over a fleet's journey and escrowed when the move is launched. A
     *       value of {@code 0} makes travel free (the inert default).</li>
     *   <li>{@code interceptionEnabled} - master switch for mid-transit interception.
     *       When {@code true}, a hostile fleet holding a contested lane forces a battle
     *       on a fleet traversing it (the trigger E1-09 detects; combat lands in
     *       E1-10). When {@code false}, fleets pass freely (the inert default).</li>
     * </ul>
     */
    public record Movement(
            double energyCostPerLaneTick,
            boolean interceptionEnabled
    ) {
        /** Inert defaults for a profile that omits the movement block: free, no interception. */
        public static Movement defaults() {
            return new Movement(0.0, false);
        }
    }

    /**
     * Tech DAG costs (Tech resource), times (ticks), applied multipliers and the
     * prerequisite / unlock edges that make it a directed acyclic graph (E1-08).
     *
     * <ul>
     *   <li>{@code costs} - Tech resource to research each node, keyed by tech id.</li>
     *   <li>{@code times} - ticks each node takes once research begins.</li>
     *   <li>{@code multipliers} - the production multiplier an unlocked node applies
     *       (consumed by {@code EconomyResolution}); a node with no passive
     *       multiplier is simply absent.</li>
     *   <li>{@code prereqs} - the DAG edges: a tech id to the list of tech ids that
     *       must be {@code UNLOCKED} before it can be researched. A node absent from
     *       this map (or mapped to an empty list) is a root with no prerequisites.
     *       This is the prerequisite gate the validator/resolver enforce; keeping it
     *       in config (rule 6) means the tree shape is tunable, not hardcoded.</li>
     *   <li>{@code unlocks} - what each tech node gates once unlocked: the list of
     *       config keys (ship-spec or building configKeys) that become available to
     *       the faction. The inverse index (capability key to the tech that gates it)
     *       lets the validator reject a gated Build/BuildFleet whose required tech is
     *       not yet unlocked ({@code TECH_PREREQ_MISSING}). A capability not named by
     *       any tech is ungated (available from the start).</li>
     * </ul>
     */
    public record Tech(
            Map<String, Double> costs,
            Map<String, Integer> times,
            Map<String, Double> multipliers,
            Map<String, List<String>> prereqs,
            Map<String, List<String>> unlocks
    ) {
        public Tech {
            costs = Map.copyOf(costs);
            times = Map.copyOf(times);
            multipliers = Map.copyOf(multipliers);
            // prereqs/unlocks are additive in E1-08; a profile that omits them has a
            // flat (rootless) tree with nothing gated (defensive, forward-compatible).
            prereqs = copyOfLists(prereqs);
            unlocks = copyOfLists(unlocks);
        }

        private static Map<String, List<String>> copyOfLists(Map<String, List<String>> in) {
            if (in == null) {
                return Map.of();
            }
            Map<String, List<String>> out = new java.util.LinkedHashMap<>();
            for (Map.Entry<String, List<String>> e : in.entrySet()) {
                out.put(e.getKey(), List.copyOf(e.getValue()));
            }
            return Map.copyOf(out);
        }
    }

    /** Reputation ledger plus per-treaty enforcement weights. */
    public record Diplomacy(
            Reputation reputation,
            Map<String, Double> treatyEnforcement
    ) {
        public Diplomacy {
            treatyEnforcement = Map.copyOf(treatyEnforcement);
        }
    }

    /**
     * Public reputation ledger. penaltyBreakTreaty is the coefficient applied to
     * (treaty weight x remaining duration in ticks) when a treaty is broken; the
     * per-treaty weight lives in Diplomacy.treatyEnforcement.
     */
    public record Reputation(
            double gainHonourTreaty,
            double penaltyBreakTreaty,
            double penaltyUnprovokedWar,
            double espionageDetectedPenalty
    ) {
    }

    /**
     * The five victory conditions, the one selected for this match, the win/lifecycle
     * switches, plus the ranking weights (E1-15; game-design 07).
     *
     * <p><b>The active condition (game-design 07 section 1: "one selected per match").</b>
     * {@code active} names the single primary victory condition the
     * {@code VictoryEvaluation} step checks each tick; the other four condition records
     * carry their thresholds so a profile is fully tunable, but only {@code active}'s
     * threshold can win the match. {@code active} defaults to {@link VictoryKind#SURVIVAL}
     * (the always-reachable tick-limit fallback) when a profile omits it.
     *
     * <p><b>Switches (forward-compatible, both default OFF).</b>
     * <ul>
     *   <li>{@code conditionEnabled} - master switch for the active victory condition. When
     *       {@code false} (the inert default) no condition ever fires (a never-ending /
     *       sandbox match); a real match turns it on. Keeping it off by default means a
     *       legacy fixture/profile that omits the E1-15 block resolves exactly as before
     *       (no spurious CONCLUDED transition).</li>
     *   <li>{@code eliminationEnabled} - master switch for capital-loss elimination and
     *       vassalage survival (game-design 07 section 3). When {@code false} (the inert
     *       default) no faction is ever eliminated; a real match turns it on. Off by
     *       default for the same byte-identity reason.</li>
     * </ul>
     */
    public record Victory(
            VictoryKind active,
            boolean conditionEnabled,
            boolean eliminationEnabled,
            Domination domination,
            Economic economic,
            Diplomatic diplomatic,
            Survival survival,
            Wonder wonder,
            ScoreWeights scoreWeights
    ) {
        /**
         * Compact constructor defaulting the E1-15 {@code active} selector to
         * {@link VictoryKind#SURVIVAL} when a profile omits it (the always-reachable
         * tick-limit fallback). The two boolean switches are primitives that default to
         * {@code false} (both OFF) for older positional callers via the delegating
         * constructor below.
         */
        public Victory {
            active = active == null ? VictoryKind.SURVIVAL : active;
        }

        /**
         * Backwards-compatible six-arg constructor predating the E1-15 {@code active}
         * selector and the two switches: delegates with {@link VictoryKind#SURVIVAL}
         * active and both switches OFF. Lets pre-E1-15 fixtures/profiles that build a
         * Victory positionally (the five condition records + score weights) keep
         * compiling unchanged, and - critically - keeps victory/elimination evaluation
         * inert for them so the golden state hash is unperturbed.
         */
        public Victory(Domination domination, Economic economic, Diplomatic diplomatic,
                       Survival survival, Wonder wonder, ScoreWeights scoreWeights) {
            this(VictoryKind.SURVIVAL, false, false,
                    domination, economic, diplomatic, survival, wonder, scoreWeights);
        }
    }

    /**
     * The closed set of primary victory conditions (game-design 07 section 1). Exactly
     * one is the match's {@link Victory#active} condition; it names which threshold the
     * {@code VictoryEvaluation} step checks each tick. Kept in the config module (not the
     * state module) because it is a tunable profile selector, not per-tick game state.
     */
    public enum VictoryKind {
        /** Control >= {@code domination.systemPct} of habitable systems. */
        DOMINATION,
        /** Reach {@code economic.influenceTarget} Influence (or hold top for N ticks). */
        ECONOMIC,
        /** Lead an alliance controlling {@code diplomatic.allianceMajorityPct} of systems. */
        DIPLOMATIC,
        /** Be the last faction with a capital, or survive to {@code survival.tickLimit}. */
        SURVIVAL,
        /** Complete and hold a galaxy Wonder for {@code wonder.holdTicks} ticks. */
        WONDER
    }

    /** Fraction (0..1) of habitable systems required to win. */
    public record Domination(double systemPct) {
    }

    /** Influence target, or ticks one must hold top Influence. */
    public record Economic(double influenceTarget, int orTopForTicks) {
    }

    /** Fraction (0..1) of galaxy influence/territory an alliance must control. */
    public record Diplomatic(double allianceMajorityPct) {
    }

    /** Hard match length in ticks for the survival condition. */
    public record Survival(int tickLimit) {
    }

    /** Wonder build stages and ticks the completed Wonder must be held. */
    public record Wonder(int stages, int holdTicks) {
    }

    /** Config-weighted ranking factors (game-design 07 section 2). */
    public record ScoreWeights(
            double systems,
            double influence,
            double economy,
            double tech,
            double reputation,
            double military,
            double centrality
    ) {
    }

    /** Tick cadence: interval, negotiation rounds, per-phase timeout. */
    public record Tick(
            long intervalMs,
            int negotiationRounds,
            long phaseTimeoutMs
    ) {
    }

    /**
     * E2-04 home placement tunables (game-design 01 section 6: homes placed with a
     * minimum separation and balanced local resource potential, "so no Sovereign
     * begins boxed-in or starved. Placement is seeded and reproducible.").
     *
     * <p>These are the authoritative values; the framework-free galaxy module cannot
     * depend on the engine, so the orchestrator / promotion boundary copies them into
     * the galaxy's mirror {@code HomePlacementConfig} to drive {@code
     * HomePlacementGenerator}. Kept here (rule 6) so the placement is tunable per
     * profile, never hardcoded in the generator.
     *
     * <ul>
     *   <li>{@code factionCount} - homes to place, one per faction (>= 1).</li>
     *   <li>{@code minSeparationHops} - minimum lane hops required between any two
     *       homes; the anti-cramping guarantee (>= 1).</li>
     *   <li>{@code neighbourhoodHops} - the hop radius K defining a home's local
     *       neighbourhood for the balance measure (habitable/colonisable bodies and
     *       resource accessibility within K hops) (>= 0).</li>
     *   <li>{@code qualityToleranceFraction} - max relative spread of neighbourhood
     *       quality across the chosen homes, in [0,1]; smaller = stricter fairness.</li>
     *   <li>{@code homeBiome} - the cradle biome a home system must carry (the
     *       colonised home world the faction starts on); a
     *       {@link com.stellarcompact.engine.state.Biome#configKey()} value.</li>
     * </ul>
     */
    public record HomePlacement(
            int factionCount,
            int minSeparationHops,
            int neighbourhoodHops,
            double qualityToleranceFraction,
            String homeBiome
    ) {
        /**
         * Inert defaults for a profile that omits the E2-04 block: a single faction,
         * one-hop separation, a one-hop neighbourhood, a generous half tolerance, on the
         * Oceanic cradle. Inert in the sense that it always loads; a real match supplies
         * the profile's own values.
         */
        public static HomePlacement defaults() {
            return new HomePlacement(1, 1, 1, 0.5, "oceanic");
        }
    }

    /**
     * Espionage tunables (E1-13; game-design 03 section C "Espionage", 06 section 3).
     * Every probabilistic espionage outcome reads its odds, cost and effect magnitude
     * from here - nothing is hardcoded in {@code EspionageResolution} (rule 6).
     *
     * <p>All per-operation maps are keyed by the operation's
     * {@link com.stellarcompact.engine.action.EspionageOperation#configKey()}
     * ({@code "scout"}, {@code "stealIntel"}, {@code "sabotage"}, {@code "inciteUnrest"}).
     * An operation absent from a map takes the neutral default noted below, so a
     * partial profile degrades gracefully.
     *
     * <ul>
     *   <li>{@code successBase} - base success probability in [0,1] per op (default 0:
     *       an unconfigured op always fails). The effective success is
     *       {@code clamp(successBase - counterIntelSuccessPenalty if the target has the
     *       counter-intel tech, 0, 1)}; the seeded success roll {@code r in [0,1)}
     *       succeeds iff {@code r < effectiveSuccess}.</li>
     *   <li>{@code detectionBase} - base probability in [0,1] that a run (success OR
     *       failure) is detected and attributed to the actor (default 0: never
     *       detected). The effective detection is
     *       {@code clamp(detectionBase + counterIntelDetectionBonus if the target has
     *       counter-intel, 0, 1)}; a separate seeded roll decides detection.</li>
     *   <li>{@code cost} - the resources (typically Influence/Tech) escrowed when the
     *       op is run, win or lose (an op absent costs nothing).</li>
     *   <li>{@code counterIntelTech} - the {@code TechId} value of the tech that, when
     *       UNLOCKED by the <em>target</em>, confers counter-intelligence; blank/absent
     *       disables the counter-intel mechanic. (game-design 06: "Intelligence Agency").</li>
     *   <li>{@code counterIntelSuccessPenalty} - amount subtracted from an op's success
     *       odds when the target holds the counter-intel tech (>= 0).</li>
     *   <li>{@code counterIntelDetectionBonus} - amount added to an op's detection odds
     *       when the target holds the counter-intel tech (>= 0).</li>
     *   <li>{@code stealResourceFraction} - fraction (0..1) of the target's stockpile a
     *       successful {@code STEAL_INTEL} transfers when no stealable tech is available
     *       (the resource fallback; a successful steal prefers transferring a tech).</li>
     *   <li>{@code unrestPopulationLoss} - population removed from the struck colony on a
     *       successful {@code INCITE_UNREST} (>= 0).</li>
     *   <li>{@code unrestLoyaltyLoss} - loyalty (0..1, floored at 0) removed from the
     *       struck system on a successful {@code INCITE_UNREST} (>= 0).</li>
     * </ul>
     */
    public record Espionage(
            Map<String, Double> successBase,
            Map<String, Double> detectionBase,
            Map<String, ResourceBundle> cost,
            String counterIntelTech,
            double counterIntelSuccessPenalty,
            double counterIntelDetectionBonus,
            double stealResourceFraction,
            long unrestPopulationLoss,
            double unrestLoyaltyLoss
    ) {
        public Espionage {
            successBase = successBase == null ? Map.of() : Map.copyOf(successBase);
            detectionBase = detectionBase == null ? Map.of() : Map.copyOf(detectionBase);
            cost = cost == null ? Map.of() : Map.copyOf(cost);
            if (counterIntelSuccessPenalty < 0.0) {
                counterIntelSuccessPenalty = 0.0;
            }
            if (counterIntelDetectionBonus < 0.0) {
                counterIntelDetectionBonus = 0.0;
            }
            if (unrestPopulationLoss < 0) {
                unrestPopulationLoss = 0;
            }
            if (unrestLoyaltyLoss < 0.0) {
                unrestLoyaltyLoss = 0.0;
            }
        }

        /**
         * Inert defaults for a profile that omits the espionage block: every op always
         * fails (empty {@code successBase}) and is never detected (empty
         * {@code detectionBase}), no costs, no counter-intel tech, zero effect
         * magnitudes. A match that wants espionage supplies the block.
         */
        public static Espionage defaults() {
            return new Espionage(Map.of(), Map.of(), Map.of(), "", 0.0, 0.0, 0.0, 0, 0.0);
        }
    }

    /**
     * Influence accrual &amp; decay tunables (E1-14; game-design 02 section 1, 5, 7).
     * Influence is the political-capital resource: it is <em>never</em> hauled along a
     * route nor market-traded (game-design 02 section 1 - it can only be granted via a
     * treaty term, not sold) - this step only accrues it from behaviour and decays it.
     * Every number the INFLUENCE step applies reads from here; nothing is hardcoded
     * in {@code InfluenceResolution} (rule 6).
     *
     * <p>The four documented accrual sources (game-design 02 section 1/7: "Influence
     * accrues from capitals, trade volume, monuments and honoured diplomacy"):
     * <ul>
     *   <li>{@code perCapitalSystem} - flat Influence per owned home/capital system per
     *       tick. The home/capital is modelled as a system carrying an active
     *       {@link com.stellarcompact.engine.state.BuildingType#MONUMENT} is NOT the
     *       capital marker; capitals are every owned system in this minimal model (the
     *       soft-power base of holding territory). A {@code 0.0} disables it.</li>
     *   <li>{@code perMonument} - flat Influence per active
     *       {@link com.stellarcompact.engine.state.BuildingType#MONUMENT} the faction
     *       holds (the prestige building's ongoing Influence, on top of any biome yield
     *       the production step already credits). A {@code 0.0} disables it.</li>
     *   <li>{@code perTradeVolume} - Influence per unit of per-tick throughput of each
     *       ACTIVE route the faction owns (commerce builds soft power, game-design 02
     *       section 5). A BLOCKADED route's throughput is scaled by
     *       {@code (1 - market.blockadeThroughputFactor)} so a choked route earns less.
     *       A {@code 0.0} disables it.</li>
     *   <li>{@code perActiveTreaty} - flat Influence per ACTIVE treaty the faction is a
     *       signatory to (honoured diplomacy - standing agreements project prestige).
     *       A {@code 0.0} disables it.</li>
     * </ul>
     *
     * <p>Decay (game-design 02 section 7 - Influence is a soft, behaviour-tied currency,
     * not a hoard): after accrual, the faction's Influence stockpile is multiplied by
     * {@code (1 - decayRate)} each tick, so an idle faction's influence bleeds toward
     * zero and only sustained behaviour keeps it high. {@code decayRate} is a fraction
     * in {@code [0,1]} ({@code 0.0} = no decay, the inert default).
     */
    public record Influence(
            double perCapitalSystem,
            double perMonument,
            double perTradeVolume,
            double perActiveTreaty,
            double decayRate
    ) {
        /**
         * Compact constructor clamping {@code decayRate} into {@code [0,1]} so a malformed
         * profile cannot amplify (a rate &gt; 1 would flip the sign) nor un-decay (negative).
         * Accrual rates are left as-authored (a designer may want any non-negative value);
         * a negative accrual is clamped to {@code 0.0} so influence can never be drained by
         * a source.
         */
        public Influence {
            perCapitalSystem = nonNegative(perCapitalSystem);
            perMonument = nonNegative(perMonument);
            perTradeVolume = nonNegative(perTradeVolume);
            perActiveTreaty = nonNegative(perActiveTreaty);
            if (decayRate < 0.0) {
                decayRate = 0.0;
            } else if (decayRate > 1.0) {
                decayRate = 1.0;
            }
        }

        private static double nonNegative(double v) {
            return v < 0.0 ? 0.0 : v;
        }

        /**
         * Inert defaults for a profile that omits the E1-14 block: no accrual from any
         * source and no decay. A match that wants the influence economy supplies the block
         * (the shipped {@code small-default} / {@code large-persistent} profiles do).
         */
        public static Influence defaults() {
            return new Influence(0.0, 0.0, 0.0, 0.0, 0.0);
        }
    }

    /**
     * Small-&gt;large progression and identity/reputation-only carry-over tunables
     * (E9-01; game-design 07 section 6, 06 section 5).
     *
     * <p>The progression loop: a Sovereign proves itself in cheap, fast, ephemeral
     * <b>small</b> galaxies, earns <em>standing</em> (a seat), then graduates into the
     * expensive, slow, persistent <b>large</b> campaign. The cardinal fairness rule
     * (game-design 06 section 5 / 07 section 6): a graduating Sovereign carries
     * <b>only identity and reputation</b> - never raw resources, tech, fleets or
     * territory - so a veteran cannot buy a runaway start. This block is the entire
     * set of numbers that gates the seat and weights the carried reputation; nothing
     * is hardcoded in {@code progression.*} (rule 6).
     *
     * <ul>
     *   <li>{@code sizeClass} - which galaxy tier this profile configures:
     *       {@code "SMALL"} (the funnel: completing one earns standing) or
     *       {@code "LARGE"} (the persistent campaign: entry is gated by standing).
     *       A small profile sets it {@code "SMALL"}; a large profile {@code "LARGE"}.
     *       Defaults to {@code "SMALL"} (the open-entry tier) when omitted.</li>
     *   <li>{@code seatThresholdScore} - the minimum final {@code Scoring} a faction
     *       must reach in a concluded <em>small</em> match to earn a seat in a large
     *       galaxy. A faction at or above this is admitted; below it is denied. Only
     *       meaningful on a LARGE profile (it gates entry <em>into</em> that tier);
     *       {@code 0.0} (the default) opens the seat to anyone who completed a match.</li>
     *   <li>{@code winGrantsSeat} - if {@code true}, winning (placing first) a small
     *       match earns a seat <em>regardless</em> of {@code seatThresholdScore}, so a
     *       narrow-but-victorious campaign still graduates. Defaults {@code false}.</li>
     *   <li>{@code reputationCarryWeight} - the fraction [0,1] of a graduating
     *       Sovereign's prior-standing reputation that seeds its identity in the new
     *       large match. {@code 1.0} carries reputation intact; {@code 0.0} starts the
     *       reputation clean (identity-only). This is the <em>only</em> non-material
     *       quantity that crosses the boundary. Defaults {@code 0.0} (clean start).</li>
     *   <li>{@code starterStockpile} - the material loadout EVERY faction entering a
     *       match of this tier starts with: the fresh resource bundle that REPLACES any
     *       prior stockpile. Carry-over resets material state to exactly this, so no
     *       resources/tech/fleets leak across matches (the enforcement of the
     *       no-material-advantage rule). Defaults to the all-zero bundle.</li>
     * </ul>
     */
    public record Progression(
            String sizeClass,
            double seatThresholdScore,
            boolean winGrantsSeat,
            double reputationCarryWeight,
            ResourceBundle starterStockpile
    ) {
        /**
         * Compact constructor: normalises {@code sizeClass} to upper-case (so
         * {@code "small"}/{@code "SMALL"} are equivalent, defaulting blank to
         * {@code "SMALL"}), floors the seat threshold at {@code 0.0}, and clamps the
         * reputation carry weight into {@code [0,1]} so a malformed profile can neither
         * subtract reputation nor amplify it past the prior value. A null starter bundle
         * defaults to the all-zero loadout (a clean material start).
         */
        public Progression {
            sizeClass = (sizeClass == null || sizeClass.isBlank())
                    ? "SMALL" : sizeClass.trim().toUpperCase();
            if (seatThresholdScore < 0.0) {
                seatThresholdScore = 0.0;
            }
            if (reputationCarryWeight < 0.0) {
                reputationCarryWeight = 0.0;
            } else if (reputationCarryWeight > 1.0) {
                reputationCarryWeight = 1.0;
            }
            starterStockpile = starterStockpile == null
                    ? new ResourceBundle(0.0, 0.0, 0.0, 0.0, 0.0) : starterStockpile;
        }

        /**
         * Inert defaults for a profile that omits the E9-01 block: a SMALL galaxy with an
         * open seat ({@code 0.0} threshold, win does not auto-grant), no reputation carry
         * ({@code 0.0} - identity-only) and an all-zero starter stockpile. A real match
         * supplies the tier's own values (the shipped {@code small-default} /
         * {@code large-persistent} profiles do).
         */
        public static Progression defaults() {
            return new Progression("SMALL", 0.0, false, 0.0,
                    new ResourceBundle(0.0, 0.0, 0.0, 0.0, 0.0));
        }
    }

    /**
     * Tournament / season aggregation weights (E9-03; game-design 07 section 2 + section
     * 6). A <b>season</b> aggregates the per-match {@code StandingRecord}s of multiple
     * concluded matches into a single season ranking (the leaderboard) per Sovereign, and
     * that aggregate then feeds the same {@code progression} seat gate that a single match
     * does (no forked ranking/gating logic - the season aggregate is projected into a
     * {@code StandingRecord} and passed to {@code ProgressionEvaluation.admits}).
     *
     * <p>Every number here is config (rule 6); the aggregation in {@code engine.season} is
     * a pure function of the input standings + these weights, so the same standings always
     * produce the same season ranking (determinism, rule 1).
     *
     * <ul>
     *   <li>{@code matchScoreWeight} - the weight applied to a Sovereign's <em>summed</em>
     *       per-match {@code Scoring} across the season. {@code 1.0} means the season
     *       aggregate is the raw sum of match scores; a smaller value damps raw score in
     *       favour of the win bonus below. Must be &gt;= 0 (floored at 0).</li>
     *   <li>{@code winBonus} - a flat bonus added to the aggregate for every match the
     *       Sovereign <em>won</em> (placed 1st). Rewards consistency/victories over a
     *       single high-scoring blow-out. {@code 0.0} = scores-only seasons. Floored at
     *       0.</li>
     *   <li>{@code participationBonus} - a flat bonus per concluded match the Sovereign
     *       took part in (rewards showing up across the season). {@code 0.0} = no
     *       participation credit. Floored at 0.</li>
     *   <li>{@code minMatchesForSeat} - the minimum number of concluded matches a Sovereign
     *       must have played in the season before its season aggregate is allowed to clear
     *       a LARGE seat (a Sovereign with fewer plays is held below the gate regardless of
     *       aggregate). {@code 1} (the default) means a single match suffices; raise it to
     *       require a real campaign body of work. Floored at 1.</li>
     * </ul>
     */
    public record Season(
            double matchScoreWeight,
            double winBonus,
            double participationBonus,
            int minMatchesForSeat
    ) {
        /**
         * Compact constructor: floors {@code matchScoreWeight}/{@code winBonus}/
         * {@code participationBonus} at {@code 0.0} (a malformed profile can never subtract
         * from the aggregate) and {@code minMatchesForSeat} at {@code 1} (at least one
         * match is always required to have a standing at all).
         */
        public Season {
            if (matchScoreWeight < 0.0) {
                matchScoreWeight = 0.0;
            }
            if (winBonus < 0.0) {
                winBonus = 0.0;
            }
            if (participationBonus < 0.0) {
                participationBonus = 0.0;
            }
            if (minMatchesForSeat < 1) {
                minMatchesForSeat = 1;
            }
        }

        /**
         * Inert defaults for a profile that omits the E9-03 block: the season aggregate is
         * exactly the raw sum of per-match scores ({@code matchScoreWeight = 1.0}, no win or
         * participation bonus) and a single match suffices for a seat
         * ({@code minMatchesForSeat = 1}). A real season tier supplies its own weights.
         */
        public static Season defaults() {
            return new Season(1.0, 0.0, 0.0, 1);
        }
    }
}
