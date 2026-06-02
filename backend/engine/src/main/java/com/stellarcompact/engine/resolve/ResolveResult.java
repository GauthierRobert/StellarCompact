package com.stellarcompact.engine.resolve;

import com.stellarcompact.engine.state.GameState;

import java.util.List;

/**
 * The full output of resolving one tick (board card E1-16): the next
 * {@link GameState} snapshot plus the ordered list of {@link PublicEvent}s emitted
 * during that resolution.
 *
 * <p><b>Why a separate record.</b> The persistent, hashed snapshot is
 * {@link GameState} alone; public events are <em>transient per-tick output</em>, not
 * part of the snapshot (so they never perturb the golden state hash). Bundling them in
 * this thin result keeps {@code Resolver.resolveResult(...)} a single pure function
 * while the long-standing {@code Resolver.resolve(...)} entry points keep returning bare
 * {@link GameState} for callers that do not consume the live event stream.
 *
 * <p>{@code events} is append-only and ordered by the resolution (and hence tick)
 * order; it is defensively copied so the result is immutable.
 *
 * @param state  the next snapshot (never {@code null})
 * @param events the tick's public events in deterministic emission order (never {@code null})
 */
public record ResolveResult(GameState state, List<PublicEvent> events) {

    public ResolveResult {
        if (state == null) {
            throw new IllegalArgumentException("ResolveResult.state must be set");
        }
        if (events == null) {
            throw new IllegalArgumentException("ResolveResult.events must be set");
        }
        events = List.copyOf(events);
    }
}
