package com.stellarcompact.engine.progression;

import com.stellarcompact.engine.config.BalanceProfile;
import com.stellarcompact.engine.config.BalanceProfile.VictoryKind;
import com.stellarcompact.engine.state.ActiveSystem;
import com.stellarcompact.engine.state.Biome;
import com.stellarcompact.engine.state.Coords;
import com.stellarcompact.engine.state.Faction;
import com.stellarcompact.engine.state.FactionId;
import com.stellarcompact.engine.state.GameState;
import com.stellarcompact.engine.state.GameStatus;
import com.stellarcompact.engine.state.Planet;
import com.stellarcompact.engine.state.PlanetId;
import com.stellarcompact.engine.state.ResourceBundle;
import com.stellarcompact.engine.state.SystemId;
import com.stellarcompact.engine.state.TechId;
import com.stellarcompact.engine.state.TechProgress;
import com.stellarcompact.engine.state.TechStatus;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Deterministic, hand-authored tests for the E9-01 small-&gt;large progression kernel
 * (game-design 07 section 6, 06 section 5). They prove the three contract clauses:
 *
 * <ol>
 *   <li>completing a small galaxy gates entry to a large one - a faction below the
 *       configured seat threshold is denied; at/above is admitted;</li>
 *   <li>only identity/reputation carries - a carried-over Sovereign starts with the
 *       destination profile's starter loadout (NOT its prior resources/tech), while its
 *       identity and a config-weighted slice of reputation persist;</li>
 *   <li>no resource carry-over - material state is reset to the starter, reputation is
 *       seeded from prior standing scaled by the carry weight.</li>
 * </ol>
 *
 * <p>Every threshold/weight asserted is read from a per-test {@link BalanceProfile}
 * (rule 6); nothing is hardcoded in {@code ProgressionEvaluation}.
 */
class ProgressionEvaluationTest {

    private static final FactionId ALPHA = new FactionId("alpha");
    private static final FactionId BETA = new FactionId("beta");

    // ---- profile builders ----------------------------------------------------

    private static BalanceProfile profile(String name, String sizeClass,
                                          double seatThreshold, boolean winGrantsSeat,
                                          double repCarryWeight,
                                          BalanceProfile.ResourceBundle starter) {
        BalanceProfile.Victory victory = new BalanceProfile.Victory(
                new BalanceProfile.Domination(0.6),
                new BalanceProfile.Economic(1000.0, 50),
                new BalanceProfile.Diplomatic(0.6),
                new BalanceProfile.Survival(300),
                new BalanceProfile.Wonder(3, 30),
                new BalanceProfile.ScoreWeights(3.0, 2.0, 1.5, 1.0, 1.0, 1.5, 1.0));
        BalanceProfile.Progression progression = new BalanceProfile.Progression(
                sizeClass, seatThreshold, winGrantsSeat, repCarryWeight, starter);
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
                progression);
    }

    /** A SMALL funnel profile (open seat, no carry). */
    private static BalanceProfile smallProfile() {
        return profile("small-test", "SMALL", 0.0, false, 0.0,
                new BalanceProfile.ResourceBundle(0, 0, 0, 0, 0));
    }

    /** A LARGE campaign profile gating entry at {@code threshold} with a carry weight. */
    private static BalanceProfile largeProfile(double threshold, boolean winGrantsSeat,
                                               double repCarryWeight) {
        return profile("large-test", "LARGE", threshold, winGrantsSeat, repCarryWeight,
                // a generous fresh starter loadout every entrant gets
                new BalanceProfile.ResourceBundle(100, 100, 50, 10, 0));
    }

    // ---- state builders ------------------------------------------------------

    /** A faction loaded with material wealth + unlocked tech (its prior-match spoils). */
    private static Faction richFaction(FactionId id, String name, double reputation) {
        Map<TechId, TechProgress> tech = Map.of(
                new TechId("capitalDoctrine"),
                new TechProgress(new TechId("capitalDoctrine"), TechStatus.UNLOCKED, 0));
        return new Faction(id, name, reputation,
                new ResourceBundle(9999, 9999, 9999, 9999, 9999), tech);
    }

    private static ActiveSystem ownedSystem(String id, FactionId owner) {
        Planet planet = new Planet(new PlanetId("p-" + id), Biome.TERRAN, 8, 0, List.of());
        return new ActiveSystem(new SystemId(id), "Sys " + id, new Coords(1, 1),
                Optional.of(owner), List.of(planet), 0, 1.0);
    }

    /** A CONCLUDED small match: alpha dominates (more systems) so it outranks beta. */
    private static GameState concludedSmallMatch(double alphaRep, double betaRep) {
        Map<FactionId, Faction> factions = new LinkedHashMap<>();
        factions.put(ALPHA, richFaction(ALPHA, "Alpha Sovereign", alphaRep));
        factions.put(BETA, richFaction(BETA, "Beta Sovereign", betaRep));
        Map<SystemId, ActiveSystem> systems = new LinkedHashMap<>();
        // alpha owns 3 systems, beta owns 1 -> alpha scores higher (systems weight 3.0)
        systems.put(new SystemId("s1"), ownedSystem("s1", ALPHA));
        systems.put(new SystemId("s2"), ownedSystem("s2", ALPHA));
        systems.put(new SystemId("s3"), ownedSystem("s3", ALPHA));
        systems.put(new SystemId("s4"), ownedSystem("s4", BETA));
        return new GameState(42L, 300L, GameStatus.CONCLUDED, "small-test", 1,
                factions, systems, Map.of(), Map.of(), Map.of(), Map.of(), Set.of());
    }

    // ===== 1. standing derivation =============================================

    @Test
    void standingRanksWinnerFirstAndCarriesIdentityNotMaterial() {
        GameState concluded = concludedSmallMatch(40.0, 12.0);
        BalanceProfile small = smallProfile();

        List<StandingRecord> standings = ProgressionEvaluation.standings(concluded, small);
        assertEquals(2, standings.size());
        StandingRecord first = standings.get(0);
        assertEquals(ALPHA, first.faction(), "alpha (3 systems) places first");
        assertEquals(1, first.placement());
        assertTrue(first.won(), "placement 1 is a win");
        assertEquals(GalaxySizeClass.SMALL, first.sizeClass());
        assertEquals(40.0, first.reputation(), 1e-9);
        assertEquals(2, first.matchSize());

        StandingRecord second = standings.get(1);
        assertEquals(BETA, second.faction());
        assertEquals(2, second.placement());
        assertFalse(second.won());
        assertTrue(first.score() > second.score(), "more systems -> higher score");
    }

    @Test
    void standingOnlyFromConcludedMatch() {
        Map<FactionId, Faction> factions = new LinkedHashMap<>();
        factions.put(ALPHA, richFaction(ALPHA, "Alpha", 10));
        GameState running = new GameState(42L, 1L, GameStatus.RUNNING, "small-test", 1,
                factions, Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Set.of());
        assertThrows(IllegalArgumentException.class,
                () -> ProgressionEvaluation.standingOf(running, ALPHA, smallProfile()));
    }

    // ===== 2. seat gating (small completion gates large entry) ================

    @Test
    void seatThresholdGatesLargeGalaxyEntry() {
        GameState concluded = concludedSmallMatch(40.0, 12.0);
        BalanceProfile small = smallProfile();
        StandingRecord alphaStanding = ProgressionEvaluation.standingOf(concluded, ALPHA, small);
        StandingRecord betaStanding = ProgressionEvaluation.standingOf(concluded, BETA, small);

        // Threshold ABOVE beta's score but at/below alpha's: alpha admitted, beta denied.
        double bar = (alphaStanding.score() + betaStanding.score()) / 2.0;
        BalanceProfile large = largeProfile(bar, false, 0.5);

        assertTrue(ProgressionEvaluation.admits(alphaStanding, large),
                "alpha at/above threshold is admitted to the large galaxy");
        assertFalse(ProgressionEvaluation.admits(betaStanding, large),
                "beta below threshold is denied a large-galaxy seat");
    }

    @Test
    void winGrantsSeatBypassesThreshold() {
        GameState concluded = concludedSmallMatch(40.0, 12.0);
        StandingRecord alphaStanding =
                ProgressionEvaluation.standingOf(concluded, ALPHA, smallProfile());
        // An impossibly high threshold nobody clears, but winGrantsSeat is on and alpha won.
        BalanceProfile large = largeProfile(1_000_000.0, true, 0.5);
        assertTrue(ProgressionEvaluation.admits(alphaStanding, large),
                "winning a small match earns a seat regardless of the score threshold");

        // With winGrantsSeat OFF, the same winner is denied by the impossible threshold.
        BalanceProfile strict = largeProfile(1_000_000.0, false, 0.5);
        assertFalse(ProgressionEvaluation.admits(alphaStanding, strict));
    }

    @Test
    void smallGalaxyIsOpenEntry() {
        GameState concluded = concludedSmallMatch(40.0, 12.0);
        StandingRecord betaStanding =
                ProgressionEvaluation.standingOf(concluded, BETA, smallProfile());
        // Entering ANOTHER small (funnel) galaxy is never gated, even for a low scorer.
        BalanceProfile anotherSmall = smallProfile();
        assertTrue(ProgressionEvaluation.admits(betaStanding, anotherSmall));
    }

    // ===== 3. carry ONLY identity + reputation, reset material ================

    @Test
    void carryOverSeedsIdentityAndWeightedReputationOnly() {
        GameState concluded = concludedSmallMatch(40.0, 12.0);
        BalanceProfile small = smallProfile();
        BalanceProfile large = largeProfile(0.0, false, 0.5); // open + half reputation carry

        Faction seeded = ProgressionEvaluation.carryOver(concluded, ALPHA, small, large);

        // Identity persists.
        assertEquals(ALPHA, seeded.id());
        assertEquals("Alpha Sovereign", seeded.name(), "name (identity) carries");

        // Reputation seeded from prior standing scaled by the carry weight (40 * 0.5).
        assertEquals(20.0, seeded.reputation(), 1e-9);

        // Material state is RESET to the destination's starter loadout, NOT the prior
        // match's 9999 stockpiles.
        ResourceBundle starter = seeded.stockpiles();
        assertEquals(100.0, starter.energy(), 1e-9);
        assertEquals(100.0, starter.minerals(), 1e-9);
        assertEquals(50.0, starter.food(), 1e-9);
        assertEquals(10.0, starter.tech(), 1e-9);
        assertEquals(0.0, starter.influence(), 1e-9);

        // No tech carries: the prior capitalDoctrine unlock is gone (fresh DAG).
        assertTrue(seeded.techProgress().isEmpty(),
                "no prior tech carries across the small->large boundary");
    }

    @Test
    void zeroCarryWeightStartsReputationClean() {
        GameState concluded = concludedSmallMatch(40.0, 12.0);
        BalanceProfile large = largeProfile(0.0, false, 0.0); // identity-only, no reputation
        Faction seeded = ProgressionEvaluation.carryOver(concluded, ALPHA, smallProfile(), large);
        assertEquals(0.0, seeded.reputation(), 1e-9, "weight 0 => clean reputation start");
        assertEquals(ALPHA, seeded.id(), "identity still carries");
    }

    @Test
    void carryOverDeniedWhenStandingBelowSeatThreshold() {
        GameState concluded = concludedSmallMatch(40.0, 12.0);
        BalanceProfile small = smallProfile();
        BalanceProfile large = largeProfile(1_000_000.0, false, 0.5); // nobody clears

        assertThrows(IllegalStateException.class,
                () -> ProgressionEvaluation.carryOver(concluded, BETA, small, large),
                "an unqualified Sovereign cannot be carried into the large galaxy");
    }
}
