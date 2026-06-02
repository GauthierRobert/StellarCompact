package com.stellarcompact.orchestrator.tick;

import com.stellarcompact.engine.config.BalanceProfile;
import com.stellarcompact.engine.state.ActiveSystem;
import com.stellarcompact.engine.state.Biome;
import com.stellarcompact.engine.state.Coords;
import com.stellarcompact.engine.state.Faction;
import com.stellarcompact.engine.state.FactionId;
import com.stellarcompact.engine.state.FleetId;
import com.stellarcompact.engine.state.GameState;
import com.stellarcompact.engine.state.GameStatus;
import com.stellarcompact.engine.state.MarketOrderId;
import com.stellarcompact.engine.state.Planet;
import com.stellarcompact.engine.state.PlanetId;
import com.stellarcompact.engine.state.ResourceBundle;
import com.stellarcompact.engine.state.RouteId;
import com.stellarcompact.engine.state.SystemId;
import com.stellarcompact.engine.state.TechId;
import com.stellarcompact.engine.state.TechProgress;
import com.stellarcompact.engine.state.Treaty;
import com.stellarcompact.engine.state.TreatyId;
import com.stellarcompact.engine.state.Fleet;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Self-contained, deterministic GameState + BalanceProfile builders for the
 * {@link TickOrchestrator} tests. No randomness, no I/O. Mirrors the {@code sovereign}
 * package fixtures but lives here (those are package-private) so this test set is
 * independent.
 */
final class TickFixtures {

    static final FactionId ALPHA = new FactionId("alpha");
    static final FactionId BETA = new FactionId("beta");

    private TickFixtures() {
    }

    private static ResourceBundle rich() {
        return new ResourceBundle(1000, 1000, 1000, 1000, 1000);
    }

    private static Faction faction(FactionId id) {
        return new Faction(id, "F-" + id.value(), 0.0, rich(), Map.<TechId, TechProgress>of());
    }

    private static Planet emptyPlanet(PlanetId id, int slots) {
        return new Planet(id, Biome.TERRAN, slots, 100, List.of());
    }

    private static ActiveSystem ownedSystem(SystemId id, FactionId owner, List<Planet> planets) {
        return new ActiveSystem(id, "name-" + id.value(), new Coords(0, 0),
                java.util.Optional.of(owner), planets, 100, 1.0);
    }

    private static GameState build(Map<FactionId, Faction> factions,
                                   Map<SystemId, ActiveSystem> systems) {
        return new GameState(
                42L, 5L, GameStatus.RUNNING, "small-default", 1,
                factions, systems,
                Map.<FleetId, Fleet>of(),
                Map.<TreatyId, Treaty>of(),
                Map.<RouteId, com.stellarcompact.engine.state.Route>of(),
                Map.<MarketOrderId, com.stellarcompact.engine.state.MarketOrder>of(),
                Set.<com.stellarcompact.engine.state.WarState>of());
    }

    /**
     * ALPHA and BETA each own a system with a 3-slot empty planet and rich stockpiles,
     * so a {@link com.stellarcompact.orchestrator.sovereign.ScriptedSovereign} emits a
     * MINE build for each - a non-Hold action whose effect makes the resolved-state hash
     * a meaningful determinism witness.
     */
    static GameState buildableTwoFactionState() {
        Map<FactionId, Faction> factions = new LinkedHashMap<>();
        factions.put(ALPHA, faction(ALPHA));
        factions.put(BETA, faction(BETA));
        Map<SystemId, ActiveSystem> systems = new LinkedHashMap<>();
        systems.put(new SystemId("sysA"),
                ownedSystem(new SystemId("sysA"), ALPHA, List.of(emptyPlanet(new PlanetId("planetA"), 3))));
        systems.put(new SystemId("sysB"),
                ownedSystem(new SystemId("sysB"), BETA, List.of(emptyPlanet(new PlanetId("planetB"), 3))));
        return build(factions, systems);
    }

    /**
     * N factions each owning one bare system - enough for the load test (the bots all
     * sleep, so no buildable detail is needed; one owned system keeps each faction a
     * valid seat the WorldView builder can project).
     */
    static GameState nFactionState(List<FactionId> ids) {
        Map<FactionId, Faction> factions = new LinkedHashMap<>();
        Map<SystemId, ActiveSystem> systems = new LinkedHashMap<>();
        for (FactionId id : ids) {
            factions.put(id, faction(id));
            SystemId sys = new SystemId("sys-" + id.value());
            systems.put(sys, ownedSystem(sys, id, List.of(emptyPlanet(new PlanetId("p-" + id.value()), 1))));
        }
        return build(factions, systems);
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
