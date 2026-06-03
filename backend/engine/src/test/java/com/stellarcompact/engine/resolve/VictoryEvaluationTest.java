package com.stellarcompact.engine.resolve;

import com.stellarcompact.engine.config.BalanceProfile;
import com.stellarcompact.engine.config.BalanceProfile.VictoryKind;
import com.stellarcompact.engine.state.ActiveSystem;
import com.stellarcompact.engine.state.Biome;
import com.stellarcompact.engine.state.Building;
import com.stellarcompact.engine.state.BuildingStatus;
import com.stellarcompact.engine.state.BuildingType;
import com.stellarcompact.engine.state.Coords;
import com.stellarcompact.engine.state.Faction;
import com.stellarcompact.engine.state.FactionId;
import com.stellarcompact.engine.state.GameState;
import com.stellarcompact.engine.state.GameStatus;
import com.stellarcompact.engine.state.Planet;
import com.stellarcompact.engine.state.PlanetId;
import com.stellarcompact.engine.state.ResourceBundle;
import com.stellarcompact.engine.state.SystemId;
import com.stellarcompact.engine.state.Treaty;
import com.stellarcompact.engine.state.TreatyId;
import com.stellarcompact.engine.state.TreatyStatus;
import com.stellarcompact.engine.state.TreatyType;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Hand-authored, deterministic tests for E1-15 victory / scoring / elimination / lifecycle
 * (game-design 07). Each victory condition is exercised exactly at and just below its
 * configured threshold; scoring uses the config weights; alliance shared-victory splits the
 * win; the dormant {@code VictoryAchieved}/{@code FactionEliminated} events emit through the
 * EVENTS step with spec-matching payloads; and a determinism check proves the same
 * snapshot + profile yields an identical event stream.
 *
 * <p>Every threshold/weight asserted here is read from a per-test {@link BalanceProfile}
 * built below (rule 6) - nothing is hardcoded in {@code VictoryEvaluation}/{@code Scoring}.
 */
class VictoryEvaluationTest {

    private static final FactionId ALPHA = new FactionId("alpha");
    private static final FactionId BETA = new FactionId("beta");
    private static final FactionId GAMMA = new FactionId("gamma");

    // ---- profile builder -----------------------------------------------------

    private static BalanceProfile profile(VictoryKind active, boolean conditionEnabled,
                                          boolean eliminationEnabled,
                                          double dominationPct, double influenceTarget,
                                          double diplomaticPct, int survivalTickLimit,
                                          int wonderStages,
                                          BalanceProfile.ScoreWeights weights) {
        return profile(active, conditionEnabled, eliminationEnabled, dominationPct,
                influenceTarget, diplomaticPct, survivalTickLimit, wonderStages, weights, 0);
    }

    private static BalanceProfile profile(VictoryKind active, boolean conditionEnabled,
                                          boolean eliminationEnabled,
                                          double dominationPct, double influenceTarget,
                                          double diplomaticPct, int survivalTickLimit,
                                          int wonderStages,
                                          BalanceProfile.ScoreWeights weights,
                                          int hardTickLimit) {
        BalanceProfile.Victory victory = new BalanceProfile.Victory(
                active, conditionEnabled, eliminationEnabled,
                new BalanceProfile.Domination(dominationPct),
                new BalanceProfile.Economic(influenceTarget, 50),
                new BalanceProfile.Diplomatic(diplomaticPct),
                new BalanceProfile.Survival(survivalTickLimit),
                new BalanceProfile.Wonder(wonderStages, 30),
                weights, hardTickLimit);
        return new BalanceProfile(
                "test", 1,
                new BalanceProfile.Resources(Map.of(), Map.of(), 0.1),
                new BalanceProfile.Population(1.0, 1.0, 0.1, 5, Map.of()),
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
                new BalanceProfile.Tick(1000, 2, 5000));
    }

    private static BalanceProfile.ScoreWeights weights() {
        return new BalanceProfile.ScoreWeights(3.0, 2.0, 1.5, 1.0, 1.0, 1.5, 1.0);
    }

    // ---- state builders ------------------------------------------------------

    private static Faction faction(FactionId id, double influence) {
        return new Faction(id, "F-" + id.value(), 0.0,
                new ResourceBundle(0, 0, 0, 0, influence), Map.of());
    }

    private static ActiveSystem ownedSystem(String id, FactionId owner, List<Building> buildings) {
        Planet planet = new Planet(new PlanetId("p-" + id), Biome.TERRAN, 8, 0, buildings);
        return new ActiveSystem(new SystemId(id), "Sys " + id, new Coords(1, 1),
                Optional.of(owner), List.of(planet), 0, 1.0);
    }

    private static ActiveSystem neutralSystem(String id) {
        Planet planet = new Planet(new PlanetId("p-" + id), Biome.TERRAN, 8, 0, List.of());
        return new ActiveSystem(new SystemId(id), "Sys " + id, new Coords(1, 1),
                Optional.empty(), List.of(planet), 0, 1.0);
    }

    private static Building monument(int slot) {
        return new Building(slot, BuildingType.MONUMENT, BuildingStatus.ACTIVE, 0);
    }

    private static GameState state(long tick, Map<FactionId, Faction> factions,
                                   List<ActiveSystem> systems, List<Treaty> treaties) {
        Map<SystemId, ActiveSystem> sysMap = new LinkedHashMap<>();
        for (ActiveSystem s : systems) {
            sysMap.put(s.id(), s);
        }
        Map<TreatyId, Treaty> treatyMap = new LinkedHashMap<>();
        for (Treaty t : treaties) {
            treatyMap.put(t.id(), t);
        }
        return new GameState(42L, tick, GameStatus.RUNNING, "test", 1,
                factions, sysMap, Map.of(), treatyMap, Map.of(), Map.of(), Set.of());
    }

    private static Treaty alliance(FactionId a, FactionId b) {
        return new Treaty(new TreatyId("ally-" + a.value() + "-" + b.value()),
                TreatyType.ALLIANCE, List.of(a, b), Map.of(), 0L, Long.MAX_VALUE,
                TreatyStatus.ACTIVE);
    }

    private static Treaty vassalage(FactionId suzerain, FactionId vassal) {
        return new Treaty(new TreatyId("vassal-" + suzerain.value() + "-" + vassal.value()),
                TreatyType.VASSALAGE, List.of(suzerain, vassal), Map.of(), 0L, Long.MAX_VALUE,
                TreatyStatus.ACTIVE);
    }

    private static List<PublicEvent> evaluate(GameState s, BalanceProfile p) {
        List<PublicEvent> events = new ArrayList<>();
        VictoryEvaluation.evaluate(s, p, events);
        return events;
    }

    private static long victories(List<PublicEvent> events) {
        return events.stream().filter(e -> e instanceof PublicEvent.VictoryAchieved).count();
    }

    private static List<FactionId> winners(List<PublicEvent> events) {
        List<FactionId> w = new ArrayList<>();
        for (PublicEvent e : events) {
            if (e instanceof PublicEvent.VictoryAchieved v) {
                w.add(v.winner());
            }
        }
        return w;
    }

    // ===== Domination ==========================================================

    @Test
    void dominationFiresAtThresholdNotBelow() {
        BalanceProfile p = profile(VictoryKind.DOMINATION, true, false,
                0.5, 1000, 0.6, 1000, 3, weights());
        // 4 systems; alpha owns 2 (== 50%) -> win.
        GameState win = state(5L,
                Map.of(ALPHA, faction(ALPHA, 0), BETA, faction(BETA, 0)),
                List.of(ownedSystem("s1", ALPHA, List.of()), ownedSystem("s2", ALPHA, List.of()),
                        ownedSystem("s3", BETA, List.of()), neutralSystem("s4")),
                List.of());
        assertEquals(List.of(ALPHA), winners(evaluate(win, p)), "alpha at 50% wins domination");

        // alpha owns only 1 (== 25%) -> no win.
        GameState below = state(5L,
                Map.of(ALPHA, faction(ALPHA, 0), BETA, faction(BETA, 0)),
                List.of(ownedSystem("s1", ALPHA, List.of()), ownedSystem("s3", BETA, List.of()),
                        neutralSystem("s4"), neutralSystem("s5")),
                List.of());
        assertEquals(0, victories(evaluate(below, p)), "25% does not win domination");
    }

    @Test
    void dominationConcludesTheMatch() {
        BalanceProfile p = profile(VictoryKind.DOMINATION, true, false,
                0.5, 1000, 0.6, 1000, 3, weights());
        GameState win = state(5L,
                Map.of(ALPHA, faction(ALPHA, 0)),
                List.of(ownedSystem("s1", ALPHA, List.of()), ownedSystem("s2", ALPHA, List.of())),
                List.of());
        List<PublicEvent> events = new ArrayList<>();
        GameState after = VictoryEvaluation.evaluate(win, p, events);
        assertEquals(GameStatus.CONCLUDED, after.status(), "RUNNING -> CONCLUDED on victory");
    }

    /**
     * E11-03 reconciliation (4-agent-sim finding L3): on the galaxy-generated live small map a
     * faction can own 1..8 systems (4 homes + up to 4 planeted neutrals from
     * {@code MatchBootstrap}), so {@code small-default}'s DOMINATION {@code systemPct = 0.6} -
     * which needs {@code ceil(0.6 * 8) = 5} systems - is now reachable-but-not-trivial. This
     * pins that arithmetic against the shipped 0.6 on an 8-system snapshot: 5 of 8 wins, 4 of 8
     * does not (so 0.6 demands genuine dominance, not merely the largest of four ~2-system
     * factions). The old stub allowed at most 1 owned system, which is why L3 reported it
     * unreachable; the fix was the E11-01 generator (planeted neutrals + a connected region),
     * not a lower threshold.
     */
    @Test
    void dominationReachableOnEightSystemGeneratedSmallMap() {
        BalanceProfile p = profile(VictoryKind.DOMINATION, true, false,
                0.6, 1000, 0.6, 100000, 3, weights());
        // 8 systems (mirrors small-default: 4 homes + 4 neutrals). alpha owns 5 (>= ceil(4.8)=5).
        GameState win = state(5L,
                Map.of(ALPHA, faction(ALPHA, 0), BETA, faction(BETA, 0)),
                List.of(ownedSystem("s1", ALPHA, List.of()), ownedSystem("s2", ALPHA, List.of()),
                        ownedSystem("s3", ALPHA, List.of()), ownedSystem("s4", ALPHA, List.of()),
                        ownedSystem("s5", ALPHA, List.of()), ownedSystem("s6", BETA, List.of()),
                        neutralSystem("s7"), neutralSystem("s8")),
                List.of());
        assertEquals(List.of(ALPHA), winners(evaluate(win, p)),
                "5 of 8 (62.5%) clears the 0.6 domination bar on the generated small map");

        // alpha owns only 4 of 8 (50%): below 0.6's required 4.8 -> no win (not trivial).
        GameState below = state(5L,
                Map.of(ALPHA, faction(ALPHA, 0), BETA, faction(BETA, 0)),
                List.of(ownedSystem("s1", ALPHA, List.of()), ownedSystem("s2", ALPHA, List.of()),
                        ownedSystem("s3", ALPHA, List.of()), ownedSystem("s4", ALPHA, List.of()),
                        ownedSystem("s5", BETA, List.of()), ownedSystem("s6", BETA, List.of()),
                        neutralSystem("s7"), neutralSystem("s8")),
                List.of());
        assertEquals(0, victories(evaluate(below, p)),
                "4 of 8 (50%) does not clear 0.6 -> domination still demands real dominance");
    }

    // ===== Economic ============================================================

    @Test
    void economicFiresAtTargetNotBelow() {
        BalanceProfile p = profile(VictoryKind.ECONOMIC, true, false,
                0.6, 1000, 0.6, 1000, 3, weights());
        GameState atTarget = state(5L,
                Map.of(ALPHA, faction(ALPHA, 1000), BETA, faction(BETA, 999)),
                List.of(ownedSystem("s1", ALPHA, List.of()), ownedSystem("s2", BETA, List.of())),
                List.of());
        assertEquals(List.of(ALPHA), winners(evaluate(atTarget, p)), "alpha at 1000 wins economic");

        GameState below = state(5L,
                Map.of(ALPHA, faction(ALPHA, 999)),
                List.of(ownedSystem("s1", ALPHA, List.of())),
                List.of());
        assertEquals(0, victories(evaluate(below, p)), "999 < 1000 does not win economic");
    }

    // ===== Diplomatic (alliance shared) ========================================

    @Test
    void diplomaticAllianceWinsAndSharesTheVictory() {
        BalanceProfile p = profile(VictoryKind.DIPLOMATIC, true, false,
                0.6, 1000, 0.5, 1000, 3, weights());
        // 4 systems; alpha+beta allied own 1 each (combined 50% == threshold) -> shared win.
        GameState s = state(5L,
                Map.of(ALPHA, faction(ALPHA, 0), BETA, faction(BETA, 0), GAMMA, faction(GAMMA, 0)),
                List.of(ownedSystem("s1", ALPHA, List.of()), ownedSystem("s2", BETA, List.of()),
                        ownedSystem("s3", GAMMA, List.of()), neutralSystem("s4")),
                List.of(alliance(ALPHA, BETA)));
        assertEquals(List.of(ALPHA, BETA), winners(evaluate(s, p)),
                "alliance shares the diplomatic win, in id order");
    }

    @Test
    void diplomaticLoneFactionDoesNotWin() {
        BalanceProfile p = profile(VictoryKind.DIPLOMATIC, true, false,
                0.6, 1000, 0.5, 1000, 3, weights());
        // alpha alone owns 2/4 = 50% but there is NO alliance -> diplomatic needs a real alliance.
        GameState s = state(5L,
                Map.of(ALPHA, faction(ALPHA, 0), BETA, faction(BETA, 0)),
                List.of(ownedSystem("s1", ALPHA, List.of()), ownedSystem("s2", ALPHA, List.of()),
                        ownedSystem("s3", BETA, List.of()), neutralSystem("s4")),
                List.of());
        assertEquals(0, victories(evaluate(s, p)), "a lone faction cannot win Diplomatic");
    }

    // ===== Survival ============================================================

    @Test
    void survivalLastWithCapitalWins() {
        BalanceProfile p = profile(VictoryKind.SURVIVAL, true, false,
                0.6, 1000, 0.6, 1000, 3, weights());
        // beta owns nothing; only alpha holds a capital -> alpha wins.
        GameState s = state(5L,
                Map.of(ALPHA, faction(ALPHA, 0), BETA, faction(BETA, 0)),
                List.of(ownedSystem("s1", ALPHA, List.of())),
                List.of());
        assertEquals(List.of(ALPHA), winners(evaluate(s, p)), "last faction with a capital wins");
    }

    @Test
    void survivalDoesNotFireWhileTwoFactionsHoldCapitalsBeforeTickLimit() {
        BalanceProfile p = profile(VictoryKind.SURVIVAL, true, false,
                0.6, 1000, 0.6, 1000, 3, weights());
        GameState s = state(5L,
                Map.of(ALPHA, faction(ALPHA, 0), BETA, faction(BETA, 0)),
                List.of(ownedSystem("s1", ALPHA, List.of()), ownedSystem("s2", BETA, List.of())),
                List.of());
        assertEquals(0, victories(evaluate(s, p)), "two survivors before tick limit -> no win");
    }

    @Test
    void survivalTickLimitCrownsTheTopRankedSurvivor() {
        BalanceProfile p = profile(VictoryKind.SURVIVAL, true, false,
                0.6, 1000, 0.6, 10, 3, weights());
        // tick == limit; both hold a capital, beta has far more influence -> beta tops the ranking.
        GameState s = state(10L,
                Map.of(ALPHA, faction(ALPHA, 0), BETA, faction(BETA, 5000)),
                List.of(ownedSystem("s1", ALPHA, List.of()), ownedSystem("s2", BETA, List.of())),
                List.of());
        assertEquals(List.of(BETA), winners(evaluate(s, p)),
                "at the tick limit the highest-scoring survivor wins");
    }

    // ===== Wonder ==============================================================

    @Test
    void wonderFiresWhenStagesComplete() {
        BalanceProfile p = profile(VictoryKind.WONDER, true, false,
                0.6, 1000, 0.6, 1000, 2, weights());
        // alpha holds 2 ACTIVE monuments (== stages) -> win.
        GameState win = state(5L,
                Map.of(ALPHA, faction(ALPHA, 0)),
                List.of(ownedSystem("s1", ALPHA, List.of(monument(0), monument(1)))),
                List.of());
        assertEquals(List.of(ALPHA), winners(evaluate(win, p)), "2 monuments completes the Wonder");

        GameState below = state(5L,
                Map.of(ALPHA, faction(ALPHA, 0)),
                List.of(ownedSystem("s1", ALPHA, List.of(monument(0)))),
                List.of());
        assertEquals(0, victories(evaluate(below, p)), "1 monument < 2 stages does not win");
    }

    // ===== Elimination & vassalage =============================================

    @Test
    void factionWithNoSystemsIsEliminated() {
        BalanceProfile p = profile(VictoryKind.DOMINATION, false, true,
                0.99, 1000, 0.6, 1000, 3, weights());
        GameState s = state(5L,
                Map.of(ALPHA, faction(ALPHA, 0), BETA, faction(BETA, 0)),
                List.of(ownedSystem("s1", ALPHA, List.of())), // beta owns nothing
                List.of());
        List<FactionId> eliminated = new ArrayList<>();
        for (PublicEvent e : evaluate(s, p)) {
            if (e instanceof PublicEvent.FactionEliminated fe) {
                eliminated.add(fe.faction());
            }
        }
        assertEquals(List.of(BETA), eliminated, "the factionless faction is eliminated");
    }

    @Test
    void vassalIsShieldedFromElimination() {
        BalanceProfile p = profile(VictoryKind.DOMINATION, false, true,
                0.99, 1000, 0.6, 1000, 3, weights());
        // beta owns nothing but is alpha vassal -> NOT eliminated.
        GameState s = state(5L,
                Map.of(ALPHA, faction(ALPHA, 0), BETA, faction(BETA, 0)),
                List.of(ownedSystem("s1", ALPHA, List.of())),
                List.of(vassalage(ALPHA, BETA)));
        for (PublicEvent e : evaluate(s, p)) {
            assertFalse(e instanceof PublicEvent.FactionEliminated,
                    "a vassal must not be eliminated");
        }
    }

    @Test
    void disabledSwitchesEmitNothing() {
        BalanceProfile p = profile(VictoryKind.DOMINATION, false, false,
                0.1, 1, 0.1, 1, 1, weights());
        GameState s = state(5L,
                Map.of(ALPHA, faction(ALPHA, 9999), BETA, faction(BETA, 0)),
                List.of(ownedSystem("s1", ALPHA, List.of())),
                List.of());
        assertTrue(evaluate(s, p).isEmpty(), "both switches off -> no events, no conclusion");
    }

    // ===== E11-02 hard timeout-victory (always ends) ===========================

    @Test
    void hardTimeoutConcludesWithTopRankedLeaderRegardlessOfActiveKind() {
        // Active kind is DOMINATION and NOBODY meets its threshold, so without the hard
        // timeout this match would run forever (board finding L3). hardTickLimit == 50.
        BalanceProfile p = profile(VictoryKind.DOMINATION, true, false,
                0.99, 100000, 0.99, 100000, 99, weights(), 50);
        // tick == limit; no faction owns 99% of systems; beta leads on influence.
        GameState s = state(50L,
                Map.of(ALPHA, faction(ALPHA, 10), BETA, faction(BETA, 5000), GAMMA, faction(GAMMA, 0)),
                List.of(ownedSystem("s1", ALPHA, List.of()), ownedSystem("s2", BETA, List.of()),
                        ownedSystem("s3", GAMMA, List.of()), neutralSystem("s4")),
                List.of());
        List<PublicEvent> events = new ArrayList<>();
        GameState after = VictoryEvaluation.evaluate(s, p, events);
        assertEquals(List.of(BETA), winners(events),
                "the highest-scoring faction wins at the hard timeout");
        assertEquals(GameStatus.CONCLUDED, after.status(), "the match concludes at the hard limit");
    }

    @Test
    void hardTimeoutTieBreaksByFactionId() {
        // Two factions with IDENTICAL weighted score (same 1 system, same influence) at the
        // limit: the deterministic Scoring tie-break (faction id ascending) crowns ALPHA.
        BalanceProfile p = profile(VictoryKind.DOMINATION, true, false,
                0.99, 100000, 0.99, 100000, 99, weights(), 50);
        GameState s = state(50L,
                Map.of(ALPHA, faction(ALPHA, 100), BETA, faction(BETA, 100)),
                List.of(ownedSystem("s1", ALPHA, List.of()), ownedSystem("s2", BETA, List.of()),
                        neutralSystem("s3")),
                List.of());
        assertEquals(List.of(ALPHA), winners(evaluate(s, p)),
                "on an exact score tie the lowest faction id wins (alpha < beta)");
    }

    @Test
    void hardTimeoutDoesNotFireBeforeTheLimit() {
        BalanceProfile p = profile(VictoryKind.DOMINATION, true, false,
                0.99, 100000, 0.99, 100000, 99, weights(), 50);
        // tick 49 < 50: still RUNNING, no primary win -> no conclusion yet.
        GameState s = state(49L,
                Map.of(ALPHA, faction(ALPHA, 10), BETA, faction(BETA, 5000)),
                List.of(ownedSystem("s1", ALPHA, List.of()), ownedSystem("s2", BETA, List.of())),
                List.of());
        List<PublicEvent> events = new ArrayList<>();
        GameState after = VictoryEvaluation.evaluate(s, p, events);
        assertEquals(0, victories(events), "before the hard limit nothing is crowned");
        assertEquals(GameStatus.RUNNING, after.status(), "match keeps running before the limit");
    }

    @Test
    void disabledHardTimeoutNeverFires() {
        // hardTickLimit == 0 (disabled): even far past any reasonable horizon, no fallback.
        BalanceProfile p = profile(VictoryKind.DOMINATION, true, false,
                0.99, 100000, 0.99, 100000, 99, weights(), 0);
        GameState s = state(1_000_000L,
                Map.of(ALPHA, faction(ALPHA, 10), BETA, faction(BETA, 5000)),
                List.of(ownedSystem("s1", ALPHA, List.of()), ownedSystem("s2", BETA, List.of())),
                List.of());
        List<PublicEvent> events = new ArrayList<>();
        GameState after = VictoryEvaluation.evaluate(s, p, events);
        assertEquals(0, victories(events), "a disabled hard timeout never crowns a winner");
        assertEquals(GameStatus.RUNNING, after.status(), "a disabled timeout never concludes");
    }

    @Test
    void primaryVictoryTakesPrecedenceOverHardTimeout() {
        // Both fire on the same tick: ALPHA meets DOMINATION (2/2 systems) AND tick == limit.
        // The primary condition must win, and BETA (which would top the timeout ranking on
        // influence) must NOT be crowned.
        BalanceProfile p = profile(VictoryKind.DOMINATION, true, false,
                0.5, 100000, 0.99, 100000, 99, weights(), 50);
        GameState s = state(50L,
                Map.of(ALPHA, faction(ALPHA, 0), BETA, faction(BETA, 9999)),
                List.of(ownedSystem("s1", ALPHA, List.of()), ownedSystem("s2", ALPHA, List.of())),
                List.of());
        List<FactionId> w = winners(evaluate(s, p));
        assertEquals(List.of(ALPHA), w, "the primary DOMINATION winner takes precedence at the limit");
    }

    @Test
    void hardTimeoutIsDeterministicSameSnapshotSameWinner() {
        BalanceProfile p = profile(VictoryKind.WONDER, true, true,
                0.99, 100000, 0.99, 100000, 99, weights(), 50);
        GameState s = state(50L,
                Map.of(ALPHA, faction(ALPHA, 100), BETA, faction(BETA, 300), GAMMA, faction(GAMMA, 200)),
                List.of(ownedSystem("s1", ALPHA, List.of()), ownedSystem("s2", BETA, List.of()),
                        ownedSystem("s3", GAMMA, List.of())),
                List.of());
        assertEquals(describe(evaluate(s, p)), describe(evaluate(s, p)),
                "same seed/snapshot -> identical timeout outcome");
        // And the leader is exactly Scoring.rank's element 0 (id-tiebroken).
        assertEquals(List.of(Scoring.rank(s, p).get(0).faction()), winners(evaluate(s, p)),
                "the timeout winner is the canonical ranking leader");
    }

    // ===== scoring =============================================================

    @Test
    void scoreUsesConfigWeights() {
        BalanceProfile p = profile(VictoryKind.DOMINATION, false, false,
                0.6, 1000, 0.6, 1000, 3, weights());
        // alpha: 2 systems (w=3), influence 10 (w=2), reputation 0 -> 2*3 + 10*2 = 26.
        GameState s = state(5L,
                Map.of(ALPHA, faction(ALPHA, 10)),
                List.of(ownedSystem("s1", ALPHA, List.of()), ownedSystem("s2", ALPHA, List.of())),
                List.of());
        double score = Scoring.score(s, ALPHA, p);
        assertEquals(2 * 3.0 + 10 * 2.0, score, 1e-9, "weighted score = systems*3 + influence*2");
    }

    @Test
    void rankingOrdersByScoreThenId() {
        BalanceProfile p = profile(VictoryKind.DOMINATION, false, false,
                0.6, 1000, 0.6, 1000, 3, weights());
        GameState s = state(5L,
                Map.of(ALPHA, faction(ALPHA, 0), BETA, faction(BETA, 100), GAMMA, faction(GAMMA, 0)),
                List.of(ownedSystem("s1", ALPHA, List.of())),
                List.of());
        List<Scoring.FactionScore> rank = Scoring.rank(s, p);
        // beta (influence 100 -> 200) leads; then alpha (1 system -> 3) > gamma (0); id breaks ties.
        assertEquals(BETA, rank.get(0).faction(), "highest score leads");
        assertEquals(ALPHA, rank.get(1).faction());
        assertEquals(GAMMA, rank.get(2).faction());
    }

    // ===== events wiring + determinism =========================================

    @Test
    void victoryEventPayloadMatchesSpec() {
        BalanceProfile p = profile(VictoryKind.ECONOMIC, true, false,
                0.6, 100, 0.6, 1000, 3, weights());
        GameState s = state(7L,
                Map.of(ALPHA, faction(ALPHA, 100)),
                List.of(ownedSystem("s1", ALPHA, List.of())),
                List.of());
        PublicEvent.VictoryAchieved v = (PublicEvent.VictoryAchieved) evaluate(s, p).get(0);
        assertEquals("VictoryAchieved", v.type());
        assertEquals(List.of(ALPHA), v.parties());
        assertTrue(v.systemId().isEmpty());
        assertEquals(7L, v.tick());
    }

    @Test
    void wiredThroughResolverEventsStep() {
        // Prove the evaluation runs in its fixed EVENTS slot via the full Resolver.
        BalanceProfile p = profile(VictoryKind.ECONOMIC, true, false,
                0.6, 100, 0.6, 1000, 3, weights());
        GameState s = state(3L,
                Map.of(ALPHA, faction(ALPHA, 100)),
                List.of(ownedSystem("s1", ALPHA, List.of())),
                List.of());
        ResolveResult r = Resolver.resolveResult(s, List.of(), p, s.gameSeed());
        assertEquals(GameStatus.CONCLUDED, r.state().status(), "match concluded through Resolver");
        assertEquals(List.of(ALPHA), winners(r.events()), "VictoryAchieved emitted in EVENTS step");
    }

    @Test
    void sameSnapshotYieldsIdenticalEventStream() {
        BalanceProfile p = profile(VictoryKind.DOMINATION, true, true,
                0.5, 1000, 0.6, 1000, 3, weights());
        GameState s = state(5L,
                Map.of(ALPHA, faction(ALPHA, 0), BETA, faction(BETA, 0), GAMMA, faction(GAMMA, 0)),
                List.of(ownedSystem("s1", ALPHA, List.of()), ownedSystem("s2", ALPHA, List.of()),
                        ownedSystem("s3", ALPHA, List.of())),
                List.of());
        // beta & gamma own nothing -> eliminated (deterministic id order); alpha wins.
        assertEquals(describe(evaluate(s, p)), describe(evaluate(s, p)),
                "identical input -> identical event stream");
    }

    private static List<String> describe(List<PublicEvent> events) {
        List<String> out = new ArrayList<>();
        for (PublicEvent e : events) {
            out.add(e.type() + ":" + e.parties() + "@" + e.tick());
        }
        return out;
    }
}
