package com.stellarcompact.engine.resolve;

import com.stellarcompact.engine.action.Action;
import com.stellarcompact.engine.config.BalanceProfile;
import com.stellarcompact.engine.map.LaneNetwork;
import com.stellarcompact.engine.rng.SaltDomain;
import com.stellarcompact.engine.state.FactionId;
import com.stellarcompact.engine.state.Fleet;
import com.stellarcompact.engine.state.FleetId;
import com.stellarcompact.engine.state.GameState;
import com.stellarcompact.engine.state.ResourceBundle;
import com.stellarcompact.engine.state.SystemId;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * The MOVEMENT resolution step (board card E1-09; game-design 03 step 3, 05 section
 * 4, 01 section 3). Runs once per tick in its fixed slot (step 3, after espionage and
 * before combat), in two phases the {@link Resolver} drives:
 *
 * <ol>
 *   <li><b>Launch</b> ({@link #launch}) - folds the ordered {@code MoveFleet} action
 *       slice: a fleet at its path origin begins travelling the first lane of its
 *       (already-validated) path. {@code etaTicks} is set to that lane's length, the
 *       fleet's whole Energy travel cost (summed lane ticks x
 *       {@code movement.energyCostPerLaneTick}) is escrowed once through the tick-wide
 *       {@link SpendLedger}, and {@code location} is pinned to the lane origin while
 *       the rest of the route rides in {@code enroutePath}. Travel ETAs are therefore
 *       a pure function of the {@link LaneNetwork} geometry - deterministic and
 *       replay-stable.</li>
 *   <li><b>Advance</b> ({@link #advance}) - the passive per-tick sweep over every
 *       en-route fleet (stable fleet-id order): first <em>detect interception</em> on
 *       the lane the fleet is currently traversing (a hostile, at-war fleet holding
 *       either lane endpoint contests it) and record a {@link PendingBattle} for the
 *       COMBAT step (E1-10) to resolve; then decrement {@code etaTicks}, arriving the
 *       fleet at the lane target when it reaches zero and either starting the next
 *       lane or parking at the destination.</li>
 * </ol>
 *
 * <p><b>Interception is a trigger here, not a fight.</b> This card detects the
 * contested lane and the two belligerents and emits an ordered list of
 * {@link PendingBattle}s; the actual power comparison / seeded losses are E1-10. The
 * detection is gated by {@code movement.interceptionEnabled} (config, rule 6) and by
 * a positive war-state between the two owners ({@link GameState#atWar}) - a fleet is
 * never intercepted by a faction it is at peace with. The resolver MUST NOT move or
 * fight on an unvalidated path: an absent ({@link LaneNetwork#isEmpty() empty}) lane
 * network means launch cannot prove the lanes, so it is a defensive no-op (the fleet
 * stays put) and no battle is triggered.
 *
 * <p><b>Purity / determinism.</b> Pure arithmetic, config lookups and graph lookups
 * only - no I/O, no wall-clock. The only seeded value is each battle's stable
 * {@code battleId}, derived from the participants + contested lane + tick via the
 * engine's {@link SaltDomain} mixer, so the same tick reproduces the same battles.
 * Fleets are visited in stable id order so the advance fold and the emitted
 * {@code PendingBattle} list are replay-stable regardless of map iteration order.
 */
final class MovementResolution {

    private MovementResolution() {
    }

    /** The advance phase result: the next state plus the ordered battles interception triggered. */
    record AdvanceResult(GameState state, List<PendingBattle> battles) {
        AdvanceResult {
            battles = List.copyOf(battles);
        }
    }

    // ===== phase 1: launch (action-driven) ====================================

    /**
     * Launch one {@link Action.MoveFleet}: put the fleet en route along the first
     * lane of its validated path and escrow its whole Energy travel cost. Defensive
     * no-op (state unchanged, no escrow) if the lane network is absent, the fleet is
     * missing/already moving, or the path is not a real lane walk from the fleet's
     * current location - the validator already rejects those, this is belt-and-braces
     * so the resolver never moves on an unvalidated path.
     */
    static GameState launch(GameState state, FactionId actor, Action.MoveFleet a,
                            LaneNetwork network, BalanceProfile profile, SpendLedger ledger) {
        if (network == null || network.isEmpty()) {
            return state; // cannot prove lanes -> do not move (F2 / determinism guard)
        }
        Fleet fleet = state.fleets().get(a.fleet());
        if (fleet == null || !actor.equals(fleet.owner())) {
            return state;
        }
        if (fleet.enRoute() || fleet.location().isEmpty()) {
            return state; // only a stationary, located fleet can launch
        }
        SystemId origin = fleet.location().get();
        List<SystemId> path = a.path();
        Optional<Long> total = network.pathTicks(origin, path);
        if (total.isEmpty()) {
            return state; // some hop is not a real lane -> reject the move
        }
        // The first lane the fleet flies: origin -> path[0]. Its length seeds etaTicks.
        int firstLane = network.lengthTicks(origin, path.get(0)).orElseThrow();
        // Energy cost is the whole journey, escrowed once at launch (atomic ledger).
        double costPerTick = profile.movement().energyCostPerLaneTick();
        if (costPerTick > 0.0) {
            ledger.escrow(actor, new ResourceBundle(total.get() * costPerTick, 0, 0, 0, 0));
        }
        Fleet moving = fleet.withTravel(
                Optional.of(origin),                 // location = current lane origin
                Optional.of(List.copyOf(path)),      // remaining route, head = lane target
                Optional.of(firstLane));             // ticks left on the first lane
        return state.withFleet(moving);
    }

    // ===== phase 2: advance (passive per-tick sweep) ==========================

    /**
     * Advance every en-route fleet by one tick. For each (stable fleet-id order):
     * detect interception on its current lane (emitting a {@link PendingBattle} when a
     * hostile at-war fleet holds an endpoint and interception is enabled), then
     * decrement its ETA, arriving / chaining lanes / parking as the lane completes.
     */
    static AdvanceResult advance(GameState state, LaneNetwork network, BalanceProfile profile) {
        if (network == null || network.isEmpty()) {
            return new AdvanceResult(state, List.of());
        }
        boolean interceptionOn = profile.movement().interceptionEnabled();
        List<PendingBattle> battles = new ArrayList<>();
        GameState next = state;

        for (FleetId fid : sortedEnRouteFleetIds(state)) {
            Fleet fleet = next.fleets().get(fid);
            if (fleet == null || !fleet.enRoute()) {
                continue; // a fleet removed/parked earlier this sweep
            }
            SystemId origin = fleet.location().orElse(null);
            List<SystemId> route = fleet.enroutePath().orElse(List.of());
            if (origin == null || route.isEmpty()) {
                continue; // malformed en-route fleet: leave untouched
            }
            SystemId target = route.get(0);

            if (interceptionOn) {
                PendingBattle battle = detectInterception(next, fleet, origin, target);
                if (battle != null) {
                    battles.add(battle);
                }
            }

            next = next.withFleet(stepOneTick(fleet, origin, route, target, network));
        }
        return new AdvanceResult(next, battles);
    }

    /** Decrement the fleet's ETA one tick and arrive / chain / park as the lane completes. */
    private static Fleet stepOneTick(Fleet fleet, SystemId origin, List<SystemId> route,
                                     SystemId target, LaneNetwork network) {
        int eta = fleet.etaTicks().orElseThrow() - 1;
        if (eta > 0) {
            // Still on the same lane; only the ETA changes.
            return fleet.withTravel(Optional.of(origin), Optional.of(route), Optional.of(eta));
        }
        // Arrived at the lane target. Pop it from the route.
        List<SystemId> remaining = new ArrayList<>(route.subList(1, route.size()));
        if (remaining.isEmpty()) {
            // Journey complete: park at the destination, clear travel.
            return fleet.withTravel(Optional.of(target), Optional.empty(), Optional.empty());
        }
        // Begin the next lane: target -> remaining[0].
        SystemId nextTarget = remaining.get(0);
        int nextLane = network.lengthTicks(target, nextTarget).orElse(0);
        if (nextLane <= 0) {
            // Onward lane missing (should not happen for a validated path): park safely
            // at the reached system rather than move on an unproven hop.
            return fleet.withTravel(Optional.of(target), Optional.empty(), Optional.empty());
        }
        return fleet.withTravel(Optional.of(target), Optional.of(remaining), Optional.of(nextLane));
    }

    /**
     * Detect a forced engagement on the lane {@code origin <-> target} the fleet is
     * traversing: the first hostile fleet (different owner, at war with the mover) that
     * is <em>holding</em> either lane endpoint (stationed there, not itself en route).
     * Holding a chokepoint endpoint is what lets a faction contest passage
     * (game-design 05 section 4). Candidate interceptors are scanned in stable id
     * order so the chosen interceptor (and thus the battle) is deterministic.
     *
     * @return the triggered {@link PendingBattle}, or {@code null} if the lane is uncontested
     */
    private static PendingBattle detectInterception(GameState state, Fleet mover,
                                                     SystemId origin, SystemId target) {
        FactionId moverOwner = mover.owner();
        for (FleetId fid : sortedFleetIds(state)) {
            Fleet candidate = state.fleets().get(fid);
            if (candidate == null || candidate.enRoute()) {
                continue; // an en-route fleet does not hold a chokepoint
            }
            FactionId owner = candidate.owner();
            if (owner.equals(moverOwner)) {
                continue;
            }
            if (!state.atWar(moverOwner, owner)) {
                continue; // F1: no interception without a positive war state
            }
            SystemId at = candidate.location().orElse(null);
            if (at == null) {
                continue;
            }
            if (at.equals(origin) || at.equals(target)) {
                long battleId = battleId(state.gameSeed(), state.tick(),
                        mover.id(), candidate.id(), origin, target);
                SystemId laneA = canonicalLow(origin, target);
                SystemId laneB = canonicalHigh(origin, target);
                return new PendingBattle(battleId, laneA, laneB,
                        mover.id(), moverOwner, candidate.id(), owner);
            }
        }
        return null;
    }

    // ===== helpers (pure) =====================================================

    /**
     * Stable, replay-safe per-battle salt key from the participants + contested lane +
     * tick. Folds the textual ids through the engine's {@link SaltDomain} string mixer
     * (cross-JVM stable) so the COMBAT step (E1-10) can seed each battle independently.
     */
    private static long battleId(long gameSeed, long tick, FleetId mover, FleetId interceptor,
                                 SystemId origin, SystemId target) {
        SystemId laneA = canonicalLow(origin, target);
        SystemId laneB = canonicalHigh(origin, target);
        String key = mover.value() + "|" + interceptor.value() + "|"
                + laneA.value() + "|" + laneB.value();
        // Mix in seed/tick via the INTERCEPTION domain so the id is unique per tick.
        long base = SaltDomain.INTERCEPTION.salt(key);
        return base ^ Long.rotateLeft(gameSeed, 17) ^ Long.rotateLeft(tick, 33);
    }

    private static SystemId canonicalLow(SystemId a, SystemId b) {
        return a.value().compareTo(b.value()) <= 0 ? a : b;
    }

    private static SystemId canonicalHigh(SystemId a, SystemId b) {
        return a.value().compareTo(b.value()) <= 0 ? b : a;
    }

    private static List<FleetId> sortedFleetIds(GameState state) {
        List<FleetId> ids = new ArrayList<>(state.fleets().keySet());
        ids.sort(Comparator.comparing(FleetId::value));
        return ids;
    }

    private static List<FleetId> sortedEnRouteFleetIds(GameState state) {
        List<FleetId> ids = new ArrayList<>();
        for (FleetId fid : sortedFleetIds(state)) {
            Fleet f = state.fleets().get(fid);
            if (f != null && f.enRoute()) {
                ids.add(fid);
            }
        }
        return ids;
    }
}
