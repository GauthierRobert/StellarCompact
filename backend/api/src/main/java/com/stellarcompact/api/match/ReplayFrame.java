package com.stellarcompact.api.match;

import java.util.List;

/**
 * One seekable replay frame returned by {@code GET /api/games/{id}/replay/{tick}} (board
 * card E9-02). It is the server-side, fog-free public projection of a single replayed tick
 * (built by re-resolving the recorded {@code (seed, action log)} through the pure engine
 * resolver) - deliberately shaped to feed the <em>same</em> frontend stores the live STOMP
 * path feeds, so the spectator renders replay identically to live:
 *
 * <ul>
 *   <li>{@code tick}/{@code status} drive the tick heartbeat (the live {@code TickEvent}).</li>
 *   <li>{@code events[]} are the tick's public events in the wire shape
 *       {@code { type, parties[], systemId?, tick }} (the live {@code PublicEventMessage} /
 *       {@code EventsPage.Event}) - they feed the EventsStore.</li>
 *   <li>{@code leaderboard[]} is the engine's config-weighted {@code Scoring.rank} at this
 *       tick's snapshot (the live {@code LeaderboardResponse} entries) - it feeds the
 *       standings.</li>
 *   <li>{@code reputations[]} is the public reputation ledger at this tick (the
 *       {@code SpectatorView} ledger).</li>
 * </ul>
 *
 * <p><b>Public-only (principle 2).</b> A spectator names no faction, so by the default-deny
 * fog rule the frame carries only common-knowledge state - exactly the strictly-public
 * surface of {@link SpectatorView} plus the public event/leaderboard projections. No
 * faction's private systems/fleets/stockpiles/tech are ever placed into a frame; the
 * authoritative {@code GameState} is never serialised.
 *
 * @param gameId      the match id
 * @param tick        the absolute tick this frame represents
 * @param status      the lifecycle status at this tick
 * @param events      the public events emitted on this tick, in resolver-emission order
 * @param leaderboard the config-weighted standings at this tick (rank 1 first)
 * @param reputations the public reputation ledger at this tick (faction id ascending)
 */
public record ReplayFrame(
        String gameId,
        long tick,
        String status,
        List<EventsPage.Event> events,
        List<LeaderboardResponse.Entry> leaderboard,
        List<SpectatorView.Reputation> reputations
) {
    public ReplayFrame {
        events = events == null ? List.of() : List.copyOf(events);
        leaderboard = leaderboard == null ? List.of() : List.copyOf(leaderboard);
        reputations = reputations == null ? List.of() : List.copyOf(reputations);
    }
}
