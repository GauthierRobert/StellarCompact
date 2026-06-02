package com.stellarcompact.engine.resolve;

import com.stellarcompact.engine.action.Action;
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
import com.stellarcompact.engine.state.Planet;
import com.stellarcompact.engine.state.PlanetId;
import com.stellarcompact.engine.state.ResourceBundle;
import com.stellarcompact.engine.state.SystemId;
import com.stellarcompact.engine.state.TechId;
import com.stellarcompact.engine.state.TechProgress;
import com.stellarcompact.engine.state.TechStatus;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Golden, hand-authored tests for the E1-08 development resolution: construction
 * build-time completion, the tech DAG (prereq gating + timed unlock), and terraform
 * biome stepping. Every tick count and cost asserted here is taken from the shipped
 * {@code small-default} balance profile, so the tests also guard that the engine
 * reads config rather than hardcoding constants (rule 6). The step is pure
 * arithmetic (no RNG), so assertions are exact post-state plus a hash determinism
 * check.
 */
class DevelopmentResolutionTest {

    private static final FactionId ALPHA = new FactionId("alpha");
    private static final SystemId SYS = new SystemId("sys-1");
    private static final PlanetId P1 = new PlanetId("p-1");

    private static final BalanceProfile SMALL = loadSmallDefault();

    private static BalanceProfile loadSmallDefault() {
        try (InputStream in = DevelopmentResolutionTest.class
                .getResourceAsStream("/balance/small-default.json")) {
            if (in == null) {
                throw new IllegalStateException("missing /balance/small-default.json");
            }
            return BalanceProfileLoader.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // ---- fixture builders ----------------------------------------------------

    private static GameState stateWith(ResourceBundle stock, Planet planet,
                                       Map<TechId, TechProgress> tech) {
        ActiveSystem system = new ActiveSystem(SYS, "Sys 1", new Coords(1, 1),
                Optional.of(ALPHA), List.of(planet), planet.population(), 1.0);
        Faction alpha = new Faction(ALPHA, "Alpha", 0.0, stock, tech);
        return new GameState(7L, 3L, GameStatus.RUNNING, "small-default", 1,
                Map.of(ALPHA, alpha), Map.of(SYS, system),
                Map.of(), Map.of(), Map.of(), Map.of());
    }

    private static GameState resolve(GameState before, SubmittedAction... actions) {
        return Resolver.resolve(before, List.of(actions), SMALL, before.gameSeed());
    }

    private static GameState resolveN(GameState s, int ticks, SubmittedAction... firstTick) {
        GameState cur = resolve(s, firstTick);
        for (int i = 1; i < ticks; i++) {
            cur = resolve(cur);
        }
        return cur;
    }

    private static Faction alphaOf(GameState s) {
        return s.factions().get(ALPHA);
    }

    private static Planet planetOf(GameState s) {
        return s.systems().get(SYS).planets().get(0);
    }

    private static Building buildingOf(GameState s) {
        return planetOf(s).buildings().get(0);
    }

    // ---- (a) construction completes after exactly its configured tick count --

    @Test
    void buildEscrowsCostAndCompletesAfterConfiguredBuildTime() {
        // small-default: mine cost minerals 30, buildTime 3 ticks.
        Planet bare = new Planet(P1, Biome.ARID, 4, 0, List.of());
        GameState before = stateWith(new ResourceBundle(0, 100, 0, 0, 0), bare, Map.of());
        SubmittedAction build = new SubmittedAction(ALPHA,
                new Action.Build(P1, 0, BuildingType.MINE), 0);

        // Tick 1: queued UNDER_CONSTRUCTION progress 1; cost 30 escrowed (100 -> 70).
        GameState t1 = resolve(before, build);
        Building b1 = buildingOf(t1);
        assertEquals(BuildingStatus.UNDER_CONSTRUCTION, b1.status());
        assertEquals(1, b1.progress(), "progress advances the same tick it is queued");
        assertEquals(70.0, alphaOf(t1).stockpiles().minerals(), 1e-9, "minerals = 100 - 30 cost");

        // Tick 2: progress 2, still under construction.
        GameState t2 = resolve(t1);
        assertEquals(BuildingStatus.UNDER_CONSTRUCTION, buildingOf(t2).status());
        assertEquals(2, buildingOf(t2).progress());

        // Tick 3: progress reaches buildTime 3 -> ACTIVE (progress reset to 0).
        GameState t3 = resolve(t2);
        assertEquals(BuildingStatus.ACTIVE, buildingOf(t3).status(), "active after exactly 3 ticks");
        assertEquals(0, buildingOf(t3).progress());
    }

    @Test
    void monumentTakesItsLongerConfiguredBuildTime() {
        // small-default: monument buildTime 10. Requires monumentalWorks unlocked
        // (the tech gate), but the resolver begin handler does not re-check the gate;
        // we assert it is still under construction at tick 9 and active at tick 10.
        Planet bare = new Planet(P1, Biome.TERRAN, 4, 0, List.of());
        GameState before = stateWith(new ResourceBundle(0, 1000, 0, 1000, 0), bare,
                unlocked("monumentalWorks", "marketNetworks"));
        SubmittedAction build = new SubmittedAction(ALPHA,
                new Action.Build(P1, 0, BuildingType.MONUMENT), 0);

        GameState t9 = resolveN(before, 9, build);
        assertEquals(BuildingStatus.UNDER_CONSTRUCTION, buildingOf(t9).status(), "still building at tick 9");

        GameState t10 = resolve(t9);
        assertEquals(BuildingStatus.ACTIVE, buildingOf(t10).status(), "active at tick 10 = monument buildTime");
    }

    // ---- (b) tech DAG: prereq gating + timed unlock --------------------------

    @Test
    void researchUnlocksAfterConfiguredTimeAndGatesShipTier() {
        // small-default: corvetteDoctrine cost 40 Tech, time 5; it unlocks "corvette".
        Planet bare = new Planet(P1, Biome.FROZEN, 4, 0, List.of());
        GameState before = stateWith(new ResourceBundle(0, 0, 0, 100, 0), bare, Map.of());
        TechId corvette = new TechId("corvetteDoctrine");
        SubmittedAction research = new SubmittedAction(ALPHA, new Action.Research(corvette), 0);

        // Ship tier gated before research completes.
        assertFalse(DevelopmentResolution.capabilityUnlocked(alphaOf(before), "corvette", SMALL),
                "corvette spec is gated until corvetteDoctrine unlocks");

        // Tick 1: RESEARCHING progress 1; 40 Tech escrowed (100 -> 60).
        GameState t1 = resolve(before, research);
        TechProgress p1 = alphaOf(t1).techProgress().get(corvette);
        assertEquals(TechStatus.RESEARCHING, p1.status());
        assertEquals(1, p1.progress());
        assertEquals(60.0, alphaOf(t1).stockpiles().tech(), 1e-9, "tech = 100 - 40 cost");

        // Ticks 2..4 still researching; tick 5 unlocks.
        GameState t4 = resolveN(t1, 3);
        assertEquals(TechStatus.RESEARCHING, alphaOf(t4).techProgress().get(corvette).status());
        assertEquals(4, alphaOf(t4).techProgress().get(corvette).progress());

        GameState t5 = resolve(t4);
        assertEquals(TechStatus.UNLOCKED, alphaOf(t5).techProgress().get(corvette).status(),
                "unlocked after exactly 5 ticks = corvetteDoctrine time");
        assertTrue(DevelopmentResolution.capabilityUnlocked(alphaOf(t5), "corvette", SMALL),
                "corvette spec is buildable once the doctrine unlocks");
    }

    @Test
    void researchPrerequisiteGateBlocksUntilPrereqUnlocked() {
        // cruiserDoctrine requires corvetteDoctrine. With no prereq unlocked, a begin
        // is a no-op (no RESEARCHING node appears, no Tech escrowed).
        Planet bare = new Planet(P1, Biome.FROZEN, 4, 0, List.of());
        GameState before = stateWith(new ResourceBundle(0, 0, 0, 500, 0), bare, Map.of());
        TechId cruiser = new TechId("cruiserDoctrine");
        SubmittedAction research = new SubmittedAction(ALPHA, new Action.Research(cruiser), 0);

        assertFalse(DevelopmentResolution.prerequisitesMet(alphaOf(before), cruiser, SMALL));
        GameState t1 = resolve(before, research);
        assertFalse(alphaOf(t1).techProgress().containsKey(cruiser), "gated research does not start");
        assertEquals(500.0, alphaOf(t1).stockpiles().tech(), 1e-9, "no Tech escrowed for a gated begin");

        // With the prerequisite unlocked, research begins and escrows the cost.
        GameState ok = stateWith(new ResourceBundle(0, 0, 0, 500, 0), bare,
                unlocked("corvetteDoctrine"));
        assertTrue(DevelopmentResolution.prerequisitesMet(alphaOf(ok), cruiser, SMALL));
        GameState okT1 = resolve(ok, research);
        assertEquals(TechStatus.RESEARCHING, alphaOf(okT1).techProgress().get(cruiser).status());
        assertEquals(420.0, alphaOf(okT1).stockpiles().tech(), 1e-9, "cruiserDoctrine cost 80 escrowed");
    }

    // ---- (c) terraform advances a biome one step over many ticks -------------

    @Test
    void terraformAdvancesBiomeOneStepAfterConfiguredStepTime() {
        // small-default: terraformerStep 6 ticks; chain toxic -> arid. An active
        // Terraformer on a terraformable planet advances one step per tick.
        Building terraformer = new Building(0, BuildingType.TERRAFORMER, BuildingStatus.ACTIVE, 0);
        Planet toxic = new Planet(P1, Biome.TOXIC, 4, 0, List.of(terraformer));
        GameState before = stateWith(new ResourceBundle(1000, 0, 0, 0, 0), toxic, Map.of());
        SubmittedAction terraform = new SubmittedAction(ALPHA, new Action.Terraform(P1), 0);

        // Tick 1: progress advances 0 -> 1, biome still TOXIC.
        GameState t1 = resolve(before, terraform);
        assertEquals(Biome.TOXIC, planetOf(t1).biome());
        assertEquals(1, buildingOf(t1).progress(), "terraform progress advances the first tick");

        // After 4 more ticks progress is 5, still TOXIC.
        GameState t5 = resolveN(t1, 4);
        assertEquals(Biome.TOXIC, planetOf(t5).biome(), "still TOXIC at progress 5");
        assertEquals(5, buildingOf(t5).progress());

        // Tick 6: progress reaches terraformerStep 6 -> biome steps to ARID.
        GameState t6 = resolve(t5);
        assertEquals(Biome.ARID, planetOf(t6).biome(), "stepped one rung toward habitable after 6 ticks");
        assertEquals(0, buildingOf(t6).progress(), "terraform progress resets after a step");
    }

    @Test
    void terraformStopsWhenBiomeReachesHabitableEnd() {
        // oceanic has no chain entry -> an active Terraformer never advances it.
        Building terraformer = new Building(0, BuildingType.TERRAFORMER, BuildingStatus.ACTIVE, 0);
        Planet oceanic = new Planet(P1, Biome.OCEANIC, 4, 0, List.of(terraformer));
        GameState before = stateWith(new ResourceBundle(1000, 0, 0, 0, 0), oceanic, Map.of());
        SubmittedAction terraform = new SubmittedAction(ALPHA, new Action.Terraform(P1), 0);

        GameState t1 = resolveN(before, 5, terraform);
        assertEquals(0, buildingOf(t1).progress(), "no step available -> terraform makes no progress");
        assertEquals(Biome.OCEANIC, planetOf(t1).biome());
    }

    // ---- (d) determinism -----------------------------------------------------

    @Test
    void developmentResolutionIsDeterministicAcrossRuns() {
        Building terraformer = new Building(1, BuildingType.TERRAFORMER, BuildingStatus.ACTIVE, 0);
        Planet p = new Planet(P1, Biome.VOLCANIC, 6, 2, List.of(terraformer));
        GameState before = stateWith(new ResourceBundle(1000, 1000, 0, 1000, 0), p,
                unlocked("improvedExtraction"));
        SubmittedAction build = new SubmittedAction(ALPHA,
                new Action.Build(P1, 0, BuildingType.MINE), 0);
        SubmittedAction research = new SubmittedAction(ALPHA,
                new Action.Research(new TechId("terraformingI")), 1);
        SubmittedAction terraform = new SubmittedAction(ALPHA, new Action.Terraform(P1), 2);

        String a = GoldenStateHash.sha256Hex(resolve(before, build, research, terraform));
        String b = GoldenStateHash.sha256Hex(resolve(before, build, research, terraform));
        assertEquals(a, b, "same state + profile + actions -> byte-identical resolution");
    }

    // ---- helpers -------------------------------------------------------------

    private static Map<TechId, TechProgress> unlocked(String... techKeys) {
        Map<TechId, TechProgress> m = new LinkedHashMap<>();
        for (String key : techKeys) {
            TechId id = new TechId(key);
            m.put(id, new TechProgress(id, TechStatus.UNLOCKED, 0));
        }
        return m;
    }
}
