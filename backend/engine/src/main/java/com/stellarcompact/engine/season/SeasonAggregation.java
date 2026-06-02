package com.stellarcompact.engine.season;

import com.stellarcompact.engine.config.BalanceProfile;
import com.stellarcompact.engine.progression.GalaxySizeClass;
import com.stellarcompact.engine.progression.ProgressionEvaluation;
import com.stellarcompact.engine.progression.StandingRecord;
import com.stellarcompact.engine.state.FactionId;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The pure tournament/season aggregation kernel (E9-03; game-design 07 section 2 + section
 * 6). A <b>season</b> is a set of concluded matches; this class folds the per-match
 * {@link StandingRecord}s those matches produced into a single season-level
 * <b>leaderboard</b> (one {@link SeasonStanding} per Sovereign), and feeds that aggregate
 * back into the <em>existing</em> small-&gt;large seat gate.
 *
 * <p>Two non-negotiables (rules 1 + 6):
 * <ul>
 *   <li><b>Deterministic.</b> The leaderboard is a pure function of the input standings
 *       plus the active profile's {@code season.*} weights - no clock, no I/O, no
 *       randomness. Sovereigns are ordered in a total order (aggregate score desc, then
 *       faction id asc) so ties break stably and the same standings always produce the same
 *       ranking, byte for byte.</li>
 *   <li><b>No forked ranking/gating.</b> Per-match scores come from E1-15 {@code Scoring}
 *       (already baked into each {@link StandingRecord#score()}); the seat gate is E9-01
 *       {@link ProgressionEvaluation#admits}. This class only <em>aggregates</em>: it sums
 *       the E1-15 scores under config weights, then projects the aggregate into a
 *       {@link StandingRecord} and hands it to the E9-01 gate. It re-implements neither
 *       scoring nor gating.</li>
 * </ul>
 *
 * <p>Persisting standings between matches and assembling a season is an orchestration
 * concern (persistence/api modules call into these pure functions); the engine only
 * folds and gates.
 */
public final class SeasonAggregation {

    private SeasonAggregation() {
    }

    /** Season leaderboard order: aggregate score descending, then faction id ascending. */
    private static final Comparator<SeasonStanding> RANK =
            Comparator.comparingDouble(SeasonStanding::aggregateScore).reversed()
                    .thenComparing(s -> s.faction().value());

    /**
     * Aggregate a season's per-match standings into the season leaderboard.
     *
     * <p>For each Sovereign that appears in {@code matchStandings} (keyed by
     * {@link FactionId}) the season aggregate is, under the profile's {@code season.*}
     * weights:
     * <pre>aggregate = matchScoreWeight x SUM(matchScore)
     *           + winBonus           x wins
     *           + participationBonus x matchesPlayed</pre>
     * The Sovereign's {@code bestPlacement} (lowest across the season), {@code wins}
     * (placements == 1), {@code matchesPlayed} and {@code bestReputation} (max final
     * reputation) are also folded so the aggregate can honour the seat gate.
     *
     * <p>All input standings for a given Sovereign must come from the same tier; their
     * common {@link GalaxySizeClass} becomes that Sovereign's season {@code sizeClass}.
     * (Mixing tiers for one Sovereign in one season is a caller error and rejected.)
     *
     * @param matchStandings every per-match {@link StandingRecord} the season's concluded
     *                       matches produced (any order; one Sovereign may appear many
     *                       times, once per match it played)
     * @param profile        the active profile (source of the {@code season.*} weights)
     * @return the season leaderboard - one {@link SeasonStanding} per Sovereign, ordered by
     * aggregate score (highest first; faction id breaks ties), each tagged with its 1-based
     * {@code seasonRank}
     */
    public static List<SeasonStanding> aggregate(List<StandingRecord> matchStandings,
                                                 BalanceProfile profile) {
        if (matchStandings == null) {
            throw new IllegalArgumentException("aggregate: matchStandings must be set");
        }
        if (profile == null) {
            throw new IllegalArgumentException("aggregate: profile must be set");
        }
        BalanceProfile.Season cfg = profile.season();

        // Fold per Sovereign in a deterministic (insertion-ordered) accumulator.
        Map<FactionId, Accumulator> byFaction = new LinkedHashMap<>();
        for (StandingRecord sr : matchStandings) {
            if (sr == null) {
                throw new IllegalArgumentException("aggregate: null standing in season");
            }
            Accumulator acc = byFaction.computeIfAbsent(sr.faction(),
                    fid -> new Accumulator(sr.faction(), sr.displayName(), sr.sizeClass()));
            acc.add(sr);
        }

        // Materialise unranked season standings (aggregate score applied), then sort.
        List<SeasonStanding> unranked = new ArrayList<>(byFaction.size());
        for (Accumulator acc : byFaction.values()) {
            double aggregate = cfg.matchScoreWeight() * acc.scoreSum
                    + cfg.winBonus() * acc.wins
                    + cfg.participationBonus() * acc.matchesPlayed;
            unranked.add(new SeasonStanding(
                    acc.faction, acc.displayName, acc.sizeClass,
                    aggregate, acc.matchesPlayed, acc.wins, acc.bestPlacement,
                    acc.bestReputation,
                    1)); // placeholder rank, overwritten below
        }
        unranked.sort(RANK);

        // Stamp the 1-based season rank in the stable order.
        List<SeasonStanding> ranked = new ArrayList<>(unranked.size());
        for (int i = 0; i < unranked.size(); i++) {
            SeasonStanding s = unranked.get(i);
            ranked.add(new SeasonStanding(
                    s.faction(), s.displayName(), s.sizeClass(), s.aggregateScore(),
                    s.matchesPlayed(), s.wins(), s.bestPlacement(), s.bestReputation(),
                    i + 1));
        }
        return List.copyOf(ranked);
    }

    /**
     * Project a season aggregate into a single-match-shaped {@link StandingRecord} so it
     * can be fed, unchanged, into the E9-01 seat gate ({@link ProgressionEvaluation#admits}
     * / {@link ProgressionEvaluation#earnsSeat}). This is the seam that lets the season
     * leaderboard drive the small-&gt;large gate <em>without forking</em> the gating logic:
     * the aggregate score becomes the standing's {@code score}, the season rank its
     * {@code placement} (so {@code progression.winGrantsSeat} sees a season winner), the
     * best season reputation its {@code reputation}, and the leaderboard size its
     * {@code matchSize}.
     */
    public static StandingRecord toStandingRecord(SeasonStanding seasonStanding,
                                                  int leaderboardSize) {
        if (seasonStanding == null) {
            throw new IllegalArgumentException("toStandingRecord: seasonStanding must be set");
        }
        if (leaderboardSize < seasonStanding.seasonRank()) {
            throw new IllegalArgumentException(
                    "toStandingRecord: leaderboardSize (" + leaderboardSize
                            + ") < seasonRank (" + seasonStanding.seasonRank() + ")");
        }
        return new StandingRecord(
                seasonStanding.faction(),
                seasonStanding.displayName(),
                seasonStanding.sizeClass(),
                seasonStanding.aggregateScore(),
                seasonStanding.bestReputation(),
                seasonStanding.seasonRank(),
                leaderboardSize);
    }

    /**
     * @return {@code true} iff a Sovereign's {@code seasonStanding} qualifies it for a seat
     * in the {@code destination} galaxy. The season aggregate feeds the SAME E9-01 gate a
     * single match does, with one extra season-level guard: the Sovereign must have played
     * at least {@code season.minMatchesForSeat} matches (a thin body of work cannot buy a
     * seat off one lucky match when the season demands a campaign). Below that floor the
     * Sovereign is denied regardless of aggregate; at/above it, admission is exactly
     * {@link ProgressionEvaluation#admits} on the projected standing.
     */
    public static boolean admits(SeasonStanding seasonStanding, int leaderboardSize,
                                 BalanceProfile destination) {
        if (seasonStanding == null) {
            return false;
        }
        if (destination == null) {
            throw new IllegalArgumentException("admits: destination profile must be set");
        }
        if (seasonStanding.matchesPlayed() < destination.season().minMatchesForSeat()) {
            return false;
        }
        return ProgressionEvaluation.admits(
                toStandingRecord(seasonStanding, leaderboardSize), destination);
    }

    // ===== helpers ============================================================

    /** Mutable per-Sovereign fold accumulator (local to one aggregate() call). */
    private static final class Accumulator {
        private final FactionId faction;
        private final String displayName;
        private final GalaxySizeClass sizeClass;
        private double scoreSum = 0.0;
        private int matchesPlayed = 0;
        private int wins = 0;
        private int bestPlacement = Integer.MAX_VALUE;
        private double bestReputation = Double.NEGATIVE_INFINITY;

        private Accumulator(FactionId faction, String displayName, GalaxySizeClass sizeClass) {
            this.faction = faction;
            this.displayName = displayName;
            this.sizeClass = sizeClass;
        }

        private void add(StandingRecord sr) {
            if (sr.sizeClass() != sizeClass) {
                throw new IllegalArgumentException(
                        "aggregate: season mixes tiers for faction " + faction.value()
                                + " (" + sizeClass + " vs " + sr.sizeClass() + ")");
            }
            scoreSum += sr.score();
            matchesPlayed++;
            if (sr.won()) {
                wins++;
            }
            if (sr.placement() < bestPlacement) {
                bestPlacement = sr.placement();
            }
            if (sr.reputation() > bestReputation) {
                bestReputation = sr.reputation();
            }
        }
    }
}
