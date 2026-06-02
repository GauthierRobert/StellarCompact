package com.stellarcompact.api.faction;

/**
 * The {@code POST /api/games/{id}/factions} response (board card E6-02; rest-api spec:
 * {@code -> { factionId }}). The returned {@code factionId} is the globally-unique seat
 * handle ({@code gameId:seatId}) the owner then uses with {@code GET}/{@code PATCH
 * /api/factions/{factionId}} - a single path param is sufficient because the handle
 * embeds the match (seat ids like {@code faction-1} repeat across matches).
 *
 * @param factionId the seat handle ({@code gameId:seatId})
 * @param gameId    the match the seat belongs to
 * @param seatId    the engine faction id within the match
 */
public record AttachFactionResponse(String factionId, String gameId, String seatId) {
}
