package com.stellarcompact.engine.resolve;

import com.stellarcompact.engine.action.Action;
import com.stellarcompact.engine.action.AttackTarget;
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
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * E1-10 COMBAT step tests (game-design 05 sections 1-2,6). Hand-authored,
 * deterministic, no LLM, no unseeded RNG: every assertion is over the seeded
 * variance-band bounds and the proportional-loss / capture arithmetic, never over
 * "randomness".
 *
 * <p>The resolver consumes already-validated {@code Attack} actions, so these tests
 * drive combat through {@code Resolver.resolve} without re-asserting the war-state
 * gate (that is the validator's job, exercised in its own suite).
 */
class CombatResolutionTest {

    private static final FactionId ALPHA = new FactionId("alpha");
    private static final FactionId BETA = new FactionId("beta");
    private static final SystemId A = new SystemId("A");
    private static final SystemId B = new SystemId("B");
    private static final FleetId ATT = new FleetId("att");
    private static final FleetId DEF = new FleetId("def");

    private static final long SEED = 777L;
    private static final long TICK = 3L;

    private static final BalanceProfile PROFILE = ResolveFixtures.combatProfile();

    private static Faction faction(FactionId id) {
        return new Faction(id, "F-" + id.value(), 0.0,
                new ResourceBundle(0, 0, 0, 0, 0), Map.of());
    }

    private static Fleet fleet(FleetId id, FactionId owner, SystemId at, FleetStance stance,
                               List<Ship> ships) {
        return new Fleet(id, owner, Optional.of(at), Optional.empty(), stance, ships);
    }

    private static ActiveSystem system(SystemId id, FactionId owner, List<Building> buildings) {
        Planet p = new Planet(new PlanetId(id.value() + "-p1"), Biome.TERRAN, 4, 5, buildings);
        return new ActiveSystem(id, "name-" + id.value(), new Coords(0, 0),
                Optional.ofNullable(owner), List.of(p), 5, 1.0);
    }

    private static GameState state(Map<FleetId, Fleet> fleets, Map<SystemId, ActiveSystem> systems) {
        return new GameState(SEED, TICK, GameStatus.RUNNING, "small-default", 1,
                Map.of(ALPHA, faction(ALPHA), BETA, faction(BETA)),
                systems, fleets, Map.of(), Map.of(), Map.of());
    }

    private static GameState resolveAttack(GameState s, Action.Attack attack) {
        return Resolver.resolve(s, List.of(new SubmittedAction(ALPHA, attack, 0)),
                PROFILE, s.gameSeed());
    }

    private static int shipCount(Fleet f) {
        return f.ships().stream().mapToInt(Ship::count).sum();
    }

    // ===== golden determinism: same (seed,tick,battleId) -> identical outcome =====

    @Test
    void sameSeedTickBattleProducesIdenticalOutcome() {
        Map<FleetId, Fleet> fleets = Map.of(
                ATT, fleet(ATT, ALPHA, A, FleetStance.BALANCED, List.of(new Ship("cruiser", 5))),
                DEF, fleet(DEF, BETA, A, FleetStance.BALANCED, List.of(new Ship("cruiser", 4))));
        GameState s0 = state(fleets, Map.of(A, system(A, null, List.of())));
        Action.Attack attack = new Action.Attack(ATT, new AttackTarget.OnFleet(DEF));

        GameState r1 = resolveAttack(s0, attack);
        GameState r2 = resolveAttack(s0, attack);

        assertEquals(r1.fleets().get(ATT).ships(), r2.fleets().get(ATT).ships(),
                "attacker losses are a pure function of (seed,tick,battleId)");
        assertEquals(r1.fleets(), r2.fleets(), "whole fleet map reproduces identically");
    }

    // ===== proportional losses to BOTH sides =====================================

    @Test
    void bothSidesTakeProportionalLossesWinnerToo() {
        Map<FleetId, Fleet> fleets = Map.of(
                ATT, fleet(ATT, ALPHA, A, FleetStance.AGGRESSIVE, List.of(new Ship("capital", 5))),
                DEF, fleet(DEF, BETA, A, FleetStance.DEFENSIVE, List.of(new Ship("corvette", 1))));
        GameState s0 = state(fleets, Map.of(A, system(A, null, List.of())));

        GameState r = resolveAttack(s0, new Action.Attack(ATT, new AttackTarget.OnFleet(DEF)));

        int attShips = shipCount(r.fleets().get(ATT));
        int defShips = shipCount(r.fleets().get(DEF));
        assertTrue(attShips < 5, "winner takes attrition too (pyrrhic risk): " + attShips);
        assertTrue(attShips >= 3, "an overwhelming winner is not gutted: " + attShips);
        assertEquals(0, defShips, "the lone losing ship is destroyed: " + defShips);
    }

    // ===== variance band: stronger usually-but-not-always wins ====================

    @Test
    void varianceBandBoundsNotRandomness() {
        int attackerWins = 0;
        int total = 40;
        for (int i = 0; i < total; i++) {
            SystemId here = new SystemId("S" + i);
            FleetId att = new FleetId("att" + i);
            FleetId def = new FleetId("def" + i);
            Map<FleetId, Fleet> fleets = Map.of(
                    att, fleet(att, ALPHA, here, FleetStance.BALANCED, List.of(new Ship("cruiser", 6))),
                    def, fleet(def, BETA, here, FleetStance.BALANCED, List.of(new Ship("cruiser", 5))));
            GameState s0 = new GameState(SEED, TICK, GameStatus.RUNNING, "small-default", 1,
                    Map.of(ALPHA, faction(ALPHA), BETA, faction(BETA)),
                    Map.of(here, system(here, null, List.of())), fleets,
                    Map.of(), Map.of(), Map.of());
            GameState r = Resolver.resolve(s0,
                    List.of(new SubmittedAction(ALPHA, new Action.Attack(att, new AttackTarget.OnFleet(def)), 0)),
                    PROFILE, s0.gameSeed());
            if (shipCount(r.fleets().get(att)) > shipCount(r.fleets().get(def))) {
                attackerWins++;
            }
        }
        assertTrue(attackerWins > total / 2, "stronger force usually wins: " + attackerWins + "/" + total);
        assertTrue(attackerWins < total, "but not ALWAYS - the variance band makes it a gamble: "
                + attackerWins + "/" + total);
    }

    // ===== system assault -> capture =============================================

    @Test
    void assaultCapturesSystemTransfersBuildingsAndAppliesOccupationPenalty() {
        Building mine = new Building(0, BuildingType.MINE, BuildingStatus.ACTIVE, 0);
        Building lab = new Building(1, BuildingType.RESEARCH_LAB, BuildingStatus.ACTIVE, 0);
        ActiveSystem target = system(B, BETA, List.of(mine, lab));
        Map<FleetId, Fleet> fleets = Map.of(
                ATT, fleet(ATT, ALPHA, B, FleetStance.AGGRESSIVE, List.of(new Ship("capital", 8))));
        GameState s0 = state(fleets, Map.of(B, target));

        GameState r = resolveAttack(s0, new Action.Attack(ATT, new AttackTarget.OnSystem(B)));
        ActiveSystem captured = r.systems().get(B);

        assertEquals(Optional.of(ALPHA), captured.owner(), "ownership transfers to the captor");
        assertEquals(2, captured.planets().get(0).buildings().size(),
                "surviving buildings transfer to the captor");
        double penalty = PROFILE.combat().occupationLoyaltyPenalty();
        assertEquals(Math.max(0.0, 1.0 - penalty), captured.loyalty(), 1e-9,
                "captured system gets the reduced-loyalty occupation penalty");
        assertTrue(captured.loyalty() < 1.0, "freshly captured loyalty is depressed (unrest)");
    }

    @Test
    void failedAssaultDoesNotCapture() {
        Building platform = new Building(0, BuildingType.DEFENSE_PLATFORM, BuildingStatus.ACTIVE, 0);
        ActiveSystem target = system(B, BETA, List.of(platform));
        Map<FleetId, Fleet> fleets = Map.of(
                ATT, fleet(ATT, ALPHA, B, FleetStance.EVASIVE, List.of(new Ship("scout", 1))),
                DEF, fleet(DEF, BETA, B, FleetStance.DEFENSIVE, List.of(new Ship("capital", 6))));
        GameState s0 = state(fleets, Map.of(B, target));

        GameState r = resolveAttack(s0, new Action.Attack(ATT, new AttackTarget.OnSystem(B)));
        assertEquals(Optional.of(BETA), r.systems().get(B).owner(),
                "a repelled assault leaves ownership with the defender");
        assertEquals(1.0, r.systems().get(B).loyalty(), 1e-9, "loyalty unchanged when not captured");
    }

    @Test
    void neutralSystemAssaultCaptures() {
        Building mine = new Building(0, BuildingType.MINE, BuildingStatus.ACTIVE, 0);
        ActiveSystem neutral = system(B, null, List.of(mine));
        Map<FleetId, Fleet> fleets = Map.of(
                ATT, fleet(ATT, ALPHA, B, FleetStance.AGGRESSIVE, List.of(new Ship("capital", 6))));
        GameState s0 = new GameState(SEED, TICK, GameStatus.RUNNING, "small-default", 1,
                Map.of(ALPHA, faction(ALPHA), BETA, faction(BETA)),
                Map.of(B, neutral), fleets, Map.of(), Map.of(), Map.of());
        GameState r = resolveAttack(s0, new Action.Attack(ATT, new AttackTarget.OnSystem(B)));
        assertEquals(Optional.of(ALPHA), r.systems().get(B).owner(),
                "an undefended neutral system is captured");
    }

    // ===== a defending garrison fleet at the assaulted system fights =============

    @Test
    void systemAssaultEngagesTheGarrisonFleet() {
        // A defending fleet stationed at the target adds its defence to the system; a
        // strong enough attacker still wins, both the garrison fleet and the assault
        // fleet take losses.
        ActiveSystem target = system(B, BETA, List.of());
        Map<FleetId, Fleet> fleets = Map.of(
                ATT, fleet(ATT, ALPHA, B, FleetStance.AGGRESSIVE, List.of(new Ship("capital", 8))),
                DEF, fleet(DEF, BETA, B, FleetStance.DEFENSIVE, List.of(new Ship("cruiser", 2))));
        GameState s0 = state(fleets, Map.of(B, target));

        GameState r = resolveAttack(s0, new Action.Attack(ATT, new AttackTarget.OnSystem(B)));
        assertEquals(Optional.of(ALPHA), r.systems().get(B).owner(), "garrison overrun, system captured");
        assertTrue(shipCount(r.fleets().get(DEF)) < 2, "the defending garrison fleet took losses");
        assertTrue(shipCount(r.fleets().get(ATT)) < 8, "the assault fleet took attrition too");
    }

    // ===== independent per-battle streams =========================================

    @Test
    void differentBattleIdsGiveIndependentOutcomes() {
        FleetId a1 = new FleetId("a1");
        FleetId d1 = new FleetId("d1");
        FleetId a2 = new FleetId("a2");
        FleetId d2 = new FleetId("d2");
        SystemId s1 = new SystemId("X1");
        SystemId s2 = new SystemId("X2");
        Map<FleetId, Fleet> fleets = Map.of(
                a1, fleet(a1, ALPHA, s1, FleetStance.BALANCED, List.of(new Ship("cruiser", 6))),
                d1, fleet(d1, BETA, s1, FleetStance.BALANCED, List.of(new Ship("cruiser", 5))),
                a2, fleet(a2, ALPHA, s2, FleetStance.BALANCED, List.of(new Ship("cruiser", 6))),
                d2, fleet(d2, BETA, s2, FleetStance.BALANCED, List.of(new Ship("cruiser", 5))));
        GameState s0 = new GameState(SEED, TICK, GameStatus.RUNNING, "small-default", 1,
                Map.of(ALPHA, faction(ALPHA), BETA, faction(BETA)),
                Map.of(s1, system(s1, null, List.of()), s2, system(s2, null, List.of())),
                fleets, Map.of(), Map.of(), Map.of());
        GameState r = Resolver.resolve(s0, List.of(
                        new SubmittedAction(ALPHA, new Action.Attack(a1, new AttackTarget.OnFleet(d1)), 0),
                        new SubmittedAction(ALPHA, new Action.Attack(a2, new AttackTarget.OnFleet(d2)), 1)),
                PROFILE, s0.gameSeed());
        String o1 = shipCount(r.fleets().get(a1)) + ":" + shipCount(r.fleets().get(d1));
        String o2 = shipCount(r.fleets().get(a2)) + ":" + shipCount(r.fleets().get(d2));
        assertNotEquals(o1, o2, "independent battleId streams produce independent outcomes");
    }
}
