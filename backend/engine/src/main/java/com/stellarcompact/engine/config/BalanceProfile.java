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
        Tech tech,
        Diplomacy diplomacy,
        Victory victory,
        Tick tick
) {

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

    /** Market order-book policy and route-driven Influence. */
    public record Market(
            String matchPolicy,
            double routeInfluencePerVolume
    ) {
    }

    /** Build times (ticks) and resource costs per building / terraform step. */
    public record Construction(
            Map<String, Integer> buildTimes,
            Map<String, ResourceBundle> costs
    ) {
        public Construction {
            buildTimes = Map.copyOf(buildTimes);
            costs = Map.copyOf(costs);
        }
    }

    /**
     * Combat tunables. varianceBand is [lo, hi] bounds of the seeded roll;
     * occupationLoyaltyPenalty is the loyalty lost on a freshly captured system;
     * warExhaustionPerLoss is exhaustion accrued per ship lost.
     */
    public record Combat(
            Map<String, Double> tierMultipliers,
            List<Double> varianceBand,
            double defensePlatformBonus,
            double occupationLoyaltyPenalty,
            double warExhaustionPerLoss
    ) {
        public Combat {
            tierMultipliers = Map.copyOf(tierMultipliers);
            varianceBand = List.copyOf(varianceBand);
        }
    }

    /** Tech DAG costs (Tech resource), times (ticks) and applied multipliers. */
    public record Tech(
            Map<String, Double> costs,
            Map<String, Integer> times,
            Map<String, Double> multipliers
    ) {
        public Tech {
            costs = Map.copyOf(costs);
            times = Map.copyOf(times);
            multipliers = Map.copyOf(multipliers);
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

    /** The five victory conditions plus ranking weights. */
    public record Victory(
            Domination domination,
            Economic economic,
            Diplomatic diplomatic,
            Survival survival,
            Wonder wonder,
            ScoreWeights scoreWeights
    ) {
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
}
