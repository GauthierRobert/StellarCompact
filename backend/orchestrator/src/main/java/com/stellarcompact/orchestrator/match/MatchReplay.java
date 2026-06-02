package com.stellarcompact.orchestrator.match;

import com.stellarcompact.engine.config.BalanceProfile;
import com.stellarcompact.engine.hash.StateHasher;
import com.stellarcompact.engine.map.LaneNetwork;
import com.stellarcompact.engine.resolve.PublicEvent;
import com.stellarcompact.engine.resolve.ResolveResult;
import com.stellarcompact.engine.resolve.Resolver;
import com.stellarcompact.engine.state.GameState;
import com.stellarcompact.engine.state.GameStatus;

import java.util.ArrayList;
import java.util.List;

/**
 * The deterministic replay/seek driver (board card E9-02; game-design 07 section 5;
 * architecture 03 section 4 "matches are reproducible from (seed, action log)"). It takes
 * a recorded {@link MatchRecord} - the {@code (gameSeed, ordered action log per tick)} a
 * live run produced - and re-resolves it tick-by-tick through the <em>same</em> pure
 * {@link Resolver} the live run used, materialising the full per-tick {@link GameState}
 * snapshot and public-event stream so any tick can be <em>sought</em> directly.
 *
 * <p><b>Why a timeline, not just hashes.</b> {@link HeadlessMatchRunner#replay} re-derives
 * each tick's hash and asserts it against the witness - that is the determinism proof, but
 * it discards the intermediate snapshots. A spectator scrubbing the match needs the actual
 * state at an arbitrary tick (to render the overlay/leaderboard) and that tick's events.
 * {@link #build} folds the log once and keeps every post-tick snapshot, so {@link #seek}
 * is an O(1) lookup - seeking to tick N yields exactly the state/events of tick N,
 * independent of how you got there (the client never replays forward).
 *
 * <p><b>One resolution path (the card's constraint).</b> This reuses
 * {@link Resolver#resolveResult} with the recorded {@code gameSeed} and the constant-seed,
 * resolve-then-advance fold - byte-identical to {@link HeadlessMatchRunner#run} and
 * {@link HeadlessMatchRunner#replay}. It does NOT re-run bots, WorldView projection or
 * validation (the recorded log already captured the validated batch); it only re-folds the
 * log. As a safety net it re-asserts each re-derived hash against the recorded witness, so
 * a corrupted/incompatible log fails loudly at {@link #build} time rather than silently
 * rendering a wrong frame.
 *
 * <p>Pure and single-threaded, like every replay seam (skill {@code
 * game-engine-determinism} "Concurrency boundary"): no I/O, no clock, no randomness here -
 * only calls into the pure resolver and the canonical hash.
 */
public final class MatchReplay {

    private final long gameSeed;
    private final List<Frame> frames;

    private MatchReplay(long gameSeed, List<Frame> frames) {
        this.gameSeed = gameSeed;
        this.frames = List.copyOf(frames);
    }

    /**
     * One resolved tick of the replay: the absolute tick, the full post-tick snapshot, the
     * ordered public-event stream that tick emitted and the canonical state hash (the
     * determinism witness, re-derived here and asserted equal to the recorded one).
     *
     * @param tick      the absolute tick this frame represents
     * @param state     the authoritative post-resolution snapshot at this tick
     * @param events    the public events emitted on this tick, in resolver-emission order
     * @param stateHash the canonical post-tick hash (equal to the recorded witness)
     */
    public record Frame(long tick, GameState state, List<PublicEvent> events, String stateHash) {
        public Frame {
            if (state == null) {
                throw new IllegalArgumentException("Frame.state must be set");
            }
            if (stateHash == null || stateHash.isBlank()) {
                throw new IllegalArgumentException("Frame.stateHash must be non-blank");
            }
            events = List.copyOf(events);
        }
    }

    /**
     * Build the replay timeline by folding the recorded action log from {@code initialState}
     * through the resolver, capturing the post-tick snapshot + events for every recorded tick.
     * Each re-derived hash is asserted against the recorded witness (a divergence names the
     * tick and fails fast - the determinism guard).
     *
     * @param record       the recorded match (its {@code gameSeed} is the resolution seed)
     * @param initialState the same starting snapshot the live run began from
     * @param profile      the active balance profile (the live run's profile)
     * @param lanes        the active-region lane network ({@link LaneNetwork#EMPTY} if none)
     * @return a seekable timeline of per-tick frames in tick order
     * @throws IllegalStateException if a re-derived hash diverges from the recorded witness
     */
    public static MatchReplay build(MatchRecord record, GameState initialState,
                                    BalanceProfile profile, LaneNetwork lanes) {
        if (record == null) {
            throw new IllegalArgumentException("build.record must be set");
        }
        if (initialState == null) {
            throw new IllegalArgumentException("build.initialState must be set");
        }
        if (profile == null) {
            throw new IllegalArgumentException("build.profile must be set");
        }
        LaneNetwork network = lanes == null ? LaneNetwork.EMPTY : lanes;
        long gameSeed = record.gameSeed();

        List<Frame> frames = new ArrayList<>(record.tickCount());
        GameState state = initialState;
        for (MatchRecord.TickRecord tickRecord : record.ticks()) {
            // Re-fold the recorded validated batch straight through the resolver - the one
            // and only resolution path. Constant seed; the resolver folds the tick in.
            ResolveResult result = Resolver.resolveResult(
                    state, tickRecord.actions(), profile, gameSeed, network);
            GameState resolved = result.state();

            String hash = StateHasher.sha256Hex(resolved);
            if (!hash.equals(tickRecord.stateHash())) {
                throw new IllegalStateException(
                        "replay diverged at tick " + tickRecord.tick()
                                + ":\n  recorded = " + tickRecord.stateHash()
                                + "\n  replay   = " + hash);
            }
            frames.add(new Frame(tickRecord.tick(), resolved, result.events(), hash));

            // Resolve-then-advance, exactly like the live loop. A concluded match is not
            // advanced past the concluded tick.
            state = resolved.status() == GameStatus.CONCLUDED
                    ? resolved
                    : resolved.withTick(tickRecord.tick() + 1);
        }
        return new MatchReplay(gameSeed, frames);
    }

    /** @return the per-match seed - the reproducibility root the timeline was built from. */
    public long gameSeed() {
        return gameSeed;
    }

    /** @return the number of resolved ticks in the timeline. */
    public int tickCount() {
        return frames.size();
    }

    /** @return the absolute tick of the first frame, or {@code -1} if the timeline is empty. */
    public long firstTick() {
        return frames.isEmpty() ? -1 : frames.getFirst().tick();
    }

    /** @return the absolute tick of the last frame, or {@code -1} if the timeline is empty. */
    public long lastTick() {
        return frames.isEmpty() ? -1 : frames.getLast().tick();
    }

    /** @return all frames in tick order (immutable). */
    public List<Frame> frames() {
        return frames;
    }

    /**
     * Seek directly to the frame for absolute tick {@code tick}. Order-independent: the
     * frame returned is exactly the state/events of that tick no matter the access order
     * (the timeline was folded once at {@link #build}). The supported range is
     * {@code [firstTick(), lastTick()]}; out-of-range ticks are clamped to the nearest end
     * so a scrubbing client can drag freely without erroring.
     *
     * @return the frame at (or clamped to) {@code tick}; {@code null} only if the timeline
     * is empty
     */
    public Frame seek(long tick) {
        if (frames.isEmpty()) {
            return null;
        }
        long clamped = Math.max(firstTick(), Math.min(lastTick(), tick));
        // Frames are tick-ordered and (for the headless/in-memory loop) contiguous, but we
        // scan rather than do index arithmetic so the seek is correct for any tick numbering.
        Frame best = frames.getFirst();
        for (Frame f : frames) {
            if (f.tick() == clamped) {
                return f;
            }
            if (f.tick() <= clamped) {
                best = f;
            }
        }
        return best;
    }
}
