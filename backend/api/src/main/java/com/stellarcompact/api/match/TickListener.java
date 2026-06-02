package com.stellarcompact.api.match;

import com.stellarcompact.engine.resolve.PublicEvent;
import com.stellarcompact.engine.state.GameState;
import com.stellarcompact.orchestrator.sovereign.SystemAdjacency;

import java.util.List;

/**
 * A post-commit hook the {@link InMemoryMatchService} invokes exactly once per resolved
 * tick, after the snapshot has advanced and the public events have been logged (board
 * card E6-04 wiring). It is the seam by which the live WebSocket stream learns of a tick
 * without the service depending on the transport layer: the {@code ws} package supplies
 * an implementation ({@code LiveStreamPublisher}) that fans the tick out to the public
 * topics and each owner queue.
 *
 * <p><b>Why a listener, not a direct call.</b> Keeping the contract one-directional
 * ({@code match} knows nothing of {@code ws}) preserves the module layering: the engine
 * stays pure, the match service stays transport-agnostic, and a build with no broker
 * (e.g. the existing MockMvc REST tests) simply registers no listener. The service holds
 * the match lock while calling this, so {@code state} is the just-committed snapshot and
 * {@code events} are that tick events in deterministic order; an implementation must do
 * only cheap, non-blocking fan-out work here (never re-enter the service).
 *
 * <p><b>Fog boundary.</b> The listener receives the authoritative {@link GameState}; it
 * MUST route any per-faction projection through the server-side {@code WorldViewBuilder}
 * and MUST publish only fog-correct payloads. Raw {@code GameState} is never serialised
 * to any client.
 */
@FunctionalInterface
public interface TickListener {

    /**
     * @param gameId    the match that just resolved a tick
     * @param state     the committed snapshot after this tick (authoritative; fog-filter
     *                  before sending anything per-faction)
     * @param adjacency this match system adjacency, the input the authoritative
     *                  {@code WorldViewBuilder} needs to project a fog-correct per-faction
     *                  view
     * @param events    the public events emitted on this tick, deterministic order
     */
    void onTickCommitted(String gameId, GameState state, SystemAdjacency adjacency,
                         List<PublicEvent> events);
}
