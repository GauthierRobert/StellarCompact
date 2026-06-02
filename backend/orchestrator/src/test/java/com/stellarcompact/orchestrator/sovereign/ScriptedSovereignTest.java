package com.stellarcompact.orchestrator.sovereign;

import com.stellarcompact.engine.action.Action;
import com.stellarcompact.engine.action.AgentResponse;
import com.stellarcompact.engine.config.BalanceProfile;
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
    void poorFactionDoesNotBuildAndFallsThroughTheLadder() {
        // Below the Minerals floor: the build step is skipped. With an idle fleet and
        // a neutral neighbour, the bot explores instead.
        GameState state = SovereignFixtures.poorWithIdleFleetAndNeutralNeighbour();
        ScriptedSovereign bot = new ScriptedSovereign(ALPHA);

        AgentResponse response = bot.decide(WorldViewProjection.project(state, ALPHA));

        assertEquals(1, response.actions().size());
        assertInstanceOf(Action.Explore.class, response.actions().get(0));
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
