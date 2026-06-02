package com.stellarcompact.orchestrator.sovereign;

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
import com.stellarcompact.engine.state.TreatyId;
import com.stellarcompact.engine.state.Treaty;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Hand-authored, deterministic GameState + BalanceProfile builders for the
 * Sovereign tests. No randomness, no I/O. Mirrors the engine test fixtures but
 * lives in this module so the orchestrator tests are self-contained.
 */
final class SovereignFixtures {

    static final FactionId ALPHA = new FactionId("alpha");
    static final FactionId BETA = new FactionId("beta");
    static final SystemId SYS_A = new SystemId("sysA");
    static final SystemId SYS_B = new SystemId("sysB");
    static final SystemId SYS_NEUTRAL = new SystemId("sysN");
    static final PlanetId PLANET_A = new PlanetId("planetA");
    static final PlanetId PLANET_B = new PlanetId("planetB");
    static final FleetId FLEET_A = new FleetId("fleetA");

    private SovereignFixtures() {
    }

    static ResourceBundle rich() {
        return new ResourceBundle(1000, 1000, 1000, 1000, 1000);
    }

    static ResourceBundle poor() {
        return new ResourceBundle(0, 0, 0, 0, 0);
    }

    static Faction faction(FactionId id, ResourceBundle stockpiles) {
        return new Faction(id, "F-" + id.value(), 0.0, stockpiles, Map.<TechId, TechProgress>of());
    }

    static Planet emptyPlanet(PlanetId id, int slots) {
        return new Planet(id, Biome.TERRAN, slots, 100, List.of());
    }

    static Planet fullPlanet(PlanetId id) {
        // A single-slot planet whose only slot is occupied -> no free slot.
        return new Planet(id, Biome.TERRAN, 1, 100,
                List.of(new Building(0, BuildingType.MINE, BuildingStatus.ACTIVE, 0)));
    }

    static ActiveSystem ownedSystem(SystemId id, FactionId owner, List<Planet> planets) {
        return new ActiveSystem(id, "name-" + id.value(), new Coords(0, 0),
                Optional.of(owner), planets, 100, 1.0);
    }

    static ActiveSystem neutralSystem(SystemId id) {
        return new ActiveSystem(id, "name-" + id.value(), new Coords(1, 1),
                Optional.empty(), List.of(), 0, 1.0);
    }

    static Fleet fleetAt(FleetId id, FactionId owner, SystemId at) {
        return new Fleet(id, owner, Optional.of(at), Optional.empty(),
                FleetStance.DEFENSIVE, List.of(new Ship("scout", 1)));
    }

    private static GameState build(Map<FactionId, Faction> factions,
                                   Map<SystemId, ActiveSystem> systems,
                                   Map<FleetId, Fleet> fleets) {
        return new GameState(
                42L, 5L, GameStatus.RUNNING, "small-default", 1,
                factions, systems, fleets,
                Map.<TreatyId, Treaty>of(),
                Map.of(),
                Map.of(),
                java.util.Set.of());
    }

    /** ALPHA rich, owns SYS_A with a 3-slot empty planet and a fleet; BETA owns SYS_B. */
    static GameState richTwoFactionState() {
        return build(
                Map.of(ALPHA, faction(ALPHA, rich()), BETA, faction(BETA, rich())),
                Map.of(SYS_A, ownedSystem(SYS_A, ALPHA, List.of(emptyPlanet(PLANET_A, 3))),
                        SYS_B, ownedSystem(SYS_B, BETA, List.of(emptyPlanet(PLANET_B, 3)))),
                Map.of(FLEET_A, fleetAt(FLEET_A, ALPHA, SYS_A)));
    }

    /** ALPHA owns one full-slot planet, has no fleet, no neutral neighbour -> Hold. */
    static GameState idleState() {
        return build(
                Map.of(ALPHA, faction(ALPHA, rich())),
                Map.of(SYS_A, ownedSystem(SYS_A, ALPHA, List.of(fullPlanet(PLANET_A)))),
                Map.of());
    }

    /** ALPHA poor (below build floor), idle fleet, and a neutral neighbour -> Explore. */
    static GameState poorWithIdleFleetAndNeutralNeighbour() {
        return build(
                Map.of(ALPHA, faction(ALPHA, poor())),
                Map.of(SYS_A, ownedSystem(SYS_A, ALPHA, List.of(emptyPlanet(PLANET_A, 3))),
                        SYS_NEUTRAL, neutralSystem(SYS_NEUTRAL)),
                Map.of(FLEET_A, fleetAt(FLEET_A, ALPHA, SYS_A)));
    }

    /** A spread of distinct states to prove the bot never throws on varied input. */
    static List<GameState> assortedStates() {
        return List.of(
                richTwoFactionState(),
                idleState(),
                poorWithIdleFleetAndNeutralNeighbour());
    }

    static BalanceProfile profile() {
        return new BalanceProfile(
                "small-default", 1,
                new BalanceProfile.Resources(Map.of(), Map.of(), 0.1),
                new BalanceProfile.Population(1.0, 0.5, 0.1, 5, Map.of("mine", 1)),
                new BalanceProfile.Market("priceTimePriority", 0.5, "ENERGY"),
                new BalanceProfile.Construction(
                        Map.of("mine", 3),
                        Map.of("mine", new BalanceProfile.ResourceBundle(0, 50, 0, 0, 0)),
                        Map.of("toxic", "arid")),
                new BalanceProfile.Combat(Map.of("scout", 1.0), List.of(0.8, 1.2), 1.2, 0.3, 0.1),
                new BalanceProfile.Movement(0.5, true),
                new BalanceProfile.Tech(Map.of("warpDrive", 100.0), Map.of("warpDrive", 5),
                        Map.of("warpDrive", 1.5), Map.of(), Map.of()),
                new BalanceProfile.Diplomacy(
                        new BalanceProfile.Reputation(1.0, 1.0, 1.0, 1.0), Map.of("nonAggression", 1.0)),
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
