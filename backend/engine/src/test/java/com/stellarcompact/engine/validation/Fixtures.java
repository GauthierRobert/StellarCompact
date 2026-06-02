package com.stellarcompact.engine.validation;

import com.stellarcompact.engine.config.BalanceProfile;
import com.stellarcompact.engine.state.ActiveSystem;
import com.stellarcompact.engine.state.Biome;
import com.stellarcompact.engine.state.Building;
import com.stellarcompact.engine.state.BuildingStatus;
import com.stellarcompact.engine.state.BuildingType;
import com.stellarcompact.engine.state.Coords;
import com.stellarcompact.engine.state.Faction;
import com.stellarcompact.engine.state.FactionId;
import com.stellarcompact.engine.state.Fleet;
import com.stellarcompact.engine.state.FleetId;
import com.stellarcompact.engine.state.FleetStance;
import com.stellarcompact.engine.state.GameState;
import com.stellarcompact.engine.state.GameStatus;
import com.stellarcompact.engine.state.MarketOrder;
import com.stellarcompact.engine.state.MarketOrderId;
import com.stellarcompact.engine.state.MarketSide;
import com.stellarcompact.engine.state.OrderStatus;
import com.stellarcompact.engine.state.PhysicalResource;
import com.stellarcompact.engine.state.Planet;
import com.stellarcompact.engine.state.PlanetId;
import com.stellarcompact.engine.state.ResourceBundle;
import com.stellarcompact.engine.state.Route;
import com.stellarcompact.engine.state.RouteId;
import com.stellarcompact.engine.state.RouteKind;
import com.stellarcompact.engine.state.RouteStatus;
import com.stellarcompact.engine.state.Ship;
import com.stellarcompact.engine.state.SystemId;
import com.stellarcompact.engine.state.TechId;
import com.stellarcompact.engine.state.TechProgress;
import com.stellarcompact.engine.state.Treaty;
import com.stellarcompact.engine.state.TreatyId;
import com.stellarcompact.engine.state.TreatyStatus;
import com.stellarcompact.engine.state.TreatyType;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Hand-authored, deterministic test fixtures for {@link ActionValidator} tests -
 * pure builders, no randomness, no I/O.
 */
final class Fixtures {

    static final FactionId ALPHA = new FactionId("alpha");
    static final FactionId BETA = new FactionId("beta");
    static final SystemId SYS_A = new SystemId("sysA");
    static final SystemId SYS_B = new SystemId("sysB");
    static final SystemId SYS_NEUTRAL = new SystemId("sysN");
    static final PlanetId PLANET_A = new PlanetId("planetA");
    static final PlanetId PLANET_B = new PlanetId("planetB");
    static final FleetId FLEET_A = new FleetId("fleetA");
    static final FleetId FLEET_B = new FleetId("fleetB");
    static final RouteId ROUTE_B = new RouteId("routeB");
    static final TreatyId TREATY_1 = new TreatyId("treaty1");
    static final MarketOrderId OFFER_1 = new MarketOrderId("offer1");
    static final TechId TECH_DRIVE = new TechId("warpDrive");

    private Fixtures() {
    }

    static ResourceBundle rich() {
        return new ResourceBundle(1000, 1000, 1000, 1000, 1000);
    }

    static Planet planetWithSlots(PlanetId id, int slots, List<Building> buildings) {
        return new Planet(id, Biome.TERRAN, slots, 100, buildings);
    }

    static Building activeBuilding(int slot, BuildingType type) {
        return new Building(slot, type, BuildingStatus.ACTIVE, 0);
    }

    static ActiveSystem ownedSystem(SystemId id, FactionId owner, List<Planet> planets) {
        return new ActiveSystem(id, "name-" + id.value(), new Coords(0, 0),
                Optional.of(owner), planets, 100, 1.0);
    }

    static ActiveSystem neutralSystem(SystemId id, List<Planet> planets) {
        return new ActiveSystem(id, "name-" + id.value(), new Coords(1, 1),
                Optional.empty(), planets, 0, 1.0);
    }

    static Fleet fleetAt(FleetId id, FactionId owner, SystemId at) {
        return new Fleet(id, owner, Optional.of(at), Optional.empty(),
                FleetStance.DEFENSIVE, List.of(new Ship("scout", 1)));
    }

    static Faction faction(FactionId id, ResourceBundle stockpiles, Map<TechId, TechProgress> tech) {
        return new Faction(id, "F-" + id.value(), 0.0, stockpiles, tech);
    }

    static Treaty activeTreaty(TreatyType type, FactionId p1, FactionId p2) {
        return new Treaty(TREATY_1, type, List.of(p1, p2), Map.of(), 0L, 100L, TreatyStatus.ACTIVE);
    }

    static Treaty proposedTreaty(TreatyType type, FactionId p1, FactionId p2) {
        return new Treaty(TREATY_1, type, List.of(p1, p2), Map.of(), 0L, 100L, TreatyStatus.PROPOSED);
    }

    static Route routeOwnedBy(RouteId id, FactionId owner, SystemId a, SystemId b) {
        return new Route(id, owner, a, b, RouteKind.COMMERCIAL,
                List.of(PhysicalResource.MINERALS), 10.0, RouteStatus.ACTIVE);
    }

    /**
     * A directed peer offer: proposed by {@code owner}, addressed to {@code ALPHA}
     * (the validator's acting faction in these tests), expiring far in the future
     * (tick 1000 vs the base-state tick 5) so it is live unless a test overrides it.
     */
    static MarketOrder offer(MarketOrderId id, FactionId owner) {
        return new MarketOrder(id, SYS_A, owner, MarketSide.SELL,
                PhysicalResource.MINERALS, 10.0, 1.0, 0L, 1000L, Optional.of(ALPHA),
                OrderStatus.OPEN);
    }

    /** A directed offer addressed to {@code addressee}, expiring at {@code expiresTick}. */
    static MarketOrder offer(MarketOrderId id, FactionId owner, FactionId addressee,
                             long expiresTick) {
        return new MarketOrder(id, SYS_A, owner, MarketSide.SELL,
                PhysicalResource.MINERALS, 10.0, 1.0, 0L, expiresTick,
                Optional.of(addressee), OrderStatus.OPEN);
    }

    /** ALPHA owns SYS_A (PLANET_A) and FLEET_A; BETA owns SYS_B / FLEET_B / ROUTE_B; SYS_NEUTRAL is unowned. */
    static GameState baseState() {
        return baseState(Map.of(), rich(), Map.of(), List.of());
    }

    /** The base state but with an active war between {@code x} and {@code y} (E1-12 war gate). */
    static GameState baseStateAtWar(FactionId x, FactionId y) {
        return baseState().withWar(x, y, 1L);
    }

    static GameState baseState(Map<TreatyId, Treaty> treaties,
                               ResourceBundle alphaStockpiles,
                               Map<TechId, TechProgress> alphaTech,
                               List<Building> planetABuildings) {
        Faction alpha = faction(ALPHA, alphaStockpiles, alphaTech);
        Faction beta = faction(BETA, rich(), Map.of());

        ActiveSystem sysA = ownedSystem(SYS_A, ALPHA,
                List.of(planetWithSlots(PLANET_A, 3, planetABuildings)));
        ActiveSystem sysB = ownedSystem(SYS_B, BETA,
                List.of(planetWithSlots(PLANET_B, 3, List.of())));
        ActiveSystem sysN = neutralSystem(SYS_NEUTRAL, List.of());

        Fleet fleetA = fleetAt(FLEET_A, ALPHA, SYS_A);
        Fleet fleetB = fleetAt(FLEET_B, BETA, SYS_B);
        Route routeB = routeOwnedBy(ROUTE_B, BETA, SYS_B, SYS_NEUTRAL);

        return new GameState(
                42L, 5L, GameStatus.RUNNING, "small-default", 1,
                Map.of(ALPHA, alpha, BETA, beta),
                Map.of(SYS_A, sysA, SYS_B, sysB, SYS_NEUTRAL, sysN),
                Map.of(FLEET_A, fleetA, FLEET_B, fleetB),
                treaties,
                Map.of(ROUTE_B, routeB),
                Map.of(OFFER_1, offer(OFFER_1, BETA)));
    }

    /**
     * The base state but with {@code OFFER_1} replaced by {@code customOffer}, so
     * the directed-offer validator cases (wrong addressee, expired) can inject the
     * exact offer they need without rebuilding the whole snapshot.
     */
    static GameState baseStateWithOffer(MarketOrder customOffer) {
        GameState base = baseState();
        return base.withMarketOrders(Map.of(customOffer.id(), customOffer));
    }

    static BalanceProfile profile() {
        return new BalanceProfile(
                "small-default", 1,
                new BalanceProfile.Resources(Map.of(), Map.of(), 0.1),
                new BalanceProfile.Population(1.0, 0.5, 0.1, 5, Map.of()),
                new BalanceProfile.Market("priceTimePriority", 0.5, "ENERGY"),
                new BalanceProfile.Construction(
                        Map.of("mine", 3),
                        Map.of("mine", new BalanceProfile.ResourceBundle(0, 50, 0, 0, 0)),
                        Map.of("toxic", "arid")),
                new BalanceProfile.Combat(Map.of("scout", 1.0), List.of(0.8, 1.2), 0.2, 0.3, 0.1),
                new BalanceProfile.Tech(Map.of("warpDrive", 100.0), Map.of("warpDrive", 5),
                        Map.of("warpDrive", 1.5), Map.of(), Map.of()),
                new BalanceProfile.Diplomacy(
                        new BalanceProfile.Reputation(1.0, 1.0, 1.0, 1.0), Map.of()),
                new BalanceProfile.Victory(
                        new BalanceProfile.Domination(0.6),
                        new BalanceProfile.Economic(1000, 50),
                        new BalanceProfile.Diplomatic(0.6),
                        new BalanceProfile.Survival(1000),
                        new BalanceProfile.Wonder(3, 50),
                        new BalanceProfile.ScoreWeights(1, 1, 1, 1, 1, 1, 1)),
                new BalanceProfile.Tick(1000, 2, 5000));
    }
}
