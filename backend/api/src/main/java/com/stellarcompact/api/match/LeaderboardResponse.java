package com.stellarcompact.api.match;

import java.util.List;

/**
 * Current standings for {@code GET /api/games/{id}/leaderboard} (rest-api spec). The
 * ranking is the engine's config-weighted {@code Scoring.rank} (board card E1-15;
 * game-design 07 section 2) computed against the last resolved snapshot, so even a
 * match with no clean victory always yields a total order that feeds tournament
 * brackets and the small-to-large progression gate.
 *
 * <p>Public by construction: a score is a weighted roll-up of public/observable factors
 * (systems, influence, reputation, ...). It leaks no hidden per-faction state, so it is
 * safe for any requester / spectator.
 *
 * @param gameId  the match id
 * @param tick    the snapshot tick the scores were computed at
 * @param entries the ranked standings, rank 1 first (score desc, faction id asc on ties)
 */
public record LeaderboardResponse(String gameId, long tick, List<Entry> entries) {

    public LeaderboardResponse {
        entries = entries == null ? List.of() : List.copyOf(entries);
    }

    /**
     * One standing row.
     *
     * @param rank     1-based position in the ranking
     * @param factionId the faction
     * @param score    its weighted score
     */
    public record Entry(int rank, String factionId, double score) {
    }
}
