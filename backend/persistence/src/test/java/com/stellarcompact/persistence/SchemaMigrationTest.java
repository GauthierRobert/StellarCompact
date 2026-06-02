package com.stellarcompact.persistence;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Applies the Flyway migrations to a real PostgreSQL (Testcontainers) and asserts
 * the resulting schema matches docs/specs/data-model.md, that only mutable game
 * state (no star catalog) is created, and that {@code event_log} is append-only
 * and tick-ordered (board card E5-01 done-when).
 *
 * <p>The whole class is gated on Docker being available ({@link #dockerAvailable()}):
 * if no Docker daemon is reachable the test is skipped rather than failing, so the
 * build stays green on machines/CI without Docker. The Testcontainers test is the
 * correct artifact regardless of whether it executes in a given environment.
 */
@EnabledIf("dockerAvailable")
class SchemaMigrationTest {

    /** The tables the data-model spec defines (mutable game state only). */
    private static final Set<String> EXPECTED_TABLES = Set.of(
            "game", "faction", "active_system", "planet", "building",
            "fleet", "ship", "treaty", "route", "market_order",
            "tech_progress", "event_log");

    /** Catalog/star tables that MUST NOT exist (the procedural catalog is not stored). */
    private static final Set<String> FORBIDDEN_TABLES = Set.of(
            "star", "stars", "catalog", "star_catalog", "tile", "tiles", "lane", "lanes");

    @SuppressWarnings("resource")
    private static PostgreSQLContainer postgres;

    @BeforeAll
    static void startAndMigrate() {
        postgres = new PostgreSQLContainer(DockerImageName.parse("postgres:16-alpine"))
                .withDatabaseName("stellar")
                .withUsername("stellar")
                .withPassword("stellar");
        postgres.start();

        MigrateResult result = Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();

        // migrations apply cleanly (first half of the done-when)
        assertTrue(result.success, "Flyway migration must succeed");
        assertEquals(4, result.migrationsExecuted, "all four V*.sql migrations must run");
    }

    @AfterAll
    static void stop() {
        if (postgres != null) {
            postgres.stop();
        }
    }

    private Connection conn() throws SQLException {
        return DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    }

    @Test
    void allSpecTablesExistAndNoCatalogTables() throws SQLException {
        Set<String> tables = new HashSet<>();
        try (Connection c = conn();
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery(
                     "SELECT table_name FROM information_schema.tables "
                             + "WHERE table_schema = 'public' AND table_type = 'BASE TABLE'")) {
            while (rs.next()) {
                tables.add(rs.getString(1));
            }
        }
        for (String expected : EXPECTED_TABLES) {
            assertTrue(tables.contains(expected), "missing spec table: " + expected);
        }
        for (String forbidden : FORBIDDEN_TABLES) {
            assertFalse(tables.contains(forbidden),
                    "catalog/star table must NOT be persisted: " + forbidden);
        }
    }

    @Test
    void gameAndFactionColumnsMatchSpecTypes() throws SQLException {
        assertColumnType("game", "id", "uuid");
        assertColumnType("game", "seed", "bigint");
        assertColumnType("game", "params_json", "jsonb");
        assertColumnType("game", "created_at", "timestamp with time zone");

        assertColumnType("faction", "id", "text");
        assertColumnType("faction", "game_id", "uuid");
        assertColumnType("faction", "reputation", "double precision");
        assertColumnType("faction", "energy", "double precision");
        assertColumnType("faction", "persona_json", "jsonb");
        // owner_user_id is nullable (AI-only / unclaimed Sovereign)
        assertColumnNullable("faction", "owner_user_id", true);
    }

    @Test
    void activeSystemUsesSeedCoordsAndNullableOwner() throws SQLException {
        assertColumnType("active_system", "seed_coord_x", "bigint");
        assertColumnType("active_system", "seed_coord_y", "bigint");
        assertColumnType("active_system", "population", "bigint");
        assertColumnType("active_system", "loyalty", "double precision");
        // a neutral/contested frontier system has no owner
        assertColumnNullable("active_system", "owner_faction_id", true);
    }

    @Test
    void fleetLocationAndPathAreOptional() throws SQLException {
        assertColumnNullable("fleet", "location_system_id", true);
        assertColumnNullable("fleet", "enroute_path_json", true);
        assertColumnType("fleet", "enroute_path_json", "jsonb");
        assertColumnNullable("fleet", "eta_ticks", true);
    }

    @Test
    void closedEnumColumnsAreCheckConstrained() throws SQLException {
        // a CHECK violation proves the enum set is enforced at the DB boundary
        assertStatementRejected("INSERT INTO game (id, seed, status, balance_profile) "
                + "VALUES (gen_random_uuid(), 1, 'NOT_A_STATUS', 'default')");
    }

    @Test
    void eventLogRejectsUnknownEventType() throws SQLException {
        UUID gameId = insertGame();
        try (Connection c = conn();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO event_log (game_id, tick, seq, event_type) VALUES (?,?,?,?)")) {
            ps.setObject(1, gameId);
            ps.setLong(2, 0);
            ps.setInt(3, 0);
            ps.setString(4, "NotARealEvent");
            assertThrows(SQLException.class, ps::executeUpdate,
                    "event_type must be CHECK-constrained to the 10 PublicEvent kinds");
        }
    }

    @Test
    void eventLogIsAppendOnly_updateAndDeleteRejected() throws SQLException {
        UUID gameId = insertGame();
        long id = insertEvent(gameId, 5, 0, "WarDeclared");

        try (Connection c = conn()) {
            try (PreparedStatement up = c.prepareStatement(
                    "UPDATE event_log SET event_type = 'TreatySigned' WHERE id = ?")) {
                up.setLong(1, id);
                assertThrows(SQLException.class, up::executeUpdate,
                        "event_log UPDATE must be forbidden (append-only)");
            }
            try (PreparedStatement del = c.prepareStatement("DELETE FROM event_log WHERE id = ?")) {
                del.setLong(1, id);
                assertThrows(SQLException.class, del::executeUpdate,
                        "event_log DELETE must be forbidden (append-only)");
            }
        }

        // the row is still there, untouched
        try (Connection c = conn();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT event_type FROM event_log WHERE id = ?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                assertEquals("WarDeclared", rs.getString(1));
            }
        }
    }

    @Test
    void eventLogIsTickOrdered_olderTickRejected() throws SQLException {
        UUID gameId = insertGame();
        insertEvent(gameId, 10, 0, "SystemCaptured");

        // appending at the same tick (new seq) is fine
        long sameTick = insertEvent(gameId, 10, 1, "BattleResolved");
        assertTrue(sameTick > 0);
        // appending at a later tick is fine
        long laterTick = insertEvent(gameId, 11, 0, "VictoryAchieved");
        assertTrue(laterTick > 0);

        // appending at an EARLIER tick than the game's max must be rejected
        try (Connection c = conn();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO event_log (game_id, tick, seq, event_type) VALUES (?,?,?,?)")) {
            ps.setObject(1, gameId);
            ps.setLong(2, 9);
            ps.setInt(3, 0);
            ps.setString(4, "TreatyBroken");
            assertThrows(SQLException.class, ps::executeUpdate,
                    "event_log must reject an out-of-tick-order append");
        }
    }

    @Test
    void eventLogTickSeqIsUniquePerGame() throws SQLException {
        UUID gameId = insertGame();
        insertEvent(gameId, 3, 0, "RouteEstablished");
        try (Connection c = conn();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO event_log (game_id, tick, seq, event_type) VALUES (?,?,?,?)")) {
            ps.setObject(1, gameId);
            ps.setLong(2, 3);
            ps.setInt(3, 0);
            ps.setString(4, "RouteRaided");
            assertThrows(SQLException.class, ps::executeUpdate,
                    "(game_id, tick, seq) must be unique - no two events share an order slot");
        }
    }

    // ===== helpers ===========================================================

    private UUID insertGame() throws SQLException {
        UUID id = UUID.randomUUID();
        try (Connection c = conn();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO game (id, seed, status, balance_profile) VALUES (?,?,?,?)")) {
            ps.setObject(1, id);
            ps.setLong(2, 42L);
            ps.setString(3, "RUNNING");
            ps.setString(4, "default");
            ps.executeUpdate();
        }
        return id;
    }

    private long insertEvent(UUID gameId, long tick, int seq, String type) throws SQLException {
        try (Connection c = conn();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO event_log (game_id, tick, seq, event_type, payload_json) "
                             + "VALUES (?,?,?,?, '{}'::jsonb) RETURNING id")) {
            ps.setObject(1, gameId);
            ps.setLong(2, tick);
            ps.setInt(3, seq);
            ps.setString(4, type);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                return rs.getLong(1);
            }
        }
    }

    private void assertStatementRejected(String sql) throws SQLException {
        try (Connection c = conn(); Statement s = c.createStatement()) {
            assertThrows(SQLException.class, () -> s.executeUpdate(sql));
        }
    }

    private void assertColumnType(String table, String column, String expectedType) throws SQLException {
        try (Connection c = conn();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT data_type FROM information_schema.columns "
                             + "WHERE table_schema = 'public' AND table_name = ? AND column_name = ?")) {
            ps.setString(1, table);
            ps.setString(2, column);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "missing column " + table + "." + column);
                assertEquals(expectedType, rs.getString(1), table + "." + column + " type");
            }
        }
    }

    private void assertColumnNullable(String table, String column, boolean nullable) throws SQLException {
        try (Connection c = conn();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT is_nullable FROM information_schema.columns "
                             + "WHERE table_schema = 'public' AND table_name = ? AND column_name = ?")) {
            ps.setString(1, table);
            ps.setString(2, column);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "missing column " + table + "." + column);
                assertEquals(nullable ? "YES" : "NO", rs.getString(1),
                        table + "." + column + " nullability");
            }
        }
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
