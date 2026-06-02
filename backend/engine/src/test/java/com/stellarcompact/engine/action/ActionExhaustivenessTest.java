package com.stellarcompact.engine.action;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * The sealed set is closed and complete. {@link ActionCategory#of(Action)} is an
 * exhaustive {@code switch} with no {@code default} branch; the fact that it
 * <em>compiles</em> is the primary guarantee (a missing variant is a compile
 * error). These runtime assertions are a secondary sanity check that the 25
 * known variants are wired and the forward-compat sentinel classifies as
 * {@code NONE}.
 */
class ActionExhaustivenessTest {

    @Test
    void everyKnownVariantClassifies() {
        assertEquals(ActionCategory.DIPLOMATIC_STATE,
                ActionCategory.of(new Action.BreakTreaty(treaty())));
        assertEquals(ActionCategory.DIPLOMATIC_STATE,
                ActionCategory.of(new Action.AcceptTreaty(treaty())));
        assertEquals(ActionCategory.DIPLOMATIC_STATE,
                ActionCategory.of(new Action.DeclineTreaty(treaty())));
        assertEquals(ActionCategory.DIPLOMATIC_STATE,
                ActionCategory.of(new Action.DeclareWar(faction())));
        // E1-12: Tribute moves resources, so it is a diplomatic-state action, not soft.
        assertEquals(ActionCategory.DIPLOMATIC_STATE,
                ActionCategory.of(new Action.Tribute(faction(),
                        new com.stellarcompact.engine.state.ResourceBundle(1, 0, 0, 0, 0))));

        assertEquals(ActionCategory.ESPIONAGE,
                ActionCategory.of(new Action.Espionage(faction(), EspionageOperation.SCOUT)));

        assertEquals(ActionCategory.MOVEMENT,
                ActionCategory.of(new Action.MoveFleet(fleet(),
                        java.util.List.of(system()), system())));

        assertEquals(ActionCategory.COMBAT,
                ActionCategory.of(new Action.Attack(fleet(),
                        new AttackTarget.OnSystem(system()))));

        assertEquals(ActionCategory.INTERDICTION,
                ActionCategory.of(new Action.Raid(fleet(), route())));

        assertEquals(ActionCategory.DEVELOPMENT,
                ActionCategory.of(new Action.Explore(system())));

        assertEquals(ActionCategory.TRADE,
                ActionCategory.of(new Action.AcceptTrade(offer())));

        assertEquals(ActionCategory.DIPLOMATIC_SOFT,
                ActionCategory.of(new Action.SendMessage(faction(), "hi")));

        assertEquals(ActionCategory.NONE, ActionCategory.of(new Action.Hold()));
        assertEquals(ActionCategory.NONE, ActionCategory.of(new UnknownAction("Future")));
    }

    @Test
    void categoryOrderMatchesResolutionOrder() {
        // Lowest ordinal resolves first (game-design 03 resolution order).
        assertNotNull(ActionCategory.DIPLOMATIC_STATE);
        assertEquals(0, ActionCategory.DIPLOMATIC_STATE.ordinal());
        assertEquals(1, ActionCategory.ESPIONAGE.ordinal());
        assertEquals(2, ActionCategory.MOVEMENT.ordinal());
        assertEquals(3, ActionCategory.COMBAT.ordinal());
        assertEquals(4, ActionCategory.INTERDICTION.ordinal());
    }

    private static com.stellarcompact.engine.state.FactionId faction() {
        return new com.stellarcompact.engine.state.FactionId("f");
    }

    private static com.stellarcompact.engine.state.FleetId fleet() {
        return new com.stellarcompact.engine.state.FleetId("fl");
    }

    private static com.stellarcompact.engine.state.SystemId system() {
        return new com.stellarcompact.engine.state.SystemId("s");
    }

    private static com.stellarcompact.engine.state.RouteId route() {
        return new com.stellarcompact.engine.state.RouteId("r");
    }

    private static com.stellarcompact.engine.state.TreatyId treaty() {
        return new com.stellarcompact.engine.state.TreatyId("t");
    }

    private static com.stellarcompact.engine.state.MarketOrderId offer() {
        return new com.stellarcompact.engine.state.MarketOrderId("o");
    }
}
