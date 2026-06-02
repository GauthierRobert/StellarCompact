package com.stellarcompact.engine.resolve;

import com.stellarcompact.engine.config.BalanceProfile;
import com.stellarcompact.engine.config.BalanceProfileLoader;
import com.stellarcompact.engine.hash.GoldenStateHash;
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
import com.stellarcompact.engine.state.PlanetId;
import com.stellarcompact.engine.state.Planet;
import com.stellarcompact.engine.state.ResourceBundle;
import com.stellarcompact.engine.state.Ship;
import com.stellarcompact.engine.state.SystemId;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Golden, hand-authored tests for the E1-06 economy resolution (production,
 * upkeep, deficit attrition, population dynamics). Every number asserted here is
 * derived from the shipped {@code small-default} balance profile, so the tests
 * also guard that the engine reads the profile rather than hardcoding constants.
 *
 * <p>The economy step is pure arithmetic (no RNG), so the assertions are exact
 * post-state values plus a state-hash determinism check.
 */
class EconomyResolutionTest {

    private static final FactionId ALPHA = new FactionId("alpha");
    private static final SystemId SYS = new SystemId("sys-1");
    private static final FleetId FLEET = new FleetId("fleet-1");

    private static final BalanceProfile SMALL = loadSmallDefault();

    private static BalanceProfile loadSmallDefault() {
        try (InputStream in = EconomyResolutionTest.class.getResourceAsStream("/balance/small-default.json")) {
            if (in == null) {
                throw new IllegalStateException("missing /balance/small-default.json");
            }
            return BalanceProfileLoader.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // ---- fixture builders ----------------------------------------------------

    private static Building active(int slot, BuildingType type) {
        return new Building(slot, type, BuildingStatus.ACTIVE, 0);
    }

    private static Planet planet(Biome biome, long pop, List<Building> buildings) {
        return new Planet(new PlanetId("p-1"), biome, 8, pop, buildings);
    }

    private static GameState stateWith(ResourceBundle stockpile, Planet planet, List<Fleet> fleets) {
        ActiveSystem system = new ActiveSystem(SYS, "Sys 1", new Coords(1, 1),
                Optional.of(ALPHA), List.of(planet), planet.population(), 1.0);
        Faction alpha = new Faction(ALPHA, "Alpha", 0.0, stockpile, Map.of());
        Map<FleetId, Fleet> fleetMap = fleets.isEmpty()
                ? Map.of()
                : Map.of(FLEET, fleets.get(0));
        return new GameState(7L, 3L, GameStatus.RUNNING, "small-default", 1,
                Map.of(ALPHA, alpha), Map.of(SYS, system), fleetMap,
                Map.of(), Map.of(), Map.of());
    }

    private static GameState resolve(GameState before) {
        return Resolver.resolve(before, List.of(), SMALL, before.gameSeed());
    }

    private static Faction alphaOf(GameState s) {
        return s.factions().get(ALPHA);
    }

    private static Planet planetOf(GameState s) {
        return s.systems().get(SYS).planets().get(0);
    }

    // ---- (a) surplus growth --------------------------------------------------

    @Test
    void surplusProducesAndGrowsPopulation() {
        // TERRAN base = energy 1, minerals 1, food 4. pop 4 -> popFactor 1.4.
        // FARM -> food 4*1.4 = 5.6; MINE -> minerals 1*1.4 = 1.4.
        // Upkeep: farm energy 1 + mine energy 1 = energy 2.
        Planet p = planet(Biome.TERRAN, 4,
                List.of(active(0, BuildingType.FARM), active(1, BuildingType.MINE)));
        ResourceBundle stock = new ResourceBundle(100, 100, 100, 100, 100);
        GameState after = resolve(stateWith(stock, p, List.of()));

        ResourceBundle s = alphaOf(after).stockpiles();
        assertEquals(98.0, s.energy(), 1e-9, "energy = 100 - 2 upkeep");
        assertEquals(101.4, s.minerals(), 1e-9, "minerals = 100 + 1.4");
        assertEquals(105.6, s.food(), 1e-9, "food = 100 + 5.6");

        // No food deficit -> growth = floor((4+1)*0.25) = 1; cap = base 5 + farm 3 = 8.
        assertEquals(5L, planetOf(after).population(), "population grows by 1");
    }

    @Test
    void populationIsCappedByBaseCapPlusBuildings() {
        // pop already at cap (5 base + 3 farm = 8): growth must not exceed cap.
        Planet p = planet(Biome.TERRAN, 8, List.of(active(0, BuildingType.FARM)));
        ResourceBundle stock = new ResourceBundle(100, 100, 100, 100, 100);
        GameState after = resolve(stateWith(stock, p, List.of()));
        assertEquals(8L, planetOf(after).population(), "population stays at cap");
    }

    // ---- (b) deficit attrition ----------------------------------------------

    @Test
    void deficitFloorsStockpileAndAttritesFleet() {
        // FROZEN MINE produces minerals 1 (pop 0 -> popFactor 1). Upkeep: mine
        // energy 1 + 10 corvettes (energy 1, food 1 each) = energy 11, food 10.
        // Stockpile all zero -> energy & food deficit; minerals net +1.
        Planet p = planet(Biome.FROZEN, 0, List.of(active(0, BuildingType.MINE)));
        Fleet fleet = new Fleet(FLEET, ALPHA, Optional.of(SYS), Optional.empty(),
                FleetStance.BALANCED, List.of(new Ship("corvette", 10)));
        GameState after = resolve(stateWith(ResourceBundle.ZERO, p, List.of(fleet)));

        ResourceBundle s = alphaOf(after).stockpiles();
        assertEquals(0.0, s.energy(), 1e-9, "energy floored at zero, never negative");
        assertEquals(0.0, s.food(), 1e-9, "food floored at zero, never negative");
        assertEquals(1.0, s.minerals(), 1e-9, "minerals credited 1");

        // deficitAttritionRate 0.10 -> ceil(10*0.10) = 1 lost -> 9 remain.
        int remaining = after.fleets().get(FLEET).ships().get(0).count();
        assertEquals(9, remaining, "fleet attrites under deficit");
    }

    @Test
    void noNegativeBalanceEvenWithLargeUpkeep() {
        // A single SOLAR_ARRAY produces no upkeep but 50 capitals starve the food.
        Planet p = planet(Biome.DESERT, 0, List.of(active(0, BuildingType.SOLAR_ARRAY)));
        Fleet fleet = new Fleet(FLEET, ALPHA, Optional.of(SYS), Optional.empty(),
                FleetStance.BALANCED, List.of(new Ship("capital", 50)));
        GameState after = resolve(stateWith(ResourceBundle.ZERO, p, List.of(fleet)));
        ResourceBundle s = alphaOf(after).stockpiles();
        assertTrue(s.energy() >= 0 && s.minerals() >= 0 && s.food() >= 0
                && s.tech() >= 0 && s.influence() >= 0, "no component may go negative");
    }

    // ---- (c) population dynamics ---------------------------------------------

    @Test
    void famineShrinksPopulationOnFoodDeficit() {
        // No farm, no food production; a hungry fleet forces a food deficit so the
        // planet's population declines: ceil(10 * 0.5) = 5 -> pop 5.
        Planet p = planet(Biome.ARID, 10, List.of(active(0, BuildingType.MINE)));
        Fleet fleet = new Fleet(FLEET, ALPHA, Optional.of(SYS), Optional.empty(),
                FleetStance.BALANCED, List.of(new Ship("corvette", 20)));
        GameState after = resolve(stateWith(new ResourceBundle(100, 100, 0, 100, 0), p, List.of(fleet)));
        assertEquals(5L, planetOf(after).population(), "famine halves the population (rate 0.5)");
    }

    @Test
    void idleAndUnderConstructionBuildingsNeitherProduceNorConsume() {
        // MINE at progress 0 (buildTime 3): the E1-08 development sweep advances it to
        // 1, still UNDER_CONSTRUCTION, so it neither produces nor consumes this tick;
        // the IDLE farm likewise contributes nothing.
        Planet p = planet(Biome.TERRAN, 0, List.of(
                new Building(0, BuildingType.FARM, BuildingStatus.IDLE, 0),
                new Building(1, BuildingType.MINE, BuildingStatus.UNDER_CONSTRUCTION, 0)));
        ResourceBundle stock = new ResourceBundle(100, 100, 100, 100, 100);
        GameState after = resolve(stateWith(stock, p, List.of()));
        ResourceBundle s = alphaOf(after).stockpiles();
        assertEquals(100.0, s.energy(), 1e-9);
        assertEquals(100.0, s.minerals(), 1e-9);
        assertEquals(100.0, s.food(), 1e-9);
        // And the building is still under construction (advanced 0 -> 1, not yet ACTIVE).
        Building mine = planetOf(after).buildings().stream()
                .filter(b -> b.type() == BuildingType.MINE).findFirst().orElseThrow();
        assertEquals(BuildingStatus.UNDER_CONSTRUCTION, mine.status());
        assertEquals(1, mine.progress());
    }

    // ---- determinism ---------------------------------------------------------

    @Test
    void economyResolutionIsDeterministicAcrossRuns() {
        Planet p = planet(Biome.TERRAN, 4,
                List.of(active(0, BuildingType.FARM), active(1, BuildingType.MINE)));
        Fleet fleet = new Fleet(FLEET, ALPHA, Optional.of(SYS), Optional.empty(),
                FleetStance.BALANCED, List.of(new Ship("corvette", 7)));
        GameState before = stateWith(new ResourceBundle(50, 50, 50, 50, 50), p, List.of(fleet));
        String a = GoldenStateHash.sha256Hex(resolve(before));
        String b = GoldenStateHash.sha256Hex(resolve(before));
        assertEquals(a, b, "same state + profile -> byte-identical resolution");
    }
}
