package com.stellarcompact.api.match;

/**
 * The replay manifest returned by {@code GET /api/games/{id}/replay} (board card E9-02;
 * game-design 07 section 5). It is the spectator scrub bar's metadata: the seekable tick
 * range and how many frames the archived match has, plus the reproducibility root
 * ({@code gameSeed}) and the profile, so a client can render the timeline and request any
 * frame via {@code GET /api/games/{id}/replay/{tick}}.
 *
 * <p>Public by construction - it carries only common-knowledge match metadata, never any
 * faction's hidden state (the per-tick frames are likewise fog-free public projections).
 *
 * @param gameId    the match id
 * @param gameSeed  the per-match seed (the reproducibility root the replay re-resolves from)
 * @param profile   the active balance-profile name (provenance; numbers live in config)
 * @param firstTick the lowest seekable tick (the first recorded resolved tick), or -1 if none
 * @param lastTick  the highest seekable tick (the last recorded resolved tick), or -1 if none
 * @param tickCount the number of resolved ticks (frames) in the timeline
 */
public record ReplayManifest(
        String gameId,
        long gameSeed,
        String profile,
        long firstTick,
        long lastTick,
        int tickCount
) {
}
