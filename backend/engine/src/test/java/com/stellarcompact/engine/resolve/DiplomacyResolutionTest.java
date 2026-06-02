package com.stellarcompact.engine.resolve;

import com.stellarcompact.engine.action.Action;
import com.stellarcompact.engine.config.BalanceProfile;
import com.stellarcompact.engine.hash.GoldenStateHash;
import com.stellarcompact.engine.state.GameState;
import com.stellarcompact.engine.state.ResourceBundle;
import com.stellarcompact.engine.state.Treaty;
import com.stellarcompact.engine.state.TreatyId;
import com.stellarcompact.engine.state.TreatyStatus;
import com.stellarcompact.engine.state.TreatyType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.stellarcompact.engine.resolve.ResolveFixtures.ALPHA;
import static com.stellarcompact.engine.resolve.ResolveFixtures.BETA;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The DIPLOMATIC_STATE resolution step (board card E1-12; game-design 04, 03 step 1).
 * Hand-authored action lists assert the treaty lifecycle, reputation ledger moves
 * (all coefficients from config, rule 6), war-state recording and tribute transfer -
 * with golden-hash determinism checks. No LLM, no randomness, no wall-clock.
 */
class DiplomacyResolutionTest {

    private static final BalanceProfile PROFILE = ResolveFixtures.diplomacyProfile();
    private static final TreatyId T1 = new TreatyId("t1");

    private static GameState resolve(GameState before, SubmittedAction... actions) {
        return Resolver.resolve(before, List.of(actions), PROFILE, before.gameSeed());
    }

    private static Treaty treaty(TreatyType type, TreatyStatus status, long expires) {
        return new Treaty(T1, type, List.of(ALPHA, BETA), Map.of(), 0L, expires, status);
    }

    private static GameState withTreaty(Treaty t) {
        return ResolveFixtures.baseState().withTreaty(t);
    }

    // ===== AcceptTreaty / DeclineTreaty / ProposeTreaty =======================

    @Test
    void acceptTreatyActivatesProposedTreaty() {
        GameState before = withTreaty(treaty(TreatyType.NON_AGGRESSION, TreatyStatus.PROPOSED, 100));
        GameState after = resolve(before, new SubmittedAction(BETA, new Action.AcceptTreaty(T1), 0));
        assertEquals(TreatyStatus.ACTIVE, after.treaties().get(T1).status());
    }

    @Test
    void declineTreatyClosesProposalWithoutActivating() {
        GameState before = withTreaty(treaty(TreatyType.ALLIANCE, TreatyStatus.PROPOSED, 100));
        GameState after = resolve(before, new SubmittedAction(BETA, new Action.DeclineTreaty(T1), 0));
        assertEquals(TreatyStatus.EXPIRED, after.treaties().get(T1).status());
        // Declining a proposal is not a betrayal: no reputation penalty.
        assertEquals(0.0, after.factions().get(BETA).reputation());
    }

    @Test
    void proposeTreatyMintsAProposedTreaty() {
        GameState before = ResolveFixtures.baseState();
        GameState after = resolve(before, new SubmittedAction(ALPHA,
                new Action.ProposeTreaty(BETA, TreatyType.NON_AGGRESSION, Map.of(),
                        Optional.of(20)), 0));
        assertEquals(1, after.treaties().size());
        Treaty minted = after.treaties().values().iterator().next();
        assertEquals(TreatyStatus.PROPOSED, minted.status());
        assertEquals(TreatyType.NON_AGGRESSION, minted.type());
        assertTrue(minted.parties().contains(ALPHA) && minted.parties().contains(BETA));
    }

    @Test
    void proposeTreatyIsDeterministicAcrossRuns() {
        GameState before = ResolveFixtures.baseState();
        SubmittedAction p = new SubmittedAction(ALPHA,
                new Action.ProposeTreaty(BETA, TreatyType.ALLIANCE, Map.of(), Optional.of(20)), 0);
        GameState a = resolve(before, p);
        GameState b = resolve(before, p);
        assertEquals(GoldenStateHash.sha256Hex(a), GoldenStateHash.sha256Hex(b));
    }

    // ===== BreakTreaty: penalty scales with weight x remaining duration ========

    @Test
    void breakTreatyMarksBrokenAndPenalisesByWeightTimesRemainingDuration() {
        // base tick is 5; expires at 25 -> remaining 20. Alliance weight 3.0,
        // penaltyBreakTreaty coefficient 0.5 -> penalty = 0.5 * 3.0 * 20 = 30.
        GameState before = withTreaty(treaty(TreatyType.ALLIANCE, TreatyStatus.ACTIVE, 25));
        GameState after = resolve(before, new SubmittedAction(ALPHA, new Action.BreakTreaty(T1), 0));
        assertEquals(TreatyStatus.BROKEN, after.treaties().get(T1).status());
        assertEquals(-30.0, after.factions().get(ALPHA).reputation(), 1e-9);
        // The other party is not penalised for being betrayed.
        assertEquals(0.0, after.factions().get(BETA).reputation(), 1e-9);
    }

    @Test
    void breakTreatyZeroRemainingDurationStillTerminatesNoPenalty() {
        // expires exactly at the current tick (5): remaining duration 0 -> penalty 0.
        GameState before = withTreaty(treaty(TreatyType.NON_AGGRESSION, TreatyStatus.ACTIVE, 5));
        GameState after = resolve(before, new SubmittedAction(ALPHA, new Action.BreakTreaty(T1), 0));
        assertEquals(TreatyStatus.BROKEN, after.treaties().get(T1).status());
        assertEquals(0.0, after.factions().get(ALPHA).reputation(), 1e-9);
    }

    // ===== DeclareWar: sets war state + unprovoked-war reputation cost =========

    @Test
    void declareWarRecordsWarStateAndPenalisesDeclarer() {
        GameState before = ResolveFixtures.baseState();
        GameState after = resolve(before, new SubmittedAction(ALPHA, new Action.DeclareWar(BETA), 0));
        assertTrue(after.atWar(ALPHA, BETA), "war recorded");
        assertTrue(after.atWar(BETA, ALPHA), "war is symmetric");
        // penaltyUnprovokedWar = 15.0 in the diplomacy profile.
        assertEquals(-15.0, after.factions().get(ALPHA).reputation(), 1e-9);
        assertEquals(0.0, after.factions().get(BETA).reputation(), 1e-9);
    }

    @Test
    void redeclaringExistingWarIsIdempotentNoSecondPenalty() {
        GameState before = ResolveFixtures.baseState().withWar(ALPHA, BETA, 1L);
        double repBefore = before.factions().get(ALPHA).reputation();
        GameState after = resolve(before, new SubmittedAction(ALPHA, new Action.DeclareWar(BETA), 0));
        assertTrue(after.atWar(ALPHA, BETA));
        assertEquals(repBefore, after.factions().get(ALPHA).reputation(), 1e-9,
                "re-declaring an existing war must not re-apply the penalty");
    }

    // ===== Tribute: resource transfer through the ledger discipline ============

    @Test
    void tributeTransfersResourcesFromSenderToReceiver() {
        GameState before = ResolveFixtures.baseState();
        double aBefore = before.factions().get(ALPHA).stockpiles().minerals();
        double bBefore = before.factions().get(BETA).stockpiles().minerals();
        ResourceBundle dues = new ResourceBundle(0, 100, 0, 0, 0);
        GameState after = resolve(before, new SubmittedAction(ALPHA, new Action.Tribute(BETA, dues), 0));
        assertEquals(aBefore - 100, after.factions().get(ALPHA).stockpiles().minerals(), 1e-9);
        assertEquals(bBefore + 100, after.factions().get(BETA).stockpiles().minerals(), 1e-9);
    }

    @Test
    void tributeConservesTotalResources() {
        GameState before = ResolveFixtures.baseState();
        ResourceBundle dues = new ResourceBundle(10, 20, 0, 5, 0);
        GameState after = resolve(before, new SubmittedAction(ALPHA, new Action.Tribute(BETA, dues), 0));
        double totalBeforeMin = before.factions().get(ALPHA).stockpiles().minerals()
                + before.factions().get(BETA).stockpiles().minerals();
        double totalAfterMin = after.factions().get(ALPHA).stockpiles().minerals()
                + after.factions().get(BETA).stockpiles().minerals();
        assertEquals(totalBeforeMin, totalAfterMin, 1e-9, "tribute moves, never mints/burns");
    }

    // ===== determinism & wiring ===============================================

    @Test
    void diplomacyStepIsDeterministicAndOrderInsensitive() {
        GameState before = withTreaty(treaty(TreatyType.NON_AGGRESSION, TreatyStatus.ACTIVE, 30));
        SubmittedAction brk = new SubmittedAction(ALPHA, new Action.BreakTreaty(T1), 0);
        SubmittedAction war = new SubmittedAction(BETA, new Action.DeclareWar(ALPHA), 0);
        SubmittedAction trib = new SubmittedAction(BETA, new Action.Tribute(ALPHA,
                new ResourceBundle(0, 0, 5, 0, 0)), 1);
        GameState a = resolve(before, brk, war, trib);
        GameState b = resolve(before, trib, war, brk); // shuffled input
        assertEquals(GoldenStateHash.sha256Hex(a), GoldenStateHash.sha256Hex(b));
    }

    @Test
    void unrelatedTickLeavesDiplomacyUntouched() {
        GameState before = withTreaty(treaty(TreatyType.ALLIANCE, TreatyStatus.ACTIVE, 30));
        GameState after = resolve(before); // no actions
        assertEquals(GoldenStateHash.sha256Hex(before), GoldenStateHash.sha256Hex(after));
        assertFalse(after.atWar(ALPHA, BETA));
    }
}
