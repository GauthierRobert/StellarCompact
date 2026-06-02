package com.stellarcompact.engine.hash;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Golden state-hash test. Proves the canonicaliser yields a stable, reproducible
 * hash so that — once the real {@code GameState} exists (E1-01) — a fixed seed +
 * fixed action log can be pinned to a golden hex value that breaks loudly on any
 * unintended serialization or resolution change.
 *
 * <p>Until {@code GameState} records exist, the contract is demonstrated against a
 * small hand-authored sample modelling the shape of engine state (a faction id ->
 * resource bundle map, plus an ordered action log).
 */
class GoldenStateHashTest {

    /** A fixed, hand-authored sample standing in for a {@code GameState} snapshot. */
    private static Map<String, Object> sampleState(Map<String, Integer> resources) {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("gameSeed", 1234567890123456789L);
        state.put("tick", 42);
        state.put("resources", resources);
        // Ordered action log: order is meaningful and MUST be preserved.
        state.put("actionLog", List.of("Explore(s1)", "Colonize(p7)", "Hold"));
        state.put("activeTreaty", Optional.of("NAP:fA:fB"));
        return state;
    }

    /** The golden hash. A change here means the canonical form changed — review why. */
    private static final String GOLDEN_HEX =
            "57720b46229539cfbf76f3aad8866c218e8d63b3b2a34098f1528b1e42927dae";

    @Test
    void canonicalHashIsStableAndMatchesGolden() {
        // Insertion order A.
        Map<String, Integer> resA = new LinkedHashMap<>();
        resA.put("credits", 100);
        resA.put("alloy", 30);
        resA.put("fuel", 75);

        String hash1 = GoldenStateHash.sha256Hex(sampleState(resA));
        String hash2 = GoldenStateHash.sha256Hex(sampleState(resA));

        // Deterministic within a run.
        assertEquals(hash1, hash2, "hash must be deterministic across calls");
        // Pinned golden value: catches any serialization drift across runs/JVMs.
        assertEquals(GOLDEN_HEX, hash1, "canonical golden hash drifted");
    }

    @Test
    void mapInsertionOrderDoesNotAffectHash() {
        Map<String, Integer> resA = new LinkedHashMap<>();
        resA.put("credits", 100);
        resA.put("alloy", 30);
        resA.put("fuel", 75);

        // Same entries, shuffled insertion order.
        Map<String, Integer> resB = new LinkedHashMap<>();
        resB.put("fuel", 75);
        resB.put("credits", 100);
        resB.put("alloy", 30);

        assertEquals(
                GoldenStateHash.sha256Hex(sampleState(resA)),
                GoldenStateHash.sha256Hex(sampleState(resB)),
                "map insertion order must not affect the canonical hash");
    }

    @Test
    void differentStateProducesDifferentHash() {
        Map<String, Integer> resA = new LinkedHashMap<>();
        resA.put("credits", 100);

        Map<String, Integer> resMutated = new LinkedHashMap<>();
        resMutated.put("credits", 101);

        assertNotEquals(
                GoldenStateHash.sha256Hex(sampleState(resA)),
                GoldenStateHash.sha256Hex(sampleState(resMutated)),
                "a one-unit state change must change the hash");
    }
}
