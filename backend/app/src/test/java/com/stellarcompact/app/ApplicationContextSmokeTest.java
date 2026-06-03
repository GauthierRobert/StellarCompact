package com.stellarcompact.app;

import com.stellarcompact.api.galaxy.TileCache;
import com.stellarcompact.api.match.InMemoryMatchService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Full-application-context smoke test (board card E11-10; live-sim finding L7 /
 * decision P6). Boots the <em>entire</em> production Spring context - the app
 * module's {@link StellarCompactApplication} {@code @SpringBootApplication}, which
 * component-scans {@code com.stellarcompact} and therefore wires every Spring bean
 * across api + orchestrator + persistence + agent-runtime - so any bean-instantiation
 * or wiring bug fails here in CI rather than on a human's first boot.
 *
 * <h2>Why the FULL context, not a slice</h2>
 * The bug this test exists to catch (L7-1) was {@code TileCache} declaring two
 * constructors with neither marked {@code @Autowired}, which Spring could not resolve
 * ({@code BeanInstantiationException: No default constructor found}). That failure is
 * invisible to the per-module tests, which construct the bean directly, and would also
 * be invisible to any context slice that excluded the api/galaxy beans. Only loading
 * the same bean graph that boots in production reproduces it - so this test asserts the
 * context loads AND that the exact bean ({@link TileCache}, whose 2-arg constructor must
 * be the autowired one) plus the live-match service ({@link InMemoryMatchService}) are
 * present and resolvable.
 *
 * <h2>Datasource strategy (mirrors the persistence module)</h2>
 * The production context autoconfigures a {@code DataSource} + Flyway against
 * PostgreSQL, so a real database is required for the context to refresh. This test
 * reuses the established repo pattern from {@code persistence} (Testcontainers
 * {@link PostgreSQLContainer} + {@code postgres:16-alpine}, gated on Docker via
 * {@link #dockerAvailable()}): the container's JDBC coordinates are injected through
 * {@link DynamicPropertySource} so the autoconfigured datasource and Flyway run the
 * real {@code V*.sql} migrations against it. When no Docker daemon is reachable the
 * class is skipped (not failed), exactly like the sibling persistence tests, so the
 * build stays green locally while still running in CI where Docker exists.
 */
@EnabledIf("dockerAvailable")
@SpringBootTest(classes = StellarCompactApplication.class)
class ApplicationContextSmokeTest {

    @SuppressWarnings("resource")
    private static PostgreSQLContainer postgres;

    /**
     * Starts the Testcontainers Postgres (once) and points the app's autoconfigured
     * datasource + Flyway at it. Spring evaluates {@code @DynamicPropertySource} before
     * the context refreshes, and the whole class is gated on Docker, so this only runs
     * when a container can actually start.
     */
    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        if (postgres == null) {
            postgres = new PostgreSQLContainer(DockerImageName.parse("postgres:16-alpine"))
                    .withDatabaseName("stellar")
                    .withUsername("stellar")
                    .withPassword("stellar");
            postgres.start();
        }
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.flyway.locations", () -> "classpath:db/migration");
    }

    @AfterAll
    static void stop() {
        if (postgres != null) {
            postgres.stop();
            postgres = null;
        }
    }

    @Autowired
    private ApplicationContext context;

    /** The whole production context refreshes without a wiring/bean-instantiation failure. */
    @Test
    void contextLoads() {
        assertNotNull(context, "the application context must load");
    }

    /**
     * The exact bean that broke first boot (L7-1) is present and resolvable. If
     * {@code TileCache} ever again has an unresolvable constructor set, the context
     * refresh above fails; this additionally pins that the bean is actually in the graph
     * (i.e. the full api/galaxy slice is loaded, the only slice that would catch the bug).
     */
    @Test
    void tileCacheBeanIsWired() {
        assertTrue(context.containsBean("tileCache")
                        || context.getBeanNamesForType(TileCache.class).length > 0,
                "TileCache must be wired in the full context (the L7-1 regression guard)");
        assertNotNull(context.getBean(TileCache.class), "TileCache must be resolvable");
    }

    /** The live-match service (orchestrator-backed) is wired - proves cross-module beans load. */
    @Test
    void matchServiceBeanIsWired() {
        assertNotNull(context.getBean(InMemoryMatchService.class),
                "InMemoryMatchService must be wired in the full context");
    }

    /** Gate for {@link EnabledIf}: true iff a Docker daemon is reachable (mirrors persistence). */
    @SuppressWarnings("unused")
    static boolean dockerAvailable() {
        try {
            return org.testcontainers.DockerClientFactory.instance().isDockerAvailable();
        } catch (Throwable t) {
            return false;
        }
    }
}
