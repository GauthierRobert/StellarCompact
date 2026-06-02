package com.stellarcompact.orchestrator.tick;

import com.stellarcompact.engine.action.Action;
import com.stellarcompact.engine.action.AgentResponse;
import com.stellarcompact.engine.resolve.ResolveResult;
import com.stellarcompact.engine.resolve.Resolver;
import com.stellarcompact.engine.resolve.SubmittedAction;
import com.stellarcompact.engine.state.FactionId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.StructuredTaskScope;

/**
 * The real-time, four-phase tick orchestrator (board card E4-05). One call to
 * {@link #runTick(List, TickContext)} drives a single tick end to end:
 *
 * <ol>
 *   <li><b>Perception.</b> Each seat fog-filtered WorldView is built server-side
 *       (inside {@link Seat#decide}) from authoritative state - the engine authority +
 *       fog boundary (principle 2, skill rule 3).</li>
 *   <li><b>Negotiation.</b> A configurable number of rounds (E4-06 fleshes them out;
 *       here the structure exists and gathers messages) fan out across seats under the
 *       {@link TickProperties#negotiationTimeout() negotiation deadline}.</li>
 *   <li><b>Action.</b> Seats fan out again under the
 *       {@link TickProperties#actionTimeout() action deadline}; each contributes its
 *       validated {@link SeatDecision}.</li>
 *   <li><b>Resolution.</b> The gathered, validated batch is folded through the pure
 *       engine {@link Resolver} ONCE, single-threaded - all concurrency was in the
 *       gathering (skill rule 6, the determinism boundary).</li>
 * </ol>
 *
 * <h2>Structured concurrency and the shared deadline (the heart of this card)</h2>
 * Each agent-calling phase opens a Java 25 {@link StructuredTaskScope} configured with
 * a per-phase timeout via {@code withTimeout(Duration)} - the shared deadline. Every
 * seat is {@link StructuredTaskScope#fork forked} as its own subtask, so the scope runs
 * ONE virtual thread per seat (the default scope thread factory is virtual). We join
 * with {@link StructuredTaskScope.Joiner#awaitAll()}: it returns when every subtask has
 * finished, or the scope timeout fires, or a subtask fails. When the deadline elapses
 * the scope cancels (interrupts) every still-running subtask and {@code join()} throws
 * {@link StructuredTaskScope.TimeoutException}; a subtask that threw surfaces as
 * {@link StructuredTaskScope.FailedException}. We swallow both, then read the side-channel
 * results map each subtask wrote into before returning: a seat present completed in time
 * and contributes its {@link SeatDecision}; a seat absent was the straggler the deadline
 * cancelled (or an errored brain) and contributes {@link SeatDecision#HOLD}. A slow agent
 * therefore never stalls the tick: it Holds, and the galaxy never waits on one brain
 * (skill rules 1-2).
 *
 * <p>Because the deadline cancels stragglers rather than abandoning threads, the tick
 * completes in approximately the phase deadline regardless of how many agents are slow -
 * the load-test property.
 *
 * <h2>Determinism boundary</h2>
 * Concurrency is confined to gathering. The gathered decisions are reassembled into a
 * DETERMINISTIC {@link SubmittedAction} batch - seats sorted by faction id, each seat
 * surviving actions in submission order - so the batch handed to the resolver never
 * depends on which virtual thread finished first or on map iteration order. The resolver
 * then runs pure and single-threaded; same seed + same decisions yields the same
 * resulting state (the determinism test). The constant {@code gameSeed} is passed to the
 * resolver (it folds the tick in itself), mirroring the headless runner (E3-03).
 *
 * <h2>Provider neutrality</h2>
 * The orchestrator never names a model or provider; the Spring AI seam is entirely
 * inside {@link Seat.LlmSeat} via the injected factory (principle 4).
 */
@Component
@EnableConfigurationProperties(TickProperties.class)
public class TickOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(TickOrchestrator.class);

    private final TickProperties properties;

    public TickOrchestrator(TickProperties properties) {
        this.properties = properties == null ? TickProperties.defaults() : properties;
    }

    /** @return the timings this orchestrator paces to (config-driven, rule 6). */
    public TickProperties properties() {
        return properties;
    }

    /**
     * Drive one full tick (Perception, Negotiation, Action, Resolution) for the given
     * seats against the given context. Does NOT advance the tick clock - the caller owns
     * advancement (the source of per-tick RNG variation), mirroring the headless runner.
     *
     * @param seats the occupied faction seats (non-empty); driven concurrently per phase
     * @param ctx   the authoritative inputs for this tick
     * @return the resolved tick: post-resolution snapshot, validated batch, events, messages
     */
    public TickResult runTick(List<Seat> seats, TickContext ctx) {
        if (seats == null || seats.isEmpty()) {
            throw new IllegalArgumentException("runTick.seats must be non-empty");
        }
        if (ctx == null) {
            throw new IllegalArgumentException("runTick.ctx must be set");
        }

        // Fixed seat order: ascending faction id, so the submitted batch is stable
        // regardless of caller list order or which virtual thread finished first.
        List<Seat> ordered = seats.stream()
                .sorted(Comparator.comparing(s -> s.factionId().value()))
                .toList();

        long tick = ctx.state().tick();

        // --- Negotiation phase (rounds from config; E4-06 routes the messages) ---------
        List<AgentResponse.Message> messages = new ArrayList<>();
        for (int round = 0; round < properties.negotiationRounds(); round++) {
            Map<FactionId, SeatDecision> negotiated =
                    fanOut(ordered, ctx, properties.negotiationTimeout(), "negotiation#" + round);
            // Negotiation gathers messages only; structured proposals + delivery into the
            // next WorldView are E4-06. Actions emitted in the negotiation phase are not
            // resolved here (the action phase is the authoritative source of the batch).
            for (Seat seat : ordered) {
                SeatDecision d = negotiated.get(seat.factionId());
                if (d != null) {
                    messages.addAll(d.messages());
                }
            }
        }

        // --- Action phase: fan out under the action deadline, gather validated decisions.
        Map<FactionId, SeatDecision> decisions =
                fanOut(ordered, ctx, properties.actionTimeout(), "action");

        // --- Reassemble a DETERMINISTIC submitted batch (seat order + submission order).
        List<SubmittedAction> batch = new ArrayList<>();
        for (Seat seat : ordered) {
            FactionId actor = seat.factionId();
            SeatDecision decision = decisions.getOrDefault(actor, SeatDecision.HOLD);
            int submissionOrder = 0;
            for (Action action : decision.validActions()) {
                batch.add(new SubmittedAction(actor, action, submissionOrder++));
            }
        }

        // --- Resolution: ONE pure, single-threaded resolver call (no concurrency here).
        long gameSeed = ctx.state().gameSeed();
        ResolveResult result = Resolver.resolveResult(
                ctx.state(), batch, ctx.profile(), gameSeed, ctx.network());

        return new TickResult(tick, result.state(), batch, result.events(), messages);
    }

    /**
     * Fan out one agent-calling phase across all seats under a shared deadline, one
     * virtual thread per seat. Returns each seat contribution; a straggler the deadline
     * cancelled, or an errored seat, contributes {@link SeatDecision#HOLD}.
     *
     * <p><b>Why a side-channel results map.</b> When the scope timeout fires, {@code join()}
     * throws {@link StructuredTaskScope.TimeoutException} and the scope is NOT in the
     * joined state - so calling {@code Subtask.get()} afterwards throws "join not called".
     * Rather than fight that guard, each subtask writes its own result into a
     * {@link ConcurrentHashMap} keyed by faction id <em>before</em> it returns. After the
     * join (or its timeout/failure) we read that map: a seat present in it completed in
     * time and contributes its decision; a seat absent from it was cancelled at the
     * deadline (a straggler) and contributes {@link SeatDecision#HOLD}. This is robust to
     * both the timeout and failure exits and never touches {@code Subtask.get()}.
     */
    private Map<FactionId, SeatDecision> fanOut(List<Seat> ordered, TickContext ctx,
                                                Duration timeout, String phase) {
        ConcurrentHashMap<FactionId, SeatDecision> results = new ConcurrentHashMap<>();

        try (var scope = StructuredTaskScope.open(
                StructuredTaskScope.Joiner.<Void>awaitAll(),
                c -> c.withName("tick-" + phase).withTimeout(nonNegative(timeout)))) {

            for (Seat seat : ordered) {
                // One subtask == one virtual thread per seat (default scope factory).
                // The task records its own result; a straggler the deadline interrupts
                // simply never records, and is read back as a Hold below.
                scope.fork(() -> {
                    results.put(seat.factionId(), safeDecide(seat, ctx));
                    return null;
                });
            }

            try {
                scope.join(); // returns on all-done, or throws on timeout / subtask failure
            } catch (StructuredTaskScope.TimeoutException e) {
                // Expected when one or more brains miss the shared deadline - they are
                // cancelled; the tick proceeds with the survivors. Stragglers -> Hold.
                log.debug("phase {} hit its {} deadline at tick {}; stragglers Hold",
                        phase, timeout, ctx.state().tick());
            } catch (StructuredTaskScope.FailedException e) {
                // A subtask threw before recording. We never let one brain abort the
                // tick; that seat is simply absent from results -> Hold below.
                log.debug("phase {} had a failing subtask at tick {}; that seat Holds",
                        phase, ctx.state().tick(), e);
            }

            // Present in results -> completed in time; absent -> cancelled straggler / Hold.
            Map<FactionId, SeatDecision> out = new LinkedHashMap<>();
            for (Seat seat : ordered) {
                out.put(seat.factionId(), results.getOrDefault(seat.factionId(), SeatDecision.HOLD));
            }
            return out;
        } catch (InterruptedException e) {
            // The orchestrating thread itself was interrupted (shutdown). Re-flag and
            // treat the whole phase as a Hold - the engine never sees a partial batch.
            Thread.currentThread().interrupt();
            Map<FactionId, SeatDecision> out = new LinkedHashMap<>();
            for (Seat seat : ordered) {
                out.put(seat.factionId(), SeatDecision.HOLD);
            }
            return out;
        }
    }

    /** Decide for one seat, degrading any thrown exception to a Hold (never aborts the tick). */
    private static SeatDecision safeDecide(Seat seat, TickContext ctx) {
        try {
            SeatDecision d = seat.decide(ctx);
            return d == null ? SeatDecision.HOLD : d;
        } catch (RuntimeException e) {
            return SeatDecision.HOLD;
        }
    }

    /** A negative timeout means already past the deadline; clamp to zero (legal, all Hold). */
    private static Duration nonNegative(Duration d) {
        if (d == null || d.isNegative()) {
            return Duration.ZERO;
        }
        return d;
    }
}
