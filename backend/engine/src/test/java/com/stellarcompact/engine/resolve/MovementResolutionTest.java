package com.stellarcompact.engine.resolve;

import com.stellarcompact.engine.action.Action;
import com.stellarcompact.engine.config.BalanceProfile;
import com.stellarcompact.engine.map.LaneNetwork;
import com.stellarcompact.engine.state.ActiveSystem;
import com.stellarcompact.engine.state.Coords;
import com.stellarcompact.engine.state.Faction;
import com.stellarcompact.engine.state.FactionId;
import com.stellarcompact.engine.state.Fleet;
import com.stellarcompact.engine.state.FleetId;
import com.stellarcompact.engine.state.FleetStance;
import com.stellarcompact.engine.state.GameState;
import com.stellarcompact.engine.state.GameStatus;
import com.stellarcompact.engine.state.ResourceBundle;
import com.stellarcompact.engine.state.Ship;
import com.stellarcompact.engine.state.SystemId;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * E1-09 MOVEMENT step tests: deterministic multi-tick travel along a lane path with
 * a summed-ETA arrival and an escrowed Energy cost, plus mid-transit interception
 * triggered at a contested lane (chokepoint control) gated by a positive war state.
 * Hand-authored, deterministic, no LLM, no RNG (only the pure seeded battle id).
 */
class MovementResolutionTest {

    private static final FactionId ALPHA = new FactionId("alpha");
    private static final FactionId BETA = new FactionId("beta");
    private static final SystemId A = new SystemId("A");
    private static final SystemId B = new SystemId("B");
    private static final SystemId C = new SystemId("C");
    private static final FleetId MOVER = new FleetId("mover");
    private static final FleetId GUARD = new FleetId("guard");

    private static final BalanceProfile PROFILE = ResolveFixtures.profile(); // Movement(0.5, true)

    /** A-2-B-3-C linear chain. */
    private static LaneNetwork laneGraph() {
        return LaneNetwork.builder().addLane(A, B, 2).addLane(B, C, 3).build();
    }

    private static ActiveSystem system(SystemId id, FactionId owner) {
        return new ActiveSystem(id, "name-" + id.value(), new Coords(0, 0),
                Optional.ofNullable(owner), List.of(), 0, 1.0);
    }

    private static Fleet stationary(FleetId id, FactionId owner, SystemId at) {
        return new Fleet(id, owner, Optional.of(at), Optional.empty(),
                FleetStance.BALANCED, List.of(new Ship("cruiser", 3)));
    }

    private static Faction faction(FactionId id) {
        return new Faction(id, "F-" + id.value(), 0.0,
                new ResourceBundle(1000, 1000, 1000, 1000, 1000), Map.of());
    }

    /** ALPHA mover at A; optional GUARD fleet for BETA; optional war ALPHA-BETA. */
    private static GameState scenario(Fleet guard, boolean atWar) {
        Map<FleetId, Fleet> fleets = guard == null
                ? Map.of(MOVER, stationary(MOVER, ALPHA, A))
                : Map.of(MOVER, stationary(MOVER, ALPHA, A), GUARD, guard);
        Set<com.stellarcompact.engine.state.WarState> wars = atWar
                ? Set.of(com.stellarcompact.engine.state.WarState.between(ALPHA, BETA, 0L))
                : Set.of();
        return new GameState(99L, 0L, GameStatus.RUNNING, "small-default", 1,
                Map.of(ALPHA, faction(ALPHA), BETA, faction(BETA)),
                Map.of(A, system(A, ALPHA), B, system(B, null), C, system(C, null)),
                fleets, Map.of(), Map.of(), Map.of(), wars);
    }

    private static GameState resolveTick(GameState state, List<SubmittedAction> batch) {
        GameState next = Resolver.resolve(state, batch, PROFILE, state.gameSeed(), laneGraph());
        return next.withTick(state.tick() + 1);
    }

    // ===== travel ETA + Energy cost ("travel ETAs deterministic") =================

    @Test
    void travelArrivesAfterSummedLaneTicksAndIsDeterministic() {
        GameState s0 = scenario(null, false);
        Action.MoveFleet move = new Action.MoveFleet(MOVER, List.of(B, C), C);
        List<SubmittedAction> launch = List.of(new SubmittedAction(ALPHA, move, 0));

        // Tick 1: launch. The fleet is now en route on lane A->B (2 ticks), already
        // having taken its first travel tick this tick (eta 2 -> 1).
        GameState s1 = resolveTick(s0, launch);
        Fleet f1 = s1.fleets().get(MOVER);
        assertTrue(f1.enRoute(), "fleet en route after launch");
        assertEquals(Optional.of(A), f1.location(), "location pins the lane origin while en route");
        assertEquals(Optional.of(1), f1.etaTicks(), "one tick of the 2-tick A-B lane consumed at launch");

        // No further MoveFleet actions; the advance sweep carries it.
        GameState s2 = resolveTick(s1, List.of()); // arrives B, begins B->C (3 ticks)
        Fleet f2 = s2.fleets().get(MOVER);
        assertEquals(Optional.of(B), f2.location(), "arrived at B, now flying B->C");
        assertEquals(Optional.of(3), f2.etaTicks());

        GameState s3 = resolveTick(s2, List.of()); // eta 2
        GameState s4 = resolveTick(s3, List.of()); // eta 1
        GameState s5 = resolveTick(s4, List.of()); // eta 0 -> arrive C, park
        Fleet f5 = s5.fleets().get(MOVER);
        assertFalse(f5.enRoute(), "journey complete after the summed 2+3=5 travel ticks");
        assertEquals(Optional.of(C), f5.location(), "parked at the destination");
        assertTrue(f5.enroutePath().isEmpty());

        // Determinism: a fresh identical run reproduces the same arrival tick/state.
        GameState replayS1 = resolveTick(scenario(null, false), launch);
        assertEquals(s1.fleets().get(MOVER), replayS1.fleets().get(MOVER));
    }

    @Test
    void launchEscrowsWholeJourneyEnergyCost() {
        GameState s0 = scenario(null, false);
        double before = s0.factions().get(ALPHA).stockpiles().energy();
        GameState s1 = resolveTick(s0,
                List.of(new SubmittedAction(ALPHA, new Action.MoveFleet(MOVER, List.of(B, C), C), 0)));
        double after = s1.factions().get(ALPHA).stockpiles().energy();
        // Energy cost = summed lane ticks (5) x energyCostPerLaneTick (0.5) = 2.5.
        assertEquals(2.5, before - after, 1e-9, "whole journey's Energy charged once at launch");
    }

    @Test
    void launchOnEmptyNetworkDoesNotMove() {
        GameState s0 = scenario(null, false);
        GameState s1 = Resolver.resolve(s0,
                List.of(new SubmittedAction(ALPHA, new Action.MoveFleet(MOVER, List.of(B, C), C), 0)),
                PROFILE, s0.gameSeed(), LaneNetwork.EMPTY);
        assertFalse(s1.fleets().get(MOVER).enRoute(),
                "with no lane graph the resolver must not move the fleet on an unproven path");
        assertEquals(s0.factions().get(ALPHA).stockpiles().energy(),
                s1.factions().get(ALPHA).stockpiles().energy(), "no Energy charged when no move happens");
    }

    // ===== interception trigger + chokepoint control ==============================

    @Test
    void interceptionTriggersAtContestedLaneWhenAtWar() {
        // BETA holds the chokepoint B; ALPHA flies A->B->C while at war with BETA.
        GameState s0 = scenario(stationary(GUARD, BETA, B), true);
        // Launch the fleet onto lane A->B, then advance one tick on that lane.
        GameState launched = MovementResolution.launch(s0, ALPHA,
                new Action.MoveFleet(MOVER, List.of(B, C), C), laneGraph(), PROFILE, new SpendLedger());
        MovementResolution.AdvanceResult result =
                MovementResolution.advance(launched, laneGraph(), PROFILE);

        assertEquals(1, result.battles().size(), "one interception on the contested A-B lane");
        PendingBattle battle = result.battles().get(0);
        assertEquals(MOVER, battle.movingFleet());
        assertEquals(ALPHA, battle.movingOwner());
        assertEquals(GUARD, battle.interceptor());
        assertEquals(BETA, battle.interceptorOwner());
        // Contested lane named canonically (A < B by id).
        assertEquals(A, battle.contestedLaneA());
        assertEquals(B, battle.contestedLaneB());
    }

    @Test
    void noInterceptionWhenAtPeace() {
        // Same chokepoint guard, but no war -> the lane is uncontested (F1 gate).
        GameState s0 = scenario(stationary(GUARD, BETA, B), false);
        GameState launched = MovementResolution.launch(s0, ALPHA,
                new Action.MoveFleet(MOVER, List.of(B, C), C), laneGraph(), PROFILE, new SpendLedger());
        MovementResolution.AdvanceResult result =
                MovementResolution.advance(launched, laneGraph(), PROFILE);
        assertTrue(result.battles().isEmpty(), "no interception without a positive war state");
    }

    @Test
    void noInterceptionWhenGuardNotOnTheLane() {
        // BETA guard sits at C (off the A-B lane the fleet is currently on), at war.
        GameState s0 = scenario(stationary(GUARD, BETA, C), true);
        GameState launched = MovementResolution.launch(s0, ALPHA,
                new Action.MoveFleet(MOVER, List.of(B), B), laneGraph(), PROFILE, new SpendLedger());
        MovementResolution.AdvanceResult result =
                MovementResolution.advance(launched, laneGraph(), PROFILE);
        assertTrue(result.battles().isEmpty(),
                "a hostile fleet not holding a contested-lane endpoint does not intercept");
    }

    @Test
    void interceptionDisabledByConfigSuppressesTheTrigger() {
        BalanceProfile noIntercept = withInterception(false);
        GameState s0 = scenario(stationary(GUARD, BETA, B), true);
        GameState launched = MovementResolution.launch(s0, ALPHA,
                new Action.MoveFleet(MOVER, List.of(B, C), C), laneGraph(), noIntercept, new SpendLedger());
        MovementResolution.AdvanceResult result =
                MovementResolution.advance(launched, laneGraph(), noIntercept);
        assertTrue(result.battles().isEmpty(), "interception is config-gated off");
    }

    @Test
    void battleIdIsDeterministicForSameTickAndParticipants() {
        GameState s0 = scenario(stationary(GUARD, BETA, B), true);
        Action.MoveFleet move = new Action.MoveFleet(MOVER, List.of(B, C), C);
        long id1 = MovementResolution.advance(
                MovementResolution.launch(s0, ALPHA, move, laneGraph(), PROFILE, new SpendLedger()),
                laneGraph(), PROFILE).battles().get(0).battleId();
        long id2 = MovementResolution.advance(
                MovementResolution.launch(s0, ALPHA, move, laneGraph(), PROFILE, new SpendLedger()),
                laneGraph(), PROFILE).battles().get(0).battleId();
        assertEquals(id1, id2, "same (seed,tick,participants,lane) -> same battle id (replay)");
    }

    private static BalanceProfile withInterception(boolean on) {
        BalanceProfile b = PROFILE;
        return new BalanceProfile(b.name(), b.version(), b.resources(), b.population(),
                b.market(), b.construction(), b.combat(),
                new BalanceProfile.Movement(b.movement().energyCostPerLaneTick(), on),
                b.tech(), b.diplomacy(), b.victory(), b.tick());
    }
}
