package com.stellarcompact.engine.state;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * The complete authoritative snapshot of a match at one tick - the single
 * immutable value the deterministic resolver reads and from which it produces
 * the next snapshot (see {@code .claude/skills/game-engine-determinism}).
 *
 * <p>Determinism contract. Every collection is an unmodifiable defensive copy
 * keyed by a typed id. Maps are {@link java.util.HashMap}-backed via
 * {@link Map#copyOf} - iteration order is therefore unspecified, which is fine
 * because the canonical {@code GoldenStateHash} sorts maps by key before
 * hashing, so no insertion order can leak into the golden hash.
 *
 * <p>Balance profile reference, not embedding. {@code balanceProfileName} +
 * {@code balanceProfileVersion} identify the active {@code BalanceProfile}; the
 * whole profile is <em>not</em> embedded. The resolver receives the profile
 * separately ({@code resolve(state, actions, profile, seed)}); keeping the heavy,
 * static config out of the per-tick snapshot keeps state small and the hash
 * focused on what actually changes tick to tick.
 *
 * <p>{@code gameSeed} is the root of all seeded RNG (RNG is derived as
 * {@code gameSeed XOR tick XOR localSalt}); it never changes for a match.
 * {@code tick} is the current tick number.
 */
@JsonIgnoreProperties(ignoreUnknown = false)
public record GameState(
        long gameSeed,
        long tick,
        GameStatus status,
        String balanceProfileName,
        int balanceProfileVersion,
        Map<FactionId, Faction> factions,
        Map<SystemId, ActiveSystem> systems,
        Map<FleetId, Fleet> fleets,
        Map<TreatyId, Treaty> treaties,
        Map<RouteId, Route> routes,
        Map<MarketOrderId, MarketOrder> marketOrders,
        // --- E1-12 diplomacy: the set of active wars (matches the live E1-09 shape,
        // appended LAST). A null degrades to "no wars" so an older positional caller /
        // partial JSON stays loadable (forward-compatible, like the config DAG maps).
        Set<WarState> wars
) {
    public GameState {
        if (status == null) {
            throw new IllegalArgumentException("GameState.status must be set");
        }
        if (balanceProfileName == null || balanceProfileName.isBlank()) {
            throw new IllegalArgumentException("GameState.balanceProfileName must be non-blank");
        }
        if (balanceProfileVersion <= 0) {
            throw new IllegalArgumentException("GameState.balanceProfileVersion must be > 0");
        }
        if (tick < 0) {
            throw new IllegalArgumentException("GameState.tick must be >= 0");
        }
        factions = Map.copyOf(factions);
        systems = Map.copyOf(systems);
        fleets = Map.copyOf(fleets);
        treaties = Map.copyOf(treaties);
        routes = Map.copyOf(routes);
        marketOrders = Map.copyOf(marketOrders);
        wars = wars == null ? Set.of() : Set.copyOf(wars);
    }

    /**
     * Backwards-compatible constructor predating the E1-12 {@code wars} set: delegates
     * to the canonical constructor with an empty default. Lets pre-E1-12 callers /
     * fixtures that build a snapshot positionally (through {@code marketOrders}) keep
     * compiling unchanged.
     */
    public GameState(
            long gameSeed,
            long tick,
            GameStatus status,
            String balanceProfileName,
            int balanceProfileVersion,
            Map<FactionId, Faction> factions,
            Map<SystemId, ActiveSystem> systems,
            Map<FleetId, Fleet> fleets,
            Map<TreatyId, Treaty> treaties,
            Map<RouteId, Route> routes,
            Map<MarketOrderId, MarketOrder> marketOrders) {
        this(gameSeed, tick, status, balanceProfileName, balanceProfileVersion,
                factions, systems, fleets, treaties, routes, marketOrders, Set.of());
    }

    /** @return a new snapshot identical to this one but at the given tick. */
    public GameState withTick(long newTick) {
        return new GameState(gameSeed, newTick, status, balanceProfileName, balanceProfileVersion,
                factions, systems, fleets, treaties, routes, marketOrders, wars);
    }

    /** @return a new snapshot identical to this one but with the given lifecycle status. */
    public GameState withStatus(GameStatus newStatus) {
        return new GameState(gameSeed, tick, newStatus, balanceProfileName, balanceProfileVersion,
                factions, systems, fleets, treaties, routes, marketOrders, wars);
    }

    /**
     * Copy-on-write: a new snapshot with {@code faction} inserted/replaced by its
     * id, every other collection shared structurally. The workhorse the resolver
     * uses to fold per-faction effects into the next snapshot.
     */
    public GameState withFaction(Faction faction) {
        Map<FactionId, Faction> next = new LinkedHashMap<>(factions);
        next.put(faction.id(), faction);
        return new GameState(gameSeed, tick, status, balanceProfileName, balanceProfileVersion,
                next, systems, fleets, treaties, routes, marketOrders, wars);
    }

    /** Copy-on-write: a new snapshot with {@code system} inserted/replaced by its id. */
    public GameState withSystem(ActiveSystem system) {
        Map<SystemId, ActiveSystem> next = new LinkedHashMap<>(systems);
        next.put(system.id(), system);
        return new GameState(gameSeed, tick, status, balanceProfileName, balanceProfileVersion,
                factions, next, fleets, treaties, routes, marketOrders, wars);
    }

    /** Copy-on-write: a new snapshot with {@code fleet} inserted/replaced by its id. */
    public GameState withFleet(Fleet fleet) {
        Map<FleetId, Fleet> next = new LinkedHashMap<>(fleets);
        next.put(fleet.id(), fleet);
        return new GameState(gameSeed, tick, status, balanceProfileName, balanceProfileVersion,
                factions, systems, next, treaties, routes, marketOrders, wars);
    }

    /**
     * Copy-on-write: a new snapshot with {@code treaty} inserted/replaced by its id.
     * The DIPLOMATIC_STATE step (E1-12) folds treaty lifecycle transitions
     * (PROPOSED -&gt; ACTIVE on accept, ACTIVE -&gt; BROKEN on break) through this helper,
     * every other collection shared structurally.
     */
    public GameState withTreaty(Treaty treaty) {
        Map<TreatyId, Treaty> next = new LinkedHashMap<>(treaties);
        next.put(treaty.id(), treaty);
        return new GameState(gameSeed, tick, status, balanceProfileName, balanceProfileVersion,
                factions, systems, fleets, next, routes, marketOrders, wars);
    }

    /**
     * Copy-on-write: a new snapshot whose entire market order book is replaced by
     * {@code newOrders}. The market-matching step (E1-07) rebuilds the book once
     * per tick (filled/partial/withdrawn orders updated together), so it replaces
     * the whole map in one shot rather than inserting order by order.
     */
    public GameState withMarketOrders(Map<MarketOrderId, MarketOrder> newOrders) {
        return new GameState(gameSeed, tick, status, balanceProfileName, balanceProfileVersion,
                factions, systems, fleets, treaties, routes, newOrders, wars);
    }

    /**
     * Copy-on-write: a new snapshot whose set of active wars is replaced by
     * {@code newWars}. The DIPLOMATIC_STATE step ({@code DeclareWar}) adds a
     * {@link WarState} here; later peace/ceasefire steps remove them.
     */
    public GameState withWars(Set<WarState> newWars) {
        return new GameState(gameSeed, tick, status, balanceProfileName, balanceProfileVersion,
                factions, systems, fleets, treaties, routes, marketOrders, newWars);
    }

    /**
     * Copy-on-write convenience: this snapshot plus a war between {@code x} and
     * {@code y} begun at {@code sinceTick}. Idempotent - re-declaring an existing war
     * (same unordered pair) leaves the set unchanged (and keeps the original start
     * tick), because {@link WarState} identity is the pair alone.
     */
    public GameState withWar(FactionId x, FactionId y, long sinceTick) {
        WarState war = WarState.between(x, y, sinceTick);
        if (wars.contains(war)) {
            return this;
        }
        Set<WarState> next = new LinkedHashSet<>(wars);
        next.add(war);
        return withWars(next);
    }

    /**
     * @return {@code true} iff a {@link WarState} currently exists between {@code x}
     * and {@code y} (either orientation) - the positive war-state gate the kinetic
     * validators consult.
     */
    public boolean atWar(FactionId x, FactionId y) {
        for (WarState war : wars) {
            if (war.isBetween(x, y)) {
                return true;
            }
        }
        return false;
    }
}
