package com.stellarcompact.api.match;

import com.stellarcompact.engine.state.GameStatus;

import java.util.List;

/**
 * Match metadata / status, returned by {@code POST /api/games} (the create echo) and
 * {@code GET /api/games/{id}} (rest-api spec). It is the public, non-fog-bearing
 * descriptor of a match: identity, lifecycle status, seed, the active balance profile
 * and the seated faction ids - nothing a requester is not allowed to see (the seat
 * roster is public common knowledge, like the reputation ledger).
 *
 * @param gameId           the match id
 * @param gameSeed         the galaxy seed (reproducibility root)
 * @param status           the current lifecycle {@link GameStatus}
 * @param tick             the last resolved tick (0 before the first tick)
 * @param balanceProfile   the active balance-profile name
 * @param factions         the seated faction ids, in ascending id order
 */
public record GameSummary(
        String gameId,
        long gameSeed,
        GameStatus status,
        long tick,
        String balanceProfile,
        List<String> factions
) {
    public GameSummary {
        factions = factions == null ? List.of() : List.copyOf(factions);
    }
}
