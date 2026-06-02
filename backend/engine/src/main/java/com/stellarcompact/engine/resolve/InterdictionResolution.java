package com.stellarcompact.engine.resolve;

import com.stellarcompact.engine.action.Action;
import com.stellarcompact.engine.action.BlockadeTarget;
import com.stellarcompact.engine.config.BalanceProfile;
import com.stellarcompact.engine.rng.DeterministicRng;
import com.stellarcompact.engine.rng.SaltDomain;
import com.stellarcompact.engine.rng.SeedDerivation;
import com.stellarcompact.engine.state.FactionId;
import com.stellarcompact.engine.state.GameState;
import com.stellarcompact.engine.state.PhysicalResource;
import com.stellarcompact.engine.state.ResourceBundle;
import com.stellarcompact.engine.state.Route;
import com.stellarcompact.engine.state.RouteId;
import com.stellarcompact.engine.state.RouteStatus;
import com.stellarcompact.engine.state.SystemId;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The INTERDICTION resolution step (board card E1-11; game-design 05 section 5, 02
 * section 5). Runs once per tick in its fixed slot (step 5, after combat and before
 * development), folding the ordered Blockade / Raid action slice the Resolver hands it.
 * This is the grey-zone sub-war pressure toolkit: choke a rival economy or harass its
 * income WITHOUT capturing territory.
 *
 * <p><b>Blockade chokes throughput.</b> A Blockade on a route flips it to
 * {@link RouteStatus#BLOCKADED}; on a system it blockades every route touching that
 * system (the system market is choked at its endpoints). The throughput the choke removes
 * is the config fraction {@code market.blockadeThroughputFactor} (rule 6) - the status
 * flag here is the deterministic marker the economy/Influence steps read to scale the
 * route volume; no number is hardcoded. No resources change hands and no territory is
 * captured (game-design 05 section 5: throttle without firing on planets).
 *
 * <p><b>Raid steals a seeded shipment.</b> A Raid intercepts the cargo on a route and
 * steals a seeded fraction of it: {@code stolen = volume x raidStealFraction x roll},
 * where {@code roll} is one draw in {@code [0,1)} from a generator seeded by
 * {@code gameSeed XOR tick XOR SaltDomain.RAID.salt(routeId)}. The stolen amount is split
 * evenly across the route hauled {@link PhysicalResource}s, debited from the route owner
 * and credited to the raider, both through the tick-wide {@link SpendLedger} (the single
 * authoritative settlement seam - never a direct stockpile mutation). Same
 * {@code (seed, tick, routeId)} always reproduces the same haul (the replay contract); no
 * territory is captured.
 *
 * <p><b>Legality.</b> Both actions are validator-gated: a positive war state is required
 * and a peace treaty forbids them (ActionValidator, E1-09 F1 gate). This step is
 * belt-and-braces - it re-checks existence/ownership so a stale id is a safe no-op - but
 * never re-decides legality; only validated actions reach here.
 *
 * <p><b>Purity / determinism.</b> Pure arithmetic, config lookups and one seeded roll per
 * raid. No I/O, no wall-clock, no unseeded randomness. Actions resolve in the
 * already-deterministic slice order; routes are scanned in stable id order so a system
 * blockade effect is replay-stable regardless of map iteration order.
 */
final class InterdictionResolution {

    private InterdictionResolution() {
    }

    /**
     * Resolve the ordered Blockade/Raid action slice for the INTERDICTION step. Each
     * action is applied in the slice order the Resolver already imposed
     * ({@code (actor, submissionOrder)}); a raid steal is escrowed through {@code ledger}
     * and settled in the single authoritative pass. Non-interdiction actions are ignored.
     */
    /**
     * Backwards-compatible overload (pre-E1-16): resolves without collecting public
     * events. Existing tests that do not assert events keep this signature; the
     * {@link Resolver} uses the {@code events}-bearing overload to surface RouteRaided.
     */
    static GameState resolve(GameState state, List<SubmittedAction> slice,
                             long gameSeed, long tick, BalanceProfile profile, SpendLedger ledger) {
        return resolve(state, slice, gameSeed, tick, profile, ledger, new java.util.ArrayList<>());
    }

    static GameState resolve(GameState state, List<SubmittedAction> slice,
                             long gameSeed, long tick, BalanceProfile profile, SpendLedger ledger,
                             List<PublicEvent> events) {
        GameState next = state;
        for (SubmittedAction sa : slice) {
            switch (sa.action()) {
                case Action.Blockade b -> next = resolveBlockade(next, sa.actor(), b);
                case Action.Raid r -> next = resolveRaid(next, sa.actor(), r, gameSeed, tick, profile, ledger, events);
                default -> { /* not an interdiction action: ignore */ }
            }
        }
        return next;
    }

    // ===== Blockade ===========================================================

    /** Flip the targeted route (or every route touching the targeted system) to BLOCKADED. */
    private static GameState resolveBlockade(GameState state, FactionId actor, Action.Blockade a) {
        return switch (a.target()) {
            case BlockadeTarget.OnRoute t -> blockadeRoute(state, t.route());
            case BlockadeTarget.OnSystem t -> blockadeSystem(state, t.system());
        };
    }

    private static GameState blockadeRoute(GameState state, RouteId routeId) {
        Route route = state.routes().get(routeId);
        if (route == null) {
            return state; // stale id (validator enforces existence): safe no-op
        }
        return state.withRoute(route.withStatus(RouteStatus.BLOCKADED));
    }

    /**
     * Blockade the system market by choking every route with an endpoint at the system.
     * Routes are visited in stable id order so the fold is replay-stable.
     */
    private static GameState blockadeSystem(GameState state, SystemId system) {
        GameState next = state;
        for (RouteId rid : sortedRouteIds(state)) {
            Route route = next.routes().get(rid);
            if (route == null) {
                continue;
            }
            if (route.systemA().equals(system) || route.systemB().equals(system)) {
                next = next.withRoute(route.withStatus(RouteStatus.BLOCKADED));
            }
        }
        return next;
    }

    // ===== Raid ===============================================================

    /**
     * Steal a seeded fraction of the route cargo: {@code volume x raidStealFraction x
     * roll}, split evenly across the route hauled physical resources, debited from the
     * owner and credited to the raider through the ledger. One seeded draw per route keeps
     * the haul reproducible. A route with no hauled resources, zero volume, or a zero
     * steal fraction is a no-op.
     */
    private static GameState resolveRaid(GameState state, FactionId actor, Action.Raid a,
                                         long gameSeed, long tick, BalanceProfile profile,
                                         SpendLedger ledger, List<PublicEvent> events) {
        Route route = state.routes().get(a.routeId());
        if (route == null || actor.equals(route.owner())) {
            return state; // stale id / self-raid (validator rejects): safe no-op
        }
        double maxFraction = profile.market().raidStealFraction();
        List<PhysicalResource> cargo = route.resources();
        if (maxFraction <= 0.0 || route.volume() <= 0.0 || cargo.isEmpty()) {
            return state; // nothing to steal
        }
        DeterministicRng rng = SeedDerivation.rng(gameSeed, tick, SaltDomain.RAID,
                SaltDomain.hashString(a.routeId().value()));
        double roll = rng.nextDouble(); // [0,1): a raid haul is variable, not fixed
        double totalStolen = route.volume() * maxFraction * roll;
        if (totalStolen <= 0.0) {
            return state;
        }
        double perResource = totalStolen / cargo.size();
        ResourceBundle haul = ResourceBundle.ZERO;
        for (PhysicalResource r : cargo) {
            haul = haul.plus(bundleOf(r, perResource));
        }
        // Debit the route owner, credit the raider - both via the single authoritative
        // settlement seam (the owner floor-at-zero clamp models cannot lose more than held;
        // the raider banks what the ledger nets).
        ledger.escrow(route.owner(), haul);
        ledger.credit(actor, haul);
        // E1-16: a successful raid (a shipment was actually stolen) is announced
        // galaxy-wide (parties = [raider, route owner]). A no-op raid above emits nothing.
        events.add(new PublicEvent.RouteRaided(actor, route.owner(), tick));
        return state;
    }

    // ===== helpers (pure) =====================================================

    /** A bundle carrying {@code amount} of exactly one physical resource. */
    private static ResourceBundle bundleOf(PhysicalResource r, double amount) {
        return switch (r) {
            case ENERGY -> new ResourceBundle(amount, 0, 0, 0, 0);
            case MINERALS -> new ResourceBundle(0, amount, 0, 0, 0);
            case FOOD -> new ResourceBundle(0, 0, amount, 0, 0);
            case TECH -> new ResourceBundle(0, 0, 0, amount, 0);
        };
    }

    private static List<RouteId> sortedRouteIds(GameState state) {
        List<RouteId> ids = new ArrayList<>(state.routes().keySet());
        ids.sort(Comparator.comparing(RouteId::value));
        return ids;
    }
}
