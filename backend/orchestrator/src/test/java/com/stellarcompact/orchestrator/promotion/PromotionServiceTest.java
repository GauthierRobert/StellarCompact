package com.stellarcompact.orchestrator.promotion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.stellarcompact.engine.state.ActiveSystem;
import com.stellarcompact.engine.state.Biome;
import com.stellarcompact.engine.state.FactionId;
import com.stellarcompact.engine.state.GameState;
import com.stellarcompact.engine.state.GameStatus;
import com.stellarcompact.engine.state.Planet;
import com.stellarcompact.engine.state.SystemId;
import com.stellarcompact.engine.state.Treaty;
import com.stellarcompact.engine.state.TreatyId;
import com.stellarcompact.galaxy.gen.StarSystem;
import com.stellarcompact.galaxy.gen.SystemGenerator;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Golden-style tests for the E2-05 promotion/demotion boundary. Hand-authored
 * inputs, no randomness, no I/O - the determinism contract verified directly:
 * <ul>
 *   <li>promote is a pure function of (seed, id);</li>
 *   <li>the materialised ActiveSystem mirrors exactly what the galaxy generator
 *       produces for that seed/id (biomes, slot totals, planet count);</li>
 *   <li>demote removes the active row cleanly and a fresh promote yields an
 *       identical ActiveSystem (lossless round-trip), proving nothing was stored
 *       beyond the seed;</li>
 *   <li>promotion never enumerates the catalog - it only ever takes an explicit id.</li>
 * </ul>
 */
class PromotionServiceTest {

    private static final long SEED = 0xC0FFEEL;
    private static final FactionId OWNER = new FactionId("alpha");

    private final PromotionService service = new PromotionService();

    /** A few explicit galaxy star ids, encoded as engine SystemIds for promotion. */
    private static SystemId sysFor(long starId) {
        return SystemAddress.toSystemId(starId);
    }

    private static GameState emptyState() {
        return new GameState(
                SEED, 0L, GameStatus.RUNNING, "small-default", 1,
                Map.of(), Map.of(), Map.of(),
                Map.<TreatyId, Treaty>of(), Map.of(), Map.of(), Set.of());
    }

    @Test
    void promoteIsDeterministicPerSeedAndId() {
        SystemId id = sysFor(987654321L);
        ActiveSystem a = service.promote(SEED, id, OWNER);
        ActiveSystem b = service.promote(SEED, id, OWNER);
        assertEquals(a, b, "same (seed, id) must materialise an identical ActiveSystem");

        // The materialisation is genuinely seed-derived (not a constant): across a
        // spread of distinct seeds for the SAME id, at least one roster differs.
        boolean someSeedDiffers = false;
        for (long s = SEED + 1; s <= SEED + 8; s++) {
            if (!service.promote(s, id, OWNER).planets().equals(a.planets())) {
                someSeedDiffers = true;
                break;
            }
        }
        assertTrue(someSeedDiffers, "roster must depend on the seed, not be a constant");
    }

    @Test
    void materialisationMatchesTheGalaxyGenerator() {
        long starId = 123456789L;
        SystemId id = sysFor(starId);
        ActiveSystem active = service.promote(SEED, id, OWNER);
        StarSystem source = SystemGenerator.generate(SEED, starId);

        assertEquals(source.planetCount(), active.planets().size(),
                "planet count must mirror the generator");
        assertEquals(OWNER, active.owner().orElseThrow(), "owner must be the colonising faction");
        assertEquals(PromotionService.FOUNDING_LOYALTY, active.loyalty());
        assertEquals(0L, active.population());

        for (int i = 0; i < source.planetCount(); i++) {
            com.stellarcompact.galaxy.gen.Planet src = source.planets().get(i);
            Planet mat = active.planets().get(i);
            // Biome maps one-to-one by name.
            assertEquals(src.biome().name(), mat.biome().name(),
                    "biome must map one-to-one by name at orbit " + i);
            assertEquals(Biome.valueOf(src.biome().name()), mat.biome());
            // Engine slotsTotal = galaxy ground + orbital capacity.
            assertEquals(src.totalSlots(), mat.slotsTotal(),
                    "slot total must equal galaxy ground+orbital at orbit " + i);
            // No buildings materialised at promotion time.
            assertTrue(mat.buildings().isEmpty(), "promotion places no buildings");
        }
    }

    @Test
    void promoteIntoInsertsTheSystem() {
        SystemId id = sysFor(42L);
        GameState before = emptyState();
        GameState after = service.promoteInto(before, id, OWNER);

        assertFalse(before.systems().containsKey(id), "precondition: not yet active");
        assertTrue(after.systems().containsKey(id), "promoteInto must insert the active system");
        assertEquals(service.promote(SEED, id, OWNER), after.systems().get(id));
    }

    @Test
    void demoteRemovesCleanlyAndRoundTripsToIdentical() {
        SystemId id = sysFor(0x0123456789ABCDEFL);

        // promote -> demote -> promote must reproduce a byte-identical ActiveSystem,
        // proving demote stored nothing beyond the seed (no orphan rows, pure scenery).
        GameState promoted = service.promoteInto(emptyState(), id, OWNER);
        ActiveSystem firstMaterialisation = promoted.systems().get(id);

        GameState demoted = service.demote(promoted, id);
        assertFalse(demoted.systems().containsKey(id),
                "demote must remove the active system entirely");
        assertTrue(demoted.systems().isEmpty(), "no orphan system rows remain");

        GameState rePromoted = service.promoteInto(demoted, id, OWNER);
        ActiveSystem secondMaterialisation = rePromoted.systems().get(id);
        assertEquals(firstMaterialisation, secondMaterialisation,
                "the star must be fully regenerable from seed alone after demotion");
    }

    @Test
    void demoteOfAbsentSystemIsANoOp() {
        GameState state = emptyState();
        GameState after = service.demote(state, sysFor(7L));
        assertSame(state, after, "demoting an absent id must be a no-op (same snapshot)");
    }

    @Test
    void demoteLeavesOtherActiveSystemsUntouched() {
        SystemId keep = sysFor(11L);
        SystemId drop = sysFor(22L);
        GameState state = service.promoteInto(service.promoteInto(emptyState(), keep, OWNER), drop, OWNER);
        assertEquals(2, state.systems().size());

        GameState after = service.demote(state, drop);
        assertEquals(1, after.systems().size());
        assertTrue(after.systems().containsKey(keep));
        assertEquals(state.systems().get(keep), after.systems().get(keep),
                "the surviving system must be untouched");
    }

    /**
     * Promotion never iterates the procedural catalog - the only way in is an
     * explicit SystemId. This is verified by construction: the public API surface
     * exposes only id-keyed methods (no catalog scan). We assert the API shape so a
     * future change that added a "promote all" enumeration would fail this test.
     */
    @Test
    void apiIsAlwaysByExplicitIdNeverACatalogScan() {
        var methodNames = java.util.Arrays.stream(PromotionService.class.getDeclaredMethods())
                .filter(m -> java.lang.reflect.Modifier.isPublic(m.getModifiers()))
                .map(java.lang.reflect.Method::getName)
                .collect(java.util.stream.Collectors.toSet());
        assertEquals(Set.of("promote", "promoteInto", "demote"), methodNames,
                "promotion must expose only explicit-id operations, never a catalog enumerator");
        // And each promotion/demotion method takes a SystemId, never a collection of all stars.
        for (var m : PromotionService.class.getDeclaredMethods()) {
            if (!java.lang.reflect.Modifier.isPublic(m.getModifiers())) {
                continue;
            }
            boolean takesSystemId = java.util.Arrays.asList(m.getParameterTypes()).contains(SystemId.class);
            assertTrue(takesSystemId, m.getName() + " must take an explicit SystemId");
        }
    }

    @Test
    void promotedSystemPreservesPlanetOrbitOrder() {
        long starId = 555L;
        SystemId id = sysFor(starId);
        ActiveSystem active = service.promote(SEED, id, OWNER);
        StarSystem source = SystemGenerator.generate(SEED, starId);
        // Planet ids encode orbit index in roster order; ids must be unique and ordered.
        List<Planet> planets = active.planets();
        for (int i = 0; i < planets.size(); i++) {
            String expectedSuffix = "-p" + source.planets().get(i).orbitIndex();
            assertTrue(planets.get(i).id().value().endsWith(expectedSuffix),
                    "planet id must encode its stable orbit index");
        }
        assertEquals(planets.size(),
                planets.stream().map(p -> p.id().value()).distinct().count(),
                "planet ids must be unique within the system");
        assertTrue(active.owner().equals(Optional.of(OWNER)));
    }
}
