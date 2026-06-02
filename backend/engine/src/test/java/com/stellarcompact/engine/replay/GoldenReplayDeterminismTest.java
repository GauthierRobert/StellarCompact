package com.stellarcompact.engine.replay;

import com.stellarcompact.engine.config.BalanceProfile;
import com.stellarcompact.engine.map.LaneNetwork;
import com.stellarcompact.engine.resolve.PublicEvent;
import com.stellarcompact.engine.resolve.SubmittedAction;
import com.stellarcompact.engine.state.GameState;
import com.stellarcompact.engine.state.GameStatus;
import com.stellarcompact.engine.state.RouteStatus;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The end-to-end determinism replay test (board card E1-17; skill
 * {@code game-engine-determinism} "Replay contract", game-design 07 section 5).
 *
 * <p>It takes the recorded {@code (seed, ordered action log)} of {@link ReplayScenario} - a
 * four-faction match that genuinely exercises economy, market, development, movement +
 * interception, combat + capture, interdiction, diplomacy, espionage and influence, triggers
 * a stream of public events, and drives a domination victory / faction elimination - and
 * folds it tick by tick through {@link ReplayHarness}.
 *
 * <p>The core assertion is that the canonical state hash is identical at <em>every</em> tick
 * across two <strong>fully independent</strong> runs (each rebuilds the initial state, the
 * action log and the lane network from scratch and replays from tick zero), and that the
 * <strong>public-event stream is identical too</strong> (events are part of the determinism
 * contract even though they are transient and deliberately not in the hashed snapshot). Two
 * byte-identical runs prove no hidden wall-clock, unseeded RNG or map-iteration-order leak
 * crept into any resolution step.
 *
 * <p>The remaining tests pin the scenario's <em>shape</em> (so a future change that silently
 * stops exercising a subsystem fails loudly) and prove the hash is genuinely sensitive (a
 * one-bit seed change diverges the run), so "identical" is meaningful rather than trivially
 * true.
 */
class GoldenReplayDeterminismTest {

    private static ReplayHarness.Run freshRun() {
        // Everything rebuilt from scratch: independent state, action log and lane graph.
        GameState initial = ReplayScenario.initialState();
        Map<Long, List<SubmittedAction>> log = ReplayScenario.actionLog();
        BalanceProfile profile = ReplayScenario.profile();
        LaneNetwork lanes = ReplayScenario.lanes();
        return ReplayHarness.run(initial, log, profile, ReplayScenario.SEED, lanes,
                ReplayScenario.TICKS);
    }

    // ===== the core replay-determinism contract ===============================

    @Test
    void twoIndependentRunsProduceIdenticalPerTickHashesAndEvents() {
        ReplayHarness.Run a = freshRun();
        ReplayHarness.Run b = freshRun();

        // The whole-replay equality: same tick count, same hash at every tick, same event
        // stream at every tick. Names the diverging tick on failure.
        ReplayHarness.assertRunsEqual(a, b);

        // And the final snapshots are byte-identical (the bare-GameState surface).
        assertEquals(a.finalState(), b.finalState(),
                "the concluded snapshot must reproduce identically across independent runs");
        assertEquals(ReplayScenario.TICKS, a.tickCount(), "replayed the full tick budget");
    }

    @Test
    void aThirdIndependentRunAgreesTooNoFlakiness() {
        // A third run guards against a 50/50 nondeterminism that could pass a single A/B
        // comparison by luck; three independent rebuilds must all agree.
        ReplayHarness.Run a = freshRun();
        ReplayHarness.Run b = freshRun();
        ReplayHarness.Run c = freshRun();
        ReplayHarness.assertRunsEqual(a, b);
        ReplayHarness.assertRunsEqual(b, c);
        assertEquals(a.perTickHashes(), c.perTickHashes());
    }

    @Test
    void theEventLogIsIdenticalAndStableAcrossRuns() {
        // Compare the flat, tick-ordered event log explicitly (append-only, ordered by tick).
        List<PublicEvent> logA = freshRun().eventLog();
        List<PublicEvent> logB = freshRun().eventLog();
        assertEquals(logA, logB, "the append-only public-event log reproduces byte-for-byte");
        assertFalse(logA.isEmpty(), "the scenario must emit a non-trivial event stream");
    }

    // ===== sensitivity: identical is meaningful, not trivial ==================

    @Test
    void aDifferentSeedDivergesTheRunProvingTheHashIsSeedSensitive() {
        ReplayHarness.Run base = freshRun();
        ReplayHarness.Run flipped = ReplayHarness.run(
                ReplayScenario.initialState(), ReplayScenario.actionLog(), ReplayScenario.profile(),
                ReplayScenario.SEED ^ 1L, ReplayScenario.lanes(), ReplayScenario.TICKS);
        // Some seeded outcome (a combat variance roll, an espionage roll) must differ, so at
        // least one tick's hash diverges. If every hash matched, the seed would be ignored.
        assertNotEquals(base.perTickHashes(), flipped.perTickHashes(),
                "flipping one seed bit must perturb at least one tick's seeded outcome");
    }

    // ===== the scenario genuinely exercises each subsystem ====================

    @Test
    void theScenarioExercisesEverySubsystemViaItsEventsAndFinalState() {
        ReplayHarness.Run run = freshRun();
        List<PublicEvent> log = run.eventLog();
        Set<String> kinds = new HashSet<>();
        for (PublicEvent e : log) {
            kinds.add(e.type());
        }

        // Diplomacy (E1-12): an alliance signed + a NAP broken + a war declared.
        assertTrue(kinds.contains("TreatySigned"), "diplomacy: alliance accepted -> TreatySigned");
        assertTrue(kinds.contains("AllianceFormed"), "diplomacy: alliance accepted -> AllianceFormed");
        assertTrue(kinds.contains("TreatyBroken"), "diplomacy: the NAP was broken");
        assertTrue(kinds.contains("WarDeclared"), "diplomacy: war declared on beta");

        // Combat + capture (E1-10): at least one battle + at least one system captured.
        assertTrue(kinds.contains("BattleResolved"), "combat: a battle was resolved");
        assertTrue(kinds.contains("SystemCaptured"), "combat: a system changed hands by assault");

        // Interdiction (E1-11): the beta route was raided.
        assertTrue(kinds.contains("RouteRaided"), "interdiction: a route shipment was raided");

        // Economy (E1-07 establish route announcement).
        assertTrue(kinds.contains("RouteEstablished"), "economy: a route was established");

        // Victory / lifecycle (E1-15): beta eliminated and a victory achieved.
        assertTrue(kinds.contains("FactionEliminated"), "lifecycle: beta was eliminated");
        assertTrue(kinds.contains("VictoryAchieved"), "lifecycle: the active condition fired");

        GameState end = run.finalState();
        // Lifecycle transition RUNNING -> CONCLUDED happened on the victory tick.
        assertEquals(GameStatus.CONCLUDED, end.status(),
                "the match concluded once the domination condition fired");

        // Combat: both beta systems are now alpha's (capture applied to the snapshot).
        assertEquals(ReplayScenario.ALPHA,
                end.systems().get(ReplayScenario.BETA_FRONT).owner().orElseThrow(),
                "alpha captured the beta front");
        assertEquals(ReplayScenario.ALPHA,
                end.systems().get(ReplayScenario.BETA_HOME).owner().orElseThrow(),
                "alpha captured the beta capital");

        // Diplomacy: the war set recorded the alpha/beta war; reputation moved (war + break).
        assertTrue(end.atWar(ReplayScenario.ALPHA, ReplayScenario.BETA), "war is in the war set");
        assertTrue(end.factions().get(ReplayScenario.ALPHA).reputation() < 0.0,
                "alpha paid reputation for the unprovoked war + broken NAP");

        // Interdiction: the beta route is now BLOCKADED.
        assertEquals(RouteStatus.BLOCKADED,
                end.routes().get(ReplayScenario.BETA_ROUTE).status(),
                "the beta route was blockaded");

        // Market (E1-07): the crossing pair settled - the resting SELL is no longer OPEN.
        assertNotEquals("OPEN",
                end.marketOrders().get(ReplayScenario.SELL_MINERALS).status().name(),
                "the resting SELL was matched (fully or partially), not left OPEN");

        // Influence (E1-14): alpha (monument + capitals + alliance + decay) holds a positive,
        // changed Influence stockpile - the influence economy ran every tick.
        double alphaInfluence = end.factions().get(ReplayScenario.ALPHA).stockpiles().influence();
        assertTrue(alphaInfluence > 0.0, "alpha accrued/retained Influence over the match");
        assertNotEquals(200.0, alphaInfluence,
                "alpha's Influence moved from its starting 200 (accrual + decay ran)");
    }

    @Test
    void everyTickHashIsDistinctFromTheNextProvingStateActuallyEvolves() {
        // A degenerate scenario (no-op every tick) could produce identical hashes tick to
        // tick; this guards that the scenario truly moves state most ticks, so the per-tick
        // determinism assertion is exercised over genuinely different snapshots - not the
        // same frozen state hashed N times.
        List<String> hashes = freshRun().perTickHashes();
        int distinct = (int) hashes.stream().distinct().count();
        assertTrue(distinct >= hashes.size() - 2,
                "almost every tick changes the snapshot (state genuinely evolves): "
                        + distinct + " distinct of " + hashes.size());
    }
}
