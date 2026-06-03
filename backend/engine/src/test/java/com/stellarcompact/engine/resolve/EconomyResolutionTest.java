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
                Map.of(), Map.of(), Map.of(), java.util.Set.of());
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
        // Stockpile all zero -> energy & food deficit; minerals net +1, but the
        // E10-04 energy brownout (factor 0.5) now halves gross production under the
        // energy deficit, so the mine credits 1 * 0.5 = 0.5 minerals (F2: energy is a
        // real constraint, not cosmetic).
        Planet p = planet(Biome.FROZEN, 0, List.of(active(0, BuildingType.MINE)));
        Fleet fleet = new Fleet(FLEET, ALPHA, Optional.of(SYS), Optional.empty(),
                FleetStance.BALANCED, List.of(new Ship("corvette", 10)));
        GameState after = resolve(stateWith(ResourceBundle.ZERO, p, List.of(fleet)));

        ResourceBundle s = alphaOf(after).stockpiles();
        assertEquals(0.0, s.energy(), 1e-9, "energy floored at zero, never negative");
        assertEquals(0.0, s.food(), 1e-9, "food floored at zero, never negative");
        assertEquals(0.5, s.minerals(), 1e-9, "minerals credited 1, browned out to 0.5");

        // deficitAttritionRate 0.10 -> ceil(10*0.10) = 1 lost -> 9 remain.
        int remaining = after.fleets().get(FLEET).ships().get(0).count();
        assertEquals(9, remaining, "fleet attrites under deficit");
    }

    // ---- (d) E10-04 mineral sink (mine taper, F3) -----------------------------

    @Test
    void mineTaperEngagesPastSoftCapAndBoundsPerPlanetMineYield() {
        // VOLCANIC mine base minerals = 5. softCap = 3, taperFactor = 0.5. Five ACTIVE
        // mines, pop 0 -> popFactor 1, no tech. Energy: VOLCANIC base 3 per mine (no
        // SOLAR_ARRAY producer here, but mines themselves yield no energy) -> energy
        // upkeep = 5 mines * 1 = 5; with a big energy stockpile there is no brownout,
        // isolating the taper. Expected minerals:
        //   mines 0,1,2 (within cap): 5 + 5 + 5 = 15
        //   mine 3 (k=0 past cap): 5 * 0.5^1 = 2.5
        //   mine 4 (k=1 past cap): 5 * 0.5^2 = 1.25
        //   total = 18.75  (vs 25 with no taper -> the sink bites)
        Planet p = planet(Biome.VOLCANIC, 0, List.of(
                active(0, BuildingType.MINE), active(1, BuildingType.MINE),
                active(2, BuildingType.MINE), active(3, BuildingType.MINE),
                active(4, BuildingType.MINE)));
        ResourceBundle stock = new ResourceBundle(1000, 1000, 1000, 1000, 1000);
        GameState after = resolve(stateWith(stock, p, List.of()));

        ResourceBundle s = alphaOf(after).stockpiles();
        // energy = 1000 - 5 upkeep (5 mines * 1.0); VOLCANIC mines yield no energy.
        assertEquals(995.0, s.energy(), 1e-9, "energy = 1000 - 5 mine upkeep");
        assertEquals(1018.75, s.minerals(), 1e-9, "minerals = 1000 + tapered 18.75 (sink engaged)");
    }

    @Test
    void minesWithinSoftCapAreNotTapered() {
        // Three VOLCANIC mines exactly at the softCap (3) -> all full: 5*3 = 15.
        Planet p = planet(Biome.VOLCANIC, 0, List.of(
                active(0, BuildingType.MINE), active(1, BuildingType.MINE),
                active(2, BuildingType.MINE)));
        ResourceBundle stock = new ResourceBundle(1000, 1000, 1000, 1000, 1000);
        GameState after = resolve(stateWith(stock, p, List.of()));
        assertEquals(1015.0, alphaOf(after).stockpiles().minerals(), 1e-9,
                "three mines at the soft cap yield full 15, no taper");
    }

    // ---- (e) E10-04 energy-deficit brownout (F2) ------------------------------

    @Test
    void energyDeficitBrownsOutAllProduction() {
        // ARID base: energy 1, minerals 4, food 1. A MINE + a FARM, pop 0. Mine upkeep
        // energy 1 + farm upkeep energy 1 = 2 energy owed. Pre-production energy
        // stockpile 0 (and ARID mine/farm produce no energy that could pre-empt it,
        // and brownout is decided pre-production anyway) -> energy deficit -> brownout.
        // Gross: minerals 4 (mine), food 1 (farm). Browned out by 0.5 -> minerals 2,
        // food 0.5. Energy stockpile floors at 0.
        Planet p = planet(Biome.ARID, 0, List.of(
                active(0, BuildingType.MINE), active(1, BuildingType.FARM)));
        GameState after = resolve(stateWith(new ResourceBundle(0, 100, 100, 0, 0), p, List.of()));
        ResourceBundle s = alphaOf(after).stockpiles();
        assertEquals(0.0, s.energy(), 1e-9, "energy floored at zero under deficit");
        assertEquals(102.0, s.minerals(), 1e-9, "minerals 4 browned out to 2 (factor 0.5)");
        assertEquals(100.5, s.food(), 1e-9, "food 1 browned out to 0.5 (factor 0.5)");
    }

    @Test
    void energySurplusProducesFullOutputNoBrownout() {
        // Same planet but a fat energy stockpile covers upkeep -> no deficit, no
        // brownout: full minerals 4, full food 1.
        Planet p = planet(Biome.ARID, 0, List.of(
                active(0, BuildingType.MINE), active(1, BuildingType.FARM)));
        GameState after = resolve(stateWith(new ResourceBundle(100, 100, 100, 0, 0), p, List.of()));
        ResourceBundle s = alphaOf(after).stockpiles();
        assertEquals(98.0, s.energy(), 1e-9, "energy = 100 - 2 upkeep");
        assertEquals(104.0, s.minerals(), 1e-9, "minerals full 4, no brownout");
        assertEquals(101.0, s.food(), 1e-9, "food full 1, no brownout");
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
