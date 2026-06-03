package com.stellarcompact.engine.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;

import java.util.List;

/**
 * Pure parser + validator for {@link BalanceProfile}.
 *
 * <p>Purity contract (engine rule 1): this class performs NO I/O. It only maps a
 * JSON String the caller already holds into the immutable record hierarchy via
 * the Jackson String overload. Reading the profile bytes from the classpath, a
 * file or the database is the responsibility of the caller (persistence/app
 * modules, or this module's own tests) -- never the engine. The String-parsing
 * seam is what keeps the engine free of {@code java.io} / {@code java.net}.
 *
 * <p>{@link #parse(String)} both maps and {@link #validate(BalanceProfile)
 * validates}: a structurally-parseable but incomplete or out-of-range profile is
 * rejected with a clear {@link InvalidBalanceProfileException} so a bad config
 * fails loudly at load time rather than corrupting a match mid-tick.
 */
public final class BalanceProfileLoader {

    /**
     * Strict mapper: unknown JSON properties are a hard failure so a mistyped or
     * stale config key can never be silently ignored.
     */
    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    private BalanceProfileLoader() {
    }

    /**
     * Parse and validate a balance profile from an in-memory JSON String.
     *
     * @param json the profile JSON (already read from wherever it lives)
     * @return a validated, immutable profile
     * @throws InvalidBalanceProfileException if the JSON is malformed, has
     *                                        unknown keys, or fails validation
     */
    public static BalanceProfile parse(String json) {
        if (json == null || json.isBlank()) {
            throw new InvalidBalanceProfileException("balance profile JSON is null/blank");
        }
        final BalanceProfile profile;
        try {
            profile = MAPPER.readValue(json, BalanceProfile.class);
        } catch (JsonMappingException e) {
            throw new InvalidBalanceProfileException(
                    "balance profile JSON is invalid: " + e.getOriginalMessage(), e);
        } catch (Exception e) {
            throw new InvalidBalanceProfileException(
                    "balance profile JSON could not be parsed: " + e.getMessage(), e);
        }
        validate(profile);
        return profile;
    }

    /**
     * Assert the presence and sanity of every documented tunable group. A null
     * field on a record raises NullPointerException during construction already;
     * this adds the range/cardinality checks the type system cannot express.
     *
     * @throws InvalidBalanceProfileException on any missing or out-of-range value
     */
    public static void validate(BalanceProfile p) {
        require(p != null, "profile is null");

        // identity
        require(p.name() != null && !p.name().isBlank(), "name must be non-blank");
        require(p.version() > 0, "version must be positive");

        // resources
        BalanceProfile.Resources r = req(p.resources(), "resources");
        require(!r.biomeYields().isEmpty(), "resources.biomeYields must be non-empty");
        require(!r.upkeep().isEmpty(), "resources.upkeep must be non-empty");
        require(r.deficitAttritionRate() > 0.0 && r.deficitAttritionRate() <= 1.0,
                "resources.deficitAttritionRate must be in (0,1]");

        // population
        BalanceProfile.Population pop = req(p.population(), "population");
        require(pop.growthPerFoodSurplus() > 0.0, "population.growthPerFoodSurplus must be > 0");
        require(pop.declinePerFoodDeficit() >= 0.0, "population.declinePerFoodDeficit must be >= 0");
        require(pop.productionPerPop() >= 0.0, "population.productionPerPop must be >= 0");
        require(pop.baseCap() >= 0, "population.baseCap must be >= 0");
        require(!pop.capByBuilding().isEmpty(), "population.capByBuilding must be non-empty");

        // market
        BalanceProfile.Market m = req(p.market(), "market");
        require(m.matchPolicy() != null && !m.matchPolicy().isBlank(), "market.matchPolicy must be set");
        require(m.routeInfluencePerVolume() > 0.0, "market.routeInfluencePerVolume must be > 0");
        require(m.currency() != null && !m.currency().isBlank(), "market.currency must be set");
        require(isPhysicalResource(m.currency()),
                "market.currency must be a physical resource (ENERGY|MINERALS|FOOD|TECH), was '"
                        + m.currency() + "'");

        // construction
        BalanceProfile.Construction c = req(p.construction(), "construction");
        require(!c.buildTimes().isEmpty(), "construction.buildTimes must be non-empty");
        require(!c.costs().isEmpty(), "construction.costs must be non-empty");
        c.buildTimes().forEach((k, val) -> require(val > 0, "construction.buildTimes." + k + " must be > 0"));

        // combat
        BalanceProfile.Combat cb = req(p.combat(), "combat");
        require(!cb.tierMultipliers().isEmpty(), "combat.tierMultipliers must be non-empty");
        List<Double> band = req(cb.varianceBand(), "combat.varianceBand");
        require(band.size() == 2, "combat.varianceBand must be [lo, hi]");
        require(band.get(0) >= 0.0 && band.get(0) < band.get(1) && band.get(1) <= 1.0,
                "combat.varianceBand must satisfy 0 <= lo < hi <= 1");
        require(cb.defensePlatformBonus() >= 1.0, "combat.defensePlatformBonus must be >= 1");
        require(cb.occupationLoyaltyPenalty() >= 0.0 && cb.occupationLoyaltyPenalty() <= 1.0,
                "combat.occupationLoyaltyPenalty must be in [0,1]");
        require(cb.warExhaustionPerLoss() > 0.0, "combat.warExhaustionPerLoss must be > 0");

        // tech
        BalanceProfile.Tech t = req(p.tech(), "tech");
        require(!t.costs().isEmpty(), "tech.costs must be non-empty");
        require(!t.times().isEmpty(), "tech.times must be non-empty");
        require(!t.multipliers().isEmpty(), "tech.multipliers must be non-empty");
        t.times().forEach((k, val) -> require(val > 0, "tech.times." + k + " must be > 0"));

        // diplomacy
        BalanceProfile.Diplomacy d = req(p.diplomacy(), "diplomacy");
        BalanceProfile.Reputation rep = req(d.reputation(), "diplomacy.reputation");
        require(rep.gainHonourTreaty() > 0.0, "diplomacy.reputation.gainHonourTreaty must be > 0");
        require(rep.penaltyBreakTreaty() > 0.0, "diplomacy.reputation.penaltyBreakTreaty must be > 0");
        require(rep.penaltyUnprovokedWar() > 0.0, "diplomacy.reputation.penaltyUnprovokedWar must be > 0");
        require(rep.espionageDetectedPenalty() > 0.0, "diplomacy.reputation.espionageDetectedPenalty must be > 0");
        require(!d.treatyEnforcement().isEmpty(), "diplomacy.treatyEnforcement must be non-empty");

        // victory
        BalanceProfile.Victory v = req(p.victory(), "victory");
        // E1-15: the selected primary condition. Defaulted to SURVIVAL by the record when
        // absent, so it is never null here; assert presence defensively.
        require(v.active() != null, "victory.active must name a condition");
        require(pct(req(v.domination(), "victory.domination").systemPct()),
                "victory.domination.systemPct must be in (0,1]");
        BalanceProfile.Economic eco = req(v.economic(), "victory.economic");
        require(eco.influenceTarget() > 0.0, "victory.economic.influenceTarget must be > 0");
        require(eco.orTopForTicks() > 0, "victory.economic.orTopForTicks must be > 0");
        require(pct(req(v.diplomatic(), "victory.diplomatic").allianceMajorityPct()),
                "victory.diplomatic.allianceMajorityPct must be in (0,1]");
        require(req(v.survival(), "victory.survival").tickLimit() > 0,
                "victory.survival.tickLimit must be > 0");
        BalanceProfile.Wonder w = req(v.wonder(), "victory.wonder");
        require(w.stages() > 0, "victory.wonder.stages must be > 0");
        require(w.holdTicks() > 0, "victory.wonder.holdTicks must be > 0");
        req(v.scoreWeights(), "victory.scoreWeights");

        // tick
        BalanceProfile.Tick tick = req(p.tick(), "tick");
        require(tick.intervalMs() > 0, "tick.intervalMs must be > 0");
        require(tick.negotiationRounds() > 0, "tick.negotiationRounds must be > 0");
        require(tick.phaseTimeoutMs() > 0, "tick.phaseTimeoutMs must be > 0");

        // espionage (E1-13). Additive: a profile may omit the block entirely (the
        // record supplies inert defaults), but any odds that ARE supplied must be
        // probabilities and the effect magnitudes non-negative.
        BalanceProfile.Espionage esp = req(p.espionage(), "espionage");
        esp.successBase().forEach((k, val) ->
                require(prob(val), "espionage.successBase." + k + " must be in [0,1]"));
        esp.detectionBase().forEach((k, val) ->
                require(prob(val), "espionage.detectionBase." + k + " must be in [0,1]"));
        require(esp.counterIntelSuccessPenalty() >= 0.0,
                "espionage.counterIntelSuccessPenalty must be >= 0");
        require(esp.counterIntelDetectionBonus() >= 0.0,
                "espionage.counterIntelDetectionBonus must be >= 0");
        require(prob(esp.stealResourceFraction()),
                "espionage.stealResourceFraction must be in [0,1]");
        require(esp.unrestPopulationLoss() >= 0, "espionage.unrestPopulationLoss must be >= 0");
        require(prob(esp.unrestLoyaltyLoss()), "espionage.unrestLoyaltyLoss must be in [0,1]");

        // progression (E9-01). Additive: a profile may omit the block (inert SMALL/open
        // defaults). The record already normalises sizeClass and clamps the carry weight;
        // here we assert the tier names a known galaxy class and the threshold is sane so
        // a typo'd tier or a negative threshold fails loudly at load.
        BalanceProfile.Progression prog = req(p.progression(), "progression");
        require(prog.sizeClass().equals("SMALL") || prog.sizeClass().equals("LARGE"),
                "progression.sizeClass must be SMALL or LARGE, was '" + prog.sizeClass() + "'");
        require(prog.seatThresholdScore() >= 0.0,
                "progression.seatThresholdScore must be >= 0");
        require(prob(prog.reputationCarryWeight()),
                "progression.reputationCarryWeight must be in [0,1]");
        req(prog.starterStockpile(), "progression.starterStockpile");

        // home placement (E2-04 + E10-05 starting-economy floor). Additive: a profile may
        // omit the block (the record supplies inert defaults). The record already floors
        // the cardinality fields and the E10-05 knobs; these asserts make a malformed
        // authored value fail loudly at load (rule 7: a bad config dies at load, not
        // mid-tick). Faction count / separation / neighbourhood are validated by the
        // galaxy mirror's constructor at placement time; here we guard the load-time range
        // of the new fairness floor and the tolerance fraction.
        BalanceProfile.HomePlacement hp = req(p.homePlacement(), "homePlacement");
        require(hp.factionCount() >= 1, "homePlacement.factionCount must be >= 1");
        require(hp.minSeparationHops() >= 1, "homePlacement.minSeparationHops must be >= 1");
        require(hp.neighbourhoodHops() >= 0, "homePlacement.neighbourhoodHops must be >= 0");
        require(prob(hp.qualityToleranceFraction()),
                "homePlacement.qualityToleranceFraction must be in [0,1]");
        require(hp.homeBiome() != null && !hp.homeBiome().isBlank(),
                "homePlacement.homeBiome must be set");
        require(hp.minHomePlanetCount() >= 1, "homePlacement.minHomePlanetCount must be >= 1");
        require(hp.minHomeBiomeYield() >= 0.0, "homePlacement.minHomeBiomeYield must be >= 0");
    }

    private static boolean prob(double x) {
        return x >= 0.0 && x <= 1.0;
    }

    private static boolean pct(double x) {
        return x > 0.0 && x <= 1.0;
    }

    /**
     * @return {@code true} iff {@code name} is one of the four tradeable physical
     * resources. Checked by literal name (not by importing the {@code state} enum)
     * to keep the config module decoupled from state; the set mirrors
     * {@code com.stellarcompact.engine.state.PhysicalResource}. Influence is
     * deliberately excluded - it is never market-traded (economy 02 section 1).
     */
    private static boolean isPhysicalResource(String name) {
        String n = name.trim().toUpperCase();
        return n.equals("ENERGY") || n.equals("MINERALS") || n.equals("FOOD") || n.equals("TECH");
    }

    private static <T> T req(T value, String what) {
        if (value == null) {
            throw new InvalidBalanceProfileException("missing required group: " + what);
        }
        return value;
    }

    private static void require(boolean ok, String message) {
        if (!ok) {
            throw new InvalidBalanceProfileException(message);
        }
    }

    /** Thrown when a profile is malformed, incomplete, or out of range. */
    public static final class InvalidBalanceProfileException extends RuntimeException {
        public InvalidBalanceProfileException(String message) {
            super(message);
        }

        public InvalidBalanceProfileException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
