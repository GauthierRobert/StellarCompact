package com.stellarcompact.orchestrator.promotion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.stellarcompact.engine.state.SystemId;
import org.junit.jupiter.api.Test;

/**
 * The SystemId &lt;-&gt; star id bridge must be an exact, total inverse: promotion
 * carries the procedural star id through the engine id, so a lossy or
 * irreversible encoding would break the E2-05 demote/re-promote round-trip.
 */
class SystemAddressTest {

    @Test
    void roundTripsAcrossTheFull64BitRange() {
        long[] samples = {
                0L, 1L, -1L, Long.MAX_VALUE, Long.MIN_VALUE,
                42L, 123456789L, 0x0123_4567_89AB_CDEFL, -987654321L
        };
        for (long starId : samples) {
            SystemId id = SystemAddress.toSystemId(starId);
            assertEquals(starId, SystemAddress.toStarId(id),
                    "round-trip must be lossless for star id " + starId);
        }
    }

    @Test
    void encodingIsStableAndPrefixed() {
        SystemId id = SystemAddress.toSystemId(42L);
        assertEquals("sys-42", id.value());
        assertTrue(id.value().startsWith(SystemAddress.PREFIX));
        // Deterministic: same input always the same id.
        assertEquals(SystemAddress.toSystemId(42L), SystemAddress.toSystemId(42L));
    }

    @Test
    void rejectsForeignIds() {
        assertThrows(IllegalArgumentException.class,
                () -> SystemAddress.toStarId(new SystemId("sysOwn")));
        assertThrows(IllegalArgumentException.class,
                () -> SystemAddress.toStarId(new SystemId("sys-notanumber")));
        assertThrows(IllegalArgumentException.class,
                () -> SystemAddress.toStarId(null));
    }
}
