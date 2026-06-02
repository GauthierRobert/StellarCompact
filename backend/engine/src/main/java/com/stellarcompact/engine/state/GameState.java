package com.stellarcompact.engine.state;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.LinkedHashMap;
import java.util.Map;

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
        Map<MarketOrderId, MarketOrder> marketOrders
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
    }

    /** @return a new snapshot identical to this one but at the given tick. */
    public GameState withTick(long newTick) {
        return new GameState(gameSeed, newTick, status, balanceProfileName, balanceProfileVersion,
                factions, systems, fleets, treaties, routes, marketOrders);
    }

    /** @return a new snapshot identical to this one but with the given lifecycle status. */
    public GameState withStatus(GameStatus newStatus) {
        return new GameState(gameSeed, tick, newStatus, balanceProfileName, balanceProfileVersion,
                factions, systems, fleets, treaties, routes, marketOrders);
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
                next, systems, fleets, treaties, routes, marketOrders);
    }

    /** Copy-on-write: a new snapshot with {@code system} inserted/replaced by its id. */
    public GameState withSystem(ActiveSystem system) {
        Map<SystemId, ActiveSystem> next = new LinkedHashMap<>(systems);
        next.put(system.id(), system);
        return new GameState(gameSeed, tick, status, balanceProfileName, balanceProfileVersion,
                factions, next, fleets, treaties, routes, marketOrders);
    }

    /** Copy-on-write: a new snapshot with {@code fleet} inserted/replaced by its id. */
    public GameState withFleet(Fleet fleet) {
        Map<FleetId, Fleet> next = new LinkedHashMap<>(fleets);
        next.put(fleet.id(), fleet);
        return new GameState(gameSeed, tick, status, balanceProfileName, balanceProfileVersion,
                factions, systems, next, treaties, routes, marketOrders);
    }
}
