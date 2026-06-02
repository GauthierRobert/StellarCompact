package com.stellarcompact.orchestrator.match;

import com.stellarcompact.engine.config.BalanceProfile;
import com.stellarcompact.engine.map.LaneNetwork;
import com.stellarcompact.engine.resolve.SubmittedAction;
import com.stellarcompact.engine.state.GameState;
import com.stellarcompact.orchestrator.sovereign.ScriptedSovereign;
import com.stellarcompact.orchestrator.sovereign.Sovereign;
import com.stellarcompact.orchestrator.sovereign.SystemAdjacency;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Milestone&nbsp;M1 capstone test (board card E3-03): the headless match runner runs
 * a small, fully galaxy-generated match under scripted bots to a victory or tick limit,
 * and replaying the recorded {@code (seed, action log)} reproduces every tick's canonical
 * state hash exactly. It is the end-to-end proof that the engine + galaxy are
 * reproducible (skill {@code game-engine-determinism} "Replay contract").
 *
 * <p>The scenario is {@link GalaxyMatchScenario}: stars materialised from placement cells,
 * a natural-lane graph generated over them, two well-separated homes promoted into engine
 * active systems, and the lane graph projected into an engine {@link LaneNetwork} + fog
 * {@link SystemAdjacency}. Two peaceful {@link ScriptedSovereign}s build/explore and never
 * conclude, so the match runs to the {@link GalaxyMatchScenario#TICK_LIMIT}.
 */
class HeadlessMatchRunnerTest {

    private static List<Sovereign> bots() {
        // Two deterministic scripted bots, one per seated faction.
        return List.of(
                new ScriptedSovereign(GalaxyMatchScenario.ALPHA),
                new ScriptedSovereign(GalaxyMatchScenario.BETA));
    }

    /** A full live run from a freshly rebuilt galaxy scenario. */
    private static MatchRecord freshRun() {
        return HeadlessMatchRunner.run(
                GalaxyMatchScenario.initialState(),
                bots(),
                GalaxyMatchScenario.profile(),
                GalaxyMatchScenario.laneNetwork(),
                GalaxyMatchScenario.adjacency(),
                GalaxyMatchScenario.TICK_LIMIT);
    }

    // ===== the match actually runs to a terminal state ========================

    @Test
    void aSmallGalaxyRunsHeadlesslyToAVictoryOrTickLimit() {
        MatchRecord record = freshRun();
        assertNotNull(record.outcome(), "the match must reach a terminal outcome");
        // Peaceful bots never conclude, so this scenario hits the tick limit; either
        // terminal outcome satisfies the card. The run produced one record per tick.
        assertEquals(MatchRecord.Outcome.TICK_LIMIT, record.outcome(),
                "the peaceful two-bot match runs out the tick budget (no victory fires)");
        assertEquals(GalaxyMatchScenario.TICK_LIMIT, record.tickCount(),
                "a tick-limited match records exactly the budget of ticks");
        assertFalse(record.perTickHashes().isEmpty(), "the run recorded per-tick hashes");
    }

    @Test
    void theBotsGenuinelyActSoTheRunIsNotADegenerateAllHoldMatch() {
        MatchRecord record = freshRun();
        // At least one tick submitted a real (non-empty) validated action batch, so the
        // determinism proof is exercised over a match where the bots actually do things.
        long actingTicks = record.ticks().stream()
                .filter(t -> !t.actions().isEmpty())
                .count();
        assertTrue(actingTicks > 0,
                "scripted bots must submit at least one validated action across the match");
    }

    // ===== the core E3-03 determinism contract ================================

    @Test
    void replayingTheRecordedLogReproducesEveryTickHashExactly() {
        MatchRecord record = freshRun();
        // Replay the recorded (seed, action log) from a freshly rebuilt initial state
        // through the resolver - no bots, no WorldView, no validation - and assert every
        // tick's hash matches the live witness. replay() throws (naming the tick) on any
        // divergence; returning means every tick agreed.
        List<String> replayed = HeadlessMatchRunner.replay(
                record,
                GalaxyMatchScenario.initialState(),
                GalaxyMatchScenario.profile(),
                GalaxyMatchScenario.laneNetwork());
        assertEquals(record.perTickHashes(), replayed,
                "replay from (seed, action log) reproduces the live per-tick hash sequence");
    }

    @Test
    void twoIndependentLiveRunsProduceIdenticalLogsAndHashes() {
        MatchRecord a = freshRun();
        MatchRecord b = freshRun();

        assertEquals(a.outcome(), b.outcome(), "both live runs reach the same outcome");
        assertEquals(a.tickCount(), b.tickCount(), "both live runs resolve the same tick count");
        assertEquals(a.perTickHashes(), b.perTickHashes(),
                "two independent live runs from the same seed produce identical per-tick hashes");

        // The recorded action logs are identical too (scripted bots are deterministic).
        assertEquals(canonicalLog(a), canonicalLog(b),
                "two independent live runs record identical action logs");

        // And the event streams agree (events are part of the determinism contract).
        assertEquals(a.eventLog(), b.eventLog(),
                "two independent live runs emit identical public-event streams");

        // The concluded/tick-limited snapshots are byte-identical.
        assertEquals(a.finalState(), b.finalState(),
                "the final snapshot reproduces identically across independent runs");
    }

    @Test
    void aThirdRunAndItsReplayAllAgreeNoFlakiness() {
        // Guards a 50/50 nondeterminism that could pass a single A/B by luck: three
        // independent live runs AND a replay of one all line up.
        MatchRecord a = freshRun();
        MatchRecord b = freshRun();
        MatchRecord c = freshRun();
        assertEquals(a.perTickHashes(), b.perTickHashes());
        assertEquals(b.perTickHashes(), c.perTickHashes());

        List<String> replayed = HeadlessMatchRunner.replay(
                c, GalaxyMatchScenario.initialState(),
                GalaxyMatchScenario.profile(), GalaxyMatchScenario.laneNetwork());
        assertEquals(c.perTickHashes(), replayed);
    }

    // ===== identical is meaningful, not trivial ===============================

    @Test
    void aDifferentSeedDivergesTheRunProvingTheHashIsSeedSensitive() {
        MatchRecord base = freshRun();
        // Same galaxy + bots, but resolve under a flipped seed: the resolver derives its
        // per-tick RNG from this seed, so at least one tick's hash must differ. (We reuse
        // the same scenario state/lanes; only the resolution seed changes, which is the
        // cleanest way to prove the hash sequence is genuinely seed-driven.)
        GameState initial = GalaxyMatchScenario.initialState();
        BalanceProfile profile = GalaxyMatchScenario.profile();
        LaneNetwork lanes = GalaxyMatchScenario.laneNetwork();
        SystemAdjacency adjacency = GalaxyMatchScenario.adjacency();
        GameState flippedSeedState = withSeed(initial, initial.gameSeed() ^ 0x9E3779B97F4A7C15L);

        MatchRecord flipped = HeadlessMatchRunner.run(
                flippedSeedState, bots(), profile, lanes, adjacency, GalaxyMatchScenario.TICK_LIMIT);

        assertNotEquals(base.perTickHashes(), flipped.perTickHashes(),
                "a different gameSeed must perturb at least one tick's hash");
    }

    // ===== helpers ============================================================

    /** A flat, tick-then-order canonical view of a record's action log for equality. */
    private static List<List<SubmittedAction>> canonicalLog(MatchRecord record) {
        return record.actionLog();
    }

    /** A copy of {@code state} with a different gameSeed (everything else identical). */
    private static GameState withSeed(GameState state, long seed) {
        return new GameState(seed, state.tick(), state.status(), state.balanceProfileName(),
                state.balanceProfileVersion(), state.factions(), state.systems(), state.fleets(),
                state.treaties(), state.routes(), state.marketOrders(), state.wars());
    }
}
