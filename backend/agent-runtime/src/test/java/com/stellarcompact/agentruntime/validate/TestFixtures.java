package com.stellarcompact.agentruntime.validate;

import com.stellarcompact.engine.config.BalanceProfile;
import com.stellarcompact.engine.state.ActiveSystem;
import com.stellarcompact.engine.state.Biome;
import com.stellarcompact.engine.state.Building;
import com.stellarcompact.engine.state.BuildingStatus;
import com.stellarcompact.engine.state.BuildingType;
import com.stellarcompact.engine.state.Coords;
import com.stellarcompact.engine.state.Faction;
import com.stellarcompact.engine.state.FactionId;
import com.stellarcompact.engine.state.Fleet;
import com.stellarcompact.engine.state.FleetId;
import com.stellarcompact.engine.state.FleetStance;
import com.stellarcompact.engine.state.GameState;
import com.stellarcompact.engine.state.GameStatus;
import com.stellarcompact.engine.state.Planet;
import com.stellarcompact.engine.state.PlanetId;
import com.stellarcompact.engine.state.ResourceBundle;
import com.stellarcompact.engine.state.Ship;
import com.stellarcompact.engine.state.SystemId;
import com.stellarcompact.engine.state.TechId;
import com.stellarcompact.engine.state.TechProgress;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Minimal, deterministic engine-state fixtures for the E4-04 validation-loop tests.
 * agent-runtime cannot see the engine module's package-private test {@code Fixtures},
 * so this rebuilds just enough authoritative {@code GameState} + {@code BalanceProfile}
 * to exercise validation outcomes (a buildable owned planet, an unowned planet to fail
 * NOT_OWNED, an affordable {@code mine} cost).
 */
final class TestFixtures {

    static final FactionId ALPHA = new FactionId("alpha");
    static final FactionId BETA = new FactionId("beta");
    static final SystemId SYS_A = new SystemId("sysA");
    static final SystemId SYS_B = new SystemId("sysB");
    static final PlanetId PLANET_A = new PlanetId("planetA"); // owned by ALPHA, free slots
    static final PlanetId PLANET_B = new PlanetId("planetB"); // owned by BETA -> NOT_OWNED for ALPHA

    private TestFixtures() {
    }

    static ResourceBundle rich() {
        return new ResourceBundle(1000, 1000, 1000, 1000, 1000);
    }

    static GameState baseState() {
        Faction alpha = new Faction(ALPHA, "F-alpha", 0.0, rich(), Map.<TechId, TechProgress>of());
        Faction beta = new Faction(BETA, "F-beta", 0.0, rich(), Map.<TechId, TechProgress>of());

        Planet planetA = new Planet(PLANET_A, Biome.TERRAN, 3, 100, List.<Building>of());
        Planet planetB = new Planet(PLANET_B, Biome.TERRAN, 3, 100, List.<Building>of());
        ActiveSystem sysA = new ActiveSystem(SYS_A, "name-sysA", new Coords(0, 0),
                Optional.of(ALPHA), List.of(planetA), 100, 1.0);
        ActiveSystem sysB = new ActiveSystem(SYS_B, "name-sysB", new Coords(1, 1),
                Optional.of(BETA), List.of(planetB), 100, 1.0);

        Fleet fleetA = new Fleet(new FleetId("fleetA"), ALPHA, Optional.of(SYS_A),
                Optional.empty(), FleetStance.DEFENSIVE, List.of(new Ship("scout", 1)));

        return new GameState(
                42L, 5L, GameStatus.RUNNING, "small-default", 1,
                Map.of(ALPHA, alpha, BETA, beta),
                Map.of(SYS_A, sysA, SYS_B, sysB),
                Map.of(fleetA.id(), fleetA),
                Map.of(),
                Map.of(),
                Map.of(),
                Set.of());
    }

    /** A profile where a {@code mine} costs 50 minerals (affordable for the rich faction). */
    static BalanceProfile profile() {
        return new BalanceProfile(
                "small-default", 1,
                new BalanceProfile.Resources(Map.of(), Map.of(), 0.1),
                new BalanceProfile.Population(1.0, 0.5, 0.1, 5, Map.of()),
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

    static Building active(int slot, BuildingType type) {
        return new Building(slot, type, BuildingStatus.ACTIVE, 0);
    }
}
