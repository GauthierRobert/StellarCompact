package com.stellarcompact.engine.config;

import com.stellarcompact.engine.config.BalanceProfileLoader.InvalidBalanceProfileException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the balance-config schema, loader and the two shipped profiles.
 *
 * <p>Note on the purity seam: the engine MAIN code is forbidden from touching
 * {@code java.io} / {@code java.net} (enforced by {@code EnginePurityTest}).
 * TEST code is not. So this test does the classpath READ (an InputStream) and
 * feeds the resulting String to the pure {@link BalanceProfileLoader#parse}.
 */
class BalanceProfileTest {

    /** Reads a balance resource off the test classpath into a String. */
    private static String readResource(String name) {
        String path = "/balance/" + name;
        try (InputStream in = BalanceProfileTest.class.getResourceAsStream(path)) {
            assertNotNull(in, "missing classpath resource: " + path);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"small-default.json", "large-persistent.json"})
    void bothShippedProfilesLoadAndValidate(String file) {
        BalanceProfile profile = BalanceProfileLoader.parse(readResource(file));
        assertNotNull(profile);
        // parse() already validated; an explicit re-validate documents the contract.
        BalanceProfileLoader.validate(profile);
    }

    @Test
    void profilesCarryNameAndPositiveVersion() {
        BalanceProfile small = BalanceProfileLoader.parse(readResource("small-default.json"));
        BalanceProfile large = BalanceProfileLoader.parse(readResource("large-persistent.json"));
        assertEquals("small-default", small.name());
        assertEquals("large-persistent", large.name());
        assertTrue(small.version() > 0);
        assertTrue(large.version() > 0);
    }

    @Test
    void smallProfileTicksInSecondsLargeInMinutes() {
        BalanceProfile small = BalanceProfileLoader.parse(readResource("small-default.json"));
        BalanceProfile large = BalanceProfileLoader.parse(readResource("large-persistent.json"));
        // small: seconds-scale; large: minutes-scale and strictly slower.
        assertTrue(small.tick().intervalMs() < large.tick().intervalMs(),
                "small tick should be faster than large tick");
        assertTrue(large.tick().intervalMs() >= 60_000L, "large tick should be minutes-scale");
        // larger targets in the persistent galaxy.
        assertTrue(large.victory().economic().influenceTarget()
                > small.victory().economic().influenceTarget());
        assertTrue(large.victory().survival().tickLimit() > small.victory().survival().tickLimit());
    }

    /**
     * Enumerate EVERY documented tunable group and assert it is present,
     * non-null and within range for both shipped profiles.
     */
    @ParameterizedTest
    @ValueSource(strings = {"small-default.json", "large-persistent.json"})
    void everyDocumentedTunableGroupIsPresentAndSane(String file) {
        BalanceProfile p = BalanceProfileLoader.parse(readResource(file));

        // resources
        assertNotNull(p.resources(), "resources");
        assertTrue(p.resources().biomeYields().size() >= 5, "biomeYields populated");
        assertTrue(p.resources().upkeep().containsKey("cruiser"), "upkeep has ship tiers");
        assertTrue(p.resources().deficitAttritionRate() > 0.0, "deficitAttritionRate");

        // population
        assertNotNull(p.population(), "population");
        assertTrue(p.population().growthPerFoodSurplus() > 0.0, "growthPerFoodSurplus");
        assertTrue(p.population().capByBuilding().containsKey("farm"), "capByBuilding");

        // market
        assertNotNull(p.market(), "market");
        assertEquals("priceTimePriority", p.market().matchPolicy(), "matchPolicy");
        assertTrue(p.market().routeInfluencePerVolume() > 0.0, "routeInfluencePerVolume");

        // construction
        assertNotNull(p.construction(), "construction");
        assertTrue(p.construction().buildTimes().containsKey("shipyard"), "buildTimes");
        assertTrue(p.construction().buildTimes().containsKey("terraformerStep"), "terraformerStep time");
        assertTrue(p.construction().costs().containsKey("mine"), "costs");

        // combat
        assertNotNull(p.combat(), "combat");
        assertTrue(p.combat().tierMultipliers().containsKey("capital"), "tierMultipliers");
        assertEquals(2, p.combat().varianceBand().size(), "varianceBand [lo,hi]");
        assertTrue(p.combat().defensePlatformBonus() >= 1.0, "defensePlatformBonus");
        assertTrue(p.combat().occupationLoyaltyPenalty() >= 0.0, "occupationLoyaltyPenalty");
        assertTrue(p.combat().warExhaustionPerLoss() > 0.0, "warExhaustionPerLoss");

        // tech
        assertNotNull(p.tech(), "tech");
        assertTrue(p.tech().costs().containsKey("capitalDoctrine"), "tech.costs");
        assertTrue(p.tech().times().containsKey("capitalDoctrine"), "tech.times");
        assertTrue(p.tech().multipliers().containsKey("improvedExtraction"), "tech.multipliers");

        // diplomacy
        assertNotNull(p.diplomacy(), "diplomacy");
        assertNotNull(p.diplomacy().reputation(), "reputation");
        assertTrue(p.diplomacy().reputation().gainHonourTreaty() > 0.0, "gainHonourTreaty");
        assertTrue(p.diplomacy().reputation().penaltyBreakTreaty() > 0.0, "penaltyBreakTreaty");
        assertTrue(p.diplomacy().reputation().penaltyUnprovokedWar() > 0.0, "penaltyUnprovokedWar");
        assertTrue(p.diplomacy().reputation().espionageDetectedPenalty() > 0.0, "espionageDetectedPenalty");
        assertTrue(p.diplomacy().treatyEnforcement().containsKey("alliance"), "treatyEnforcement weights");

        // victory
        assertNotNull(p.victory(), "victory");
        assertTrue(p.victory().domination().systemPct() > 0.0, "domination.systemPct");
        assertTrue(p.victory().economic().influenceTarget() > 0.0, "economic.influenceTarget");
        assertTrue(p.victory().economic().orTopForTicks() > 0, "economic.orTopForTicks");
        assertTrue(p.victory().diplomatic().allianceMajorityPct() > 0.0, "diplomatic.allianceMajorityPct");
        assertTrue(p.victory().survival().tickLimit() > 0, "survival.tickLimit");
        assertTrue(p.victory().wonder().stages() > 0, "wonder.stages");
        assertTrue(p.victory().wonder().holdTicks() > 0, "wonder.holdTicks");
        assertNotNull(p.victory().scoreWeights(), "scoreWeights");
        assertTrue(p.victory().scoreWeights().systems() > 0.0, "scoreWeights.systems");

        // tick
        assertNotNull(p.tick(), "tick");
        assertTrue(p.tick().intervalMs() > 0, "tick.intervalMs");
        assertTrue(p.tick().negotiationRounds() > 0, "tick.negotiationRounds");
        assertTrue(p.tick().phaseTimeoutMs() > 0, "tick.phaseTimeoutMs");
    }

    @Test
    void profileRecordsAreImmutableCollections() {
        BalanceProfile p = BalanceProfileLoader.parse(readResource("small-default.json"));
        assertThrows(UnsupportedOperationException.class,
                () -> p.resources().biomeYields().put("rogue", null),
                "biomeYields must be unmodifiable");
        assertThrows(UnsupportedOperationException.class,
                () -> p.combat().varianceBand().add(0.99),
                "varianceBand must be unmodifiable");
        assertThrows(UnsupportedOperationException.class,
                () -> p.tech().costs().clear(),
                "tech.costs must be unmodifiable");
    }

    @Test
    void blankJsonIsRejected() {
        assertThrows(InvalidBalanceProfileException.class, () -> BalanceProfileLoader.parse("  "));
        assertThrows(InvalidBalanceProfileException.class, () -> BalanceProfileLoader.parse(null));
    }

    @Test
    void unknownPropertyIsRejected() {
        String json = readResource("small-default.json").replaceFirst("\\{", "{ \"bogusKey\": 1,");
        assertThrows(InvalidBalanceProfileException.class, () -> BalanceProfileLoader.parse(json));
    }

    @Test
    void missingTunableGroupIsRejectedWithClearMessage() {
        // A syntactically valid profile that omits the entire 'combat' group.
        String json = """
                {
                  "name": "broken",
                  "version": 1,
                  "resources": { "biomeYields": { "terran": { "energy":1,"minerals":1,"food":1,"tech":0,"influence":0 } },
                    "upkeep": { "mine": { "energy":1,"minerals":0,"food":0,"tech":0,"influence":0 } },
                    "deficitAttritionRate": 0.1 },
                  "population": { "growthPerFoodSurplus": 0.2, "capByBuilding": { "farm": 1 } },
                  "market": { "matchPolicy": "priceTimePriority", "routeInfluencePerVolume": 0.5 },
                  "construction": { "buildTimes": { "mine": 1 },
                    "costs": { "mine": { "energy":0,"minerals":1,"food":0,"tech":0,"influence":0 } } },
                  "combat": null,
                  "tech": { "costs": { "x": 1 }, "times": { "x": 1 }, "multipliers": { "x": 1 } },
                  "diplomacy": { "reputation": { "gainHonourTreaty":1,"penaltyBreakTreaty":1,
                    "penaltyUnprovokedWar":1,"espionageDetectedPenalty":1 },
                    "treatyEnforcement": { "alliance": 1 } },
                  "victory": { "domination": { "systemPct": 0.5 },
                    "economic": { "influenceTarget": 1, "orTopForTicks": 1 },
                    "diplomatic": { "allianceMajorityPct": 0.5 },
                    "survival": { "tickLimit": 1 },
                    "wonder": { "stages": 1, "holdTicks": 1 },
                    "scoreWeights": { "systems":1,"influence":1,"economy":1,"tech":1,
                      "reputation":1,"military":1,"centrality":1 } },
                  "tick": { "intervalMs": 1000, "negotiationRounds": 1, "phaseTimeoutMs": 1000 }
                }
                """;
        InvalidBalanceProfileException ex =
                assertThrows(InvalidBalanceProfileException.class, () -> BalanceProfileLoader.parse(json));
        assertTrue(ex.getMessage().contains("combat"), "message should name the missing group: " + ex.getMessage());
    }

    @Test
    void outOfRangeValueIsRejected() {
        // deficitAttritionRate of 0 is out of (0,1].
        String json = readResource("small-default.json")
                .replace("\"deficitAttritionRate\": 0.10", "\"deficitAttritionRate\": 0.0");
        InvalidBalanceProfileException ex =
                assertThrows(InvalidBalanceProfileException.class, () -> BalanceProfileLoader.parse(json));
        assertTrue(ex.getMessage().contains("deficitAttritionRate"), ex.getMessage());
    }
}
