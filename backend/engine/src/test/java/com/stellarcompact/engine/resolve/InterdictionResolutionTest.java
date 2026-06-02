package com.stellarcompact.engine.resolve;

import com.stellarcompact.engine.action.Action;
import com.stellarcompact.engine.action.BlockadeTarget;
import com.stellarcompact.engine.config.BalanceProfile;
import com.stellarcompact.engine.state.ActiveSystem;
import com.stellarcompact.engine.state.Coords;
import com.stellarcompact.engine.state.Faction;
import com.stellarcompact.engine.state.FactionId;
import com.stellarcompact.engine.state.FleetId;
import com.stellarcompact.engine.state.GameState;
import com.stellarcompact.engine.state.GameStatus;
import com.stellarcompact.engine.state.PhysicalResource;
import com.stellarcompact.engine.state.ResourceBundle;
import com.stellarcompact.engine.state.Route;
import com.stellarcompact.engine.state.RouteId;
import com.stellarcompact.engine.state.RouteKind;
import com.stellarcompact.engine.state.RouteStatus;
import com.stellarcompact.engine.state.SystemId;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * E1-11 INTERDICTION step tests: blockade chokes route/system throughput deterministically
 * (a route flips to BLOCKADED); raid steals a seeded cargo fraction debited from the owner
 * and credited to the raider through the ledger; the raid haul is reproducible for a fixed
 * (seed, tick, routeId). Hand-authored, deterministic, no LLM, no unseeded RNG.
 */
class InterdictionResolutionTest {

    private static final FactionId RAIDER = new FactionId("raider");
    private static final FactionId OWNER = new FactionId("owner");
    private static final SystemId HUB = new SystemId("hub");
    private static final SystemId FAR = new SystemId("far");
    private static final SystemId OTHER = new SystemId("other");
    private static final FleetId STRIKE = new FleetId("strike");
    private static final RouteId R1 = new RouteId("r1");
    private static final RouteId R2 = new RouteId("r2");

    private static final long SEED = 42L;
    private static final long TICK = 7L;

    private static Faction faction(FactionId id, ResourceBundle stock) {
        return new Faction(id, "F-" + id.value(), 0.0, stock, Map.of());
    }

    private static ActiveSystem system(SystemId id) {
        return new ActiveSystem(id, "name-" + id.value(), new Coords(0, 0),
                Optional.empty(), List.of(), 0, 1.0);
    }

    private static Route route(RouteId id, SystemId a, SystemId b, double volume,
                               List<PhysicalResource> cargo) {
        return new Route(id, OWNER, a, b, RouteKind.COMMERCIAL, cargo, volume, RouteStatus.ACTIVE);
    }

    private static GameState state(Map<RouteId, Route> routes, ResourceBundle ownerStock,
                                   ResourceBundle raiderStock) {
        return new GameState(SEED, TICK, GameStatus.RUNNING, "small-default", 1,
                Map.of(OWNER, faction(OWNER, ownerStock), RAIDER, faction(RAIDER, raiderStock)),
                Map.of(HUB, system(HUB), FAR, system(FAR), OTHER, system(OTHER)),
                Map.of(), Map.of(), routes, Map.of(), Set.of());
    }

    /** Profile with concrete interdiction knobs: 75% choke, up to 50% raid steal. */
    private static BalanceProfile profile() {
        BalanceProfile base = ResolveFixtures.profile();
        BalanceProfile.Market market = new BalanceProfile.Market(
                "priceTimePriority", 0.5, "ENERGY", 0.75, 0.5);
        return new BalanceProfile(
                base.name(), base.version(), base.resources(), base.population(), market,
                base.construction(), base.combat(), base.movement(), base.tech(),
                base.diplomacy(), base.victory(), base.tick(), base.homePlacement());
    }

    private static SubmittedAction submitted(FactionId actor, Action action) {
        return new SubmittedAction(actor, action, 0);
    }

    // ===== Blockade ===========================================================

    @Test
    void blockadeRouteFlipsItToBlockaded() {
        Route r = route(R1, HUB, FAR, 100, List.of(PhysicalResource.MINERALS));
        GameState s = state(Map.of(R1, r), ResourceBundle.ZERO, ResourceBundle.ZERO);
        Action.Blockade blockade = new Action.Blockade(STRIKE, new BlockadeTarget.OnRoute(R1));

        GameState next = InterdictionResolution.resolve(s, List.of(submitted(RAIDER, blockade)),
                SEED, TICK, profile(), new SpendLedger());

        assertEquals(RouteStatus.BLOCKADED, next.routes().get(R1).status());
    }

    @Test
    void blockadeSystemChokesEveryRouteTouchingThatSystem() {
        Route touching = route(R1, HUB, FAR, 100, List.of(PhysicalResource.MINERALS));
        Route elsewhere = route(R2, FAR, OTHER, 100, List.of(PhysicalResource.FOOD));
        GameState s = state(Map.of(R1, touching, R2, elsewhere),
                ResourceBundle.ZERO, ResourceBundle.ZERO);
        Action.Blockade blockade = new Action.Blockade(STRIKE, new BlockadeTarget.OnSystem(HUB));

        GameState next = InterdictionResolution.resolve(s, List.of(submitted(RAIDER, blockade)),
                SEED, TICK, profile(), new SpendLedger());

        assertEquals(RouteStatus.BLOCKADED, next.routes().get(R1).status(),
                "route with an endpoint at HUB is choked");
        assertEquals(RouteStatus.ACTIVE, next.routes().get(R2).status(),
                "route not touching HUB is untouched");
    }

    @Test
    void blockadeOfStaleRouteIsSafeNoOp() {
        GameState s = state(Map.of(), ResourceBundle.ZERO, ResourceBundle.ZERO);
        Action.Blockade blockade = new Action.Blockade(STRIKE, new BlockadeTarget.OnRoute(R1));

        GameState next = InterdictionResolution.resolve(s, List.of(submitted(RAIDER, blockade)),
                SEED, TICK, profile(), new SpendLedger());

        assertEquals(s, next);
    }

    // ===== Raid ===============================================================

    @Test
    void raidStealsASeededCargoFractionFromOwnerToRaider() {
        Route r = route(R1, HUB, FAR, 100, List.of(PhysicalResource.MINERALS));
        GameState s = state(Map.of(R1, r),
                new ResourceBundle(0, 1000, 0, 0, 0), ResourceBundle.ZERO);
        Action.Raid raid = new Action.Raid(STRIKE, R1);
        SpendLedger ledger = new SpendLedger();

        InterdictionResolution.resolve(s, List.of(submitted(RAIDER, raid)),
                SEED, TICK, profile(), ledger);

        // The same draw the resolver makes: volume(100) x maxFraction(0.5) x roll.
        double roll = com.stellarcompact.engine.rng.SeedDerivation
                .rng(SEED, TICK, com.stellarcompact.engine.rng.SaltDomain.RAID,
                        com.stellarcompact.engine.rng.SaltDomain.hashString(R1.value()))
                .nextDouble();
        double expected = 100 * 0.5 * roll;
        assertTrue(expected > 0.0, "fixture roll should be positive");

        assertEquals(expected, ledger.accrued(OWNER).minerals(), 1e-9,
                "owner is debited the stolen minerals");
        assertEquals(expected, ledger.creditedTo(RAIDER).minerals(), 1e-9,
                "raider is credited the stolen minerals");
    }

    @Test
    void raidSplitsHaulEvenlyAcrossHauledResources() {
        Route r = route(R1, HUB, FAR, 100,
                List.of(PhysicalResource.MINERALS, PhysicalResource.ENERGY));
        GameState s = state(Map.of(R1, r), ResourceBundle.ZERO, ResourceBundle.ZERO);
        SpendLedger ledger = new SpendLedger();

        InterdictionResolution.resolve(s, List.of(submitted(RAIDER, new Action.Raid(STRIKE, R1))),
                SEED, TICK, profile(), ledger);

        ResourceBundle owed = ledger.accrued(OWNER);
        assertEquals(owed.minerals(), owed.energy(), 1e-9,
                "two-resource cargo is split evenly");
        assertTrue(owed.minerals() > 0.0);
    }

    @Test
    void raidIsDeterministicForSameSeedTickRoute() {
        Route r = route(R1, HUB, FAR, 100, List.of(PhysicalResource.MINERALS));
        GameState s = state(Map.of(R1, r), ResourceBundle.ZERO, ResourceBundle.ZERO);

        SpendLedger a = new SpendLedger();
        SpendLedger b = new SpendLedger();
        InterdictionResolution.resolve(s, List.of(submitted(RAIDER, new Action.Raid(STRIKE, R1))),
                SEED, TICK, profile(), a);
        InterdictionResolution.resolve(s, List.of(submitted(RAIDER, new Action.Raid(STRIKE, R1))),
                SEED, TICK, profile(), b);

        assertEquals(a.accrued(OWNER).minerals(), b.accrued(OWNER).minerals(), 0.0,
                "same (seed, tick, routeId) reproduces the identical haul");
    }

    @Test
    void raidHaulVariesWithTick() {
        Route r = route(R1, HUB, FAR, 100, List.of(PhysicalResource.MINERALS));
        GameState s1 = state(Map.of(R1, r), ResourceBundle.ZERO, ResourceBundle.ZERO);

        SpendLedger l1 = new SpendLedger();
        SpendLedger l2 = new SpendLedger();
        InterdictionResolution.resolve(s1, List.of(submitted(RAIDER, new Action.Raid(STRIKE, R1))),
                SEED, 7L, profile(), l1);
        InterdictionResolution.resolve(s1, List.of(submitted(RAIDER, new Action.Raid(STRIKE, R1))),
                SEED, 8L, profile(), l2);

        assertNotEquals(l1.accrued(OWNER).minerals(), l2.accrued(OWNER).minerals(),
                "the seeded roll moves with the tick");
    }

    @Test
    void raidWithZeroStealFractionTakesNothing() {
        Route r = route(R1, HUB, FAR, 100, List.of(PhysicalResource.MINERALS));
        GameState s = state(Map.of(R1, r), ResourceBundle.ZERO, ResourceBundle.ZERO);
        BalanceProfile base = ResolveFixtures.profile(); // Market default raidStealFraction = 0.0
        SpendLedger ledger = new SpendLedger();

        InterdictionResolution.resolve(s, List.of(submitted(RAIDER, new Action.Raid(STRIKE, R1))),
                SEED, TICK, base, ledger);

        assertTrue(ledger.isEmpty(), "no steal -> nothing escrowed");
    }
}
