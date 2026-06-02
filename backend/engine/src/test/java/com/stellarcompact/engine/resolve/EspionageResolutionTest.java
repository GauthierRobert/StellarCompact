package com.stellarcompact.engine.resolve;

import com.stellarcompact.engine.action.Action;
import com.stellarcompact.engine.action.EspionageOperation;
import com.stellarcompact.engine.config.BalanceProfile;
import com.stellarcompact.engine.config.BalanceProfileLoader;
import com.stellarcompact.engine.hash.GoldenStateHash;
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
import com.stellarcompact.engine.state.TechId;
import com.stellarcompact.engine.state.TechProgress;
import com.stellarcompact.engine.state.TechStatus;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Golden, hand-authored tests for the E1-13 espionage resolution: seeded
 * success/detection rolls, the four operation effects (reveal intel / steal tech or
 * resources / disable a building / incite unrest), counter-intel odds shifting,
 * detection reputation cost, config-driven costs escrowed through the ledger, and
 * per-(seed,tick,opId) determinism.
 *
 * <p>The seeded rolls make a "force success" / "force failure" profile the cleanest
 * way to assert effects deterministically: setting an op's {@code successBase} to 1.0
 * makes its success roll always pass and 0.0 makes it always fail, independently of
 * the seed; likewise {@code detectionBase}. The shipped {@code small-default} profile
 * is exercised separately to guard that the step reads config (rule 6), not constants.
 */
class EspionageResolutionTest {

    private static final FactionId SPY = new FactionId("spy");
    private static final FactionId MARK = new FactionId("mark"); // the target
    private static final SystemId SYS = new SystemId("sys-mark");
    private static final PlanetId P1 = new PlanetId("p-mark-1");

    private static final long SEED = 1234L;
    private static final long TICK = 7L;

    private static final BalanceProfile SMALL = loadSmallDefault();

    private static BalanceProfile loadSmallDefault() {
        try (InputStream in = EspionageResolutionTest.class
                .getResourceAsStream("/balance/small-default.json")) {
            if (in == null) {
                throw new IllegalStateException("missing /balance/small-default.json");
            }
            return BalanceProfileLoader.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // ===== effect: SCOUT reveals intel ========================================

    @Test
    void scoutSuccessRevealsTargetIntelToActor() {
        BalanceProfile profile = espionageProfile(force(1.0), force(0.0));
        GameState state = baseState(richMark());
        GameState next = runOne(state, profile, EspionageOperation.SCOUT);

        Faction mark = next.factions().get(MARK);
        assertTrue(mark.revealedIntel().contains(SPY),
                "a successful SCOUT records the spy in the target's revealedIntel set");
        // No detection => spy reputation unchanged.
        assertEquals(0.0, next.factions().get(SPY).reputation(), 1e-9);
    }

    @Test
    void scoutFailureRevealsNothing() {
        BalanceProfile profile = espionageProfile(force(0.0), force(0.0));
        GameState next = runOne(baseState(richMark()), profile, EspionageOperation.SCOUT);
        assertFalse(next.factions().get(MARK).revealedIntel().contains(SPY),
                "a failed SCOUT reveals nothing");
    }

    // ===== effect: STEAL_INTEL ================================================

    @Test
    void stealIntelSuccessTransfersATech() {
        BalanceProfile profile = espionageProfile(force(1.0), force(0.0));
        Faction mark = new Faction(MARK, "Mark", 0.0, rich(),
                Map.of(new TechId("hydroponics"), unlocked("hydroponics")));
        GameState next = runOne(baseState(mark), profile, EspionageOperation.STEAL_INTEL);

        TechProgress got = next.factions().get(SPY).techProgress().get(new TechId("hydroponics"));
        assertTrue(got != null && got.status() == TechStatus.UNLOCKED,
                "a successful STEAL_INTEL grants the spy the stolen tech UNLOCKED");
        // The target keeps the tech (theft copies knowledge; it is not erased).
        assertEquals(TechStatus.UNLOCKED,
                next.factions().get(MARK).techProgress().get(new TechId("hydroponics")).status());
    }

    @Test
    void stealIntelFallsBackToResourcesWhenNoTechToSteal() {
        BalanceProfile profile = espionageProfile(force(1.0), force(0.0));
        // Mark has no UNLOCKED tech -> resource fallback (small-default stealResourceFraction=0.2).
        Faction mark = new Faction(MARK, "Mark", 0.0,
                new ResourceBundle(0, 0, 0, 0, 100), Map.of());
        Faction spy = new Faction(SPY, "Spy", 0.0, new ResourceBundle(0, 0, 0, 0, 0), Map.of());
        GameState state = stateWith(spy, mark);
        GameState next = runOne(state, profile, EspionageOperation.STEAL_INTEL);

        double fraction = profile.espionage().stealResourceFraction(); // 0.2
        assertEquals(100 * (1 - fraction), next.factions().get(MARK).stockpiles().influence(), 1e-9);
        assertEquals(100 * fraction, next.factions().get(SPY).stockpiles().influence(), 1e-9);
    }

    // ===== effect: SABOTAGE ===================================================

    @Test
    void sabotageSuccessIdlesAnActiveBuilding() {
        BalanceProfile profile = espionageProfile(force(1.0), force(0.0));
        GameState next = runOne(baseState(markOwningSystemWithActiveMine()), profile,
                EspionageOperation.SABOTAGE);

        Building b = next.systems().get(SYS).planets().get(0).buildings().get(0);
        assertEquals(BuildingStatus.IDLE, b.status(),
                "a successful SABOTAGE disables the first active building");
        assertEquals(BuildingType.MINE, b.type());
    }

    @Test
    void sabotageFailureLeavesBuildingsActive() {
        BalanceProfile profile = espionageProfile(force(0.0), force(0.0));
        GameState next = runOne(baseState(markOwningSystemWithActiveMine()), profile,
                EspionageOperation.SABOTAGE);
        assertEquals(BuildingStatus.ACTIVE,
                next.systems().get(SYS).planets().get(0).buildings().get(0).status());
    }

    // ===== effect: INCITE_UNREST ==============================================

    @Test
    void inciteUnrestSuccessReducesPopulationAndLoyalty() {
        BalanceProfile profile = espionageProfile(force(1.0), force(0.0));
        GameState next = runOne(baseState(markOwningPopulousSystem()), profile,
                EspionageOperation.INCITE_UNREST);

        ActiveSystem struck = next.systems().get(SYS);
        long popLoss = profile.espionage().unrestPopulationLoss();   // 2
        double loyaltyLoss = profile.espionage().unrestLoyaltyLoss(); // 0.2
        assertEquals(10 - popLoss, struck.population());
        assertEquals(1.0 - loyaltyLoss, struck.loyalty(), 1e-9);
    }

    // ===== detection => reputation penalty ====================================

    @Test
    void detectedOperationDocksReputationByConfiguredPenalty() {
        // Force failure but force detection: reputation must still be paid.
        BalanceProfile profile = espionageProfile(force(0.0), force(1.0));
        GameState next = runOne(baseState(richMark()), profile, EspionageOperation.SABOTAGE);

        double penalty = profile.diplomacy().reputation().espionageDetectedPenalty();
        assertEquals(-penalty, next.factions().get(SPY).reputation(), 1e-9,
                "a detected op docks the actor reputation by the configured penalty");
    }

    @Test
    void undetectedOperationLeavesReputationUntouched() {
        BalanceProfile profile = espionageProfile(force(1.0), force(0.0));
        GameState next = runOne(baseState(richMark()), profile, EspionageOperation.SCOUT);
        assertEquals(0.0, next.factions().get(SPY).reputation(), 1e-9);
    }

    // ===== counter-intel tech shifts the odds =================================

    @Test
    void counterIntelTechSuppressesSuccessThatWouldOtherwiseLand() {
        // Base success 0.5, counter-intel penalty 0.5 -> effective 0.0 when the target
        // holds the counter-intel tech. The guarded mark must NEVER be revealed (any
        // seed); the unguarded mark, at 0.5 odds, IS revealed for at least one seed -
        // together that proves counter-intel changed the outcome, without hardcoding a
        // single seed's roll.
        BalanceProfile profile = counterIntelProfile(0.5, 0.5, 0.0, 0.0);
        Faction plainMark = new Faction(MARK, "Mark", 0.0, rich(), Map.of());
        Faction guardedMark = new Faction(MARK, "Mark", 0.0, rich(),
                Map.of(new TechId("intelligenceAgency"), unlocked("intelligenceAgency")));

        boolean anyPlainSucceeded = false;
        for (long seed = 0; seed < 50; seed++) {
            GameState guarded = scoutWithSeed(baseState(guardedMark), profile, seed);
            assertFalse(guarded.factions().get(MARK).revealedIntel().contains(SPY),
                    "counter-intel drives effective success to 0 -> the scout can never succeed (seed " + seed + ")");
            GameState plain = scoutWithSeed(baseState(plainMark), profile, seed);
            if (plain.factions().get(MARK).revealedIntel().contains(SPY)) {
                anyPlainSucceeded = true;
            }
        }
        assertTrue(anyPlainSucceeded,
                "at 0.5 odds the unprotected scout lands for at least one seed, so counter-intel mattered");
    }

    @Test
    void counterIntelTechRaisesDetectionOdds() {
        // Force success regardless; detection base 0.0 but +1.0 for counter-intel -> always detected.
        BalanceProfile profile = counterIntelProfile(1.0, 0.0, 0.0, 1.0);
        Faction guardedMark = new Faction(MARK, "Mark", 0.0, rich(),
                Map.of(new TechId("intelligenceAgency"), unlocked("intelligenceAgency")));

        GameState next = runOne(baseState(guardedMark), profile, EspionageOperation.SCOUT);
        double penalty = profile.diplomacy().reputation().espionageDetectedPenalty();
        assertEquals(-penalty, next.factions().get(SPY).reputation(), 1e-9,
                "counter-intel raises detection to certainty -> the spy is always caught");
    }

    // ===== cost escrowed through the ledger ===================================

    @Test
    void costIsEscrowedWinOrLose() {
        BalanceProfile profile = SMALL; // shipped costs (rule 6 guard)
        SpendLedger ledger = new SpendLedger();
        GameState state = baseState(richMark());
        EspionageResolution.resolve(state, List.of(submit(EspionageOperation.STEAL_INTEL)),
                SEED, TICK, profile, ledger);

        ResourceBundle accrued = ledger.accrued(SPY);
        BalanceProfile.ResourceBundle cost = profile.espionage().cost().get("stealIntel");
        assertEquals(cost.influence(), accrued.influence(), 1e-9);
        assertEquals(cost.tech(), accrued.tech(), 1e-9);
    }

    @Test
    void endToEndThroughResolverSettlesTheCost() {
        BalanceProfile profile = SMALL;
        GameState state = baseState(richMark());
        GameState next = Resolver.resolve(state,
                List.of(submit(EspionageOperation.SCOUT)), profile, SEED);

        BalanceProfile.ResourceBundle cost = profile.espionage().cost().get("scout");
        // SPY had rich() = 1000 influence; the scout cost is debited at settlement.
        double expectedInfluence = 1000 - cost.influence();
        assertEquals(expectedInfluence, next.factions().get(SPY).stockpiles().influence(), 1e-6);
    }

    // ===== determinism (the replay contract) ==================================

    @Test
    void sameSeedTickOpProducesIdenticalState() {
        BalanceProfile profile = espionageProfile(force(0.5), force(0.5));
        GameState state = baseState(richMark());
        GameState a = runOne(state, profile, EspionageOperation.SABOTAGE);
        GameState b = runOne(state, profile, EspionageOperation.SABOTAGE);
        assertEquals(GoldenStateHash.sha256Hex(a), GoldenStateHash.sha256Hex(b),
                "same (seed, tick, op) must reproduce the same outcome byte-for-byte");
    }

    @Test
    void tickParticipatesInTheSeededStream() {
        // 0.5/0.5 odds: the success/detection outcome must vary across ticks (not all
        // ticks can yield the identical result), proving the tick feeds the stream.
        // Compared by reputation parity (detected or not), which a 0.5 detection roll
        // toggles - robust without hardcoding any single roll.
        BalanceProfile profile = espionageProfile(force(0.5), force(0.5));
        Faction mark = richMark();
        boolean sawDetected = false;
        boolean sawUndetected = false;
        for (long tick = 0; tick < 30; tick++) {
            GameState state = stateWith(new Faction(SPY, "Spy", 0.0, rich(), Map.of()), mark)
                    .withTick(tick);
            GameState next = EspionageResolution.resolve(state,
                    List.of(submit(EspionageOperation.SCOUT)), SEED, tick, profile, new SpendLedger());
            if (next.factions().get(SPY).reputation() < 0.0) {
                sawDetected = true;
            } else {
                sawUndetected = true;
            }
        }
        assertTrue(sawDetected && sawUndetected,
                "across ticks the seeded detection roll toggles, so the tick truly feeds the stream");
    }

    @Test
    void selfTargetIsANoOp() {
        BalanceProfile profile = espionageProfile(force(1.0), force(1.0));
        GameState state = baseState(richMark());
        SubmittedAction selfOp = new SubmittedAction(SPY,
                new Action.Espionage(SPY, EspionageOperation.SCOUT), 0);
        GameState next = EspionageResolution.resolve(state, List.of(selfOp),
                SEED, TICK, profile, new SpendLedger());
        assertSame(state, next, "a self-targeted op (defensive guard) changes nothing");
    }

    // ===== fixtures (hand-authored, pure) =====================================

    private static GameState runOne(GameState state, BalanceProfile profile, EspionageOperation op) {
        return EspionageResolution.resolve(state, List.of(submit(op)),
                SEED, TICK, profile, new SpendLedger());
    }

    private static GameState scoutWithSeed(GameState state, BalanceProfile profile, long seed) {
        return EspionageResolution.resolve(state, List.of(submit(EspionageOperation.SCOUT)),
                seed, TICK, profile, new SpendLedger());
    }

    private static SubmittedAction submit(EspionageOperation op) {
        return new SubmittedAction(SPY, new Action.Espionage(MARK, op), 0);
    }

    private static ResourceBundle rich() {
        return new ResourceBundle(1000, 1000, 1000, 1000, 1000);
    }

    private static TechProgress unlocked(String id) {
        return new TechProgress(new TechId(id), TechStatus.UNLOCKED, 0);
    }

    private static Faction richMark() {
        return new Faction(MARK, "Mark", 0.0, rich(), Map.of());
    }

    private static Faction markOwningSystemWithActiveMine() {
        return richMark();
    }

    private static Faction markOwningPopulousSystem() {
        return richMark();
    }

    /** Spy + a target faction, no systems. */
    private static GameState stateWith(Faction spy, Faction mark) {
        return new GameState(SEED, TICK, GameStatus.RUNNING, "small-default", 1,
                Map.of(SPY, spy, MARK, mark),
                Map.of(), Map.of(), Map.of(), Map.of(), Map.of());
    }

    /**
     * Spy (rich) + the given target. When the target is one of the system-owning
     * fixtures the matching system is attached; otherwise no systems exist.
     */
    private static GameState baseState(Faction mark) {
        Faction spy = new Faction(SPY, "Spy", 0.0, rich(), Map.of());
        ActiveSystem sys = systemFor(mark.id());
        Map<SystemId, ActiveSystem> systems = sys == null ? Map.of() : Map.of(SYS, sys);
        return new GameState(SEED, TICK, GameStatus.RUNNING, "small-default", 1,
                Map.of(SPY, spy, MARK, mark),
                systems, Map.of(), Map.of(), Map.of(), Map.of());
    }

    /** The system fixture: an active MINE for sabotage and population 10/loyalty 1.0 for unrest. */
    private static ActiveSystem systemFor(FactionId owner) {
        Building mine = new Building(0, BuildingType.MINE, BuildingStatus.ACTIVE, 3);
        Planet planet = new Planet(P1, Biome.ARID, 3, 10, List.of(mine));
        return new ActiveSystem(SYS, "Mark Prime", new Coords(1, 1),
                Optional.of(owner), List.of(planet), 10, 1.0);
    }

    // ===== espionage-tuned profiles ===========================================

    private static Map<String, Double> force(double v) {
        return Map.of("scout", v, "stealIntel", v, "sabotage", v, "inciteUnrest", v);
    }

    /** A profile whose espionage block forces the given success/detection odds for every op. */
    private static BalanceProfile espionageProfile(Map<String, Double> success,
                                                   Map<String, Double> detection) {
        BalanceProfile.Espionage esp = new BalanceProfile.Espionage(
                success, detection,
                Map.of("scout", new BalanceProfile.ResourceBundle(0, 0, 0, 5, 10),
                        "stealIntel", new BalanceProfile.ResourceBundle(0, 0, 0, 15, 25),
                        "sabotage", new BalanceProfile.ResourceBundle(0, 0, 0, 10, 20),
                        "inciteUnrest", new BalanceProfile.ResourceBundle(0, 0, 0, 5, 30)),
                "intelligenceAgency", 0.5, 0.2, 0.2, 2, 0.2);
        return withEspionage(esp);
    }

    /** A profile with single-op SCOUT odds and explicit counter-intel shift magnitudes. */
    private static BalanceProfile counterIntelProfile(double success, double successPenalty,
                                                      double detection, double detectionBonus) {
        BalanceProfile.Espionage esp = new BalanceProfile.Espionage(
                Map.of("scout", success), Map.of("scout", detection),
                Map.of(), "intelligenceAgency", successPenalty, detectionBonus, 0.2, 2, 0.2);
        return withEspionage(esp);
    }

    /** Clone the shipped small-default profile but swap in the given espionage block. */
    private static BalanceProfile withEspionage(BalanceProfile.Espionage esp) {
        return new BalanceProfile(
                SMALL.name(), SMALL.version(), SMALL.resources(), SMALL.population(), SMALL.market(),
                SMALL.construction(), SMALL.combat(), SMALL.tech(), SMALL.diplomacy(), SMALL.victory(),
                SMALL.tick(), esp);
    }
}
