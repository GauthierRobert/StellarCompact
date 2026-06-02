package com.stellarcompact.engine.resolve;

import com.stellarcompact.engine.action.Action;
import com.stellarcompact.engine.action.AttackTarget;
import com.stellarcompact.engine.action.EspionageOperation;
import com.stellarcompact.engine.action.UnknownAction;
import com.stellarcompact.engine.state.FactionId;
import com.stellarcompact.engine.state.FleetId;
import com.stellarcompact.engine.state.MarketOrderId;
import com.stellarcompact.engine.state.RouteId;
import com.stellarcompact.engine.state.SystemId;
import com.stellarcompact.engine.state.TreatyId;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The deterministic ordering contract of game-design 03 "Resolution order": the
 * resolver orders validated actions by (step, faction id, submission order). These
 * tests pin that ordering on hand-authored action lists and prove it is stable
 * under input shuffling - the determinism guarantee from
 * {@code .claude/skills/game-engine-determinism}.
 */
class ResolutionOrderingTest {

    private static final FactionId ALPHA = new FactionId("alpha");
    private static final FactionId BETA = new FactionId("beta");
    private static final FactionId GAMMA = new FactionId("gamma");

    // ---- step mapping: each action lands in the right one of the eleven steps ----

    @Test
    void stepMappingFollowsTheElevenStepOrder() {
        assertEquals(ResolutionStep.DIPLOMATIC_STATE, SubmittedAction.stepOf(new Action.DeclareWar(BETA)));
        assertEquals(ResolutionStep.DIPLOMATIC_STATE, SubmittedAction.stepOf(new Action.BreakTreaty(new TreatyId("t"))));
        assertEquals(ResolutionStep.ESPIONAGE, SubmittedAction.stepOf(new Action.Espionage(BETA, EspionageOperation.SCOUT)));
        assertEquals(ResolutionStep.MOVEMENT, SubmittedAction.stepOf(new Action.MoveFleet(new FleetId("f"), List.of(new SystemId("s")), new SystemId("s"))));
        assertEquals(ResolutionStep.COMBAT, SubmittedAction.stepOf(new Action.Attack(new FleetId("f"), new AttackTarget.OnSystem(new SystemId("s")))));
        assertEquals(ResolutionStep.INTERDICTION, SubmittedAction.stepOf(new Action.Raid(new FleetId("f"), new RouteId("r"))));
        assertEquals(ResolutionStep.DEVELOPMENT, SubmittedAction.stepOf(new Action.Explore(new SystemId("s"))));
        // Colonize splits out of the DEVELOPMENT action bucket into step 7.
        assertEquals(ResolutionStep.COLONISATION, SubmittedAction.stepOf(new Action.Colonize(new com.stellarcompact.engine.state.PlanetId("p"), new FleetId("f"))));
        assertEquals(ResolutionStep.MARKET, SubmittedAction.stepOf(new Action.AcceptTrade(new MarketOrderId("o"))));
    }

    @Test
    void softDiplomacyAndNoOpsMapToNoStep() {
        assertNull(SubmittedAction.stepOf(new Action.SendMessage(BETA, "hi")));
        assertNull(SubmittedAction.stepOf(new Action.Hold()));
        assertNull(SubmittedAction.stepOf(new UnknownAction("Future")));
        assertNull(SubmittedAction.stepOf(new Action.Tribute(BETA,
                new com.stellarcompact.engine.state.ResourceBundle(1, 0, 0, 0, 0))));
    }

    // ---- ordering: step, then faction id, then submission order ------------------

    @Test
    void ordersByStepThenFactionThenSubmission() {
        // Hand-authored, deliberately scrambled input. Each entry tags the faction
        // and submission index; the expected canonical order is asserted below.
        List<SubmittedAction> input = new ArrayList<>();
        // BETA combat, submission 1
        input.add(new SubmittedAction(BETA, new Action.Attack(new FleetId("fb"),
                new AttackTarget.OnSystem(new SystemId("s"))), 1));
        // ALPHA diplomatic-state, submission 0
        input.add(new SubmittedAction(ALPHA, new Action.DeclareWar(GAMMA), 0));
        // ALPHA combat, submission 0
        input.add(new SubmittedAction(ALPHA, new Action.Attack(new FleetId("fa"),
                new AttackTarget.OnSystem(new SystemId("s"))), 0));
        // BETA combat, submission 0 (same faction+step as the first entry; earlier index)
        input.add(new SubmittedAction(BETA, new Action.Attack(new FleetId("fb2"),
                new AttackTarget.OnSystem(new SystemId("s"))), 0));
        // ALPHA development, submission 9
        input.add(new SubmittedAction(ALPHA, new Action.Explore(new SystemId("s")), 9));

        List<SubmittedAction> sorted = new ArrayList<>(input);
        sorted.sort(Resolver.ORDER);

        // Expected: DIPLOMATIC_STATE(alpha) < COMBAT(alpha) < COMBAT(beta:0) <
        //           COMBAT(beta:1) < DEVELOPMENT(alpha)
        assertEquals(ResolutionStep.DIPLOMATIC_STATE, sorted.get(0).step());
        assertEquals(ALPHA, sorted.get(0).actor());

        assertEquals(ResolutionStep.COMBAT, sorted.get(1).step());
        assertEquals(ALPHA, sorted.get(1).actor());

        assertEquals(ResolutionStep.COMBAT, sorted.get(2).step());
        assertEquals(BETA, sorted.get(2).actor());
        assertEquals(0, sorted.get(2).submissionOrder());

        assertEquals(ResolutionStep.COMBAT, sorted.get(3).step());
        assertEquals(BETA, sorted.get(3).actor());
        assertEquals(1, sorted.get(3).submissionOrder());

        assertEquals(ResolutionStep.DEVELOPMENT, sorted.get(4).step());
        assertEquals(ALPHA, sorted.get(4).actor());
    }

    @Test
    void orderingIsStableUnderArbitraryInputShuffles() {
        // Build a fixed batch, then assert every shuffle normalises to one order
        // (property test from the skill: submission shuffling within a slot is
        // normalised by resolution order).
        List<SubmittedAction> batch = List.of(
                new SubmittedAction(ALPHA, new Action.DeclareWar(BETA), 0),
                new SubmittedAction(ALPHA, new Action.Explore(new SystemId("s")), 1),
                new SubmittedAction(BETA, new Action.DeclareWar(ALPHA), 0),
                new SubmittedAction(GAMMA, new Action.Espionage(ALPHA, EspionageOperation.SCOUT), 0),
                new SubmittedAction(BETA, new Action.Explore(new SystemId("s")), 2));

        List<String> canonical = sortKeys(batch);

        // Deterministic pseudo-shuffles via a fixed seed list of rotations.
        for (int rot = 0; rot < batch.size(); rot++) {
            List<SubmittedAction> shuffled = new ArrayList<>(batch);
            Collections.rotate(shuffled, rot);
            assertEquals(canonical, sortKeys(shuffled),
                    "rotation " + rot + " must normalise to the same canonical order");
        }
        // Also the full reverse.
        List<SubmittedAction> reversed = new ArrayList<>(batch);
        Collections.reverse(reversed);
        assertEquals(canonical, sortKeys(reversed));
    }

    private static List<String> sortKeys(List<SubmittedAction> input) {
        List<SubmittedAction> sorted = new ArrayList<>(input);
        sorted.sort(Resolver.ORDER);
        List<String> keys = new ArrayList<>();
        for (SubmittedAction sa : sorted) {
            keys.add(sa.step() + ":" + sa.actor().value() + ":" + sa.submissionOrder());
        }
        return keys;
    }
}
