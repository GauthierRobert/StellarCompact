package com.stellarcompact.persistence;

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
import com.stellarcompact.engine.state.TechStatus;
import com.stellarcompact.engine.state.Treaty;
import com.stellarcompact.engine.state.TreatyId;
import com.stellarcompact.engine.state.TreatyStatus;
import com.stellarcompact.engine.state.TreatyType;
import com.stellarcompact.engine.state.WarState;
import com.stellarcompact.persistence.repo.GameStateRepository;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import javax.sql.DataSource;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Round-trips a representative multi-faction {@link GameState} through real
 * PostgreSQL (Testcontainers + Flyway) and back, asserting {@code load(save(state))}
 * reconstructs an equal snapshot, and that a demoted system's rows are gone after a
 * save (board card E5-02 done-when).
 *
 * <p>Equality is asserted via the canonical golden state hash (same order-insensitive
 * SHA-256 the engine determinism contract uses): identical hash means identical state
 * for every engine purpose. Gated on Docker like {@code SchemaMigrationTest} - skipped
 * (not failed) when no Docker daemon is reachable.
 */
@EnabledIf("dockerAvailable")
class GameStateRoundTripTest {

    @SuppressWarnings("resource")
    private static PostgreSQLContainer postgres;
    private static DataSource dataSource;

    private GameStateRepository repo;

    @BeforeAll
    static void startAndMigrate() {
        postgres = new PostgreSQLContainer(DockerImageName.parse("postgres:16-alpine"))
                .withDatabaseName("stellar")
                .withUsername("stellar")
                .withPassword("stellar");
        postgres.start();

        Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();

        PGSimpleDataSource ds = new PGSimpleDataSource();
        ds.setUrl(postgres.getJdbcUrl());
        ds.setUser(postgres.getUsername());
        ds.setPassword(postgres.getPassword());
        dataSource = ds;
    }

    @AfterAll
    static void stop() {
        if (postgres != null) {
            postgres.stop();
        }
    }

    @BeforeEach
    void setUp() {
        repo = new GameStateRepository(JdbcClient.create(dataSource));
        // Entity ids (sys-A, fac-alpha, ...) are global primary keys, so wipe the DB
        // between methods to keep each test independent. event_log is append-only
        // (no DELETE), so TRUNCATE ... CASCADE which bypasses the row trigger.
        JdbcClient.create(dataSource).sql("""
                TRUNCATE game, faction, active_system, planet, building, fleet, ship,
                         treaty, route, market_order, tech_progress, event_log CASCADE
                """).update();
    }

    @Test
    void roundTripsRepresentativeMultiFactionState() {
        UUID gameId = UUID.randomUUID();
        GameState original = sampleState();

        repo.save(gameId, original);
        GameState loaded = repo.load(gameId);

        assertNotNull(loaded, "load must return the saved snapshot");
        assertEquals(GoldenHash.sha256Hex(original), GoldenHash.sha256Hex(loaded),
                "load(save(state)) must reconstruct an equal GameState (golden hash)");
    }

    @Test
    void loadOfUnknownGameReturnsNull() {
        assertNull(repo.load(UUID.randomUUID()), "load of an unknown game id returns null");
    }

    @Test
    void demotedSystemRowsAreGoneAfterSave() {
        UUID gameId = UUID.randomUUID();
        GameState original = sampleState();
        repo.save(gameId, original);

        SystemId demoted = SystemId.of("sys-B");
        assertTrue(original.systems().containsKey(demoted));

        // Demote sys-B and drop every reference to it (fleet path/route), mirroring what
        // the resolver does before abandoning a system. The demoted system is simply not
        // re-inserted, so its rows must be gone after this save.
        GameState afterDemote = original
                .withoutSystem(demoted)
                .withFleet(parkedFleet())
                .withRoute(intraSystemRoute());
        repo.save(gameId, afterDemote);

        GameState loaded = repo.load(gameId);
        assertNotNull(loaded);
        assertNull(loaded.systems().get(demoted), "demoted system must be absent after save");
        assertEquals(1, loaded.systems().size(), "only the surviving system remains");
        assertEquals(GoldenHash.sha256Hex(afterDemote), GoldenHash.sha256Hex(loaded),
                "post-demotion snapshot must still round-trip");

        long planetRows = JdbcClient.create(dataSource)
                .sql("SELECT count(*) FROM planet WHERE active_system_id = :s")
                .param("s", "sys-B")
                .query(Long.class).single();
        assertEquals(0L, planetRows, "demoted system's planet rows must be gone (cascade)");
    }

    @Test
    void explicitInsertAndDeleteSystemPersistsPromoteDemote() {
        UUID gameId = UUID.randomUUID();
        // bare game + a single faction so FKs are satisfiable, then promote/demote one system
        GameState base = minimalState();
        repo.save(gameId, base);

        ActiveSystem promoted = new ActiveSystem(
                SystemId.of("sys-C"), "Cygnus", new Coords(3, 4),
                Optional.of(FactionId.of("fac-alpha")),
                List.of(new Planet(PlanetId.of("sys-C-p0"), Biome.ARID, 4, 0, List.of())),
                0, 1.0);
        repo.insertSystem(gameId, promoted);
        assertTrue(repo.load(gameId).systems().containsKey(SystemId.of("sys-C")),
                "promoted system present after insertSystem");

        repo.deleteSystem(gameId, SystemId.of("sys-C"));
        assertNull(repo.load(gameId).systems().get(SystemId.of("sys-C")),
                "demoted system absent after deleteSystem");
        // deleting an absent id is a no-op
        repo.deleteSystem(gameId, SystemId.of("sys-C"));
    }

    // ===== sample state =======================================================

    private static GameState sampleState() {
        FactionId alpha = FactionId.of("fac-alpha");
        FactionId beta = FactionId.of("fac-beta");

        TechId drive = TechId.of("warp-drive");
        Faction fAlpha = new Faction(alpha, "Alpha Hegemony", 12.5,
                new ResourceBundle(100, 200, 50, 30, 75),
                Map.of(drive, new TechProgress(drive, TechStatus.RESEARCHING, 4)),
                Set.of(beta));
        Faction fBeta = new Faction(beta, "Beta Collective", -3.0,
                new ResourceBundle(10, 5, 90, 0, 12),
                Map.of(), Set.of());

        ActiveSystem sysA = new ActiveSystem(
                SystemId.of("sys-A"), "Aurora", new Coords(10, 20),
                Optional.of(alpha),
                List.of(new Planet(PlanetId.of("sys-A-p0"), Biome.TERRAN, 6, 5000,
                        List.of(new Building(0, BuildingType.MINE, BuildingStatus.ACTIVE, 0),
                                new Building(1, BuildingType.RESEARCH_LAB,
                                        BuildingStatus.UNDER_CONSTRUCTION, 3)))),
                5000, 0.9);
        ActiveSystem sysB = new ActiveSystem(
                SystemId.of("sys-B"), "Borealis", new Coords(-5, 7),
                Optional.empty(),
                List.of(new Planet(PlanetId.of("sys-B-p0"), Biome.FROZEN, 0, 0, List.of())),
                0, 1.0);

        Fleet enroute = new Fleet(FleetId.of("fleet-1"), alpha,
                Optional.of(SystemId.of("sys-A")),
                Optional.of(List.of(SystemId.of("sys-B"))),
                FleetStance.AGGRESSIVE,
                List.of(new Ship("cruiser", 4), new Ship("scout", 2)),
                Optional.of(3));

        Treaty treaty = new Treaty(TreatyId.of("treaty-1"), TreatyType.TRADE_PACT,
                List.of(alpha, beta), Map.of("scope", "energy", "duration", "20"),
                5, 25, TreatyStatus.ACTIVE);

        Route route = new Route(RouteId.of("route-1"), alpha,
                SystemId.of("sys-A"), SystemId.of("sys-B"), RouteKind.COMMERCIAL,
                List.of(PhysicalResource.ENERGY, PhysicalResource.MINERALS), 42.0,
                RouteStatus.ACTIVE);

        MarketOrder open = new MarketOrder(MarketOrderId.of("order-1"),
                SystemId.of("sys-A"), beta, MarketSide.BUY, PhysicalResource.FOOD,
                100, 2.5, 6, 30, Optional.empty(), OrderStatus.OPEN);
        MarketOrder directed = new MarketOrder(MarketOrderId.of("order-2"),
                SystemId.of("sys-A"), alpha, MarketSide.SELL, PhysicalResource.TECH,
                10, 9.0, 6, 12, Optional.of(beta), OrderStatus.PARTIALLY_FILLED);

        return new GameState(
                987654321L, 7L, GameStatus.RUNNING, "default", 3,
                Map.of(alpha, fAlpha, beta, fBeta),
                Map.of(sysA.id(), sysA, sysB.id(), sysB),
                Map.of(enroute.id(), enroute),
                Map.of(treaty.id(), treaty),
                Map.of(route.id(), route),
                Map.of(open.id(), open, directed.id(), directed),
                Set.of(WarState.between(alpha, beta, 2)));
    }

    private static GameState minimalState() {
        FactionId alpha = FactionId.of("fac-alpha");
        Faction f = new Faction(alpha, "Alpha", 0.0, ResourceBundle.ZERO, Map.of(), Set.of());
        return new GameState(1L, 0L, GameStatus.RUNNING, "default", 1,
                Map.of(alpha, f), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Set.of());
    }

    private static Fleet parkedFleet() {
        return new Fleet(FleetId.of("fleet-1"), FactionId.of("fac-alpha"),
                Optional.of(SystemId.of("sys-A")), Optional.empty(), FleetStance.AGGRESSIVE,
                List.of(new Ship("cruiser", 4), new Ship("scout", 2)), Optional.empty());
    }

    private static Route intraSystemRoute() {
        return new Route(RouteId.of("route-1"), FactionId.of("fac-alpha"),
                SystemId.of("sys-A"), SystemId.of("sys-A"), RouteKind.COMMERCIAL,
                List.of(PhysicalResource.ENERGY, PhysicalResource.MINERALS), 42.0,
                RouteStatus.ACTIVE);
    }

    /** Gate for {@link EnabledIf}: true iff a Docker daemon is reachable. */
    @SuppressWarnings("unused")
    static boolean dockerAvailable() {
        try {
            return org.testcontainers.DockerClientFactory.instance().isDockerAvailable();
        } catch (Throwable t) {
            return false;
        }
    }
}
