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
        // richTwoFactionState holds 1000 Minerals (>= the E11-05 ship-economy pivot 200)
        // and owns no Shipyard, so the bot's first move is to queue the SHIPYARD - the
        // growth engine - on its free slot, spending the hoard rather than idling it.
        GameState state = SovereignFixtures.richTwoFactionState();
        ScriptedSovereign bot = new ScriptedSovereign(ALPHA);

        AgentResponse response = bot.decide(WorldViewProjection.project(state, ALPHA));

        assertEquals(1, response.actions().size());
        assertInstanceOf(Action.Build.class, response.actions().get(0),
                "a Mineral-rich faction with a free slot and no Shipyard should build one");
        assertEquals(BuildingType.SHIPYARD,
                ((Action.Build) response.actions().get(0)).buildingType(),
                "the ship-economy pivot queues a SHIPYARD, not yet-another economy building");
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

    // === E11-05 ship/colony economy (finding L5 / decision P4) ===================

    @Test
    void buildsAShipyardWhenMineralsAreHighAndItLacksOne() {
        // The mineral hoard (L5) has climbed past the ship-economy threshold and the
        // faction owns no Shipyard, so the bot spends a slot on the growth engine - a
        // SHIPYARD - rather than yet-another economy building. This is the mineral sink's
        // first rung: without a Shipyard there is nothing to construct ships with.
        GameState state = SovereignFixtures.mineralRichNoShipyardState();
        ScriptedSovereign bot = new ScriptedSovereign(ALPHA);

        AgentResponse response = bot.decide(WorldViewProjection.project(state, ALPHA));

        assertEquals(1, response.actions().size());
        Action action = response.actions().get(0);
        assertInstanceOf(Action.Build.class, action);
        Action.Build build = (Action.Build) action;
        assertEquals(BuildingType.SHIPYARD, build.buildingType(),
                "a Mineral-rich, shipyard-less faction must build a SHIPYARD");
        assertEquals(SovereignFixtures.PLANET_A, build.planet(),
                "builds on the lowest-id owned planet");
        assertEquals(0, build.slot(), "builds in the lowest free slot");
        // The engine validator accepts it.
        ValidationResult result = ActionValidator.validate(
                state, ALPHA, action, SovereignFixtures.profile());
        assertTrue(result.isValid(), "the emitted Shipyard Build must validate: " + result);
    }

    @Test
    void constructsAColonyShipWhenItHasAShipyardAndAReachableNeutral() {
        // Mineral-rich, already owns a Shipyard, and a reachable neutral neighbour exists
        // to expand toward (but no fleet parked there yet, so it is not colonisable this
        // tick). The bot spends the hoard constructing a colony ship via BuildFleet at the
        // Shipyard system - the mineral sink + growth path (P4).
        GameState state = SovereignFixtures.mineralRichWithShipyardAndNeutralState();
        ScriptedSovereign bot = new ScriptedSovereign(ALPHA);

        AgentResponse response = bot.decide(WorldViewProjection.project(state, ALPHA));

        assertEquals(1, response.actions().size());
        Action action = response.actions().get(0);
        assertInstanceOf(Action.BuildFleet.class, action);
        Action.BuildFleet buildFleet = (Action.BuildFleet) action;
        assertEquals(SovereignFixtures.SYS_A, buildFleet.system(),
                "constructs at the owned Shipyard system");
        assertEquals(ScriptedSovereign.DEFAULT_COLONY_SHIP_SPEC, buildFleet.shipSpec(),
                "constructs the configured colony-ship archetype");
        // The engine validator accepts it (owned system, active Shipyard, ungated spec).
        ValidationResult result = ActionValidator.validate(
                state, ALPHA, action, SovereignFixtures.profile());
        assertTrue(result.isValid(), "the emitted BuildFleet must validate: " + result);
    }

    @Test
    void colonisesAReachedNeutralBeforeConstructingMoreShips() {
        // With a Shipyard AND a fleet already parked at a colonisable neutral, finishing
        // expansion (Colonize) must rank ahead of laying down yet-another ship. The
        // shipyard-ed home planet is full, so only the Colonise-vs-construct ordering is
        // under test here.
        GameState state = SovereignFixtures.shipyardWithFleetParkedAtColonisableNeutral();
        ScriptedSovereign bot = new ScriptedSovereign(ALPHA);

        AgentResponse response = bot.decide(WorldViewProjection.project(state, ALPHA));

        assertEquals(1, response.actions().size());
        assertInstanceOf(Action.Colonize.class, response.actions().get(0),
                "an already-reached neutral is settled before more ships are built");
    }

    @Test
    void poorFactionStillDoesTheEarlyGameThingNotTheShipEconomy() {
        // Below the build floor entirely: no shipyard pivot, no economy build, no colony
        // ship (the rungs are all Mineral-gated). With nothing buildable/colonisable/
        // explorable the bot Holds - early-game behaviour is untouched by E11-05.
        GameState state = SovereignFixtures.poorWithRevealedNeutralNeighbour();
        ScriptedSovereign bot = new ScriptedSovereign(ALPHA);

        AgentResponse response = bot.decide(WorldViewProjection.project(state, ALPHA));

        assertEquals(1, response.actions().size());
        Action action = response.actions().get(0);
        assertFalse(action instanceof Action.Build,
                "a poor faction must not build (shipyard or economy)");
        assertFalse(action instanceof Action.BuildFleet,
                "a poor faction must not construct ships");
        assertInstanceOf(Action.Hold.class, action,
                "with nothing affordable the bot holds, exactly as before E11-05");
    }

    @Test
    void shipEconomyDecisionsAreDeterministicAcrossRunsAndFreshInstances() {
        GameState state = SovereignFixtures.mineralRichWithShipyardAndNeutralState();
        WorldView view = WorldViewProjection.project(state, ALPHA);
        ScriptedSovereign bot = new ScriptedSovereign(ALPHA);

        AgentResponse first = bot.decide(view);
        AgentResponse second = bot.decide(view);
        AgentResponse fresh = new ScriptedSovereign(ALPHA).decide(view);

        assertEquals(first, second, "same WorldView must yield an equal ship-economy choice");
        assertEquals(first, fresh, "a fresh bot must decide the ship economy identically");
    }

    // === E12-01 multi-tick plan memory (scout -> colonise -> fortify, finding P7a) ===

    @Test
    void commitsToAnExpansionTargetAndRecordsThePhase() {
        // Two colonisable neutrals exist; the bot must commit to the canonical
        // (lowest-id) one and record that it is pursuing it - not stay planless.
        GameState state = SovereignFixtures.twoColonisableNeutralsState();
        ScriptedSovereign bot = new ScriptedSovereign(ALPHA);

        AgentResponse response = bot.decide(WorldViewProjection.project(state, ALPHA));

        assertInstanceOf(Action.Colonize.class, response.actions().get(0));
        assertEquals(java.util.Optional.of(SovereignFixtures.SYS_NEUTRAL), bot.plan().coloniseTarget(),
                "the bot commits to the lowest-id colonisable neutral as its plan target");
    }

    @Test
    void followsThroughOnTheSameColoniseTargetAcrossTicks() {
        // The lower-id neutral (sysN) is colonisable. The bot commits to it and, fed the
        // same view sequence, keeps colonising THAT system tick after tick - it does not
        // flip to the other reachable neutral (sysN2). This is the multi-tick commitment.
        ScriptedSovereign bot = new ScriptedSovereign(ALPHA);
        WorldView view = WorldViewProjection.project(
                SovereignFixtures.twoColonisableNeutralsState(), ALPHA);

        for (int tick = 0; tick < 5; tick++) {
            AgentResponse response = bot.decide(view);
            assertEquals(1, response.actions().size());
            Action action = response.actions().get(0);
            assertInstanceOf(Action.Colonize.class, action, "tick " + tick + " must colonise");
            assertEquals(SovereignFixtures.PLANET_N1, ((Action.Colonize) action).planet(),
                    "tick " + tick + ": stays committed to sysN's planet, never flips to sysN2");
            assertEquals(java.util.Optional.of(SovereignFixtures.SYS_NEUTRAL),
                    bot.plan().coloniseTarget(), "tick " + tick + ": target unchanged");
        }
    }

    @Test
    void recoversWhenItsCommittedTargetIsTakenByARival() {
        // Tick 1: commit to sysN (lowest-id colonisable). Tick 2: the WorldView now shows
        // sysN owned by BETA (plan contradicted) and only sysN2 still colonisable. The bot
        // must reconcile, forget sysN, and recover onto sysN2 - colonising its planet.
        ScriptedSovereign bot = new ScriptedSovereign(ALPHA);

        WorldView before = WorldViewProjection.project(
                SovereignFixtures.twoColonisableNeutralsState(), ALPHA);
        bot.decide(before);
        assertEquals(java.util.Optional.of(SovereignFixtures.SYS_NEUTRAL),
                bot.plan().coloniseTarget(), "precondition: committed to sysN");

        WorldView after = WorldViewProjection.project(
                SovereignFixtures.neutralTakenByRivalState(), ALPHA);
        AgentResponse response = bot.decide(after);

        assertEquals(1, response.actions().size());
        Action action = response.actions().get(0);
        assertInstanceOf(Action.Colonize.class, action,
                "after losing its target the bot recovers onto the surviving neutral");
        assertEquals(SovereignFixtures.PLANET_M1, ((Action.Colonize) action).planet(),
                "recovers onto sysN2's planet, the only live target left");
        assertEquals(java.util.Optional.of(SovereignFixtures.SYS_NEUTRAL2),
                bot.plan().coloniseTarget(), "the plan now tracks sysN2");
        ValidationResult result = ActionValidator.validate(
                SovereignFixtures.neutralTakenByRivalState(), ALPHA, action,
                SovereignFixtures.profile());
        assertTrue(result.isValid(), "the recovery Colonize must validate: " + result);
    }

    @Test
    void clearsThePlanWhenTheFrontierOffersNothing() {
        // No neutral frontier at all (idle single-system state) -> no target to commit,
        // the plan stays empty, and the bot Holds. Memory must not invent a target.
        GameState state = SovereignFixtures.idleState();
        ScriptedSovereign bot = new ScriptedSovereign(ALPHA);

        AgentResponse response = bot.decide(WorldViewProjection.project(state, ALPHA));

        assertInstanceOf(Action.Hold.class, response.actions().get(0));
        assertTrue(bot.plan().coloniseTarget().isEmpty(),
                "with no frontier the bot commits to nothing");
    }

    @Test
    void planDrivenDecisionsAreDeterministicAcrossRunsAndFreshInstances() {
        // The plan memory must not break replay-stability: a fresh bot fed the same
        // single view, and an existing bot fed it twice, must agree (commit is idempotent
        // against an unchanged view).
        WorldView view = WorldViewProjection.project(
                SovereignFixtures.twoColonisableNeutralsState(), ALPHA);
        ScriptedSovereign bot = new ScriptedSovereign(ALPHA);

        AgentResponse first = bot.decide(view);
        AgentResponse second = bot.decide(view);
        AgentResponse fresh = new ScriptedSovereign(ALPHA).decide(view);

        assertEquals(first, second, "same WorldView must yield an equal plan-driven choice");
        assertEquals(first, fresh, "a fresh bot must reconstruct the same committed plan");
    }
}
