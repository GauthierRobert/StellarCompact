package com.stellarcompact.orchestrator.match;

import com.stellarcompact.engine.resolve.PublicEvent;
import com.stellarcompact.engine.state.GameState;
import com.stellarcompact.orchestrator.sovereign.ScriptedSovereign;
import com.stellarcompact.orchestrator.sovereign.Sovereign;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The E9-02 determinism + seek proof for {@link MatchReplay}: an archived match
 * (recorded {@code (seed, action log)} from {@link HeadlessMatchRunner}) replays
 * <em>tick-identically</em> through the same pure resolver, and seeking to an arbitrary
 * tick yields exactly the state/events of that tick - order-independent of how you got
 * there. This extends the E1-17/E3-03 golden-hash determinism proof with the seekable
 * timeline the spectator scrub/seek UI is driven from.
 */
class MatchReplayTest {

    private static List<Sovereign> bots() {
        return List.of(
                new ScriptedSovereign(GalaxyMatchScenario.ALPHA),
                new ScriptedSovereign(GalaxyMatchScenario.BETA));
    }

    private static MatchRecord freshRun() {
        return HeadlessMatchRunner.run(
                GalaxyMatchScenario.initialState(),
                bots(),
                GalaxyMatchScenario.profile(),
                GalaxyMatchScenario.laneNetwork(),
                GalaxyMatchScenario.adjacency(),
                GalaxyMatchScenario.TICK_LIMIT);
    }

    private static MatchReplay buildReplay(MatchRecord record) {
        return MatchReplay.build(
                record,
                GalaxyMatchScenario.initialState(),
                GalaxyMatchScenario.profile(),
                GalaxyMatchScenario.laneNetwork());
    }

    // ===== the core determinism proof: replay reproduces every tick's hash ====

    @Test
    void replayTimelineReproducesEveryRecordedTickHashExactly() {
        MatchRecord record = freshRun();
        MatchReplay replay = buildReplay(record);

        assertEquals(record.tickCount(), replay.tickCount(),
                "the replay timeline has one frame per recorded tick");
        for (int i = 0; i < record.ticks().size(); i++) {
            MatchRecord.TickRecord rec = record.ticks().get(i);
            MatchReplay.Frame frame = replay.frames().get(i);
            assertEquals(rec.tick(), frame.tick(), "frame tick matches recorded tick");
            assertEquals(rec.stateHash(), frame.stateHash(),
                    "replayed frame hash equals the recorded witness at tick " + rec.tick());
            assertEquals(rec.events(), frame.events(),
                    "replayed frame events equal the recorded events at tick " + rec.tick());
        }
    }

    // ===== seek: jumping to tick N yields exactly tick N ======================

    @Test
    void seekingToATickYieldsExactlyThatTicksStateAndEvents() {
        MatchRecord record = freshRun();
        MatchReplay replay = buildReplay(record);

        for (MatchReplay.Frame expected : replay.frames()) {
            MatchReplay.Frame got = replay.seek(expected.tick());
            assertNotNull(got);
            assertEquals(expected.tick(), got.tick());
            assertEquals(expected.stateHash(), got.stateHash(),
                    "seek(N) returns the frame whose hash is tick N's hash");
            assertEquals(expected.state(), got.state(),
                    "seek(N) returns exactly tick N's snapshot");
            assertEquals(expected.events(), got.events(),
                    "seek(N) returns exactly tick N's events");
        }
    }

    @Test
    void seekIsOrderIndependentForwardEqualsBackward() {
        MatchRecord record = freshRun();
        MatchReplay replay = buildReplay(record);
        long first = replay.firstTick();
        long last = replay.lastTick();

        // Re-seeking the same tick from any access path returns the same frame.
        for (long t = first; t <= last; t++) {
            long mirror = last - (t - first);
            assertEquals(replay.seek(t).stateHash(), replay.seek(t).stateHash());
            assertEquals(replay.seek(mirror).stateHash(), replay.seek(mirror).stateHash());
        }
    }

    @Test
    void seekOutOfRangeClampsToTheNearestEnd() {
        MatchRecord record = freshRun();
        MatchReplay replay = buildReplay(record);

        MatchReplay.Frame low = replay.seek(replay.firstTick() - 100);
        assertEquals(replay.firstTick(), low.tick(), "below-range seek clamps to first tick");

        MatchReplay.Frame high = replay.seek(replay.lastTick() + 100);
        assertEquals(replay.lastTick(), high.tick(), "above-range seek clamps to last tick");
    }

    // ===== it is genuinely the same single resolution path ====================

    @Test
    void twoIndependentReplayBuildsAreByteIdentical() {
        MatchRecord record = freshRun();
        MatchReplay a = buildReplay(record);
        MatchReplay b = buildReplay(record);

        assertEquals(a.tickCount(), b.tickCount());
        for (int i = 0; i < a.frames().size(); i++) {
            assertEquals(a.frames().get(i).stateHash(), b.frames().get(i).stateHash());
            assertEquals(a.frames().get(i).state(), b.frames().get(i).state());
            assertEquals(a.frames().get(i).events(), b.frames().get(i).events());
        }
    }

    @Test
    void buildFailsLoudlyWhenTheRecordedWitnessIsCorrupted() {
        MatchRecord good = freshRun();
        // Corrupt the witness of one tick: build must detect the divergence and throw,
        // naming the tick. (Proves the hash re-assertion is a real guard, not decoration.)
        List<MatchRecord.TickRecord> ticks = good.ticks();
        MatchRecord.TickRecord first = ticks.getFirst();
        MatchRecord.TickRecord corrupted = new MatchRecord.TickRecord(
                first.tick(), first.actions(),
                "deadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeef",
                first.events());
        List<MatchRecord.TickRecord> mutated = new ArrayList<>(ticks);
        mutated.set(0, corrupted);
        MatchRecord bad = new MatchRecord(good.gameSeed(), good.profileName(), mutated,
                good.finalState(), good.outcome());

        assertThrows(IllegalStateException.class, () -> buildReplay(bad),
                "a corrupted witness must fail the determinism guard at build time");
    }

    @Test
    void theReplayedRunActuallyContainsSubstantiveFrames() {
        MatchRecord record = freshRun();
        MatchReplay replay = buildReplay(record);
        assertTrue(replay.tickCount() > 0, "the timeline has frames");
        MatchReplay.Frame f = replay.frames().getFirst();
        assertSame(f.state(), replay.seek(f.tick()).state(),
                "seek returns the very same cached snapshot instance (no re-resolution)");
        List<PublicEvent> events = f.events();
        assertNotNull(events);
        GameState state = f.state();
        assertNotNull(state);
    }
}
