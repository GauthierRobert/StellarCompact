package com.stellarcompact.engine.season;

import com.stellarcompact.engine.progression.GalaxySizeClass;
import com.stellarcompact.engine.state.FactionId;

/**
 * One Sovereign's aggregated result across an entire <b>season</b> (E9-03; game-design 07
 * section 2 "feeds tournament brackets" + section 6 "large galaxies feed seasonal
 * tournaments"). A season aggregates the per-match {@link
 * com.stellarcompact.engine.progression.StandingRecord}s of multiple concluded matches
 * into one entry per Sovereign; this record is that entry - the leaderboard row.
 *
 * <p>It is a pure projection of a Sovereign's per-match standings under the profile's
 * {@code season.*} weights (see {@link SeasonAggregation}); it holds NO material state, by
 * the same construction as {@code StandingRecord}, so a season standing can never carry
 * material advantage across the small-&gt;large boundary. It carries:
 *
 * <ul>
 *   <li>{@code faction} - the Sovereign's stable identity across the season's matches.</li>
 *   <li>{@code displayName} - the Sovereign's name (identity, not advantage).</li>
 *   <li>{@code sizeClass} - the tier the season's matches were played at (the season
 *       leaderboard of a SMALL funnel feeds the LARGE seat gate).</li>
 *   <li>{@code aggregateScore} - the config-weighted season total: {@code
 *       matchScoreWeight x sum(matchScores) + winBonus x wins + participationBonus x
 *       matchesPlayed}. This is the single number the leaderboard orders by AND the score
 *       the LARGE seat gate reads.</li>
 *   <li>{@code matchesPlayed} - how many concluded matches contributed (gates seat
 *       eligibility via {@code season.minMatchesForSeat}).</li>
 *   <li>{@code wins} - how many of those matches the Sovereign won (placed 1st).</li>
 *   <li>{@code bestPlacement} - the best (lowest) finishing placement across the season; a
 *       {@code 1} means the Sovereign won at least one match (used so the season aggregate
 *       can honour {@code progression.winGrantsSeat}).</li>
 *   <li>{@code bestReputation} - the highest final reputation the Sovereign held across the
 *       season's matches (the non-material quantity a carried identity may seed from).</li>
 *   <li>{@code seasonRank} - the 1-based position on the season leaderboard (1 = top).</li>
 * </ul>
 *
 * <p>Pure value object: no clock, no I/O, deeply immutable.
 */
public record SeasonStanding(
        FactionId faction,
        String displayName,
        GalaxySizeClass sizeClass,
        double aggregateScore,
        int matchesPlayed,
        int wins,
        int bestPlacement,
        double bestReputation,
        int seasonRank
) {
    public SeasonStanding {
        if (faction == null) {
            throw new IllegalArgumentException("SeasonStanding.faction must be set");
        }
        if (displayName == null || displayName.isBlank()) {
            throw new IllegalArgumentException("SeasonStanding.displayName must be non-blank");
        }
        if (sizeClass == null) {
            throw new IllegalArgumentException("SeasonStanding.sizeClass must be set");
        }
        if (matchesPlayed < 1) {
            throw new IllegalArgumentException("SeasonStanding.matchesPlayed must be >= 1");
        }
        if (wins < 0 || wins > matchesPlayed) {
            throw new IllegalArgumentException(
                    "SeasonStanding.wins (" + wins + ") must be in [0, matchesPlayed]");
        }
        if (bestPlacement < 1) {
            throw new IllegalArgumentException("SeasonStanding.bestPlacement must be >= 1");
        }
        if (seasonRank < 1) {
            throw new IllegalArgumentException("SeasonStanding.seasonRank must be >= 1");
        }
    }

    /** @return {@code true} iff the Sovereign won at least one match in the season. */
    public boolean wonAny() {
        return bestPlacement == 1;
    }
}
