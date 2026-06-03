package com.stellarcompact.api.me;

/**
 * One row of the {@code GET /api/me/games} dashboard: a seat the caller owns, joined with
 * its match's live summary so the UI can show the game's evolution at a glance.
 *
 * @param gameId         the match id
 * @param factionId      the global seat handle {@code gameId:seatId} (config-read + STOMP key)
 * @param seatId         the per-match seat id (the owner-view queue id)
 * @param status         current lifecycle status (e.g. {@code RUNNING})
 * @param tick           last resolved tick (0 before the first)
 * @param gameSeed       the galaxy seed (reproducibility root)
 * @param balanceProfile active balance-profile name
 * @param factionCount   number of seats in the match (context for standings)
 */
public record MyGameView(
        String gameId,
        String factionId,
        String seatId,
        String status,
        long tick,
        long gameSeed,
        String balanceProfile,
        int factionCount) {
}
