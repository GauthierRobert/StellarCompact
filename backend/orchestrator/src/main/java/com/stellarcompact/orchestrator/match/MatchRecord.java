package com.stellarcompact.orchestrator.match;

import com.stellarcompact.engine.resolve.PublicEvent;
import com.stellarcompact.engine.resolve.SubmittedAction;
import com.stellarcompact.engine.state.GameState;

import java.util.ArrayList;
import java.util.List;

/**
 * The immutable, replayable record of one headless match driven by
 * {@link HeadlessMatchRunner} (board card E3-03). It is the concrete realisation of
 * the engine's replay contract (skill {@code game-engine-determinism}, "Replay
 * contract"): {@code (gameSeed, ordered action log per tick)} plus, for verification,
 * the per-tick canonical state hash and public-event stream the live run produced.
 *
 * <p><b>What it captures, per resolved tick</b> (see {@link TickRecord}): the absolute
 * tick the batch resolved on, the ordered, already-validated {@link SubmittedAction}s
 * submitted that tick (the action log - the only thing replay needs to reproduce the
 * run), the canonical post-tick state hash ({@code StateHasher.sha256Hex}) and the
 * ordered public-event stream. The hash and events are <em>witnesses</em>: replay
 * re-derives them from the action log alone and asserts they match, which is the proof
 * the match is reproducible.
 *
 * <p><b>Why the hashes are stored, not just the actions.</b> The replay contract is
 * "re-running resolution over the recorded log reproduces every tick exactly". To
 * assert "exactly" we keep the live run's per-tick hashes so the replay can be compared
 * tick-by-tick (and the diverging tick named on failure). The action log is the input
 * to replay; the hash sequence is the expected output.
 *
 * <p>Pure immutable value: no I/O, no clock, no Spring. All lists are defensively
 * copied.
 *
 * @param gameSeed    the per-match seed - root of all seeded RNG (passed straight to
 *                    the resolver, which folds the tick in itself); replay must use the
 *                    same value
 * @param profileName the active balance-profile name (rule 6: numbers live in config);
 *                    recorded for provenance
 * @param ticks       one {@link TickRecord} per resolved tick, in tick order
 * @param finalState  the concluded / tick-limited snapshot the live run ended on
 * @param outcome     why the match ended (a victory concluded it, or the tick limit hit)
 */
public record MatchRecord(long gameSeed, String profileName, List<TickRecord> ticks,
                          GameState finalState, Outcome outcome) {

    public MatchRecord {
        if (profileName == null || profileName.isBlank()) {
            throw new IllegalArgumentException("MatchRecord.profileName must be non-blank");
        }
        if (finalState == null) {
            throw new IllegalArgumentException("MatchRecord.finalState must be set");
        }
        if (outcome == null) {
            throw new IllegalArgumentException("MatchRecord.outcome must be set");
        }
        ticks = List.copyOf(ticks);
    }

    /** How the match ended. */
    public enum Outcome {
        /** A victory condition fired and the resolver concluded the match (status -> CONCLUDED). */
        VICTORY,
        /** No victory fired within the configured tick budget; the runner stopped at the limit. */
        TICK_LIMIT
    }

    /** @return the number of resolved ticks recorded. */
    public int tickCount() {
        return ticks.size();
    }

    /** @return the per-tick canonical state hashes, in tick order. */
    public List<String> perTickHashes() {
        List<String> out = new ArrayList<>(ticks.size());
        for (TickRecord t : ticks) {
            out.add(t.stateHash());
        }
        return List.copyOf(out);
    }

    /** @return the action log keyed positionally: the i-th entry is tick i's submitted batch. */
    public List<List<SubmittedAction>> actionLog() {
        List<List<SubmittedAction>> out = new ArrayList<>(ticks.size());
        for (TickRecord t : ticks) {
            out.add(t.actions());
        }
        return List.copyOf(out);
    }

    /** @return the flat, tick-ordered concatenation of every tick's events (the full event log). */
    public List<PublicEvent> eventLog() {
        List<PublicEvent> out = new ArrayList<>();
        for (TickRecord t : ticks) {
            out.addAll(t.events());
        }
        return List.copyOf(out);
    }

    /**
     * One resolved tick's recorded slice.
     *
     * @param tick      the absolute tick this batch resolved on
     * @param actions   the ordered, already-validated actions submitted this tick (the
     *                  replay input); empty when every faction held
     * @param stateHash the canonical post-tick state hash (the replay witness)
     * @param events    the ordered public-event stream this tick emitted
     */
    public record TickRecord(long tick, List<SubmittedAction> actions, String stateHash,
                             List<PublicEvent> events) {
        public TickRecord {
            if (stateHash == null || stateHash.isBlank()) {
                throw new IllegalArgumentException("TickRecord.stateHash must be non-blank");
            }
            actions = List.copyOf(actions);
            events = List.copyOf(events);
        }
    }
}
