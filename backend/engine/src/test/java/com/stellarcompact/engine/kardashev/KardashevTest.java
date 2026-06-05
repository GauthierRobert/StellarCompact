package com.stellarcompact.engine.kardashev;

import com.stellarcompact.engine.config.BalanceProfile;
import com.stellarcompact.engine.config.BalanceProfileLoader;
import com.stellarcompact.engine.state.ActiveSystem;
import com.stellarcompact.engine.state.Biome;
import com.stellarcompact.engine.state.Building;
import com.stellarcompact.engine.state.BuildingStatus;
import com.stellarcompact.engine.state.BuildingType;
import com.stellarcompact.engine.state.Coords;
import com.stellarcompact.engine.state.Faction;
import com.stellarcompact.engine.state.FactionId;
import com.stellarcompact.engine.state.GameState;
import com.stellarcompact.engine.state.GameStatus;
import com.stellarcompact.engine.state.Planet;
import com.stellarcompact.engine.state.PlanetId;
import com.stellarcompact.engine.state.ResourceBundle;
import com.stellarcompact.engine.state.SystemId;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * E12-06/07/08 — the Kardashev calculator + the tiered-tech / megastructure config.
 * Pure, deterministic: the calculator derives K from authoritative state with no
 * RNG/clock, and the climb is config-driven.
 */
class KardashevTest {

    private static String readResource(String name) {
        try (InputStream in = KardashevTest.class.getResourceAsStream("/balance/" + name)) {
            assertNotNull(in, "missing classpath resource /balance/" + name);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static BalanceProfile small() {
        return BalanceProfileLoader.parse(readResource("small-default.json"));
    }

    // ----- the K = (log10 W − 6)/10 definition -------------------------------

    @Test
    void kFormulaMatchesTheKardashevDefinition() {
        assertEquals(1.0, KardashevCalculator.kFromWatts(1e16), 1e-6, "Type I ≈ 10^16 W");
        assertEquals(2.0, KardashevCalculator.kFromWatts(1e26), 1e-6, "Type II ≈ 10^26 W");
        assertEquals(3.0, KardashevCalculator.kFromWatts(1e36), 1e-6, "Type III ≈ 10^36 W");
        assertTrue(KardashevCalculator.kFromWatts(2e26) > KardashevCalculator.kFromWatts(1e26), "monotone");
        assertEquals(0.0, KardashevCalculator.kFromWatts(0.0), "clamped at 0");
        assertEquals(0.0, KardashevCalculator.kFromWatts(100.0), "sub-10^6 W ⇒ K 0 (clamped)");
    }

    // ----- E12-07: advanced tech takes far longer than simple tech -----------

    @Test
    void advancedTechTakesFarLongerThanSimpleTech() {
        Map<String, Integer> t = small().tech().times();
        int basic = t.get("corvetteDoctrine"); // a T0 root
        assertTrue(t.get("orbitalCollectors") > basic);
        assertTrue(t.get("dysonTheory") > t.get("orbitalCollectors"));
        assertTrue(t.get("stellarMastery") > t.get("planetaryUnification"));
        assertTrue(t.get("galacticAscendancy") > t.get("stellarMastery"));
        // The K3 ascension gate is dramatically slower than a T0 root.
        assertTrue(t.get("galacticAscendancy") >= basic * 20,
                "ascension tech must dwarf basic research time");
    }

    // ----- E12-08: megastructures are configured + tech-gated -----------------

    @Test
    void megastructuresAreConfiguredAndTechGated() {
        BalanceProfile p = small();
        assertTrue(p.construction().buildTimes().containsKey("dysonSwarm"), "build time");
        assertNotNull(p.construction().costs().get("dysonSwarm"), "cost");
        assertTrue(p.kardashev().wattsPerBuilding().containsKey("dysonSwarm"), "watts");
        // dysonSwarm building is gated by the dysonTheory tech via tech.unlocks
        assertTrue(p.tech().unlocks().get("dysonTheory").contains("dysonSwarm"));
        assertTrue(BuildingType.DYSON_SWARM.isMegastructure());
        assertFalse(BuildingType.SOLAR_ARRAY.isMegastructure());
    }

    // ----- E12-06: captured watts / tier derived from state -------------------

    @Test
    void aCompletedDysonSwarmLiftsAFactionToTypeII() {
        BalanceProfile p = small();
        FactionId a = new FactionId("a");
        GameState active = stateWithBuilding(a, BuildingType.DYSON_SWARM, BuildingStatus.ACTIVE, p);
        assertEquals(2, KardashevCalculator.tier(active, a, p), "one ACTIVE Dyson Swarm ⇒ Type II");
        assertTrue(KardashevCalculator.kValue(active, a, p) >= 2.0);

        // An UNDER_CONSTRUCTION megastructure contributes nothing yet.
        GameState building = stateWithBuilding(a, BuildingType.DYSON_SWARM, BuildingStatus.UNDER_CONSTRUCTION, p);
        assertTrue(KardashevCalculator.tier(building, a, p) < 2, "in-progress megastructure does not count");
    }

    @Test
    void aBlackHoleTapReachesTypeIII() {
        BalanceProfile p = small();
        FactionId a = new FactionId("a");
        GameState s = stateWithBuilding(a, BuildingType.BLACK_HOLE_TAP, BuildingStatus.ACTIVE, p);
        assertEquals(3, KardashevCalculator.tier(s, a, p));
    }

    @Test
    void capturedWattsIsPureAndDeterministic() {
        BalanceProfile p = small();
        FactionId a = new FactionId("a");
        GameState s = stateWithBuilding(a, BuildingType.DYSON_SWARM, BuildingStatus.ACTIVE, p);
        double w1 = KardashevCalculator.capturedWatts(s, a, p);
        double w2 = KardashevCalculator.capturedWatts(s, a, p);
        assertEquals(w1, w2, "recomputing K from the same state is byte-identical");
        assertEquals(0.0, KardashevCalculator.capturedWatts(s, new FactionId("ghost"), p),
                "absent faction captures nothing");
    }

    /** A faction owning one system with one planet carrying one building of the given type/status. */
    private static GameState stateWithBuilding(FactionId owner, BuildingType type,
                                               BuildingStatus status, BalanceProfile p) {
        Building b = new Building(0, type, status, 0);
        Planet planet = new Planet(new PlanetId("p1"), Biome.OCEANIC, 4, 1000, List.of(b));
        ActiveSystem sys = new ActiveSystem(new SystemId("s1"), "Sys", new Coords(0, 0),
                Optional.of(owner), List.of(planet), 1000, 1.0);
        Faction f = new Faction(owner, "F", 0.0, new ResourceBundle(0, 0, 0, 0, 0), Map.of());
        return new GameState(1L, 0L, GameStatus.RUNNING, p.name(), p.version(),
                Map.of(owner, f), Map.of(sys.id(), sys),
                Map.of(), Map.of(), Map.of(), Map.of(), Set.of());
    }
}
