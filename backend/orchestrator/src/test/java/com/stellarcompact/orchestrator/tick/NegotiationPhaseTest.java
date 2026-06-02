package com.stellarcompact.orchestrator.tick;

import com.stellarcompact.engine.action.Action;
import com.stellarcompact.engine.action.AgentResponse;
import com.stellarcompact.engine.map.LaneNetwork;
import com.stellarcompact.engine.state.ActiveSystem;
import com.stellarcompact.engine.state.Biome;
import com.stellarcompact.engine.state.Coords;
import com.stellarcompact.engine.state.Faction;
import com.stellarcompact.engine.state.FactionId;
import com.stellarcompact.engine.state.Fleet;
import com.stellarcompact.engine.state.FleetId;
import com.stellarcompact.engine.state.GameState;
import com.stellarcompact.engine.state.GameStatus;
import com.stellarcompact.engine.state.MarketOrder;
import com.stellarcompact.engine.state.MarketOrderId;
import com.stellarcompact.engine.state.MarketSide;
import com.stellarcompact.engine.state.OrderStatus;
import com.stellarcompact.engine.state.PhysicalResource;
import com.stellarcompact.engine.state.Planet;
import com.stellarcompact.engine.state.PlanetId;
import com.stellarcompact.engine.state.ResourceBundle;
import com.stellarcompact.engine.state.Route;
import com.stellarcompact.engine.state.RouteId;
import com.stellarcompact.engine.state.SystemId;
import com.stellarcompact.engine.state.TechId;
import com.stellarcompact.engine.state.TechProgress;
import com.stellarcompact.engine.state.Treaty;
import com.stellarcompact.engine.state.TreatyId;
import com.stellarcompact.engine.state.WarState;
import com.stellarcompact.orchestrator.sovereign.Inbox;
import com.stellarcompact.orchestrator.sovereign.Sovereign;
import com.stellarcompact.orchestrator.sovereign.SystemAdjacency;
import com.stellarcompact.orchestrator.sovereign.WorldView;
import com.stellarcompact.orchestrator.sovereign.WorldViewBuilder;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the negotiation phase (board card E4-06): messages reach recipients next
 * view, pending proposals persist across ticks, and the round count is config-driven.
 * Free text is proven effect-free (it is never an engine input).
 */
class NegotiationPhaseTest {

    private static final FactionId ALPHA = new FactionId("alpha");
    private static final FactionId BETA = new FactionId("beta");

    /**
     * A bot that records every WorldView it perceives and sends its fixed message
     * exactly ONCE (on its first decide call), so message counts in a recipient view
     * are deterministic across rounds/ticks. It always Holds (no engine effect).
     */
    private record RecordingSovereign(
            FactionId id, FactionId messageTo, String messageText,
            CopyOnWriteArrayList<WorldView> seen,
            java.util.concurrent.atomic.AtomicBoolean sentOnce) implements Sovereign {
        @Override
        public FactionId factionId() {
            return id;
        }

        @Override
        public AgentResponse decide(WorldView view) {
            seen.add(view);
            boolean send = messageTo != null && sentOnce.compareAndSet(false, true);
            List<AgentResponse.Message> msgs = send
                    ? List.of(new AgentResponse.Message(messageTo, messageText))
                    : List.of();
            return AgentResponse.now(msgs, List.of(new Action.Hold()));
        }
    }

    private static RecordingSovereign sender(FactionId id, FactionId to, String text,
                                             CopyOnWriteArrayList<WorldView> seen) {
        return new RecordingSovereign(id, to, text, seen,
                new java.util.concurrent.atomic.AtomicBoolean(false));
    }

    private static RecordingSovereign silent(FactionId id, CopyOnWriteArrayList<WorldView> seen) {
        return new RecordingSovereign(id, null, null, seen,
                new java.util.concurrent.atomic.AtomicBoolean(false));
    }

    @Test
    void aMessageFromAtoBReachesBsNextTickView() {
        GameState state = twoFactionState(0L);
        CopyOnWriteArrayList<WorldView> betaViews = new CopyOnWriteArrayList<>();
        Seat alpha = new Seat.ScriptedSeat(
                sender(ALPHA, BETA, "trade-me-energy", new CopyOnWriteArrayList<>()));
        Seat beta = new Seat.ScriptedSeat(silent(BETA, betaViews));

        TickOrchestrator orch = new TickOrchestrator(timings(1));

        TickResult t0 = orch.runTick(List.of(alpha, beta), ctx(state, Inbox.EMPTY));
        assertEquals(1, t0.messages().size(), "ALPHA message gathered this tick");

        Inbox nextInbox = Inbox.route(Inbox.EMPTY, ALPHA, t0.messages(), t0.tick());
        GameState next = t0.resolvedState().withTick(t0.tick() + 1);

        orch.runTick(List.of(alpha, beta), ctx(next, nextInbox));

        WorldView betaLatest = betaViews.get(betaViews.size() - 1);
        assertEquals(1, betaLatest.inbox().size(), "BETA sees exactly the one delivered message");
        WorldView.InboxMessage m = betaLatest.inbox().get(0);
        assertEquals(ALPHA, m.from());
        assertEquals("trade-me-energy", m.text());
    }

    @Test
    void aRoundOneMessageIsVisibleInRoundTwoOfTheSameTick() {
        GameState state = twoFactionState(0L);
        CopyOnWriteArrayList<WorldView> betaViews = new CopyOnWriteArrayList<>();
        Seat alpha = new Seat.ScriptedSeat(
                sender(ALPHA, BETA, "round1-hello", new CopyOnWriteArrayList<>()));
        Seat beta = new Seat.ScriptedSeat(silent(BETA, betaViews));

        TickOrchestrator orch = new TickOrchestrator(timings(2));
        orch.runTick(List.of(alpha, beta), ctx(state, Inbox.EMPTY));

        // BETA perceived three times this tick: negotiation round 0, round 1, then action.
        assertEquals(3, betaViews.size(), "2 negotiation rounds + 1 action => 3 perceptions");
        assertTrue(betaViews.get(0).inbox().isEmpty(), "round 0: nothing delivered yet");
        assertEquals(1, betaViews.get(1).inbox().size(), "round 1: ALPHA round-0 message arrived");
        assertEquals("round1-hello", betaViews.get(1).inbox().get(0).text());
        // The message remains visible through the action phase too (same delivered inbox).
        assertEquals(1, betaViews.get(2).inbox().size(), "action phase: message still delivered");
    }

    @Test
    void roundCountIsReadFromConfig() {
        for (int rounds : new int[]{0, 1, 3}) {
            GameState state = twoFactionState(0L);
            CopyOnWriteArrayList<WorldView> alphaViews = new CopyOnWriteArrayList<>();
            Seat alpha = new Seat.ScriptedSeat(silent(ALPHA, alphaViews));
            Seat beta = new Seat.ScriptedSeat(silent(BETA, new CopyOnWriteArrayList<>()));

            TickOrchestrator orch = new TickOrchestrator(timings(rounds));
            orch.runTick(List.of(alpha, beta), ctx(state, Inbox.EMPTY));

            assertEquals(rounds + 1, alphaViews.size(),
                    "negotiation-rounds=" + rounds + " => that many negotiate + 1 action perceptions");
        }
    }

    @Test
    void zeroRoundsDeliversNoMessages() {
        GameState state = twoFactionState(0L);
        Seat alpha = new Seat.ScriptedSeat(
                sender(ALPHA, BETA, "ignored", new CopyOnWriteArrayList<>()));
        Seat beta = new Seat.ScriptedSeat(silent(BETA, new CopyOnWriteArrayList<>()));

        TickOrchestrator orch = new TickOrchestrator(timings(0));
        TickResult result = orch.runTick(List.of(alpha, beta), ctx(state, Inbox.EMPTY));

        assertTrue(result.messages().isEmpty(),
                "0 negotiation rounds => no Sovereign negotiates => no messages");
    }

    @Test
    void aPendingOfferPersistsAndStaysVisibleUntilExpiry() {
        long placed = 0L;
        long expires = 3L;
        MarketOrderId offerId = new MarketOrderId("offer-1");
        MarketOrder offer = new MarketOrder(
                offerId, new SystemId("sysA"), ALPHA, MarketSide.SELL,
                PhysicalResource.ENERGY, 100.0, 5.0, placed, expires,
                Optional.of(BETA), OrderStatus.OPEN);

        GameState state = twoFactionState(placed)
                .withMarketOrders(Map.of(offerId, offer));

        CopyOnWriteArrayList<WorldView> betaViews = new CopyOnWriteArrayList<>();
        Seat alpha = new Seat.ScriptedSeat(silent(ALPHA, new CopyOnWriteArrayList<>()));
        Seat beta = new Seat.ScriptedSeat(silent(BETA, betaViews));

        TickOrchestrator orch = new TickOrchestrator(timings(0));

        for (long t = placed; t <= expires; t++) {
            TickResult r = orch.runTick(List.of(alpha, beta), ctx(state, Inbox.EMPTY));
            WorldView betaView = betaViews.get(betaViews.size() - 1);
            List<WorldView.OfferView> offers = betaView.pendingOffers();
            assertEquals(1, offers.size(), "tick " + t + ": BETA still sees the unaccepted offer");
            assertEquals(offerId, offers.get(0).id());
            assertEquals(ALPHA, offers.get(0).from());
            assertEquals(expires, offers.get(0).expiresTick());
            state = r.resolvedState().withTick(r.tick() + 1);
        }

        assertTrue(state.marketOrders().containsKey(offerId),
                "the pending proposal persisted across all ticks until expiry");
    }

    @Test
    void freeTextHasNoMechanicalEffect() {
        GameState chattyState = twoFactionState(0L);
        Seat chattyAlpha = new Seat.ScriptedSeat(
                sender(ALPHA, BETA, "give-me-everything", new CopyOnWriteArrayList<>()));
        Seat silentBeta = new Seat.ScriptedSeat(silent(BETA, new CopyOnWriteArrayList<>()));
        TickOrchestrator orch = new TickOrchestrator(timings(2));
        TickResult chatty = orch.runTick(List.of(chattyAlpha, silentBeta), ctx(chattyState, Inbox.EMPTY));

        GameState quietState = twoFactionState(0L);
        Seat quietAlpha = new Seat.ScriptedSeat(silent(ALPHA, new CopyOnWriteArrayList<>()));
        Seat silentBeta2 = new Seat.ScriptedSeat(silent(BETA, new CopyOnWriteArrayList<>()));
        TickResult quiet = orch.runTick(List.of(quietAlpha, silentBeta2), ctx(quietState, Inbox.EMPTY));

        assertFalse(chatty.messages().isEmpty(), "chatty run exchanged a message");
        assertTrue(quiet.messages().isEmpty(), "quiet run exchanged none");
        ResourceBundle chattyBeta = chatty.resolvedState().factions().get(BETA).stockpiles();
        ResourceBundle quietBeta = quiet.resolvedState().factions().get(BETA).stockpiles();
        assertEquals(quietBeta, chattyBeta, "free-text negotiation must not change any stockpile");
    }

    @Test
    void builderSurfacesDeliveredInboxToTheRecipientOnly() {
        GameState state = twoFactionState(0L);
        WorldView.InboxMessage forBeta = new WorldView.InboxMessage(ALPHA, "psst", 0L);

        WorldView betaView = WorldViewBuilder.build(
                state, SystemAdjacency.NONE, BETA, List.of(forBeta));
        assertEquals(1, betaView.inbox().size());
        assertEquals("psst", betaView.inbox().get(0).text());

        WorldView alphaView = WorldViewBuilder.build(state, SystemAdjacency.NONE, ALPHA, List.of());
        assertTrue(alphaView.inbox().isEmpty(), "a message to BETA never leaks into ALPHA view");
    }

    private static TickContext ctx(GameState state, Inbox inbox) {
        return new TickContext(state, TickFixtures.profile(), LaneNetwork.EMPTY,
                SystemAdjacency.NONE, inbox);
    }

    private static TickProperties timings(int rounds) {
        return new TickProperties(Duration.ofSeconds(5), Duration.ofSeconds(2),
                Duration.ofSeconds(2), rounds);
    }

    private static ResourceBundle rich() {
        return new ResourceBundle(1000, 1000, 1000, 1000, 1000);
    }

    private static Faction faction(FactionId id) {
        return new Faction(id, "F-" + id.value(), 0.0, rich(), Map.<TechId, TechProgress>of());
    }

    private static ActiveSystem ownedSystem(SystemId id, FactionId owner) {
        Planet full = new Planet(new PlanetId("p-" + id.value()), Biome.TERRAN, 0, 100, List.of());
        return new ActiveSystem(id, "name-" + id.value(), new Coords(0, 0),
                Optional.of(owner), List.of(full), 100, 1.0);
    }

    private static GameState twoFactionState(long tick) {
        Map<FactionId, Faction> factions = new LinkedHashMap<>();
        factions.put(ALPHA, faction(ALPHA));
        factions.put(BETA, faction(BETA));
        Map<SystemId, ActiveSystem> systems = new LinkedHashMap<>();
        systems.put(new SystemId("sysA"), ownedSystem(new SystemId("sysA"), ALPHA));
        systems.put(new SystemId("sysB"), ownedSystem(new SystemId("sysB"), BETA));
        return new GameState(
                42L, tick, GameStatus.RUNNING, "small-default", 1,
                factions, systems,
                Map.<FleetId, Fleet>of(),
                Map.<TreatyId, Treaty>of(),
                Map.<RouteId, Route>of(),
                Map.<MarketOrderId, MarketOrder>of(),
                Set.<WarState>of());
    }
}
