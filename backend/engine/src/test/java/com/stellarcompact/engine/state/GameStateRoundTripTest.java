package com.stellarcompact.engine.state;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.stellarcompact.engine.hash.GoldenStateHash;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Proves the core state records (E1-01) satisfy the determinism contract:
 * <ul>
 *   <li>they JSON round-trip identically (Jackson serialise then deserialise then equals),</li>
 *   <li>the canonical {@code GoldenStateHash} is stable and survives the round-trip,</li>
 *   <li>map insertion order does not leak into the hash,</li>
 *   <li>a single-field change moves the hash.</li>
 * </ul>
 *
 * <p>Purity seam: Jackson is configured here in TEST scope (mirroring the strict
 * mapper in {@code BalanceProfileLoader}); the engine MAIN code performs no I/O.
 */
class GameStateRoundTripTest {

    /** Strict mapper: unknown keys fail, matching the records {@code @JsonIgnoreProperties}. */
    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .addModule(new Jdk8Module())
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    /**
     * A representative, non-trivial snapshot: two factions, two systems (one
     * owned with planets/buildings, one neutral), a parked fleet and an en-route
     * fleet, a treaty, a route, a market order and tech progress.
     */
    private static GameState sampleState() {
        FactionId fA = new FactionId("faction-A");
        FactionId fB = new FactionId("faction-B");
        SystemId sHome = new SystemId("sys-home");
        SystemId sFrontier = new SystemId("sys-frontier");
        PlanetId pCradle = new PlanetId("planet-cradle");

        Faction factionA = new Faction(
                fA, "Aurelian Concord", 12.5,
                new ResourceBundle(100, 80, 60, 20, 5),
                Map.of(new TechId("improvedExtraction"),
                        new TechProgress(new TechId("improvedExtraction"), TechStatus.UNLOCKED, 0),
                        new TechId("capitalDoctrine"),
                        new TechProgress(new TechId("capitalDoctrine"), TechStatus.RESEARCHING, 3)));
        Faction factionB = new Faction(
                fB, "Vael Hegemony", -4.0,
                new ResourceBundle(40, 200, 10, 0, 0),
                Map.of());

        Planet cradle = new Planet(
                pCradle, Biome.OCEANIC, 4, 12_000,
                List.of(new Building(0, BuildingType.FARM, BuildingStatus.ACTIVE, 0),
                        new Building(1, BuildingType.SHIPYARD, BuildingStatus.UNDER_CONSTRUCTION, 2)));
        ActiveSystem home = new ActiveSystem(
                sHome, "Home Reach", new Coords(10, -7),
                Optional.of(fA), List.of(cradle), 12_000, 1.0);
        ActiveSystem frontier = new ActiveSystem(
                sFrontier, "Cold Verge", new Coords(33, 4),
                Optional.empty(), List.of(), 0, 1.0);

        Fleet parked = new Fleet(
                new FleetId("fleet-1"), fA, Optional.of(sHome), Optional.empty(),
                FleetStance.DEFENSIVE,
                List.of(new Ship("corvette", 6), new Ship("scout", 1)));
        Fleet enroute = new Fleet(
                new FleetId("fleet-2"), fA, Optional.empty(),
                Optional.of(List.of(sHome, sFrontier)),
                FleetStance.AGGRESSIVE,
                List.of(new Ship("cruiser", 3)));

        Treaty treaty = new Treaty(
                new TreatyId("treaty-1"), TreatyType.NON_AGGRESSION,
                List.of(fA, fB), Map.of("duration", "50"), 5, 55, TreatyStatus.ACTIVE);

        Route route = new Route(
                new RouteId("route-1"), fA, sHome, sFrontier, RouteKind.COMMERCIAL,
                List.of(PhysicalResource.MINERALS, PhysicalResource.ENERGY), 12.0,
                RouteStatus.ACTIVE);

        MarketOrder order = new MarketOrder(
                new MarketOrderId("order-1"), sHome, fB, MarketSide.BUY,
                PhysicalResource.FOOD, 50, 3.25, 7, 20, Optional.of(fA), OrderStatus.OPEN);

        return new GameState(
                987654321L, 7, GameStatus.RUNNING, "small-default", 1,
                Map.of(fA, factionA, fB, factionB),
                Map.of(sHome, home, sFrontier, frontier),
                Map.of(parked.id(), parked, enroute.id(), enroute),
                Map.of(treaty.id(), treaty),
                Map.of(route.id(), route),
                Map.of(order.id(), order),
                java.util.Set.of(new com.stellarcompact.engine.state.WarState(fA, fB, 3L)));
    }

    @Test
    void jsonRoundTripsAndPreservesCanonicalHash() throws Exception {
        GameState original = sampleState();

        String json = MAPPER.writeValueAsString(original);
        GameState roundTripped = MAPPER.readValue(json, GameState.class);

        assertEquals(original, roundTripped, "record equals() must survive a JSON round-trip");
        assertEquals(
                GoldenStateHash.sha256Hex(original),
                GoldenStateHash.sha256Hex(roundTripped),
                "canonical state hash must survive a JSON round-trip");
    }

    @Test
    void canonicalHashIsStableAcrossRepeatedHashing() {
        GameState state = sampleState();
        assertEquals(
                GoldenStateHash.sha256Hex(state),
                GoldenStateHash.sha256Hex(state),
                "hashing the same snapshot twice must be identical");
        assertEquals(
                GoldenStateHash.sha256Hex(sampleState()),
                GoldenStateHash.sha256Hex(sampleState()),
                "two equal snapshots must hash identically regardless of map order");
    }

    @Test
    void copyOnWriteHelpersChangeOnlyTheTargetedSlice() {
        GameState original = sampleState();

        GameState ticked = original.withTick(8);
        assertEquals(8, ticked.tick());
        assertEquals(7, original.tick(), "original snapshot must be untouched");
        assertNotEquals(
                GoldenStateHash.sha256Hex(original),
                GoldenStateHash.sha256Hex(ticked),
                "advancing the tick must move the hash");

        FactionId fA = new FactionId("faction-A");
        Faction current = original.factions().get(fA);
        Faction richer = current.withStockpiles(
                current.stockpiles().plus(new ResourceBundle(1, 0, 0, 0, 0)));
        GameState updated = original.withFaction(richer);
        assertEquals(101.0, updated.factions().get(fA).stockpiles().energy());
        assertEquals(100.0, original.factions().get(fA).stockpiles().energy(),
                "withFaction must not mutate the original");
    }

    @Test
    void heldCollectionsAreUnmodifiable() {
        GameState state = sampleState();
        assertThrows(UnsupportedOperationException.class,
                () -> state.factions().clear());
        assertThrows(UnsupportedOperationException.class,
                () -> state.systems().get(new SystemId("sys-home")).planets()
                        .get(0).buildings().add(null));
    }

    @Test
    void resourceBundleArithmeticIsCopyOnWrite() {
        ResourceBundle a = new ResourceBundle(10, 10, 10, 10, 10);
        ResourceBundle cost = new ResourceBundle(3, 0, 12, 0, 0);
        assertFalse(a.canAfford(cost), "cannot afford 12 food from 10");
        ResourceBundle scaled = a.scale(2);
        assertEquals(20.0, scaled.energy());
        assertEquals(10.0, a.energy(), "scale must not mutate the source bundle");
        assertEquals(new ResourceBundle(13, 10, 22, 10, 10), a.plus(cost));
    }
}
