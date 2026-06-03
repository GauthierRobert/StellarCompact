package com.stellarcompact.api.me;

import java.util.List;

/**
 * Body of {@code GET /api/me/games}: the authenticated user's identity plus every game in
 * which they own a seat (ascending by {@code gameId}). Empty {@code games} is a valid
 * response — a logged-in user who has not yet claimed a Sovereign anywhere.
 *
 * @param username the authenticated principal ({@code sub})
 * @param games    the owned-seat rows, joined with live match status
 */
public record MyGamesResponse(String username, List<MyGameView> games) {

    public MyGamesResponse {
        games = games == null ? List.of() : List.copyOf(games);
    }
}
