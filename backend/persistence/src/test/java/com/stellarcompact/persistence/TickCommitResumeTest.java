package com.stellarcompact.persistence;

import com.stellarcompact.engine.config.BalanceProfile;
import com.stellarcompact.engine.config.BalanceProfileLoader;
import com.stellarcompact.engine.hash.StateHasher;
import com.stellarcompact.engine.map.LaneNetwork;
import com.stellarcompact.engine.resolve.PublicEvent;
import com.stellarcompact.engine.resolve.ResolveResult;
import com.stellarcompact.engine.resolve.Resolver;
import com.stellarcompact.engine.resolve.SubmittedAction;
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
import com.stellarcompact.persistence.repo.EventLogRepository;
import com.stellarcompact.persistence.repo.GameStateRepository;
import com.stellarcompact.persistence.repo.TickCommitService;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.extension.ExtendWith;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proves the E5-03 done-when properties against a real PostgreSQL (Testcontainers +
 * Flyway), with Spring driving the transaction so the rollback is genuine.
 *
 * <ol>
 *   <li>Kill-and-resume reproduces the next tick identically: commit tick N, reload from
 *       the DB via the resume path, run tick N+1 from the reloaded state, and assert its
 *       canonical state hash equals tick N+1 from an uninterrupted in-memory run.</li>
 *   <li>Partial-tick failure rolls back atomically: a commit that throws mid-commit (after
 *       the active-set save, before the event append) leaves the DB byte-identical to the
 *       pre-tick snapshot and writes NO event rows for the failed tick (no orphans).</li>
 * </ol>
 *
 * <p>Gated on Docker like the sibling persistence tests: skipped when no Docker is reachable.
 */
@EnabledIf("com.stellarcompact.persistence.TickCommitResumeTest#dockerAvailable")
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = TickCommitResumeTest.TestConfig.class)
class TickCommitResumeTest {

    @EnableTransactionManagement
    @Configuration
    static class TestConfig {

        @SuppressWarnings("resource")
        static final PostgreSQLContainer POSTGRES;

        static {
            POSTGRES = new PostgreSQLContainer(DockerImageName.parse("postgres:16-alpine"))
                    .withDatabaseName("stellar")
                    .withUsername("stellar")
                    .withPassword("stellar");
            if (dockerAvailable()) {
                POSTGRES.start();
                Flyway.configure()
                        .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                                POSTGRES.getPassword())
                        .locations("classpath:db/migration")
                        .load()
                        .migrate();
            }
        }

        @Bean
        DataSource dataSource() {
            PGSimpleDataSource ds = new PGSimpleDataSource();
            ds.setUrl(POSTGRES.getJdbcUrl());
            ds.setUser(POSTGRES.getUsername());
            ds.setPassword(POSTGRES.getPassword());
            return ds;
        }

        @Bean
        JdbcClient jdbcClient(DataSource ds) {
            return JdbcClient.create(ds);
        }

        @Bean
        PlatformTransactionManager transactionManager(DataSource ds) {
            return new DataSourceTransactionManager(ds);
        }

        @Bean
        GameStateRepository gameStateRepository(JdbcClient jdbc) {
            return new GameStateRepository(jdbc);
        }

        @Bean
        EventLogRepository eventLogRepository(JdbcClient jdbc) {
            return new EventLogRepository(jdbc);
        }

        @Bean
        TickCommitService tickCommitService(GameStateRepository state, EventLogRepository events) {
            return new TickCommitService(state, events);
        }
    }

    @Autowired
    TickCommitService commit;
    @Autowired
    JdbcClient jdbc;
    @Autowired
    GameStateRepository stateRepo;
    @Autowired
    EventLogRepository eventRepo;

    private static final BalanceProfile PROFILE = loadProfile();

    /**
     * Load the default balance profile from the engine's classpath resource (the engine
     * loader is a pure String parser, so the test reads the bytes - rule 6: every gameplay
     * number comes from a profile, never hardcoded).
     */
    private static BalanceProfile loadProfile() {
        try (var in = TickCommitResumeTest.class.getResourceAsStream("/balance/small-default.json")) {
            assertNotNull(in, "engine classpath resource /balance/small-default.json must exist");
            String json = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            return BalanceProfileLoader.parse(json);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("could not read default balance profile", e);
        }
    }

    @BeforeEach
    void wipe() {
        jdbc.sql("""
                TRUNCATE game, faction, active_system, planet, building, fleet, ship,
                         treaty, route, market_order, tech_progress, event_log CASCADE
                """).update();
    }

    @Test
    void killAndResumeReproducesNextTickIdentically() {
        UUID gameId = UUID.randomUUID();
        GameState tickN = stateAtTick(0);

        ResolveResult resN = resolve(tickN);
        GameState afterN = resN.state().withTick(tickN.tick() + 1);
        ResolveResult resN1Uninterrupted = resolve(afterN);
        String goldenNextHash = StateHasher.sha256Hex(resN1Uninterrupted.state());

        commit.commitTick(gameId, resN.state(), resN.events());

        GameState resumed = commit.resume(gameId);
        assertNotNull(resumed, "resume must reconstruct the committed snapshot");
        assertEquals(StateHasher.sha256Hex(resN.state()), StateHasher.sha256Hex(resumed),
                "resumed snapshot must equal the committed tick-N state (golden hash)");
        assertEquals(tickN.tick(), resumed.tick(), "resume restores the last committed tick");

        GameState afterNResumed = resumed.withTick(resumed.tick() + 1);
        ResolveResult resN1Resumed = resolve(afterNResumed);

        assertEquals(goldenNextHash, StateHasher.sha256Hex(resN1Resumed.state()),
                "tick N+1 after kill-and-resume must hash identically to the uninterrupted run");

        assertEquals(resN.events().size(), eventRepo.countEvents(gameId),
                "exactly the committed tick events are logged");
    }

    @Test
    void partialTickFailureRollsBackAtomically() {
        UUID gameId = UUID.randomUUID();

        GameState baseline = stateAtTick(0);
        ResolveResult base = resolve(baseline);
        commit.commitTick(gameId, base.state(), base.events());

        String beforeHash = StateHasher.sha256Hex(stateRepo.load(gameId));
        long eventsBefore = eventRepo.countEvents(gameId);

        GameState nextResolved = base.state()
                .withTick(base.state().tick() + 1)
                .withSystem(promotedSystem());
        List<PublicEvent> nextEvents = List.of(
                new PublicEvent.SystemCaptured(FactionId.of("fac-alpha"),
                        SystemId.of("sys-NEW"), nextResolved.tick()));

        RuntimeException boom = assertThrows(RuntimeException.class, () ->
                commit.commitTick(gameId, nextResolved, nextEvents,
                        () -> {
                            throw new IllegalStateException("injected mid-commit failure");
                        }));
        assertTrue(boom.getMessage().contains("injected mid-commit failure"));

        GameState reloaded = stateRepo.load(gameId);
        assertNotNull(reloaded);
        assertEquals(beforeHash, StateHasher.sha256Hex(reloaded),
                "a mid-commit failure must roll back the active-set save (state unchanged)");
        assertNull(reloaded.systems().get(SystemId.of("sys-NEW")),
                "the promoted system written by save must be gone after rollback");
        assertEquals(base.state().tick(), reloaded.tick(),
                "the failed tick number must not be committed");

        assertEquals(eventsBefore, eventRepo.countEvents(gameId),
                "no event_log rows may be appended for a tick that rolled back");
        long orphanRows = jdbc.sql(
                        "SELECT count(*) FROM event_log WHERE game_id = :g AND tick = :t")
                .param("g", gameId).param("t", nextResolved.tick())
                .query(Long.class).single();
        assertEquals(0L, orphanRows, "no event_log rows for the rolled-back tick");
    }

    @Test
    void resumeOfUnknownGameReturnsNull() {
        assertNull(commit.resume(UUID.randomUUID()));
    }

    private static ResolveResult resolve(GameState state) {
        return Resolver.resolveResult(state, List.<SubmittedAction>of(), PROFILE,
                state.gameSeed(), LaneNetwork.EMPTY);
    }

    private static GameState stateAtTick(long tick) {
        FactionId alpha = FactionId.of("fac-alpha");
        FactionId beta = FactionId.of("fac-beta");
        Faction fAlpha = new Faction(alpha, "Alpha", 5.0,
                new ResourceBundle(100, 80, 40, 20, 10), Map.of(), Set.of());
        Faction fBeta = new Faction(beta, "Beta", -2.0,
                new ResourceBundle(30, 30, 60, 5, 8), Map.of(), Set.of());

        ActiveSystem sysA = new ActiveSystem(
                SystemId.of("sys-A"), "Aurora", new Coords(10, 20), Optional.of(alpha),
                List.of(new Planet(PlanetId.of("sys-A-p0"), Biome.TERRAN, 6, 1000, List.of())),
                1000, 0.9);
        ActiveSystem sysB = new ActiveSystem(
                SystemId.of("sys-B"), "Borealis", new Coords(-5, 7), Optional.of(beta),
                List.of(new Planet(PlanetId.of("sys-B-p0"), Biome.OCEANIC, 4, 500, List.of())),
                500, 0.8);

        return new GameState(
                123456789L, tick, GameStatus.RUNNING, "default", 1,
                Map.of(alpha, fAlpha, beta, fBeta),
                Map.of(sysA.id(), sysA, sysB.id(), sysB),
                Map.of(), Map.of(), Map.of(), Map.of(), Set.of());
    }

    private static ActiveSystem promotedSystem() {
        return new ActiveSystem(
                SystemId.of("sys-NEW"), "Cygnus", new Coords(3, 4),
                Optional.of(FactionId.of("fac-alpha")),
                List.of(new Planet(PlanetId.of("sys-NEW-p0"), Biome.ARID, 4, 0, List.of())),
                0, 1.0);
    }

    @SuppressWarnings("unused")
    static boolean dockerAvailable() {
        try {
            return org.testcontainers.DockerClientFactory.instance().isDockerAvailable();
        } catch (Throwable t) {
            return false;
        }
    }
}
