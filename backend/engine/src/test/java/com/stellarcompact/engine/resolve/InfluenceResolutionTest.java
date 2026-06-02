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
import com.stellarcompact.engine.state.GameState;
import com.stellarcompact.engine.state.GameStatus;
import com.stellarcompact.engine.state.PhysicalResource;
import com.stellarcompact.engine.state.Planet;
import com.stellarcompact.engine.state.PlanetId;
import com.stellarcompact.engine.state.ResourceBundle;
import com.stellarcompact.engine.state.Route;
import com.stellarcompact.engine.state.RouteId;
import com.stellarcompact.engine.state.RouteKind;
import com.stellarcompact.engine.state.RouteStatus;
import com.stellarcompact.engine.state.Treaty;
import com.stellarcompact.engine.state.TreatyId;
import com.stellarcompact.engine.state.TreatyStatus;
import com.stellarcompact.engine.state.TreatyType;
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

/**
 * Golden, hand-authored tests for the E1-14 INFLUENCE resolution (accrual from
 * capitals, monuments, trade volume and honoured treaties, then proportional decay).
 * Every number asserted here is derived from the shipped {@code small-default} balance
 * profile (the {@code influence} block: perCapitalSystem 1.0, perMonument 3.0,
 * perTradeVolume 0.5, perActiveTreaty 0.5, decayRate 0.02), so the tests also guard
 * that the engine reads the profile rather than hardcoding constants (rule 6).
 *
 * <p>The influence step is pure arithmetic (no RNG), so assertions are exact
 * post-state values plus a state-hash determinism check. Each accrual-source test
 * exercises {@link InfluenceResolution#resolve} in isolation so production/upkeep does
 * not perturb the Influence component; one test runs the whole {@link Resolver} to
 * prove the step is wired into its fixed slot.
 */
class InfluenceResolutionTest {

    private static final FactionId ALPHA = new FactionId("alpha");
    private static final FactionId BETA = new FactionId("beta");

    private static final BalanceProfile SMALL = loadSmallDefault();

    private static BalanceProfile loadSmallDefault() {
        try (InputStream in = InfluenceResolutionTest.class.getResourceAsStream("/balance/small-default.json")) {
            if (in == null) {
                throw new IllegalStateException("missing /balance/small-default.json");
            }
            return BalanceProfileLoader.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // ---- fixture builders ----------------------------------------------------

    private static Faction faction(FactionId id, double influence) {
        return new Faction(id, "F-" + id.value(), 0.0,
                new ResourceBundle(0, 0, 0, 0, influence), Map.of());
    }

    private static com.stellarcompact.engine.state.SystemId sysId(String v) {
        return new com.stellarcompact.engine.state.SystemId(v);
    }

    private static ActiveSystem ownedSystem(String id, FactionId owner, List<Building> buildings) {
        Planet planet = new Planet(new PlanetId("p-" + id), Biome.TERRAN, 8, 0, buildings);
        return new ActiveSystem(sysId(id), "Sys " + id, new Coords(1, 1),
                Optional.of(owner), List.of(planet), 0, 1.0);
    }

    private static Building active(int slot, BuildingType type) {
        return new Building(slot, type, BuildingStatus.ACTIVE, 0);
    }

    private static double influenceOf(GameState s, FactionId id) {
        return s.factions().get(id).stockpiles().influence();
    }

    // ---- (a) accrual: capitals ------------------------------------------------

    @Test
    void capitalsAccrueFlatInfluencePerOwnedSystem() {
        // ALPHA owns two systems (no monuments/routes/treaties). perCapitalSystem 1.0
        // -> +2.0 accrual; start 0 -> 2.0; decay 0.02 -> 2.0 * 0.98 = 1.96.
        ActiveSystem s1 = ownedSystem("s1", ALPHA, List.of());
        ActiveSystem s2 = ownedSystem("s2", ALPHA, List.of());
        GameState state = new GameState(7L, 3L, GameStatus.RUNNING, "small-default", 1,
                Map.of(ALPHA, faction(ALPHA, 0.0)),
                Map.of(s1.id(), s1, s2.id(), s2),
                Map.of(), Map.of(), Map.of(), Map.of(), Set.of());

        GameState after = InfluenceResolution.resolve(state, SMALL);
        assertEquals(1.96, influenceOf(after, ALPHA), 1e-9,
                "2 capitals x 1.0 = 2.0 accrual, then x0.98 decay");
    }

    // ---- (b) accrual: monuments ----------------------------------------------

    @Test
    void monumentsAccruePerActiveMonument() {
        // One owned system with two ACTIVE monuments + one UNDER_CONSTRUCTION (ignored).
        // capitals: 1 x 1.0 = 1.0; monuments: 2 x 3.0 = 6.0; total 7.0; x0.98 = 6.86.
        ActiveSystem s = ownedSystem("s1", ALPHA, List.of(
                active(0, BuildingType.MONUMENT),
                active(1, BuildingType.MONUMENT),
                new Building(2, BuildingType.MONUMENT, BuildingStatus.UNDER_CONSTRUCTION, 0)));
        GameState state = new GameState(7L, 3L, GameStatus.RUNNING, "small-default", 1,
                Map.of(ALPHA, faction(ALPHA, 0.0)),
                Map.of(s.id(), s),
                Map.of(), Map.of(), Map.of(), Map.of(), Set.of());

        GameState after = InfluenceResolution.resolve(state, SMALL);
        assertEquals(6.86, influenceOf(after, ALPHA), 1e-9,
                "1 capital + 2 active monuments (the under-construction one earns nothing)");
    }

    // ---- (c) accrual: trade volume -------------------------------------------

    @Test
    void tradeVolumeAccruesAndBlockadeScalesItDown() {
        // ALPHA owns one capital system and two routes: an ACTIVE route volume 10 and a
        // BLOCKADED route volume 10. perTradeVolume 0.5.
        //   capital:   1 x 1.0 = 1.0
        //   active:    10 x 0.5 = 5.0
        //   blockaded: 10 x (1 - 0.75) x 0.5 = 1.25   (blockadeThroughputFactor 0.75)
        //   total 7.25; x0.98 = 7.105.
        ActiveSystem cap = ownedSystem("s1", ALPHA, List.of());
        var sysB = sysId("s2");
        Route activeRoute = new Route(new RouteId("r-active"), ALPHA, cap.id(), sysB,
                RouteKind.COMMERCIAL, List.of(PhysicalResource.MINERALS), 10.0, RouteStatus.ACTIVE);
        Route blockaded = new Route(new RouteId("r-blockaded"), ALPHA, cap.id(), sysB,
                RouteKind.COMMERCIAL, List.of(PhysicalResource.ENERGY), 10.0, RouteStatus.BLOCKADED);
        GameState state = new GameState(7L, 3L, GameStatus.RUNNING, "small-default", 1,
                Map.of(ALPHA, faction(ALPHA, 0.0)),
                Map.of(cap.id(), cap),
                Map.of(),
                Map.of(),
                Map.of(activeRoute.id(), activeRoute, blockaded.id(), blockaded),
                Map.of(), Set.of());

        GameState after = InfluenceResolution.resolve(state, SMALL);
        assertEquals(7.105, influenceOf(after, ALPHA), 1e-9,
                "capital + active route + down-scaled blockaded route, then decay");
    }

    // ---- (d) accrual: honoured diplomacy (active treaties) -------------------

    @Test
    void activeTreatiesAccrueButNonActiveDoNot() {
        // ALPHA and BETA each own one capital. One ACTIVE treaty involving both, plus a
        // PROPOSED treaty (must NOT accrue). perActiveTreaty 0.5.
        //   ALPHA: capital 1.0 + active-treaty 0.5 = 1.5; x0.98 = 1.47.
        ActiveSystem capA = ownedSystem("s1", ALPHA, List.of());
        ActiveSystem capB = ownedSystem("s2", BETA, List.of());
        Treaty activeTreaty = new Treaty(new TreatyId("t-active"), TreatyType.ALLIANCE,
                List.of(ALPHA, BETA), Map.of(), 1L, Long.MAX_VALUE, TreatyStatus.ACTIVE);
        Treaty proposed = new Treaty(new TreatyId("t-proposed"), TreatyType.TRADE_PACT,
                List.of(ALPHA, BETA), Map.of(), 1L, Long.MAX_VALUE, TreatyStatus.PROPOSED);
        GameState state = new GameState(7L, 3L, GameStatus.RUNNING, "small-default", 1,
                Map.of(ALPHA, faction(ALPHA, 0.0), BETA, faction(BETA, 0.0)),
                Map.of(capA.id(), capA, capB.id(), capB),
                Map.of(),
                Map.of(activeTreaty.id(), activeTreaty, proposed.id(), proposed),
                Map.of(), Map.of(), Set.of());

        GameState after = InfluenceResolution.resolve(state, SMALL);
        assertEquals(1.47, influenceOf(after, ALPHA), 1e-9,
                "1 capital + 1 active treaty (proposed treaty earns nothing), then decay");
        assertEquals(1.47, influenceOf(after, BETA), 1e-9,
                "the other signatory accrues the same active-treaty influence");
    }

    // ---- (e) decay ------------------------------------------------------------

    @Test
    void decayBleedsAnIdleStockpileWithNoSources() {
        // No systems/routes/treaties => zero accrual; an existing 100 influence just
        // decays: 100 x (1 - 0.02) = 98.0.
        GameState state = new GameState(7L, 3L, GameStatus.RUNNING, "small-default", 1,
                Map.of(ALPHA, faction(ALPHA, 100.0)),
                Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Set.of());
        GameState after = InfluenceResolution.resolve(state, SMALL);
        assertEquals(98.0, influenceOf(after, ALPHA), 1e-9, "idle influence decays by 2%");
    }

    @Test
    void influenceIsTheOnlyComponentTouched() {
        // The four physical resources must be left exactly as-is; only influence moves.
        ActiveSystem cap = ownedSystem("s1", ALPHA, List.of());
        Faction alpha = new Faction(ALPHA, "Alpha", 0.0,
                new ResourceBundle(11, 22, 33, 44, 0), Map.of());
        GameState state = new GameState(7L, 3L, GameStatus.RUNNING, "small-default", 1,
                Map.of(ALPHA, alpha), Map.of(cap.id(), cap),
                Map.of(), Map.of(), Map.of(), Map.of(), Set.of());
        ResourceBundle s = InfluenceResolution.resolve(state, SMALL).factions().get(ALPHA).stockpiles();
        assertEquals(11.0, s.energy(), 1e-9);
        assertEquals(22.0, s.minerals(), 1e-9);
        assertEquals(33.0, s.food(), 1e-9);
        assertEquals(44.0, s.tech(), 1e-9);
        assertEquals(0.98, s.influence(), 1e-9, "1 capital x 1.0, then x0.98 decay");
    }

    // ---- (f) wiring + determinism --------------------------------------------

    @Test
    void influenceStepIsWiredIntoTheResolver() {
        // Full resolver pass with no actions: the INFLUENCE step still runs in its slot.
        // System has no buildings so production credits nothing; only influence accrues.
        ActiveSystem cap = ownedSystem("s1", ALPHA, List.of());
        GameState state = new GameState(7L, 3L, GameStatus.RUNNING, "small-default", 1,
                Map.of(ALPHA, faction(ALPHA, 0.0)),
                Map.of(cap.id(), cap),
                Map.of(), Map.of(), Map.of(), Map.of(), Set.of());
        GameState after = Resolver.resolve(state, List.of(), SMALL, state.gameSeed());
        assertEquals(0.98, influenceOf(after, ALPHA), 1e-9,
                "INFLUENCE step runs in its fixed slot under the full resolver");
    }

    @Test
    void influenceResolutionIsDeterministicAcrossRuns() {
        ActiveSystem cap = ownedSystem("s1", ALPHA, List.of(active(0, BuildingType.MONUMENT)));
        var sysB = sysId("s2");
        Route route = new Route(new RouteId("r1"), ALPHA, cap.id(), sysB,
                RouteKind.COMMERCIAL, List.of(PhysicalResource.MINERALS), 7.0, RouteStatus.ACTIVE);
        Treaty treaty = new Treaty(new TreatyId("t1"), TreatyType.ALLIANCE,
                List.of(ALPHA, BETA), Map.of(), 1L, Long.MAX_VALUE, TreatyStatus.ACTIVE);
        GameState before = new GameState(7L, 3L, GameStatus.RUNNING, "small-default", 1,
                Map.of(ALPHA, faction(ALPHA, 42.0)),
                Map.of(cap.id(), cap),
                Map.of(),
                Map.of(treaty.id(), treaty),
                Map.of(route.id(), route),
                Map.of(), Set.of());
        String a = GoldenStateHash.sha256Hex(InfluenceResolution.resolve(before, SMALL));
        String b = GoldenStateHash.sha256Hex(InfluenceResolution.resolve(before, SMALL));
        assertEquals(a, b, "same state + profile -> byte-identical influence resolution");
    }
}
