package com.stellarcompact.engine.replay;

import com.stellarcompact.engine.config.BalanceProfile;
import com.stellarcompact.engine.hash.GoldenStateHash;
import com.stellarcompact.engine.map.LaneNetwork;
import com.stellarcompact.engine.resolve.PublicEvent;
import com.stellarcompact.engine.resolve.ResolveResult;
import com.stellarcompact.engine.resolve.Resolver;
import com.stellarcompact.engine.resolve.SubmittedAction;
import com.stellarcompact.engine.state.GameState;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * A small, reusable end-to-end replay driver for the deterministic engine (board card
 * E1-17; skill {@code game-engine-determinism} "Replay contract"). TEST scope only.
 *
 * <p>Given an immutable starting snapshot, a per-tick ordered action log, the active
 * {@link BalanceProfile}, the gameSeed and the static {@link LaneNetwork}, it folds the
 * match tick by tick through {@link Resolver#resolveResult} and records, for every tick,
 * the canonical state hash ({@link GoldenStateHash#sha256Hex}) and the ordered
 * {@link PublicEvent} stream that tick emitted. The {@link Run} result is the per-tick
 * hash sequence plus the per-tick event sequence - the two things the replay contract
 * requires to be reproducible.
 *
 * <p>How a tick advances: the {@link Resolver} is a pure state-to-state fold that reads
 * {@code state.tick()} (to seed RNG and stamp events) but never bumps it; advancing the
 * tick is the caller job (exactly as the orchestrator and {@code MovementResolutionTest}
 * do). This harness drives the canonical loop: resolve the tick action slice, record
 * (hash, events), then advance the snapshot to {@code tick + 1}. The action log is keyed
 * by the absolute tick a batch is submitted on; a tick with no entry resolves an empty
 * batch (every faction held).
 *
 * <p>Why this proves determinism: running the same inputs twice - from independently
 * rebuilt state - must yield two byte-identical {@link Run}s: identical per-tick hashes
 * (no hidden wall-clock / unseeded RNG / map-iteration-order leak perturbs the snapshot)
 * AND an identical per-tick event stream (events are part of the determinism contract even
 * though they are transient and deliberately NOT in the hashed snapshot). Future cards
 * (E3-03 headless runner) can reuse the same {@link Run}/{@link #run} seam.
 *
 * <p>Pure: no I/O, no clock, no randomness here - it only orchestrates calls into the pure
 * resolver and the test-scope canonical hash.
 */
final class ReplayHarness {

    private ReplayHarness() {
    }

    /**
     * The per-tick observable output of a full replay: for each resolved tick, in order, the
     * canonical post-tick state hash and the ordered public-event stream that tick emitted.
     */
    record Run(List<String> perTickHashes, List<List<PublicEvent>> perTickEvents,
               GameState finalState) {
        Run {
            perTickHashes = List.copyOf(perTickHashes);
            List<List<PublicEvent>> evts = new ArrayList<>(perTickEvents.size());
            for (List<PublicEvent> tickEvents : perTickEvents) {
                evts.add(List.copyOf(tickEvents));
            }
            perTickEvents = List.copyOf(evts);
        }

        int tickCount() {
            return perTickHashes.size();
        }

        /** @return the flat, tick-ordered concatenation of every tick events (the full event log). */
        List<PublicEvent> eventLog() {
            List<PublicEvent> log = new ArrayList<>();
            for (List<PublicEvent> tickEvents : perTickEvents) {
                log.addAll(tickEvents);
            }
            return List.copyOf(log);
        }
    }

    /**
     * Replay {@code ticks} consecutive ticks from {@code initialState}, applying on each tick
     * the action batch {@code actionLogByTick} holds for that absolute tick (absent/empty =
     * everyone held). Records the canonical hash and the event stream after each tick and
     * advances the snapshot to {@code tick + 1} between iterations.
     */
    static Run run(GameState initialState,
                   Map<Long, List<SubmittedAction>> actionLogByTick,
                   BalanceProfile profile, long seed, LaneNetwork lanes, int ticks) {
        if (ticks <= 0) {
            throw new IllegalArgumentException("ReplayHarness.run: ticks must be > 0");
        }
        List<String> hashes = new ArrayList<>(ticks);
        List<List<PublicEvent>> events = new ArrayList<>(ticks);

        GameState state = initialState;
        for (int i = 0; i < ticks; i++) {
            long tick = state.tick();
            List<SubmittedAction> batch = actionLogByTick.getOrDefault(tick, List.of());

            ResolveResult result = Resolver.resolveResult(state, batch, profile, seed, lanes);

            hashes.add(GoldenStateHash.sha256Hex(result.state()));
            events.add(result.events());

            // Advance to the next tick: the resolver reads tick but never bumps it (the
            // caller owns advancement). This is the source of per-tick RNG variation.
            state = result.state().withTick(tick + 1);
        }
        return new Run(hashes, events, state);
    }

    /**
     * Assert two runs are byte-identical across the whole replay: same tick count, the same
     * canonical hash at every tick, and the same public-event stream at every tick. A failure
     * names the exact tick that diverged.
     */
    static void assertRunsEqual(Run a, Run b) {
        if (a.tickCount() != b.tickCount()) {
            throw new AssertionError("replay tick count diverged: " + a.tickCount()
                    + " vs " + b.tickCount());
        }
        for (int t = 0; t < a.tickCount(); t++) {
            String ha = a.perTickHashes().get(t);
            String hb = b.perTickHashes().get(t);
            if (!ha.equals(hb)) {
                throw new AssertionError("state hash diverged at tick index " + t
                        + ":\n  run A = " + ha + "\n  run B = " + hb);
            }
            List<PublicEvent> ea = a.perTickEvents().get(t);
            List<PublicEvent> eb = b.perTickEvents().get(t);
            if (!ea.equals(eb)) {
                throw new AssertionError("public-event stream diverged at tick index " + t
                        + ":\n  run A = " + ea + "\n  run B = " + eb);
            }
        }
    }
}
