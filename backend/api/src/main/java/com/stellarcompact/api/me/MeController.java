package com.stellarcompact.api.me;

import com.stellarcompact.api.match.GameSummary;
import com.stellarcompact.api.match.MatchNotFoundException;
import com.stellarcompact.api.match.MatchService;
import com.stellarcompact.api.ws.FactionOwnershipRegistry;
import com.stellarcompact.api.ws.FactionOwnershipRegistry.OwnedFaction;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;
import java.util.ArrayList;
import java.util.List;

/**
 * The authenticated user's personal dashboard surface (spec {@code docs/specs/rest-api.md}
 * "Authentication (dev)"). {@code GET /api/me/games} answers "which games are mine and how
 * are they evolving?" by a <b>reverse lookup</b> of the {@link FactionOwnershipRegistry}
 * (every seat this principal owns), each row joined with its match's live
 * {@link GameSummary} (status + tick + seed + profile).
 *
 * <p><b>Trust boundary.</b> The principal is taken only from the verified token (the
 * {@code /api/me/**} matcher is {@code authenticated()}), never from a query/body field, so
 * a caller can only ever enumerate their own seats. Each row is the match's <em>public</em>
 * summary — no other faction's private state is read here (the owner-only live WorldView is
 * the separate STOMP queue, gated independently).
 *
 * <p>Not cached: ownership and match status both change over time.
 */
@RestController
@RequestMapping("/api/me")
public class MeController {

    private final FactionOwnershipRegistry ownership;
    private final MatchService matches;

    public MeController(FactionOwnershipRegistry ownership, MatchService matches) {
        this.ownership = ownership;
        this.matches = matches;
    }

    @GetMapping("/games")
    public ResponseEntity<MyGamesResponse> games(Principal principal) {
        String username = principal.getName();
        List<MyGameView> rows = new ArrayList<>();

        for (OwnedFaction owned : ownership.factionsOwnedBy(username)) {
            GameSummary summary;
            try {
                summary = matches.summary(owned.gameId());
            } catch (MatchNotFoundException e) {
                // The match was archived/cleared but a stale binding lingered: skip it.
                continue;
            }
            String seatId = owned.faction().value();
            rows.add(new MyGameView(
                    owned.gameId(),
                    owned.gameId() + ":" + seatId,
                    seatId,
                    summary.status().name(),
                    summary.tick(),
                    summary.gameSeed(),
                    summary.balanceProfile(),
                    summary.factions().size()));
        }

        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(new MyGamesResponse(username, rows));
    }
}
