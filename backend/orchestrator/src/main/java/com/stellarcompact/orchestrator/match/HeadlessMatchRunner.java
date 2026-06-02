package com.stellarcompact.orchestrator.match;

import com.stellarcompact.engine.action.Action;
import com.stellarcompact.engine.action.AgentResponse;
import com.stellarcompact.engine.config.BalanceProfile;
import com.stellarcompact.engine.hash.StateHasher;
import com.stellarcompact.engine.map.LaneNetwork;
import com.stellarcompact.engine.resolve.ResolveResult;
import com.stellarcompact.engine.resolve.Resolver;
import com.stellarcompact.engine.resolve.SubmittedAction;
import com.stellarcompact.engine.state.FactionId;
import com.stellarcompact.engine.state.GameState;
import com.stellarcompact.engine.state.GameStatus;
import com.stellarcompact.engine.validation.ActionValidator;
import com.stellarcompact.engine.validation.ValidationResult;
import com.stellarcompact.orchestrator.sovereign.Sovereign;
import com.stellarcompact.orchestrator.sovereign.SystemAdjacency;
import com.stellarcompact.orchestrator.sovereign.WorldView;
import com.stellarcompact.orchestrator.sovereign.WorldViewBuilder;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The headless match runner and determinism-replay driver - the Milestone&nbsp;M1
 * capstone (board card E3-03; roadmap Phase&nbsp;1 "Headless match runner; verify
 * determinism via replay"). It proves the engine + galaxy are reproducible end to end:
 * a small galaxy plays out under scripted bots, the validated per-tick action log is
 * recorded, and replaying that log through the resolver reproduces every tick's
 * canonical state hash exactly.
 *
 * <p><b>No Spring (the card says "no Spring needed").</b> A plain Java class. The
 * orchestrator owns concurrency elsewhere (virtual threads + structured concurrency in
 * the live tick loop, later cards); this runner is the <em>deterministic</em> seam and
 * is deliberately <strong>single-threaded</strong> - the engine resolver is pure and
 * must never be parallelised (skill {@code game-engine-determinism} "Concurrency
 * boundary"). Running the bots sequentially in a fixed faction order is what makes the
 * recorded log byte-stable.
 *
 * <p><b>The live tick loop</b> mirrors exactly how a real tick is wired (see
 * {@code ScriptedMatchTest}), one tick at a time, until a victory concludes the match
 * or the tick budget is exhausted:
 * <ol>
 *   <li><b>Perceive.</b> For each seated {@link Sovereign}, in ascending faction-id
 *       order (a total order, so the submitted batch never depends on list order or
 *       map iteration), build its fog-filtered {@link WorldView} from authoritative
 *       state via {@link WorldViewBuilder} with the supplied {@link SystemAdjacency}
 *       (so sensor-range reveal works).</li>
 *   <li><b>Decide.</b> Ask the bot for its {@link AgentResponse}. Scripted bots are
 *       deterministic per seed, so this is reproducible.</li>
 *   <li><b>Validate.</b> Run every emitted {@link Action} through the engine's
 *       {@link ActionValidator} against authoritative state and the {@link LaneNetwork}.
 *       Only {@link ValidationResult.Valid} actions become {@link SubmittedAction}s
 *       (closed agent I/O, principle 5 / skill "Validate before resolve"); a rejected
 *       action is dropped, never reaching resolution.</li>
 *   <li><b>Resolve.</b> Fold the tick's validated batch through
 *       {@link Resolver#resolveResult} with the constant {@code gameSeed} and the lane
 *       network. The resolver folds the tick number into its own RNG derivation, so the
 *       caller passes the constant seed (never {@code seed ^ tick}, which would
 *       double-mix).</li>
 *   <li><b>Record &amp; advance.</b> Capture the validated batch, the canonical
 *       post-tick hash ({@link StateHasher#sha256Hex}) and the event stream into a
 *       {@link MatchRecord.TickRecord}, then advance the snapshot to {@code tick + 1}
 *       (the resolver reads the tick but never bumps it - advancement is the caller's
 *       job, the source of per-tick RNG variation).</li>
 * </ol>
 *
 * <p>The loop stops the moment the resolver concludes the match
 * ({@link GameStatus#CONCLUDED}, recorded as {@link MatchRecord.Outcome#VICTORY}) or
 * when {@code maxTicks} resolved ticks have elapsed without a victory
 * ({@link MatchRecord.Outcome#TICK_LIMIT}). Either way the match terminates, which is
 * the card's "runs to a victory or tick limit".
 */
public final class HeadlessMatchRunner {

    private HeadlessMatchRunner() {
    }

    /**
     * Run a headless match with scripted bots and record its replayable log.
     *
     * @param initialState the starting authoritative snapshot (must be
     *                     {@link GameStatus#RUNNING}); its {@code gameSeed} is the root
     *                     of all seeded RNG and the seed replay must reuse
     * @param sovereigns   the seated bots (one per faction seat); driven in ascending
     *                     faction-id order each tick
     * @param profile      the active balance profile - the source of every gameplay
     *                     number (rule 6)
     * @param lanes        the active-region lane network (use {@link LaneNetwork#EMPTY}
     *                     for no graph); consumed by both validation and resolution
     * @param adjacency    the lane adjacency for fog-of-war sensor reveal (use
     *                     {@link SystemAdjacency#NONE} for none)
     * @param maxTicks     the tick budget; the runner stops at this many resolved ticks
     *                     if no victory fires first (must be {@code > 0})
     * @return the {@link MatchRecord}: the per-tick action log + hash + event witnesses,
     * the final snapshot and the outcome
     */
    public static MatchRecord run(GameState initialState, List<Sovereign> sovereigns,
                                  BalanceProfile profile, LaneNetwork lanes,
                                  SystemAdjacency adjacency, int maxTicks) {
        if (initialState == null) {
            throw new IllegalArgumentException("run.initialState must be set");
        }
        if (sovereigns == null || sovereigns.isEmpty()) {
            throw new IllegalArgumentException("run.sovereigns must be non-empty");
        }
        if (profile == null) {
            throw new IllegalArgumentException("run.profile must be set");
        }
        if (maxTicks <= 0) {
            throw new IllegalArgumentException("run.maxTicks must be > 0");
        }
        LaneNetwork network = lanes == null ? LaneNetwork.EMPTY : lanes;
        SystemAdjacency adj = adjacency == null ? SystemAdjacency.NONE : adjacency;
        long gameSeed = initialState.gameSeed();

        // Fixed faction order: ascending id, so the submitted batch is stable
        // regardless of the caller's list order or any map iteration order.
        List<Sovereign> ordered = sovereigns.stream()
                .sorted(Comparator.comparing(s -> s.factionId().value()))
                .toList();

        List<MatchRecord.TickRecord> records = new ArrayList<>(maxTicks);
        GameState state = initialState;
        MatchRecord.Outcome outcome = MatchRecord.Outcome.TICK_LIMIT;

        for (int i = 0; i < maxTicks; i++) {
            long tick = state.tick();

            // 1-3. Perceive -> decide -> validate, gathering this tick's valid batch.
            List<SubmittedAction> batch = gatherTick(state, ordered, profile, network, adj);

            // 4. Resolve the tick with the constant gameSeed (tick folded in by the resolver).
            ResolveResult result = Resolver.resolveResult(state, batch, profile, gameSeed, network);
            GameState resolved = result.state();

            // 5. Record the validated log + canonical hash + event stream for this tick.
            records.add(new MatchRecord.TickRecord(
                    tick, batch, StateHasher.sha256Hex(resolved), result.events()));

            // The resolver concludes the match when a victory condition fires.
            if (resolved.status() == GameStatus.CONCLUDED) {
                outcome = MatchRecord.Outcome.VICTORY;
                state = resolved; // do not advance the tick past a concluded match
                break;
            }

            // Advance to the next tick (caller owns advancement; the source of RNG variation).
            state = resolved.withTick(tick + 1);
        }

        return new MatchRecord(gameSeed, profile.name(), records, state, outcome);
    }

    /**
     * Replay a recorded {@link MatchRecord} from the same starting snapshot, profile and
     * lane network, asserting the per-tick canonical state hash sequence is identical to
     * the live run's. This is the determinism proof: replay consumes only the recorded
     * <em>action log</em> (no bots, no WorldView, no validation - those produced the log
     * already) and re-derives each tick's hash, comparing it to the live witness.
     *
     * <p>Pure and single-threaded, like the live run. Uses the same constant-seed,
     * resolve-then-advance fold so the RNG stream lines up tick for tick.
     *
     * @return the replayed per-tick hashes (equal, tick for tick, to
     * {@code record.perTickHashes()} when determinism holds)
     * @throws IllegalStateException if any tick's replayed hash diverges from the
     *                               recorded one (names the diverging tick)
     */
    public static List<String> replay(MatchRecord record, GameState initialState,
                                       BalanceProfile profile, LaneNetwork lanes) {
        if (record == null) {
            throw new IllegalArgumentException("replay.record must be set");
        }
        if (initialState == null) {
            throw new IllegalArgumentException("replay.initialState must be set");
        }
        if (profile == null) {
            throw new IllegalArgumentException("replay.profile must be set");
        }
        LaneNetwork network = lanes == null ? LaneNetwork.EMPTY : lanes;
        long gameSeed = record.gameSeed();

        List<String> replayed = new ArrayList<>(record.tickCount());
        GameState state = initialState;
        for (MatchRecord.TickRecord tickRecord : record.ticks()) {
            // Replay folds the recorded action log straight back through the resolver -
            // no re-derivation of the batch, which is the point of recording it.
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
            replayed.add(hash);

            if (resolved.status() == GameStatus.CONCLUDED) {
                state = resolved;
            } else {
                state = resolved.withTick(tickRecord.tick() + 1);
            }
        }
        return List.copyOf(replayed);
    }

    /**
     * One tick's perceive -> decide -> validate pass: project each bot's fog-filtered
     * {@link WorldView}, ask it to decide, validate every emitted action and keep only
     * the valid ones as {@link SubmittedAction}s with a per-faction submission index.
     */
    private static List<SubmittedAction> gatherTick(GameState state, List<Sovereign> ordered,
                                                     BalanceProfile profile, LaneNetwork network,
                                                     SystemAdjacency adjacency) {
        List<SubmittedAction> batch = new ArrayList<>();
        for (Sovereign sovereign : ordered) {
            FactionId actor = sovereign.factionId();
            WorldView view = WorldViewBuilder.build(state, adjacency, actor);
            AgentResponse response = sovereign.decide(view);

            int submissionOrder = 0;
            for (Action action : response.actions()) {
                ValidationResult result = ActionValidator.validate(state, actor, action, profile, network);
                if (result.isValid()) {
                    batch.add(new SubmittedAction(actor, action, submissionOrder++));
                }
                // Rejected actions are dropped (the bot idles that slot) - closed agent
                // I/O: only Valid actions ever reach the resolver.
            }
        }
        return batch;
    }
}
