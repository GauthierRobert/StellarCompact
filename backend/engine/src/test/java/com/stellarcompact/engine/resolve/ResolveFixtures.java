package com.stellarcompact.engine.resolve;

import com.stellarcompact.engine.config.BalanceProfile;
import com.stellarcompact.engine.state.ActiveSystem;
import com.stellarcompact.engine.state.Coords;
import com.stellarcompact.engine.state.Faction;
import com.stellarcompact.engine.state.FactionId;
import com.stellarcompact.engine.state.GameState;
import com.stellarcompact.engine.state.GameStatus;
import com.stellarcompact.engine.state.ResourceBundle;
import com.stellarcompact.engine.state.SystemId;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Hand-authored, deterministic fixtures for the {@link Resolver} tests - pure
 * builders, no randomness, no I/O, no wall-clock.
 */
final class ResolveFixtures {

    static final FactionId ALPHA = new FactionId("alpha");
    static final FactionId BETA = new FactionId("beta");
    static final FactionId GAMMA = new FactionId("gamma");
    static final SystemId SYS_A = new SystemId("sysA");

    private ResolveFixtures() {
    }

    static ResourceBundle rich() {
        return new ResourceBundle(1000, 1000, 1000, 1000, 1000);
    }

    static Faction faction(FactionId id) {
        return new Faction(id, "F-" + id.value(), 0.0, rich(), Map.of());
    }

    /** Three factions, one neutral system; enough to drive ordering/wiring tests. */
    static GameState baseState() {
        ActiveSystem sysA = new ActiveSystem(SYS_A, "name-A", new Coords(0, 0),
                Optional.empty(), List.of(), 0, 1.0);
        return new GameState(
                42L, 5L, GameStatus.RUNNING, "small-default", 1,
                Map.of(ALPHA, faction(ALPHA), BETA, faction(BETA), GAMMA, faction(GAMMA)),
                Map.of(SYS_A, sysA),
                Map.of(), Map.of(), Map.of(), Map.of(), java.util.Set.of());
    }

    static BalanceProfile profile() {
        return new BalanceProfile(
                "small-default", 1,
                new BalanceProfile.Resources(Map.of(), Map.of(), 0.1),
                new BalanceProfile.Population(1.0, 1.0, 0.1, 5, Map.of()),
                new BalanceProfile.Market("priceTimePriority", 0.5, "ENERGY"),
                new BalanceProfile.Construction(
                        Map.of("mine", 3),
                        Map.of("mine", new BalanceProfile.ResourceBundle(0, 50, 0, 0, 0)),
                        Map.of("toxic", "arid")),
                new BalanceProfile.Combat(Map.of("scout", 1.0), List.of(0.8, 1.2), 0.2, 0.3, 0.1),
                new BalanceProfile.Movement(0.5, true),
                new BalanceProfile.Tech(Map.of("warpDrive", 100.0), Map.of("warpDrive", 5),
                        Map.of("warpDrive", 1.5), Map.of(), Map.of()),
                new BalanceProfile.Diplomacy(
                        new BalanceProfile.Reputation(1.0, 1.0, 1.0, 1.0), Map.of()),
                new BalanceProfile.Victory(
                        new BalanceProfile.Domination(0.6),
                        new BalanceProfile.Economic(1000, 50),
                        new BalanceProfile.Diplomatic(0.6),
                        new BalanceProfile.Survival(1000),
                        new BalanceProfile.Wonder(3, 50),
                        new BalanceProfile.ScoreWeights(1, 1, 1, 1, 1, 1, 1)),
                new BalanceProfile.Tick(1000, 2, 5000));
    }

    /**
     * A profile whose combat block is fully populated (E1-10): per-spec attack/defence,
     * stance modifiers, terrain, defence-platform bonus, a tight variance band and the
     * proportional loss fractions. Used by the combat resolution tests; every gameplay
     * number lives here, none in the resolver.
     */
    static BalanceProfile combatProfile() {
        BalanceProfile base = profile();
        BalanceProfile.Combat combat = new BalanceProfile.Combat(
                Map.of("scout", 0.5, "corvette", 1.0, "cruiser", 2.5, "capital", 6.0, "freighter", 0.0),
                List.of(0.4, 0.6),          // varianceBand
                1.5,                         // defensePlatformBonus
                0.5,                         // occupationLoyaltyPenalty
                1.0,                         // warExhaustionPerLoss
                Map.of("scout", 1.0, "corvette", 3.0, "cruiser", 6.0, "capital", 12.0, "freighter", 0.0),
                Map.of("scout", 1.0, "corvette", 3.0, "cruiser", 7.0, "capital", 14.0, "freighter", 1.0),
                Map.of("AGGRESSIVE", 1.3, "BALANCED", 1.0, "DEFENSIVE", 0.7, "EVASIVE", 0.5),
                Map.of("AGGRESSIVE", 0.8, "BALANCED", 1.0, "DEFENSIVE", 1.3, "EVASIVE", 1.1),
                1.25,                        // terrainDefenseMod
                0.25,                        // lossFractionWinner
                0.75);                       // lossFractionLoser
        return new BalanceProfile(
                base.name(), base.version(), base.resources(), base.population(), base.market(),
                base.construction(), combat, base.tech(), base.diplomacy(), base.victory(), base.tick());
    }

    /**
     * A profile whose diplomacy block carries the real E1-12 reputation coefficients
     * and per-treaty enforcement weights (matching {@code small-default.json}), so the
     * diplomacy resolution tests can assert exact reputation deltas. Every number lives
     * here, none in the resolver (rule 6).
     */
    static BalanceProfile diplomacyProfile() {
        BalanceProfile base = profile();
        BalanceProfile.Diplomacy diplomacy = new BalanceProfile.Diplomacy(
                new BalanceProfile.Reputation(5.0, 0.5, 15.0, 8.0),
                Map.of("ceasefire", 1.0, "nonAggression", 1.5, "tradePact", 1.0,
                        "defensivePact", 2.0, "alliance", 3.0, "vassalage", 2.5));
        return new BalanceProfile(
                base.name(), base.version(), base.resources(), base.population(), base.market(),
                base.construction(), base.combat(), base.tech(), diplomacy, base.victory(),
                base.tick());
    }
}
