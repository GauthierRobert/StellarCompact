package com.stellarcompact.engine.resolve;

import com.stellarcompact.engine.action.Action;
import com.stellarcompact.engine.config.BalanceProfile;
import com.stellarcompact.engine.hash.GoldenStateHash;
import com.stellarcompact.engine.state.ActiveSystem;
import com.stellarcompact.engine.state.Coords;
import com.stellarcompact.engine.state.Faction;
import com.stellarcompact.engine.state.FactionId;
import com.stellarcompact.engine.state.GameState;
import com.stellarcompact.engine.state.GameStatus;
import com.stellarcompact.engine.state.MarketOrder;
import com.stellarcompact.engine.state.MarketOrderId;
import com.stellarcompact.engine.state.MarketSide;
import com.stellarcompact.engine.state.OrderStatus;
import com.stellarcompact.engine.state.PhysicalResource;
import com.stellarcompact.engine.state.ResourceBundle;
import com.stellarcompact.engine.state.SystemId;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Golden + behavioural tests for the per-hub order-book matcher (E1-07): crossing,
 * non-crossing, partial fills, clearing at the resting (maker) price, per-hub price
 * differences, escrow over-offer prevention, and deterministic (price-time)
 * matching that is insensitive to the book's map iteration order.
 *
 * <p>Hand-authored, deterministic states; no RNG, no I/O. Trades run through the
 * full {@link Resolver} so the SpendLedger settle pass is exercised end-to-end, and
 * a couple of cases call {@link MarketResolution} directly to inspect the resting
 * book it produces.
 */
class MarketResolutionTest {

    private static final FactionId ALPHA = new FactionId("alpha");
    private static final FactionId BETA = new FactionId("beta");
    private static final SystemId HUB1 = new SystemId("hub1");
    private static final SystemId HUB2 = new SystemId("hub2");

    private static final BalanceProfile PROFILE = profileWithCurrency("ENERGY");

    // ===== helpers =============================================================

    private static BalanceProfile profileWithCurrency(String currency) {
        BalanceProfile base = ResolveFixtures.profile();
        return new BalanceProfile(base.name(), base.version(), base.resources(),
                base.population(),
                new BalanceProfile.Market("priceTimePriority", 0.5, currency),
                base.construction(), base.combat(), base.tech(), base.diplomacy(),
                base.victory(), base.tick());
    }

    private static Faction faction(FactionId id, ResourceBundle stock) {
        return new Faction(id, "F-" + id.value(), 0.0, stock, Map.of());
    }

    private static ResourceBundle stock(double energy, double minerals, double food, double tech) {
        return new ResourceBundle(energy, minerals, food, tech, 0);
    }

    private static MarketOrder open(String id, SystemId hub, FactionId who, MarketSide side,
                                    PhysicalResource res, double qty, double price, long tick) {
        return new MarketOrder(new MarketOrderId(id), hub, who, side, res, qty, price,
                tick, 1_000L, Optional.empty(), OrderStatus.OPEN);
    }

    private static GameState state(Map<FactionId, Faction> factions, MarketOrder... orders) {
        ActiveSystem h1 = new ActiveSystem(HUB1, "hub-1", new Coords(0, 0),
                Optional.empty(), List.of(), 0, 1.0);
        ActiveSystem h2 = new ActiveSystem(HUB2, "hub-2", new Coords(1, 1),
                Optional.empty(), List.of(), 0, 1.0);
        Map<MarketOrderId, MarketOrder> book = new LinkedHashMap<>();
        for (MarketOrder o : orders) {
            book.put(o.id(), o);
        }
        return new GameState(7L, 5L, GameStatus.RUNNING, "small-default", 1,
                factions, Map.of(HUB1, h1, HUB2, h2), Map.of(), Map.of(), Map.of(), book);
    }

    private static GameState resolveMarket(GameState before) {
        return Resolver.resolve(before, List.of(), PROFILE, before.gameSeed());
    }

    private static MarketOrder order(GameState s, String id) {
        return s.marketOrders().get(new MarketOrderId(id));
    }

    // ===== crossing ============================================================

    @Test
    void crossingOrdersFillFullyAndClearAtRestingMakerPrice() {
        // SELL (maker, placed tick 1) @ 2.0; BUY (taker, tick 2) @ 3.0 -> clears @ 2.0.
        Map<FactionId, Faction> f = Map.of(
                ALPHA, faction(ALPHA, stock(0, 10, 0, 0)),   // seller holds 10 minerals
                BETA, faction(BETA, stock(100, 0, 0, 0)));    // buyer holds 100 energy
        GameState before = state(f,
                open("s", HUB1, ALPHA, MarketSide.SELL, PhysicalResource.MINERALS, 10, 2.0, 1),
                open("b", HUB1, BETA, MarketSide.BUY, PhysicalResource.MINERALS, 10, 3.0, 2));

        GameState after = resolveMarket(before);

        assertEquals(OrderStatus.FILLED, order(after, "s").status());
        assertEquals(OrderStatus.FILLED, order(after, "b").status());
        // Clears at the maker (resting SELL) price 2.0: 10 minerals for 20 energy.
        Faction alpha = after.factions().get(ALPHA);
        Faction beta = after.factions().get(BETA);
        assertEquals(20.0, alpha.stockpiles().energy(), 1e-9, "seller receives qty*price energy");
        assertEquals(0.0, alpha.stockpiles().minerals(), 1e-9, "seller gives all minerals");
        assertEquals(80.0, beta.stockpiles().energy(), 1e-9, "buyer pays qty*price energy");
        assertEquals(10.0, beta.stockpiles().minerals(), 1e-9, "buyer receives the goods");
    }

    @Test
    void clearsAtRestingBuyPriceWhenBuyIsTheMaker() {
        // BUY (maker, tick 1) @ 3.0; SELL (taker, tick 2) @ 2.0 -> clears @ 3.0.
        Map<FactionId, Faction> f = Map.of(
                ALPHA, faction(ALPHA, stock(0, 10, 0, 0)),
                BETA, faction(BETA, stock(100, 0, 0, 0)));
        GameState before = state(f,
                open("b", HUB1, BETA, MarketSide.BUY, PhysicalResource.MINERALS, 10, 3.0, 1),
                open("s", HUB1, ALPHA, MarketSide.SELL, PhysicalResource.MINERALS, 10, 2.0, 2));

        GameState after = resolveMarket(before);
        Faction alpha = after.factions().get(ALPHA);
        Faction beta = after.factions().get(BETA);
        assertEquals(30.0, alpha.stockpiles().energy(), 1e-9, "clears at resting BUY price 3.0");
        assertEquals(70.0, beta.stockpiles().energy(), 1e-9);
    }

    // ===== non-crossing ========================================================

    @Test
    void nonCrossingOrdersRestUntouched() {
        Map<FactionId, Faction> f = Map.of(
                ALPHA, faction(ALPHA, stock(0, 10, 0, 0)),
                BETA, faction(BETA, stock(100, 0, 0, 0)));
        GameState before = state(f,
                open("s", HUB1, ALPHA, MarketSide.SELL, PhysicalResource.MINERALS, 10, 5.0, 1),
                open("b", HUB1, BETA, MarketSide.BUY, PhysicalResource.MINERALS, 10, 3.0, 2));

        GameState after = resolveMarket(before);

        assertEquals(OrderStatus.OPEN, order(after, "s").status());
        assertEquals(OrderStatus.OPEN, order(after, "b").status());
        // No fills: stockpiles unchanged, state hash identical apart from nothing.
        assertEquals(10.0, after.factions().get(ALPHA).stockpiles().minerals(), 1e-9);
        assertEquals(100.0, after.factions().get(BETA).stockpiles().energy(), 1e-9);
    }

    // ===== partial fill ========================================================

    @Test
    void partialFillLeavesMakerRemainderResting() {
        // SELL 10 (maker @2.0); BUY 4 @3.0 -> trade 4, sell PARTIALLY_FILLED (6 left).
        Map<FactionId, Faction> f = Map.of(
                ALPHA, faction(ALPHA, stock(0, 10, 0, 0)),
                BETA, faction(BETA, stock(100, 0, 0, 0)));
        GameState before = state(f,
                open("s", HUB1, ALPHA, MarketSide.SELL, PhysicalResource.MINERALS, 10, 2.0, 1),
                open("b", HUB1, BETA, MarketSide.BUY, PhysicalResource.MINERALS, 4, 3.0, 2));

        GameState after = resolveMarket(before);

        assertEquals(OrderStatus.PARTIALLY_FILLED, order(after, "s").status());
        assertEquals(6.0, order(after, "s").quantity(), 1e-9, "6 minerals remain resting");
        assertEquals(OrderStatus.FILLED, order(after, "b").status());
        assertEquals(8.0, after.factions().get(ALPHA).stockpiles().energy(), 1e-9, "4 * 2.0");
        assertEquals(4.0, after.factions().get(BETA).stockpiles().minerals(), 1e-9);
    }

    // ===== per-hub pricing =====================================================

    @Test
    void pricesDifferPerHubFromTheirOwnBooks() {
        // Two hubs, identical resource, different maker prices -> different clears.
        Map<FactionId, Faction> f = Map.of(
                ALPHA, faction(ALPHA, stock(0, 20, 0, 0)),
                BETA, faction(BETA, stock(200, 0, 0, 0)));
        GameState before = state(f,
                // hub1 sell maker @ 2.0
                open("s1", HUB1, ALPHA, MarketSide.SELL, PhysicalResource.MINERALS, 5, 2.0, 1),
                open("b1", HUB1, BETA, MarketSide.BUY, PhysicalResource.MINERALS, 5, 4.0, 2),
                // hub2 sell maker @ 7.0
                open("s2", HUB2, ALPHA, MarketSide.SELL, PhysicalResource.MINERALS, 5, 7.0, 1),
                open("b2", HUB2, BETA, MarketSide.BUY, PhysicalResource.MINERALS, 5, 9.0, 2));

        GameState after = resolveMarket(before);

        assertEquals(OrderStatus.FILLED, order(after, "s1").status());
        assertEquals(OrderStatus.FILLED, order(after, "s2").status());
        // hub1 clears 5@2.0 = 10 energy; hub2 clears 5@7.0 = 35 energy; seller total 45.
        assertEquals(45.0, after.factions().get(ALPHA).stockpiles().energy(), 1e-9,
                "two hubs cleared at their own distinct prices (10 + 35)");
        assertEquals(155.0, after.factions().get(BETA).stockpiles().energy(), 1e-9);
    }

    // ===== escrow over-offer ===================================================

    @Test
    void escrowClampsSellToGoodsHeldNoNegativeStockpile() {
        // Seller posts SELL 10 but only holds 3 minerals -> only 3 can settle.
        Map<FactionId, Faction> f = Map.of(
                ALPHA, faction(ALPHA, stock(0, 3, 0, 0)),
                BETA, faction(BETA, stock(100, 0, 0, 0)));
        GameState before = state(f,
                open("s", HUB1, ALPHA, MarketSide.SELL, PhysicalResource.MINERALS, 10, 2.0, 1),
                open("b", HUB1, BETA, MarketSide.BUY, PhysicalResource.MINERALS, 10, 3.0, 2));

        GameState after = resolveMarket(before);
        Faction alpha = after.factions().get(ALPHA);
        Faction beta = after.factions().get(BETA);
        assertEquals(0.0, alpha.stockpiles().minerals(), 1e-9, "seller cannot oversell");
        assertTrue(alpha.stockpiles().minerals() >= 0.0, "no negative stockpile");
        assertEquals(6.0, alpha.stockpiles().energy(), 1e-9, "only 3 * 2.0 cleared");
        assertEquals(3.0, beta.stockpiles().minerals(), 1e-9, "buyer only gets what was held");
        assertEquals(94.0, beta.stockpiles().energy(), 1e-9, "buyer pays only for 3");
    }

    @Test
    void escrowClampsBuyToCurrencyHeld() {
        // Buyer wants 10 but only holds 8 energy at clear price 2.0 -> 4 units affordable.
        Map<FactionId, Faction> f = Map.of(
                ALPHA, faction(ALPHA, stock(0, 10, 0, 0)),
                BETA, faction(BETA, stock(8, 0, 0, 0)));
        GameState before = state(f,
                open("s", HUB1, ALPHA, MarketSide.SELL, PhysicalResource.MINERALS, 10, 2.0, 1),
                open("b", HUB1, BETA, MarketSide.BUY, PhysicalResource.MINERALS, 10, 3.0, 2));

        GameState after = resolveMarket(before);
        Faction beta = after.factions().get(BETA);
        assertEquals(4.0, beta.stockpiles().minerals(), 1e-9, "8 energy / 2.0 price = 4 units");
        assertEquals(0.0, beta.stockpiles().energy(), 1e-9, "spent its 8 energy, never negative");
    }

    // ===== price-time priority + determinism ==================================

    @Test
    void timePriorityFillsTheEarlierSellFirstAtSamePrice() {
        // Two SELLs @ 2.0: the earlier (tick 1) should fill before the later (tick 3).
        Map<FactionId, Faction> f = Map.of(
                ALPHA, faction(ALPHA, stock(0, 10, 0, 0)),
                BETA, faction(BETA, stock(100, 0, 0, 0)));
        GameState before = state(f,
                open("sEarly", HUB1, ALPHA, MarketSide.SELL, PhysicalResource.MINERALS, 5, 2.0, 1),
                open("sLate", HUB1, ALPHA, MarketSide.SELL, PhysicalResource.MINERALS, 5, 2.0, 3),
                open("b", HUB1, BETA, MarketSide.BUY, PhysicalResource.MINERALS, 5, 3.0, 2));

        GameState after = resolveMarket(before);
        assertEquals(OrderStatus.FILLED, order(after, "sEarly").status(),
                "earlier order has time priority and fills first");
        assertEquals(OrderStatus.OPEN, order(after, "sLate").status(),
                "later order is untouched once demand is exhausted");
    }

    @Test
    void matchingIsInsensitiveToBookInsertionOrder() {
        Map<FactionId, Faction> f = Map.of(
                ALPHA, faction(ALPHA, stock(0, 10, 0, 0)),
                BETA, faction(BETA, stock(100, 0, 0, 0)));
        MarketOrder s = open("s", HUB1, ALPHA, MarketSide.SELL, PhysicalResource.MINERALS, 10, 2.0, 1);
        MarketOrder b = open("b", HUB1, BETA, MarketSide.BUY, PhysicalResource.MINERALS, 10, 3.0, 2);

        GameState forward = resolveMarket(state(f, s, b));
        GameState reverse = resolveMarket(state(f, b, s));
        assertEquals(GoldenStateHash.sha256Hex(forward), GoldenStateHash.sha256Hex(reverse),
                "matching must not depend on book insertion / map iteration order");
    }

    @Test
    void matchingIsDeterministicAcrossRuns() {
        Map<FactionId, Faction> f = Map.of(
                ALPHA, faction(ALPHA, stock(0, 10, 0, 0)),
                BETA, faction(BETA, stock(100, 0, 0, 0)));
        List<MarketOrder> orders = new ArrayList<>(List.of(
                open("s", HUB1, ALPHA, MarketSide.SELL, PhysicalResource.MINERALS, 10, 2.0, 1),
                open("b", HUB1, BETA, MarketSide.BUY, PhysicalResource.MINERALS, 7, 3.0, 2)));
        GameState first = resolveMarket(state(f, orders.toArray(MarketOrder[]::new)));
        Collections.shuffle(orders, new java.util.Random(1));
        GameState second = resolveMarket(state(f, orders.toArray(MarketOrder[]::new)));
        assertEquals(GoldenStateHash.sha256Hex(first), GoldenStateHash.sha256Hex(second));
    }

    // ===== exclusions ==========================================================

    @Test
    void expiredOrderDoesNotMatch() {
        Map<FactionId, Faction> f = Map.of(
                ALPHA, faction(ALPHA, stock(0, 10, 0, 0)),
                BETA, faction(BETA, stock(100, 0, 0, 0)));
        // SELL expired at tick 4 (state tick is 5) -> skipped by the matcher.
        MarketOrder expiredSell = new MarketOrder(new MarketOrderId("s"), HUB1, ALPHA,
                MarketSide.SELL, PhysicalResource.MINERALS, 10, 2.0, 1, 4L,
                Optional.empty(), OrderStatus.OPEN);
        GameState before = state(f, expiredSell,
                open("b", HUB1, BETA, MarketSide.BUY, PhysicalResource.MINERALS, 10, 3.0, 2));

        GameState after = resolveMarket(before);
        assertEquals(OrderStatus.OPEN, order(after, "s").status(), "expired order never matches");
        assertEquals(OrderStatus.OPEN, order(after, "b").status());
    }

    @Test
    void directedOfferIsNotMatchedOnTheOpenBook() {
        Map<FactionId, Faction> f = Map.of(
                ALPHA, faction(ALPHA, stock(0, 10, 0, 0)),
                BETA, faction(BETA, stock(100, 0, 0, 0)));
        // A directed SELL (addressee present) must be ignored by the order-book matcher.
        MarketOrder directed = new MarketOrder(new MarketOrderId("s"), HUB1, ALPHA,
                MarketSide.SELL, PhysicalResource.MINERALS, 10, 2.0, 1, 1_000L,
                Optional.of(BETA), OrderStatus.OPEN);
        GameState before = state(f, directed,
                open("b", HUB1, BETA, MarketSide.BUY, PhysicalResource.MINERALS, 10, 3.0, 2));

        GameState after = resolveMarket(before);
        assertEquals(OrderStatus.OPEN, order(after, "s").status(),
                "directed peer offer is accepted by id, never matched on the book");
        assertEquals(OrderStatus.OPEN, order(after, "b").status());
    }

    @Test
    void influenceCannotBeTraded() {
        // There is no INFLUENCE in PhysicalResource, so an order book over Influence
        // is unrepresentable. Guard the invariant at the type level: the four
        // tradeable resources never include Influence.
        for (PhysicalResource r : PhysicalResource.values()) {
            assertTrue(r == PhysicalResource.ENERGY || r == PhysicalResource.MINERALS
                    || r == PhysicalResource.FOOD || r == PhysicalResource.TECH);
        }
    }

    @Test
    void emptyBookIsANoOp() {
        Map<FactionId, Faction> f = Map.of(ALPHA, faction(ALPHA, stock(10, 10, 10, 10)));
        GameState before = state(f);
        GameState after = resolveMarket(before);
        assertEquals(GoldenStateHash.sha256Hex(before), GoldenStateHash.sha256Hex(after));
    }

    @Test
    void unrelatedProposeTradeActionDoesNotDisturbTheBook() {
        // A directed ProposeTrade is still a MARKET-step action (stub); it must not
        // break the order-book matcher running in the same step.
        Map<FactionId, Faction> f = Map.of(
                ALPHA, faction(ALPHA, stock(0, 10, 0, 0)),
                BETA, faction(BETA, stock(100, 0, 0, 0)));
        GameState before = state(f,
                open("s", HUB1, ALPHA, MarketSide.SELL, PhysicalResource.MINERALS, 10, 2.0, 1),
                open("b", HUB1, BETA, MarketSide.BUY, PhysicalResource.MINERALS, 10, 3.0, 2));
        List<SubmittedAction> actions = List.of(new SubmittedAction(ALPHA,
                new Action.ProposeTrade(BETA, new ResourceBundle(1, 0, 0, 0, 0),
                        ResourceBundle.ZERO, Optional.empty()), 0));

        GameState after = Resolver.resolve(before, actions, PROFILE, before.gameSeed());
        assertEquals(OrderStatus.FILLED, order(after, "s").status());
        assertEquals(OrderStatus.FILLED, order(after, "b").status());
    }
}
