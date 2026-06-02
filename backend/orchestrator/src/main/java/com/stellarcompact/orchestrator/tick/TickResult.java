package com.stellarcompact.orchestrator.tick;

import com.stellarcompact.engine.action.AgentResponse;
import com.stellarcompact.engine.resolve.PublicEvent;
import com.stellarcompact.engine.resolve.SubmittedAction;
import com.stellarcompact.engine.state.GameState;

import java.util.List;

/**
 * The output of one resolved tick (card E4-05): the post-resolution snapshot plus the
 * transient by-products the orchestrator surfaces - the validated action batch that was
 * resolved, the public events the resolver emitted (for the WS feed, E6-04), and the
 * negotiation messages gathered this tick (for the next tick's inbox, E4-06).
 *
 * <p>{@code resolvedState} is the snapshot AFTER {@code Resolver.resolveResult} but
 * BEFORE the tick clock is advanced - advancing the clock is the loop's job (it is the
 * source of per-tick RNG variation), mirroring the headless runner. Tests assert the
 * resolved-state hash for the determinism check.
 *
 * @param tick           the tick number this result resolved
 * @param resolvedState  the post-resolution snapshot (clock not yet advanced)
 * @param submitted      the validated batch that was resolved, in canonical-stable order
 * @param events         the resolver's public events for this tick (deterministic order)
 * @param messages       the negotiation messages gathered this tick (E4-06 routes them)
 */
public record TickResult(
        long tick,
        GameState resolvedState,
        List<SubmittedAction> submitted,
        List<PublicEvent> events,
        List<AgentResponse.Message> messages
) {

    public TickResult {
        if (resolvedState == null) {
            throw new IllegalArgumentException("TickResult.resolvedState must be set");
        }
        submitted = submitted == null ? List.of() : List.copyOf(submitted);
        events = events == null ? List.of() : List.copyOf(events);
        messages = messages == null ? List.of() : List.copyOf(messages);
    }
}
