package com.stellarcompact.engine.config;

import com.stellarcompact.engine.config.BalanceProfileLoader.InvalidBalanceProfileException;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the E9-01 {@code progression} balance block: the shipped profiles tier
 * correctly (small = SMALL/open, large = LARGE/gated) and the loader normalises /
 * range-checks the block. The numbers asserted are read from the profiles (rule 6).
 */
class ProgressionConfigTest {

    private static String readResource(String name) {
        String path = "/balance/" + name;
        try (InputStream in = ProgressionConfigTest.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("missing classpath resource: " + path);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Test
    void shippedProfilesTierAsSmallFunnelAndLargeGatedCampaign() {
        BalanceProfile small = BalanceProfileLoader.parse(readResource("small-default.json"));
        BalanceProfile large = BalanceProfileLoader.parse(readResource("large-persistent.json"));

        assertEquals("SMALL", small.progression().sizeClass(), "small-default is the funnel tier");
        assertEquals("LARGE", large.progression().sizeClass(), "large-persistent is the campaign tier");

        // The funnel is open entry (no score bar); the persistent campaign gates entry.
        assertEquals(0.0, small.progression().seatThresholdScore(), 1e-9);
        assertTrue(large.progression().seatThresholdScore() > 0.0,
                "the large galaxy gates entry on a positive standing threshold");

        // Reputation may carry into the large campaign; the funnel starts clean.
        assertEquals(0.0, small.progression().reputationCarryWeight(), 1e-9);
        assertTrue(large.progression().reputationCarryWeight() > 0.0,
                "reputation carries (weighted) into the persistent campaign");

        // Both tiers define a fresh starter loadout: the no-material-advantage anchor.
        assertTrue(large.progression().starterStockpile().minerals() > 0.0,
                "the large galaxy ships a concrete starter loadout every entrant gets");
    }

    @Test
    void sizeClassIsNormalisedAndDefaulted() {
        // Lower-case normalises to upper; blank/absent defaults to SMALL.
        BalanceProfile.Progression lower = new BalanceProfile.Progression(
                "large", 10.0, false, 0.5, null);
        assertEquals("LARGE", lower.sizeClass());
        assertEquals(BalanceProfile.Progression.defaults().sizeClass(), "SMALL");
    }

    @Test
    void carryWeightIsClampedIntoUnitRange() {
        assertEquals(1.0,
                new BalanceProfile.Progression("LARGE", 0.0, false, 5.0, null).reputationCarryWeight(),
                1e-9, "over-1 weight clamps to 1");
        assertEquals(0.0,
                new BalanceProfile.Progression("LARGE", 0.0, false, -1.0, null).reputationCarryWeight(),
                1e-9, "negative weight clamps to 0");
    }

    @Test
    void unknownSizeClassNormalisesToSmallAndIsAccepted() {
        // An unrecognised tier normalises to its upper-case form; the loader rejects
        // anything that is not SMALL or LARGE so a typo fails loudly.
        BalanceProfile.Progression bogus = new BalanceProfile.Progression(
                "MEDIUM", 0.0, false, 0.0, null);
        assertEquals("MEDIUM", bogus.sizeClass(), "record keeps the normalised string");
        assertFalse(bogus.sizeClass().equals("SMALL") || bogus.sizeClass().equals("LARGE"));
    }

    @Test
    void loaderRejectsUnknownTier() {
        String json = readResource("small-default.json")
                .replace("\"sizeClass\": \"SMALL\"", "\"sizeClass\": \"HUGE\"");
        InvalidBalanceProfileException ex = assertThrows(InvalidBalanceProfileException.class,
                () -> BalanceProfileLoader.parse(json));
        assertTrue(ex.getMessage().contains("sizeClass"), ex.getMessage());
    }
}
