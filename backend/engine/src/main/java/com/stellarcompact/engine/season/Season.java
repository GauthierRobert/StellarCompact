package com.stellarcompact.engine.season;

import com.stellarcompact.engine.config.BalanceProfile;
import com.stellarcompact.engine.progression.StandingRecord;

import java.util.List;

/**
 * An immutable <b>tournament season</b>: the bracket/structure into which humans enter
 * their Sovereigns (E9-03; game-design 07 section 2 + section 6). A season is the set of
 * per-match {@link StandingRecord}s its concluded matches produced; its <b>leaderboard</b>
 * is the pure aggregation of those standings (see {@link SeasonAggregation#aggregate}).
 *
 * <p>This is a thin, deterministic value object - it carries identity and the accumulated
 * standings, and derives the ranking on demand. It deliberately holds no material state and
 * no clock; assembling the standings (as matches conclude) is an orchestration concern that
 * appends to {@link #matchStandings()} and rebuilds the season. The shipped tier
 * ({@code small-default} / {@code large-persistent}) supplies the {@code season.*} weights
 * that the leaderboard is computed under.
 *
 * <ul>
 *   <li>{@code seasonId} - a stable identifier for the season (a tournament/season key).</li>
 *   <li>{@code matchStandings} - every per-match standing collected so far, across all the
 *       season's concluded matches (one Sovereign appears once per match it played). The
 *       raw, append-only input the leaderboard folds.</li>
 * </ul>
 */
public record Season(
        String seasonId,
        List<StandingRecord> matchStandings
) {
    public Season {
        if (seasonId == null || seasonId.isBlank()) {
            throw new IllegalArgumentException("Season.seasonId must be non-blank");
        }
        // Defensive immutable copy so an externally-held list cannot mutate the season.
        matchStandings = matchStandings == null ? List.of() : List.copyOf(matchStandings);
    }

    /** @return an empty season with the given id (no matches concluded yet). */
    public static Season open(String seasonId) {
        return new Season(seasonId, List.of());
    }

    /**
     * @return a new season identical to this one with {@code standings} (a concluded
     * match's full per-faction standings, e.g. from {@code
     * ProgressionEvaluation.standings}) appended. Pure: returns a fresh value, never
     * mutates this one.
     */
    public Season withMatch(List<StandingRecord> standings) {
        if (standings == null) {
            throw new IllegalArgumentException("withMatch: standings must be set");
        }
        java.util.ArrayList<StandingRecord> combined =
                new java.util.ArrayList<>(matchStandings);
        combined.addAll(standings);
        return new Season(seasonId, combined);
    }

    /**
     * @return this season's leaderboard - the per-Sovereign {@link SeasonStanding} list,
     * ordered by aggregate score (highest first; faction id breaks ties), computed purely
     * from {@link #matchStandings()} under {@code profile.season()} weights. Same standings
     * + same profile always yield the same leaderboard (rule 1).
     */
    public List<SeasonStanding> leaderboard(BalanceProfile profile) {
        return SeasonAggregation.aggregate(matchStandings, profile);
    }
}
