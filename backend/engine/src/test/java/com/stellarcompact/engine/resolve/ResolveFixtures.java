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
                Map.of(), Map.of(), Map.of(), Map.of());
    }

    static BalanceProfile profile() {
        return new BalanceProfile(
                "small-default", 1,
                new BalanceProfile.Resources(Map.of(), Map.of(), 0.1),
                new BalanceProfile.Population(1.0, 1.0, 0.1, 5, Map.of()),
                new BalanceProfile.Market("priceTimePriority", 0.5),
                new BalanceProfile.Construction(
                        Map.of("mine", 3),
                        Map.of("mine", new BalanceProfile.ResourceBundle(0, 50, 0, 0, 0))),
                new BalanceProfile.Combat(Map.of("scout", 1.0), List.of(0.8, 1.2), 0.2, 0.3, 0.1),
                new BalanceProfile.Tech(Map.of("warpDrive", 100.0), Map.of("warpDrive", 5),
                        Map.of("warpDrive", 1.5)),
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
}
