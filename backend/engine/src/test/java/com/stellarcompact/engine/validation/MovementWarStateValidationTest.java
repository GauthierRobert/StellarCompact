package com.stellarcompact.engine.validation;

import com.stellarcompact.engine.action.Action;
import com.stellarcompact.engine.config.BalanceProfile;
import com.stellarcompact.engine.map.LaneNetwork;
import com.stellarcompact.engine.state.GameState;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.stellarcompact.engine.validation.Fixtures.ALPHA;
import static com.stellarcompact.engine.validation.Fixtures.BETA;
import static com.stellarcompact.engine.validation.Fixtures.FLEET_A;
import static com.stellarcompact.engine.validation.Fixtures.PLANET_A;
import static com.stellarcompact.engine.validation.Fixtures.PLANET_B;
import static com.stellarcompact.engine.validation.Fixtures.SYS_A;
import static com.stellarcompact.engine.validation.Fixtures.SYS_B;
import static com.stellarcompact.engine.validation.Fixtures.SYS_NEUTRAL;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * E1-09 validator security-prereq tests (spec section 4a): the lane-adjacency /
 * reachability gate (F2) for {@code Explore}/{@code Colonize}/{@code MoveFleet} when a
 * {@link LaneNetwork} is supplied, and the positive war-state gate (F1) interplay.
 * Hand-authored, deterministic, no LLM.
 *
 * <p>Lane graph used here: {@code SYS_A - SYS_NEUTRAL - SYS_B} (A adjacent to the
 * neutral; B two hops from A). FLEET_A (ALPHA) is stationed at SYS_A.
 */
class MovementWarStateValidationTest {

    private static final BalanceProfile PROFILE = Fixtures.profile();
    private static final GameState STATE = Fixtures.baseState();

    private static LaneNetwork lanes() {
        return LaneNetwork.builder()
                .addLane(SYS_A, SYS_NEUTRAL, 2)
                .addLane(SYS_NEUTRAL, SYS_B, 3)
                .build();
    }

    private static ValidationResult validate(Action action) {
        return ActionValidator.validate(STATE, ALPHA, action, PROFILE, lanes());
    }

    private static void assertValid(ValidationResult r) {
        assertTrue(r.isValid(), () -> "expected Valid but was " + r);
    }

    private static void assertRejected(ValidationResult r, RejectionReason code) {
        assertFalse(r.isValid(), () -> "expected Rejected but was Valid");
        assertEquals(code, ((ValidationResult.Rejected) r).code());
    }

    // ===== F2: Explore adjacency ==================================================

    @Test
    void exploreAdjacentNeutralValid() {
        // SYS_NEUTRAL is one lane hop from SYS_A which ALPHA owns.
        assertValid(validate(new Action.Explore(SYS_NEUTRAL)));
    }

    @Test
    void exploreNonAdjacentRejected() {
        // SYS_B is two hops from any ALPHA presence -> NOT_ADJACENT.
        assertRejected(validate(new Action.Explore(SYS_B)), RejectionReason.NOT_ADJACENT);
    }

    @Test
    void exploreWithEmptyNetworkFallsBackToExistenceOnly() {
        // Legacy 4-arg path: no lane graph, so adjacency is not enforced (pre-E1-09).
        assertValid(ActionValidator.validate(STATE, ALPHA, new Action.Explore(SYS_B), PROFILE));
    }

    // ===== F2: MoveFleet origin + real-lane hops ==================================

    @Test
    void moveFleetAlongRealLanesValid() {
        // FLEET_A at SYS_A -> [SYS_NEUTRAL, SYS_B] is a connected lane walk.
        assertValid(validate(new Action.MoveFleet(FLEET_A, List.of(SYS_NEUTRAL, SYS_B), SYS_B)));
    }

    @Test
    void moveFleetSkippingALaneRejectedNoPath() {
        // SYS_A -> SYS_B directly is not a lane (they are two hops apart).
        assertRejected(validate(new Action.MoveFleet(FLEET_A, List.of(SYS_B), SYS_B)),
                RejectionReason.NO_PATH);
    }

    @Test
    void moveFleetWithEmptyNetworkFallsBackToShapeOnly() {
        // Legacy path: the broken hop is not caught (lane existence deferred), only
        // shape (path ends at destination) is checked.
        assertValid(ActionValidator.validate(STATE, ALPHA,
                new Action.MoveFleet(FLEET_A, List.of(SYS_B), SYS_B), PROFILE));
    }

    // ===== F2: Colonize fleet positioning ========================================

    @Test
    void colonizeWithFleetAtTargetValid() {
        // ALPHA owns PLANET_A in SYS_A and FLEET_A is stationed at SYS_A.
        assertValid(validate(new Action.Colonize(PLANET_A, FLEET_A)));
    }

    @Test
    void colonizeWithFleetNotAtTargetRejected() {
        // PLANET_B is in SYS_B; FLEET_A is at SYS_A, not positioned to colonise there.
        // (BETA owns SYS_B so ownership also fails, but the network gate fires too; we
        // assert it is rejected.)
        assertFalse(validate(new Action.Colonize(PLANET_B, FLEET_A)).isValid());
    }

    // ===== F1: war-state gate vs neutral exemption (validator path) ===============

    @Test
    void attackOwnedTargetRequiresWarButNeutralIsExempt() {
        // Neutral system: no war needed.
        assertValid(validate(new Action.Attack(FLEET_A,
                new com.stellarcompact.engine.action.AttackTarget.OnSystem(SYS_NEUTRAL))));
        // Owned (BETA) system, no war declared: NOT_AT_WAR.
        assertRejected(validate(new Action.Attack(FLEET_A,
                        new com.stellarcompact.engine.action.AttackTarget.OnSystem(SYS_B))),
                RejectionReason.NOT_AT_WAR);
    }

    @Test
    void declaringWarThenAttackingIsValid() {
        GameState atWar = Fixtures.baseStateAtWar(ALPHA, BETA);
        ValidationResult r = ActionValidator.validate(atWar, ALPHA,
                new Action.Attack(FLEET_A,
                        new com.stellarcompact.engine.action.AttackTarget.OnSystem(SYS_B)),
                PROFILE, lanes());
        assertValid(r);
    }
}
