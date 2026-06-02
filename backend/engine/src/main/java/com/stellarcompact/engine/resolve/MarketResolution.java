package com.stellarcompact.engine.resolve;

import com.stellarcompact.engine.config.BalanceProfile;
import com.stellarcompact.engine.state.Faction;
import com.stellarcompact.engine.state.FactionId;
import com.stellarcompact.engine.state.GameState;
import com.stellarcompact.engine.state.MarketOrder;
import com.stellarcompact.engine.state.MarketOrderId;
import com.stellarcompact.engine.state.MarketSide;
import com.stellarcompact.engine.state.OrderStatus;
import com.stellarcompact.engine.state.PhysicalResource;
import com.stellarcompact.engine.state.ResourceBundle;
import com.stellarcompact.engine.state.SystemId;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The MARKET resolution step (board card E1-07; game-design {@code 02-economy}
 * section 4 "The market"). Runs once per tick, in its fixed slot (step 8, after
 * the action-driven steps and before passive production), matching every trade
 * hub's resting order book by <b>price-time priority</b> and settling fills
 * through the tick-wide {@link SpendLedger} escrow seam.
 *
 * <h2>Per-hub order book</h2>
 * Orders are grouped per {@code (hubSystem, resource)} - each hub runs an
 * independent book, so prices emerge per hub from that hub's own supply/demand
 * and naturally differ between hubs (the arbitrage the design wants). Only
 * <em>open</em> orders ({@link MarketOrder#isDirected()} false), still
 * {@linkplain MarketOrder#isLive() live} and not {@linkplain
 * MarketOrder#isExpired(long) expired} on the current tick, participate; directed
 * peer offers are accepted/declined by id elsewhere and are skipped here.
 *
 * <h2>Price-time priority (deterministic ordering)</h2>
 * Within a book, buys are ranked best (highest) price first and sells best
 * (lowest) price first; ties break by {@code placedTick} ascending (older order
 * has time priority), then by {@code id} ascending - a <b>total, replay-stable
 * order that never depends on {@code HashMap} iteration</b> (skill
 * {@code game-engine-determinism}, rule 3). The top buy and top sell cross while
 * {@code bid.price >= ask.price}; each crossing pair clears at the <b>resting
 * (maker) order's price</b> - the maker being whichever of the pair has the
 * earlier {@code (placedTick, id)}, i.e. the one already resting when the other
 * arrived.
 *
 * <h2>Escrow &amp; settlement</h2>
 * The traded {@link PhysicalResource} good moves seller -&gt; buyer; the
 * <em>price</em> is paid in the configured numeraire ({@code market.currency})
 * buyer -&gt; seller, {@code qty x clearPrice} units. Neither leg debits a
 * stockpile directly: each fill {@link SpendLedger#escrow escrows} what each side
 * owes and {@link SpendLedger#credit credits} what each side gains, settled in the
 * resolver's single authoritative pass ({@code stockpile + credited - accrued},
 * floored at zero). <b>Escrow prevents over-offer:</b> before a fill is booked the
 * matcher checks, against the ledger's collective view, that the seller can still
 * cover the goods and the buyer the funds <em>net of everything already escrowed
 * this tick</em> (earlier build/trade spends and earlier fills included); the fill
 * is clamped to the largest affordable quantity, so a faction can never sell goods
 * it does not hold nor buy beyond its currency, and no stockpile can be driven
 * negative.
 *
 * <h2>Purity / determinism</h2>
 * Pure arithmetic only: no I/O, no wall-clock, no randomness (matching is fully
 * determined by state + profile). Hubs, resources and orders are visited in a
 * stable sorted order, so the fold is replay-stable regardless of map iteration
 * order. Every number comes from state or the {@link BalanceProfile}; no gameplay
 * constant is hardcoded (rule 6).
 */
final class MarketResolution {

    private MarketResolution() {
    }

    /**
     * Match every hub's order book for one tick and settle the fills through
     * {@code ledger}, returning the next snapshot with the resting book updated
     * (remaining quantities and {@link OrderStatus} reflecting each fill).
     *
     * @param state   the pre-step snapshot (already folded by steps 1-7)
     * @param profile the active balance profile - source of the trade numeraire
     * @param ledger  the tick-wide escrow/credit seam (never {@code null})
     * @return the next snapshot with the matched book; resource flow is queued in
     * {@code ledger} for the resolver settle pass
     */
    static GameState resolve(GameState state, BalanceProfile profile, SpendLedger ledger) {
        if (state.marketOrders().isEmpty()) {
            return state;
        }
        PhysicalResource currency = currencyOf(profile);

        // Working, mutable copy of the book keyed by id; the matcher rewrites the
        // entries it touches, then we hand the whole map back copy-on-write.
        Map<MarketOrderId, MarketOrder> book = new LinkedHashMap<>(state.marketOrders());

        // The remaining (un-escrowed) currency/goods budget per faction, derived
        // once from the pre-step stockpile and decremented as fills are booked, so
        // two fills in one tick cannot collectively overdraw. Seeded lazily.
        Map<FactionId, ResourceBundle> budget = new LinkedHashMap<>();

        for (BookKey key : sortedBookKeys(book, state.tick())) {
            matchOne(key, book, state, currency, budget, ledger);
        }
        return state.withMarketOrders(Map.copyOf(book));
    }

    /**
     * Match a single {@code (hub, resource)} book to exhaustion: repeatedly cross
     * the best bid and best ask while they cross and can be (partly) funded,
     * mutating {@code book} in place and accruing each fill into {@code ledger}.
     */
    private static void matchOne(BookKey key, Map<MarketOrderId, MarketOrder> book,
                                 GameState state, PhysicalResource currency,
                                 Map<FactionId, ResourceBundle> budget, SpendLedger ledger) {
        List<MarketOrderId> buys = side(book, key, MarketSide.BUY, state.tick());
        List<MarketOrderId> sells = side(book, key, MarketSide.SELL, state.tick());

        int bi = 0;
        int si = 0;
        while (bi < buys.size() && si < sells.size()) {
            MarketOrder buy = book.get(buys.get(bi));
            MarketOrder sell = book.get(sells.get(si));

            // No cross: the best bid cannot meet the best ask -> book is settled.
            if (buy.price() < sell.price()) {
                break;
            }

            // Clearing price = the resting (maker) order's price: the order with the
            // earlier (placedTick, id) was already in the book when the other arrived.
            double clearPrice = restingFirst(buy, sell) ? buy.price() : sell.price();

            double tradeQty = Math.min(buy.quantity(), sell.quantity());
            tradeQty = clampToBudget(tradeQty, clearPrice, buy.faction(), sell.faction(),
                    key.resource(), currency, state, budget);

            if (tradeQty <= 0.0) {
                // Neither side can fund any quantity. Advance the unfunded side so the
                // loop terminates; if the buyer lacks currency advance the buy, else
                // the seller lacks goods so advance the sell.
                if (available(budget, buy.faction(), currency, state) < clearPrice
                        || clearPrice == 0.0 && available(budget, sell.faction(),
                        key.resource(), state) <= 0.0) {
                    bi++;
                } else {
                    si++;
                }
                continue;
            }

            bookFill(buy, sell, tradeQty, clearPrice, key.resource(), currency,
                    budget, ledger, book);

            // Re-read the post-fill orders and advance whichever side fully filled.
            MarketOrder buyAfter = book.get(buys.get(bi));
            MarketOrder sellAfter = book.get(sells.get(si));
            if (buyAfter.quantity() <= 0.0) {
                bi++;
            }
            if (sellAfter.quantity() <= 0.0) {
                si++;
            }
        }
    }

    /**
     * Book one fill of {@code tradeQty} at {@code clearPrice}: escrow the goods from
     * the seller and the funds from the buyer, credit the mirror legs, decrement
     * both factions' working budgets, and update both orders' remaining quantity
     * and status in {@code book}.
     */
    private static void bookFill(MarketOrder buy, MarketOrder sell, double tradeQty,
                                 double clearPrice, PhysicalResource resource,
                                 PhysicalResource currency,
                                 Map<FactionId, ResourceBundle> budget, SpendLedger ledger,
                                 Map<MarketOrderId, MarketOrder> book) {
        double funds = currencyAmount(tradeQty, clearPrice);

        // Seller: -goods, +funds. Buyer: -funds, +goods.
        ledger.escrow(sell.faction(), bundleOf(resource, tradeQty));
        ledger.credit(sell.faction(), bundleOf(currency, funds));
        ledger.escrow(buy.faction(), bundleOf(currency, funds));
        ledger.credit(buy.faction(), bundleOf(resource, tradeQty));

        // Decrement working budgets (what each side may still commit this tick).
        spend(budget, sell.faction(), bundleOf(resource, tradeQty));
        spend(budget, buy.faction(), bundleOf(currency, funds));

        book.put(buy.id(), buy.withFill(buy.quantity() - tradeQty,
                fillStatus(buy.quantity() - tradeQty)));
        book.put(sell.id(), sell.withFill(sell.quantity() - tradeQty,
                fillStatus(sell.quantity() - tradeQty)));
    }

    /**
     * Clamp {@code tradeQty} to the largest quantity both factions can still fund:
     * the seller must hold the goods, the buyer the {@code qty x price} currency,
     * each net of everything already escrowed/booked this tick.
     */
    private static double clampToBudget(double tradeQty, double clearPrice,
                                        FactionId buyer, FactionId seller,
                                        PhysicalResource resource, PhysicalResource currency,
                                        GameState state, Map<FactionId, ResourceBundle> budget) {
        double sellerGoods = available(budget, seller, resource, state);
        double cap = Math.min(tradeQty, sellerGoods);
        if (cap <= 0.0) {
            return 0.0;
        }
        if (clearPrice > 0.0) {
            double buyerFunds = available(budget, buyer, currency, state);
            cap = Math.min(cap, buyerFunds / clearPrice);
        }
        return cap <= 0.0 ? 0.0 : cap;
    }

    // ===== book construction / ordering =======================================

    /**
     * The distinct {@code (hub, resource)} books present among the live, open,
     * non-expired orders, in a stable sorted order (hub id, then resource name).
     */
    private static List<BookKey> sortedBookKeys(Map<MarketOrderId, MarketOrder> book,
                                                long tick) {
        List<BookKey> keys = new ArrayList<>();
        for (MarketOrder o : book.values()) {
            if (!participates(o, tick)) {
                continue;
            }
            BookKey k = new BookKey(o.hubSystem(), o.resource());
            if (!keys.contains(k)) {
                keys.add(k);
            }
        }
        keys.sort(Comparator.<BookKey, String>comparing(k -> k.hub().value())
                .thenComparing(k -> k.resource().name()));
        return keys;
    }

    /**
     * The order ids on one side of one book, ranked by price-time priority and the
     * id tie-break: buys best (highest) price first, sells best (lowest) price
     * first; then {@code placedTick} ascending, then {@code id} ascending. Total
     * and replay-stable - no map iteration order leaks in.
     */
    private static List<MarketOrderId> side(Map<MarketOrderId, MarketOrder> book,
                                            BookKey key, MarketSide wanted, long tick) {
        List<MarketOrder> orders = new ArrayList<>();
        for (MarketOrder o : book.values()) {
            if (o.side() == wanted && participates(o, tick)
                    && o.hubSystem().equals(key.hub()) && o.resource() == key.resource()) {
                orders.add(o);
            }
        }
        Comparator<MarketOrder> byPrice = wanted == MarketSide.BUY
                ? Comparator.comparingDouble(MarketOrder::price).reversed()
                : Comparator.comparingDouble(MarketOrder::price);
        orders.sort(byPrice
                .thenComparingLong(MarketOrder::placedTick)
                .thenComparing(o -> o.id().value()));
        List<MarketOrderId> ids = new ArrayList<>(orders.size());
        for (MarketOrder o : orders) {
            ids.add(o.id());
        }
        return ids;
    }

    /** Open (non-directed), live (open/partial), non-expired orders match here. */
    private static boolean participates(MarketOrder o, long tick) {
        return !o.isDirected() && o.isLive() && !o.isExpired(tick) && o.quantity() > 0.0;
    }

    /**
     * @return {@code true} iff {@code buy} has the earlier {@code (placedTick, id)}
     * and is therefore the resting maker; otherwise the sell is the maker.
     */
    private static boolean restingFirst(MarketOrder buy, MarketOrder sell) {
        if (buy.placedTick() != sell.placedTick()) {
            return buy.placedTick() < sell.placedTick();
        }
        return buy.id().value().compareTo(sell.id().value()) <= 0;
    }

    // ===== budget bookkeeping =================================================

    /** Currency owed for {@code qty} traded at {@code price}. */
    private static double currencyAmount(double qty, double price) {
        return qty * price;
    }

    /**
     * The faction's still-uncommitted amount of {@code resource}: its pre-step
     * stockpile minus everything decremented from its working budget so far this
     * tick. Lazily seeded from the stockpile on first touch.
     */
    private static double available(Map<FactionId, ResourceBundle> budget, FactionId faction,
                                    PhysicalResource resource, GameState state) {
        ResourceBundle remaining = budget.computeIfAbsent(faction,
                f -> stockpileOf(state, f));
        return componentOf(remaining, resource);
    }

    /** Decrement a faction's working budget by a spent bundle (never below zero). */
    private static void spend(Map<FactionId, ResourceBundle> budget, FactionId faction,
                              ResourceBundle spent) {
        ResourceBundle remaining = budget.getOrDefault(faction, ResourceBundle.ZERO);
        ResourceBundle next = remaining.minus(spent);
        budget.put(faction, new ResourceBundle(
                Math.max(0.0, next.energy()),
                Math.max(0.0, next.minerals()),
                Math.max(0.0, next.food()),
                Math.max(0.0, next.tech()),
                Math.max(0.0, next.influence())));
    }

    private static ResourceBundle stockpileOf(GameState state, FactionId faction) {
        Faction f = state.factions().get(faction);
        return f == null ? ResourceBundle.ZERO : f.stockpiles();
    }

    // ===== resource <-> bundle bridge =========================================

    /** A bundle carrying {@code amount} in the slot of one physical resource. */
    private static ResourceBundle bundleOf(PhysicalResource resource, double amount) {
        return switch (resource) {
            case ENERGY -> new ResourceBundle(amount, 0, 0, 0, 0);
            case MINERALS -> new ResourceBundle(0, amount, 0, 0, 0);
            case FOOD -> new ResourceBundle(0, 0, amount, 0, 0);
            case TECH -> new ResourceBundle(0, 0, 0, amount, 0);
        };
    }

    private static double componentOf(ResourceBundle b, PhysicalResource resource) {
        return switch (resource) {
            case ENERGY -> b.energy();
            case MINERALS -> b.minerals();
            case FOOD -> b.food();
            case TECH -> b.tech();
        };
    }

    /**
     * The configured trade numeraire. Parsed from {@code market.currency}; an
     * unknown/blank value falls back to ENERGY (the "powers everything" resource),
     * keeping the matcher robust to a partial profile.
     */
    private static PhysicalResource currencyOf(BalanceProfile profile) {
        String name = profile.market().currency();
        if (name == null || name.isBlank()) {
            return PhysicalResource.ENERGY;
        }
        try {
            return PhysicalResource.valueOf(name.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            return PhysicalResource.ENERGY;
        }
    }

    private static OrderStatus fillStatus(double remaining) {
        return remaining <= 0.0 ? OrderStatus.FILLED : OrderStatus.PARTIALLY_FILLED;
    }

    /** Identity of one independent order book: a hub and the resource it trades. */
    private record BookKey(SystemId hub, PhysicalResource resource) {
    }
}
