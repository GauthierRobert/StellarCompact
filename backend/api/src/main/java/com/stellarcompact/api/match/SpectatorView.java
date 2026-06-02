package com.stellarcompact.api.match;

import java.util.List;

/**
 * The public, fog-free snapshot returned by {@code GET /api/games/{id}/state} when NO
 * {@code requester} faction is given (a pure spectator). It carries only common-knowledge
 * state: the lifecycle status, tick, the public reputation ledger and the public seat
 * roster - never any faction's private systems/fleets/stockpiles/tech.
 *
 * <p><b>Why a distinct shape from a faction's WorldView.</b> A requester who names a
 * faction gets that faction's fog-filtered {@code WorldView} (own state in full, everyone
 * else fog-limited). A spectator names no faction, so by the default-deny fog rule it is
 * entitled to nothing private at all - hence this strictly-public projection. This is the
 * server-side enforcement of "never return hidden faction state to an unauthorized
 * requester" (rest-api Conventions; principle 2).
 *
 * @param gameId      the match id
 * @param tick        the last resolved tick
 * @param status      the lifecycle status name
 * @param reputations the public reputation ledger (every faction's public reputation)
 */
public record SpectatorView(
        String gameId,
        long tick,
        String status,
        List<Reputation> reputations
) {
    public SpectatorView {
        reputations = reputations == null ? List.of() : List.copyOf(reputations);
    }

    /** A public reputation-ledger entry (the only foreign-faction scalar that is public). */
    public record Reputation(String factionId, double reputation) {
    }
}
