package com.stellarcompact.engine.validation;

import com.stellarcompact.engine.action.Action;
import com.stellarcompact.engine.config.BalanceProfile;
import com.stellarcompact.engine.config.BalanceProfileLoader;
import com.stellarcompact.engine.state.Building;
import com.stellarcompact.engine.state.BuildingStatus;
import com.stellarcompact.engine.state.BuildingType;
import com.stellarcompact.engine.state.GameState;
import com.stellarcompact.engine.state.TechId;
import com.stellarcompact.engine.state.TechProgress;
import com.stellarcompact.engine.state.TechStatus;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.stellarcompact.engine.validation.Fixtures.ALPHA;
import static com.stellarcompact.engine.validation.Fixtures.PLANET_A;
import static com.stellarcompact.engine.validation.Fixtures.SYS_A;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * E1-08 tech-DAG validation gates, exercised against the shipped {@code small-default}
 * profile (which carries real {@code tech.prereqs}/{@code tech.unlocks} edges, unlike
 * the minimal {@link Fixtures#profile()}). Covers: research prerequisite gating, the
 * ship-tier gate on {@code BuildFleet}, and the building gate on {@code Build}.
 */
class TechGateValidationTest {

    private static final BalanceProfile SMALL = loadSmallDefault();

    private static BalanceProfile loadSmallDefault() {
        try (InputStream in = TechGateValidationTest.class
                .getResourceAsStream("/balance/small-default.json")) {
            if (in == null) {
                throw new IllegalStateException("missing /balance/small-default.json");
            }
            return BalanceProfileLoader.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static ValidationResult validate(GameState state, Action action) {
        return ActionValidator.validate(state, ALPHA, action, SMALL);
    }

    private static GameState stateWithTech(Map<TechId, TechProgress> tech, List<Building> planetA) {
        return Fixtures.baseState(Map.of(), Fixtures.rich(), tech, planetA);
    }

    private static Map<TechId, TechProgress> unlocked(String... keys) {
        Map<TechId, TechProgress> m = new LinkedHashMap<>();
        for (String key : keys) {
            TechId id = new TechId(key);
            m.put(id, new TechProgress(id, TechStatus.UNLOCKED, 0));
        }
        return m;
    }

    private static Building active(int slot, BuildingType type) {
        return new Building(slot, type, BuildingStatus.ACTIVE, 0);
    }

    // ---- research prerequisite gate ------------------------------------------

    @Test
    void researchRejectedWhenPrerequisiteNotUnlocked() {
        // cruiserDoctrine requires corvetteDoctrine (small-default prereqs).
        GameState state = stateWithTech(Map.of(), List.of());
        ValidationResult r = validate(state, new Action.Research(new TechId("cruiserDoctrine")));
        ValidationResult.Rejected rej = (ValidationResult.Rejected) r;
        assertEquals(RejectionReason.TECH_PREREQ_MISSING, rej.code());
        assertTrue(rej.message().contains("corvetteDoctrine"), rej.message());
    }

    @Test
    void researchAcceptedWhenPrerequisiteUnlocked() {
        GameState state = stateWithTech(unlocked("corvetteDoctrine"), List.of());
        ValidationResult r = validate(state, new Action.Research(new TechId("cruiserDoctrine")));
        assertTrue(r.isValid(), () -> "expected Valid but was " + r);
    }

    @Test
    void rootTechWithNoPrereqsIsAccepted() {
        GameState state = stateWithTech(Map.of(), List.of());
        assertTrue(validate(state, new Action.Research(new TechId("corvetteDoctrine"))).isValid());
    }

    // ---- ship-tier gate on BuildFleet ----------------------------------------

    @Test
    void buildFleetRejectedForGatedShipTierWithoutDoctrine() {
        // cruiser is gated by cruiserDoctrine. A Shipyard is present, doctrine is not.
        GameState state = stateWithTech(Map.of(), List.of(active(0, BuildingType.SHIPYARD)));
        ValidationResult r = validate(state, new Action.BuildFleet(SYS_A, "cruiser"));
        ValidationResult.Rejected rej = (ValidationResult.Rejected) r;
        assertEquals(RejectionReason.TECH_PREREQ_MISSING, rej.code());
        assertTrue(rej.message().contains("cruiserDoctrine"), rej.message());
    }

    @Test
    void buildFleetAcceptedForGatedShipTierWithDoctrine() {
        GameState state = stateWithTech(unlocked("cruiserDoctrine"),
                List.of(active(0, BuildingType.SHIPYARD)));
        assertTrue(validate(state, new Action.BuildFleet(SYS_A, "cruiser")).isValid());
    }

    @Test
    void buildFleetAcceptedForUngatedShipSpec() {
        // "freighter" is named by no unlock entry -> ungated, buildable with a Shipyard.
        GameState state = stateWithTech(Map.of(), List.of(active(0, BuildingType.SHIPYARD)));
        assertTrue(validate(state, new Action.BuildFleet(SYS_A, "freighter")).isValid());
    }

    // ---- building gate on Build ----------------------------------------------

    @Test
    void buildRejectedForGatedBuildingWithoutTech() {
        // marketHub is gated by marketNetworks (small-default unlocks).
        GameState state = stateWithTech(Map.of(), List.of());
        ValidationResult r = validate(state, new Action.Build(PLANET_A, 0, BuildingType.MARKET_HUB));
        ValidationResult.Rejected rej = (ValidationResult.Rejected) r;
        assertEquals(RejectionReason.TECH_PREREQ_MISSING, rej.code());
        assertTrue(rej.message().contains("marketNetworks"), rej.message());
    }

    @Test
    void buildAcceptedForGatedBuildingWithTech() {
        GameState state = stateWithTech(unlocked("marketNetworks"), List.of());
        assertTrue(validate(state, new Action.Build(PLANET_A, 0, BuildingType.MARKET_HUB)).isValid());
    }

    @Test
    void buildAcceptedForUngatedBuilding() {
        // mine is gated by no tech.
        GameState state = stateWithTech(Map.of(), List.of());
        assertTrue(validate(state, new Action.Build(PLANET_A, 0, BuildingType.MINE)).isValid());
    }
}
