package com.stellarcompact.engine.season;

import com.stellarcompact.engine.config.BalanceProfile;
import com.stellarcompact.engine.progression.GalaxySizeClass;
import com.stellarcompact.engine.progression.ProgressionEvaluation;
import com.stellarcompact.engine.progression.StandingRecord;
import com.stellarcompact.engine.state.FactionId;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Deterministic, hand-authored tests for the E9-03 tournament/season scaffolding
 * (game-design 07 section 2 + section 6). They prove the card's contract:
 *
 * <ol>
 *   <li>a season aggregates the per-match {@link StandingRecord}s of multiple concluded
 *       matches into one ranking per Sovereign, ordered by aggregate score with ties
 *       broken stably;</li>
 *   <li>the season aggregate feeds the SAME E9-01 seat gate ({@link ProgressionEvaluation})
 *       - a Sovereign whose season aggregate clears the threshold is admitted to a LARGE
 *       galaxy, one below is denied - with no forked gating logic;</li>
 *   <li>aggregation is reproducible: the same standings (in any input order) always yield
 *       the same leaderboard.</li>
 * </ol>
 *
 * <p>Every weight/threshold asserted is read from a per-test {@link BalanceProfile}
 * (rule 6); nothing is hardcoded in the season kernel.
 */
class SeasonAggregationTest {

    private static final FactionId ALPHA = new FactionId("alpha");
    private static final FactionId BETA = new FactionId("beta");
    private static final FactionId GAMMA = new FactionId("gamma");

    // ---- profile builder (season + progression blocks are what matter) -------

    private static BalanceProfile profile(String name, String sizeClass,
                                          double seatThreshold, boolean winGrantsSeat,
                                          BalanceProfile.Season season) {
        BalanceProfile.Victory victory = new BalanceProfile.Victory(
                new BalanceProfile.Domination(0.6),
                new BalanceProfile.Economic(1000.0, 50),
                new BalanceProfile.Diplomatic(0.6),
                new BalanceProfile.Survival(300),
                new BalanceProfile.Wonder(3, 30),
                new BalanceProfile.ScoreWeights(3.0, 2.0, 1.5, 1.0, 1.0, 1.5, 1.0));
        BalanceProfile.Progression progression = new BalanceProfile.Progression(
                sizeClass, seatThreshold, winGrantsSeat, 0.5,
                new BalanceProfile.ResourceBundle(100, 100, 50, 10, 0));
        return new BalanceProfile(
                name, 1,
                new BalanceProfile.Resources(Map.of("terran",
                        new BalanceProfile.ResourceBundle(1, 1, 4, 0, 0)),
                        Map.of("mine", new BalanceProfile.ResourceBundle(1, 0, 0, 0, 0)), 0.1),
                new BalanceProfile.Population(1.0, 1.0, 0.1, 5, Map.of("farm", 3)),
                new BalanceProfile.Market("priceTimePriority", 0.5, "ENERGY"),
                new BalanceProfile.Construction(Map.of("mine", 3),
                        Map.of("mine", new BalanceProfile.ResourceBundle(0, 50, 0, 0, 0)), Map.of()),
                new BalanceProfile.Combat(Map.of("scout", 1.0, "cruiser", 2.5), List.of(0.4, 0.6),
                        1.5, 0.5, 1.0),
                new BalanceProfile.Movement(0.0, false),
                new BalanceProfile.Tech(Map.of("x", 1.0), Map.of("x", 1), Map.of("x", 1.0),
                        Map.of(), Map.of()),
                new BalanceProfile.Diplomacy(new BalanceProfile.Reputation(1, 1, 1, 1), Map.of()),
                victory,
                new BalanceProfile.Tick(1000, 2, 5000),
                BalanceProfile.HomePlacement.defaults(),
                BalanceProfile.Espionage.defaults(),
                BalanceProfile.Influence.defaults(),
                progression,
                season);
    }

    /** A SMALL funnel profile whose season sums raw match scores (default weights). */
    private static BalanceProfile smallSumProfile() {
        return profile("small-sum", "SMALL", 0.0, false, BalanceProfile.Season.defaults());
    }

    /** A LARGE profile gating entry at {@code threshold} under the given season block. */
    private static BalanceProfile largeProfile(double threshold, boolean winGrantsSeat,
                                               BalanceProfile.Season season) {
        return profile("large-test", "LARGE", threshold, winGrantsSeat, season);
    }

    // ---- per-match standing builder ------------------------------------------

    private static StandingRecord match(FactionId f, String name, double score,
                                        double reputation, int placement, int matchSize) {
        return new StandingRecord(f, name, GalaxySizeClass.SMALL, score, reputation,
                placement, matchSize);
    }

    // ===== 1. aggregation produces a deterministic season ranking =============

    @Test
    void seasonRanksSovereignsByAggregateScore() {
        // Three matches; aggregate = SUM of match scores (default season weights).
        // alpha: 30 + 10 + 50 = 90 ; beta: 40 + 45 + 20 = 105 ; gamma: 5 + 60 = 65
        List<StandingRecord> standings = List.of(
                match(ALPHA, "Alpha", 30, 10, 2, 3),
                match(BETA, "Beta", 40, 12, 1, 3),
                match(GAMMA, "Gamma", 5, 3, 3, 3),
                match(ALPHA, "Alpha", 10, 8, 3, 3),
                match(BETA, "Beta", 45, 20, 1, 3),
                match(GAMMA, "Gamma", 60, 30, 1, 2),
                match(ALPHA, "Alpha", 50, 15, 1, 2),
                match(BETA, "Beta", 20, 5, 2, 2));

        List<SeasonStanding> board =
                SeasonAggregation.aggregate(standings, smallSumProfile());

        assertEquals(3, board.size());
        // Order by aggregate: beta 105, alpha 90, gamma 65.
        assertEquals(BETA, board.get(0).faction());
        assertEquals(105.0, board.get(0).aggregateScore(), 1e-9);
        assertEquals(1, board.get(0).seasonRank());

        assertEquals(ALPHA, board.get(1).faction());
        assertEquals(90.0, board.get(1).aggregateScore(), 1e-9);
        assertEquals(2, board.get(1).seasonRank());

        assertEquals(GAMMA, board.get(2).faction());
        assertEquals(65.0, board.get(2).aggregateScore(), 1e-9);
        assertEquals(3, board.get(2).seasonRank());

        // Folded fields: beta played 3, won 2, best placement 1, best reputation 20.
        SeasonStanding beta = board.get(0);
        assertEquals(3, beta.matchesPlayed());
        assertEquals(2, beta.wins());
        assertEquals(1, beta.bestPlacement());
        assertEquals(20.0, beta.bestReputation(), 1e-9);
        assertTrue(beta.wonAny());
        // gamma never won across its matches? gamma won 1 (placement 1 in the 2-player match).
        assertTrue(board.get(2).wonAny());
    }

    @Test
    void tiesBreakStablyByFactionId() {
        // alpha and beta both aggregate to 50; faction id ascending breaks the tie.
        List<StandingRecord> standings = List.of(
                match(BETA, "Beta", 50, 1, 1, 2),
                match(ALPHA, "Alpha", 50, 1, 1, 2));
        List<SeasonStanding> board =
                SeasonAggregation.aggregate(standings, smallSumProfile());
        assertEquals(50.0, board.get(0).aggregateScore(), 1e-9);
        assertEquals(50.0, board.get(1).aggregateScore(), 1e-9);
        assertEquals(ALPHA, board.get(0).faction(), "tie -> faction id ascending");
        assertEquals(BETA, board.get(1).faction());
    }

    @Test
    void aggregationIsReproducibleRegardlessOfInputOrder() {
        List<StandingRecord> base = new ArrayList<>(List.of(
                match(ALPHA, "Alpha", 30, 10, 2, 3),
                match(BETA, "Beta", 40, 12, 1, 3),
                match(GAMMA, "Gamma", 5, 3, 3, 3),
                match(ALPHA, "Alpha", 60, 8, 1, 3),
                match(BETA, "Beta", 45, 20, 1, 3)));
        BalanceProfile p = smallSumProfile();

        List<SeasonStanding> first = SeasonAggregation.aggregate(base, p);

        List<StandingRecord> shuffled = new ArrayList<>(base);
        Collections.reverse(shuffled);
        List<SeasonStanding> second = SeasonAggregation.aggregate(shuffled, p);

        // Same input set, any order -> identical leaderboard (determinism).
        assertEquals(first, second);
    }

    @Test
    void winAndParticipationBonusesAreConfigWeighted() {
        // Season weights: half the raw score, +100 per win, +5 per match.
        BalanceProfile.Season weighted = new BalanceProfile.Season(0.5, 100.0, 5.0, 1);
        BalanceProfile p = profile("weighted", "SMALL", 0.0, false, weighted);

        // alpha: scores 10+10=20, 0 wins, 2 matches -> 0.5*20 + 0 + 5*2 = 20
        // beta : score 30, 1 win, 1 match           -> 0.5*30 + 100 + 5*1 = 120
        List<StandingRecord> standings = List.of(
                match(ALPHA, "Alpha", 10, 1, 2, 3),
                match(ALPHA, "Alpha", 10, 1, 2, 3),
                match(BETA, "Beta", 30, 1, 1, 3));

        List<SeasonStanding> board = SeasonAggregation.aggregate(standings, p);
        assertEquals(BETA, board.get(0).faction());
        assertEquals(120.0, board.get(0).aggregateScore(), 1e-9);
        assertEquals(ALPHA, board.get(1).faction());
        assertEquals(20.0, board.get(1).aggregateScore(), 1e-9);
    }

    // ===== 2. season aggregate feeds the E9-01 seat gate (no fork) ============

    @Test
    void seasonAggregateGatesLargeGalaxyEntry() {
        List<StandingRecord> standings = List.of(
                match(ALPHA, "Alpha", 60, 10, 1, 2),  // aggregate 60
                match(BETA, "Beta", 20, 5, 2, 2));    // aggregate 20
        BalanceProfile small = smallSumProfile();
        List<SeasonStanding> board = SeasonAggregation.aggregate(standings, small);
        SeasonStanding alpha = board.get(0);
        SeasonStanding beta = board.get(1);

        // Threshold between the two aggregates: alpha admitted, beta denied.
        BalanceProfile large = largeProfile(40.0, false, BalanceProfile.Season.defaults());

        assertTrue(SeasonAggregation.admits(alpha, board.size(), large),
                "Sovereign whose season aggregate clears the threshold is admitted");
        assertFalse(SeasonAggregation.admits(beta, board.size(), large),
                "Sovereign below the season threshold is denied a large-galaxy seat");

        // And the gate IS the E9-01 gate: project + ProgressionEvaluation.admits agrees.
        StandingRecord alphaProjected =
                SeasonAggregation.toStandingRecord(alpha, board.size());
        assertEquals(60.0, alphaProjected.score(), 1e-9);
        assertTrue(ProgressionEvaluation.admits(alphaProjected, large),
                "season aggregate reuses E9-01 gating, not a fork");
    }

    @Test
    void minMatchesForSeatHoldsBackThinCampaigns() {
        // beta tops the board on one blow-out match, but the season demands >= 2 plays.
        List<StandingRecord> standings = List.of(
                match(BETA, "Beta", 1000, 10, 1, 4),   // 1 match, huge score
                match(ALPHA, "Alpha", 30, 5, 1, 4),
                match(ALPHA, "Alpha", 30, 5, 1, 4));   // 2 matches, modest score
        BalanceProfile.Season demandTwo = new BalanceProfile.Season(1.0, 0.0, 0.0, 2);
        BalanceProfile small = smallSumProfile();
        List<SeasonStanding> board = SeasonAggregation.aggregate(standings, small);

        SeasonStanding beta = board.stream().filter(s -> s.faction().equals(BETA))
                .findFirst().orElseThrow();
        SeasonStanding alpha = board.stream().filter(s -> s.faction().equals(ALPHA))
                .findFirst().orElseThrow();

        // Low bar so score alone would admit both; the min-matches floor denies beta.
        BalanceProfile large = largeProfile(0.0, false, demandTwo);
        assertEquals(1, beta.matchesPlayed());
        assertEquals(2, alpha.matchesPlayed());
        assertFalse(SeasonAggregation.admits(beta, board.size(), large),
                "a single-match Sovereign is held below the seat when the season demands >= 2");
        assertTrue(SeasonAggregation.admits(alpha, board.size(), large),
                "a Sovereign with enough plays clears the gate");
    }

    @Test
    void winGrantsSeatHonoursSeasonWinnerThroughProjection() {
        // A season winner (top of the board, bestPlacement 1) bypasses an impossible
        // threshold when winGrantsSeat is on - proving the projection carries placement.
        List<StandingRecord> standings = List.of(
                match(ALPHA, "Alpha", 10, 2, 1, 3),
                match(BETA, "Beta", 5, 1, 2, 3));
        BalanceProfile small = smallSumProfile();
        List<SeasonStanding> board = SeasonAggregation.aggregate(standings, small);
        SeasonStanding alpha = board.get(0);

        BalanceProfile large =
                largeProfile(1_000_000.0, true, BalanceProfile.Season.defaults());
        assertTrue(SeasonAggregation.admits(alpha, board.size(), large),
                "season winner earns a seat regardless of the score threshold");

        BalanceProfile strict =
                largeProfile(1_000_000.0, false, BalanceProfile.Season.defaults());
        assertFalse(SeasonAggregation.admits(alpha, board.size(), strict),
                "without winGrantsSeat the impossible threshold denies even the winner");
    }

    // ===== 3. the Season model (immutable, append-only, derives the board) ====

    @Test
    void seasonModelAccumulatesMatchesAndDerivesLeaderboard() {
        BalanceProfile small = smallSumProfile();
        Season season = Season.open("season-2026-q2")
                .withMatch(List.of(
                        match(ALPHA, "Alpha", 30, 10, 1, 2),
                        match(BETA, "Beta", 20, 5, 2, 2)))
                .withMatch(List.of(
                        match(BETA, "Beta", 80, 9, 1, 2),
                        match(ALPHA, "Alpha", 15, 4, 2, 2)));

        // 4 per-match standings collected (2 matches x 2 factions).
        assertEquals(4, season.matchStandings().size());

        List<SeasonStanding> board = season.leaderboard(small);
        // beta 100, alpha 45.
        assertEquals(BETA, board.get(0).faction());
        assertEquals(100.0, board.get(0).aggregateScore(), 1e-9);
        assertEquals(ALPHA, board.get(1).faction());
        assertEquals(45.0, board.get(1).aggregateScore(), 1e-9);

        // withMatch is pure: the original open season is untouched.
        assertTrue(Season.open("x").matchStandings().isEmpty());
    }

    @Test
    void seasonMatchStandingsAreImmutable() {
        List<StandingRecord> input = new ArrayList<>(List.of(
                match(ALPHA, "Alpha", 10, 1, 1, 1)));
        Season season = new Season("s", input);
        // Mutating the source list after construction must not affect the season.
        input.clear();
        assertEquals(1, season.matchStandings().size());
        assertThrows(UnsupportedOperationException.class,
                () -> season.matchStandings().add(match(BETA, "Beta", 1, 1, 1, 1)));
    }
}
