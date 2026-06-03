package com.stellarcompact.orchestrator.sovereign;

import com.stellarcompact.engine.action.Action;
import com.stellarcompact.engine.action.AgentResponse;
import com.stellarcompact.engine.config.BalanceProfile;
import com.stellarcompact.engine.state.BuildingType;
import com.stellarcompact.engine.state.FactionId;
import com.stellarcompact.engine.state.GameState;
import com.stellarcompact.engine.validation.ActionValidator;
import com.stellarcompact.engine.validation.ValidationResult;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link ScriptedSovereign}: every emitted action validates,
 * decisions are deterministic, the bot can drive many ticks without throwing, and
 * it holds when there is nothing useful to do.
 */
class ScriptedSovereignTest {

    private static final FactionId ALPHA = new FactionId("alpha");

    @Test
    void everyEmittedActionPassesTheEngineValidator() {
        GameState state = SovereignFixtures.richTwoFactionState();
        BalanceProfile profile = SovereignFixtures.profile();
        ScriptedSovereign bot = new ScriptedSovereign(ALPHA);

        WorldView view = WorldViewProjection.project(state, ALPHA);
        AgentResponse response = bot.decide(view);

        assertNotNull(response);
        assertFalse(response.actions().isEmpty(), "bot must emit at least one action");
        for (Action action : response.actions()) {
            ValidationResult result = ActionValidator.validate(state, ALPHA, action, profile);
            assertTrue(result.isValid(),
                    "expected valid action but got rejection for " + action.type()
                            + ": " + result);
        }
    }

    @Test
    void richStateLeadsTheBotToBuild() {
        GameState state = SovereignFixtures.richTwoFactionState();
        ScriptedSovereign bot = new ScriptedSovereign(ALPHA);

        AgentResponse response = bot.decide(WorldViewProjection.project(state, ALPHA));

        assertEquals(1, response.actions().size());
        assertInstanceOf(Action.Build.class, response.actions().get(0),
                "a rich faction with a free slot should build economy first");
        assertTrue(response.messages().isEmpty(), "the scripted bot is silent in negotiation");
    }

    @Test
    void sameInputProducesSameOutputAcrossRuns() {
        GameState state = SovereignFixtures.richTwoFactionState();
        ScriptedSovereign bot = new ScriptedSovereign(ALPHA);
        WorldView view = WorldViewProjection.project(state, ALPHA);

        AgentResponse first = bot.decide(view);
        AgentResponse second = bot.decide(view);
        // A fresh bot instance must agree too - no per-instance hidden state.
        AgentResponse third = new ScriptedSovereign(ALPHA).decide(view);

        assertEquals(first, second, "same WorldView must yield an equal AgentResponse");
        assertEquals(first, third, "a fresh bot must decide identically");
    }

    @Test
    void emitsHoldWhenThereIsNothingUsefulToDo() {
        // No free build slots, no fleets, no neutral neighbours -> Hold.
        GameState state = SovereignFixtures.idleState();
        ScriptedSovereign bot = new ScriptedSovereign(ALPHA);

        AgentResponse response = bot.decide(WorldViewProjection.project(state, ALPHA));

        assertEquals(1, response.actions().size());
        assertInstanceOf(Action.Hold.class, response.actions().get(0));
    }

    @Test
    void doesNotReExploreAnAlreadyRevealedNeighbourAndHolds() {
        // E10-01 (a): below the Minerals floor (build skipped); the only neutral
        // neighbour is already revealed and the fleet is parked at an OWN system (not
        // colonisable). The old ladder Explored the revealed neutral here every tick
        // (finding F1); the bot must now NOT Explore it and Hold instead.
        GameState state = SovereignFixtures.poorWithRevealedNeutralNeighbour();
        ScriptedSovereign bot = new ScriptedSovereign(ALPHA);

        AgentResponse response = bot.decide(WorldViewProjection.project(state, ALPHA));

        assertEquals(1, response.actions().size());
        assertFalse(response.actions().get(0) instanceof Action.Explore,
                "must not re-Explore an already-revealed neighbour");
        assertInstanceOf(Action.Hold.class, response.actions().get(0),
                "with nothing buildable/colonisable/explorable the bot holds explicitly");
    }

    @Test
    void colonisesAReachableNeutralWhenTheFrontierIsExhausted() {
        // E10-01 (b): poor (build skipped), the frontier is exhausted (the only neutral
        // is revealed) but the bot has an idle fleet parked AT that neutral, which
        // carries planets -> it colonises the lowest-id planet via the fleet.
        GameState state = SovereignFixtures.idleFleetParkedAtColonisableNeutral();
        ScriptedSovereign bot = new ScriptedSovereign(ALPHA);

        AgentResponse response = bot.decide(WorldViewProjection.project(state, ALPHA));

        assertEquals(1, response.actions().size());
        Action action = response.actions().get(0);
        assertInstanceOf(Action.Colonize.class, action);
        Action.Colonize colonize = (Action.Colonize) action;
        assertEquals(SovereignFixtures.PLANET_N1, colonize.planet(),
                "colonises the lowest-id planet of the neutral");
        assertEquals(SovereignFixtures.FLEET_A, colonize.viaFleet(),
                "delivers the colony with the fleet parked at the neutral");
        // And the engine validator accepts it.
        ValidationResult result = ActionValidator.validate(
                state, ALPHA, action, SovereignFixtures.profile());
        assertTrue(result.isValid(), "the emitted Colonize must validate: " + result);
    }

    @Test
    void holdsWhenNeitherBuildColoniseNorExploreApplies() {
        // E10-01 (c): no free slot, no fleet, no neutral neighbour -> explicit Hold,
        // never a redundant Explore.
        GameState state = SovereignFixtures.idleState();
        ScriptedSovereign bot = new ScriptedSovereign(ALPHA);

        AgentResponse response = bot.decide(WorldViewProjection.project(state, ALPHA));

        assertEquals(1, response.actions().size());
        assertInstanceOf(Action.Hold.class, response.actions().get(0));
    }

    // === E10-02 build-ladder (F2): which building TYPE the bot queues ===========

    /**
     * Helper: assert the bot's single action is a {@link Action.Build} of the expected
     * type on the lowest free slot of the lowest-id planet (placement unchanged from
     * E10-01).
     */
    private static void assertBuildsType(GameState state, BuildingType expected) {
        ScriptedSovereign bot = new ScriptedSovereign(ALPHA);
        AgentResponse response = bot.decide(WorldViewProjection.project(state, ALPHA));

        assertEquals(1, response.actions().size());
        assertInstanceOf(Action.Build.class, response.actions().get(0));
        Action.Build buildAction = (Action.Build) response.actions().get(0);
        assertEquals(expected, buildAction.buildingType(),
                "expected the heuristic to choose " + expected);
        assertEquals(SovereignFixtures.PLANET_A, buildAction.planet(),
                "builds on the lowest-id owned planet");
        assertEquals(0, buildAction.slot(), "builds in the lowest free slot");
    }

    @Test
    void buildsSolarArrayUnderEnergyPressure() {
        // F2 core fix: Energy at/below the floor (the oceanic-home starve scenario) ->
        // the bot builds the SOLAR_ARRAY, not yet-another MINE that would deepen the
        // energy deficit. Minerals and Food are ample, so only Energy drives the choice.
        assertBuildsType(SovereignFixtures.energyStarvedBuildState(), BuildingType.SOLAR_ARRAY);
    }

    @Test
    void buildsFarmUnderFoodPressure() {
        // Energy comfortable, Food at/below the floor -> FARM (Energy is checked first,
        // so this proves the Food branch is reached only when Energy is fine).
        assertBuildsType(SovereignFixtures.foodStarvedBuildState(), BuildingType.FARM);
    }

    @Test
    void buildsMineWhenNoResourceIsUnderPressure() {
        // Energy and Food both comfortably above their floors -> the default MINE.
        assertBuildsType(SovereignFixtures.comfortableBuildState(), BuildingType.MINE);
    }

    @Test
    void buildTypeChoiceIsDeterministicAcrossRunsAndFreshInstances() {
        GameState state = SovereignFixtures.energyStarvedBuildState();
        WorldView view = WorldViewProjection.project(state, ALPHA);
        ScriptedSovereign bot = new ScriptedSovereign(ALPHA);

        AgentResponse first = bot.decide(view);
        AgentResponse second = bot.decide(view);
        AgentResponse fresh = new ScriptedSovereign(ALPHA).decide(view);

        assertEquals(first, second, "same WorldView must yield an equal build choice");
        assertEquals(first, fresh, "a fresh bot must choose the same building type");
    }

    @Test
    void neverThrowsAndAlwaysReturnsNonEmptyAcrossManyDistinctViews() {
        ScriptedSovereign bot = new ScriptedSovereign(ALPHA);
        for (GameState state : SovereignFixtures.assortedStates()) {
            WorldView view = WorldViewProjection.project(state, ALPHA);
            AgentResponse response = bot.decide(view);
            assertNotNull(response);
            assertFalse(response.actions().isEmpty());
        }
    }
}
