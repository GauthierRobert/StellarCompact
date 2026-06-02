package com.stellarcompact.engine.resolve;

import com.stellarcompact.engine.action.Action;
import com.stellarcompact.engine.action.AttackTarget;
import com.stellarcompact.engine.config.BalanceProfile;
import com.stellarcompact.engine.rng.DeterministicRng;
import com.stellarcompact.engine.rng.SaltDomain;
import com.stellarcompact.engine.rng.SeedDerivation;
import com.stellarcompact.engine.state.ActiveSystem;
import com.stellarcompact.engine.state.Building;
import com.stellarcompact.engine.state.BuildingStatus;
import com.stellarcompact.engine.state.BuildingType;
import com.stellarcompact.engine.state.FactionId;
import com.stellarcompact.engine.state.Fleet;
import com.stellarcompact.engine.state.FleetId;
import com.stellarcompact.engine.state.GameState;
import com.stellarcompact.engine.state.Planet;
import com.stellarcompact.engine.state.Ship;
import com.stellarcompact.engine.state.SystemId;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * The COMBAT resolution step (board card E1-10; game-design 05 sections 1-2,6). Runs
 * once per tick in its fixed slot (step 4, after movement/interception and before
 * blockade/raid). It resolves the ordered {@code Attack} action slice the
 * {@link Resolver} hands it: each {@link Action.Attack} is a fleet-vs-fleet
 * engagement ({@link AttackTarget.OnFleet}) or a system assault
 * ({@link AttackTarget.OnSystem}) that, when the attacker prevails, <em>captures</em>
 * the system.
 *
 * <p><b>Power model (config-driven, rule 6).</b> Every number comes from
 * {@link BalanceProfile.Combat}; nothing is hardcoded. For a fleet the side power is
 * {@code Sum over ships(shipAttack[spec] x tierMult[spec] x count)} (defence uses
 * {@code shipDefense}), scaled by the fleet's per-stance modifier. A system assault
 * adds every defending garrison fleet stationed at the target, then multiplies the
 * defender by the home-ground {@code terrainDefenseMod} and, if the system has an
 * active {@code defensePlatform} building, the {@code defensePlatformBonus}.
 *
 * <p><b>Seeded variance band (game-design 05 section 2).</b> Each battle draws one
 * roll {@code r} in {@code varianceBand = [lo, hi]} from a generator seeded by
 * {@code gameSeed XOR tick XOR localSalt} where the salt is
 * {@code SaltDomain.COMBAT.salt(battleId)} and {@code battleId} is a pure function of
 * the participants + target + tick. The attacker's effective score is
 * {@code attackerPower x r}; the defender's is {@code defenderPower x (1 - r)}. So a
 * stronger force usually wins but a marginal edge is a gamble - overwhelming force is
 * safe, reckless coin-flips are punished. Same {@code (seed, tick, battleId)} always
 * reproduces the same outcome (the replay contract).
 *
 * <p><b>Proportional losses to BOTH sides.</b> The loser loses
 * {@code lossFractionLoser} of its ships, the winner {@code lossFractionWinner} - a
 * victory is never costless (pyrrhic risk). Per ship stack the destroyed count is the
 * rounded fraction of the stack.
 *
 * <p><b>Capture + occupation unrest (section 6).</b> Winning a system assault transfers
 * ownership to the attacker; the planets and their surviving buildings travel with the
 * system, and the captured system's loyalty is dropped by
 * {@code occupationLoyaltyPenalty} (floored at 0) - the occupation/unrest brake on
 * snowballing conquest.
 *
 * <p><b>Purity / determinism.</b> Pure arithmetic, config lookups and one seeded roll
 * per battle. No I/O, no wall-clock, no unseeded randomness. Battles resolve in the
 * already-deterministic action-slice order; fleets are scanned in stable id order so
 * the garrison set and every loss are replay-stable regardless of map iteration order.
 *
 * <p><b>Interception seam (E1-09).</b> When fleet movement/interception lands, the
 * MOVEMENT step will hand this step an ordered list of forced engagements; this class's
 * {@link #resolveFleetBattle} primitive is the reusable core those will run through.
 * On the current base no interception is produced, so the step resolves only the
 * {@code Attack} slice; no fight is ever resolved on an unvalidated path.
 */
final class CombatResolution {

    private CombatResolution() {
    }

    /**
     * Resolve the ordered {@code Attack} action slice for the COMBAT step. Each action
     * is applied in the slice order the {@link Resolver} already imposed
     * ({@code (actor, submissionOrder)}), folding losses and any capture into the next
     * state. Non-attack actions in the slice are ignored (the slice is the COMBAT slice,
     * so it is only {@link Action.Attack}, but the guard keeps this total).
     */
    static GameState resolve(GameState state, List<SubmittedAction> attackSlice,
                             long gameSeed, long tick, BalanceProfile profile) {
        GameState next = state;
        for (SubmittedAction sa : attackSlice) {
            if (sa.action() instanceof Action.Attack attack) {
                next = resolveAttack(next, sa.actor(), attack, gameSeed, tick, profile);
            }
        }
        return next;
    }

    /**
     * Resolve one forced interception engagement (E1-09 MOVEMENT seam). The moving
     * fleet is the attacker of record and the lane-contesting fleet the defender; both
     * stacks take proportional losses, seeded from the {@link PendingBattle#battleId()}
     * that the MOVEMENT step derived deterministically from the participants, contested
     * lane and tick. Reuses the identical fleet-vs-fleet primitives as a deliberate
     * {@link Action.Attack} so an intercepted battle and a chosen battle resolve the
     * same way. No territory changes hands - interception is a transit clash, not an
     * assault.
     */
    static GameState resolveInterception(GameState state, PendingBattle pb,
                                         long gameSeed, long tick, BalanceProfile profile) {
        Fleet attacker = state.fleets().get(pb.movingFleet());
        Fleet defender = state.fleets().get(pb.interceptor());
        if (attacker == null || defender == null) {
            return state; // a participant already left/was destroyed earlier this tick
        }
        BalanceProfile.Combat c = profile.combat();
        double attackPower = fleetAttackPower(attacker, c);
        double defendPower = fleetDefensePower(defender, c);
        boolean attackerWins = decide(attackPower, defendPower, gameSeed, tick, pb.battleId(), c);
        Fleet newAttacker = applyLosses(attacker, attackerWins ? c.lossFractionWinner() : c.lossFractionLoser());
        Fleet newDefender = applyLosses(defender, attackerWins ? c.lossFractionLoser() : c.lossFractionWinner());
        return state.withFleet(newAttacker).withFleet(newDefender);
    }

    // ===== one Attack action ==================================================

    private static GameState resolveAttack(GameState state, FactionId actor, Action.Attack a,
                                           long gameSeed, long tick, BalanceProfile profile) {
        Fleet attacker = state.fleets().get(a.fleet());
        if (attacker == null || !actor.equals(attacker.owner())) {
            return state; // belt-and-braces: validator already enforces ownership/existence
        }
        return switch (a.target()) {
            case AttackTarget.OnFleet t -> resolveFleetAttack(state, attacker, t, gameSeed, tick, profile);
            case AttackTarget.OnSystem t -> resolveSystemAssault(state, attacker, t, gameSeed, tick, profile);
        };
    }

    /** Fleet-vs-fleet engagement: both stacks take proportional losses. */
    private static GameState resolveFleetAttack(GameState state, Fleet attacker, AttackTarget.OnFleet t,
                                                long gameSeed, long tick, BalanceProfile profile) {
        Fleet defender = state.fleets().get(t.fleet());
        if (defender == null) {
            return state;
        }
        BalanceProfile.Combat c = profile.combat();
        double attackPower = fleetAttackPower(attacker, c);
        double defendPower = fleetDefensePower(defender, c);
        long battleId = fleetBattleId(gameSeed, tick, attacker.id(), defender.id());
        boolean attackerWins = decide(attackPower, defendPower, gameSeed, tick, battleId, c);

        Fleet newAttacker = applyLosses(attacker, attackerWins ? c.lossFractionWinner() : c.lossFractionLoser());
        Fleet newDefender = applyLosses(defender, attackerWins ? c.lossFractionLoser() : c.lossFractionWinner());
        return state.withFleet(newAttacker).withFleet(newDefender);
    }

    /**
     * System assault: the attacker engages the combined defence of the system - every
     * defending garrison fleet stationed there plus the terrain / defence-platform
     * multipliers. On an attacker win the system is captured (ownership + surviving
     * buildings transfer, loyalty drops by the occupation penalty); on a loss ownership
     * is unchanged. Both the assault fleet and the garrison fleets take proportional
     * losses either way.
     */
    private static GameState resolveSystemAssault(GameState state, Fleet attacker, AttackTarget.OnSystem t,
                                                  long gameSeed, long tick, BalanceProfile profile) {
        ActiveSystem system = state.systems().get(t.system());
        if (system == null) {
            return state;
        }
        BalanceProfile.Combat c = profile.combat();
        FactionId defenderOwner = system.owner().orElse(null);
        List<Fleet> garrison = garrisonFleets(state, system.id(), attacker.owner());

        double attackPower = fleetAttackPower(attacker, c);
        double garrisonDefense = 0.0;
        for (Fleet g : garrison) {
            garrisonDefense += fleetDefensePower(g, c);
        }
        double defendPower = garrisonDefense * c.terrainDefenseMod()
                * (hasActiveDefensePlatform(system) ? c.defensePlatformBonus() : 1.0);

        long battleId = systemBattleId(gameSeed, tick, attacker.id(), system.id());
        boolean attackerWins = decide(attackPower, defendPower, gameSeed, tick, battleId, c);

        // Losses to the assault fleet and every garrison fleet.
        GameState next = state.withFleet(
                applyLosses(attacker, attackerWins ? c.lossFractionWinner() : c.lossFractionLoser()));
        for (Fleet g : garrison) {
            next = next.withFleet(
                    applyLosses(g, attackerWins ? c.lossFractionLoser() : c.lossFractionWinner()));
        }

        if (!attackerWins) {
            return next; // assault repelled: ownership and loyalty unchanged
        }
        // Capture: transfer ownership; planets + surviving buildings travel with the
        // system; apply the reduced-loyalty occupation penalty (floored at 0). A
        // self-owned target (re-attacking your own system) is a no-op capture.
        if (attacker.owner().equals(defenderOwner)) {
            return next;
        }
        double newLoyalty = Math.max(0.0, system.loyalty() - c.occupationLoyaltyPenalty());
        ActiveSystem captured = new ActiveSystem(system.id(), system.name(), system.coords(),
                Optional.of(attacker.owner()), system.planets(), system.population(), newLoyalty);
        return next.withSystem(captured);
    }

    // ===== combat primitives (reusable; the interception seam runs through here) ===

    /**
     * Decide a single engagement under the seeded variance band. Returns {@code true}
     * iff the attacker prevails. Public-by-package so a future interception driver
     * (E1-09) resolves through the identical roll.
     */
    static boolean decide(double attackPower, double defendPower, long gameSeed, long tick,
                          long battleId, BalanceProfile.Combat c) {
        if (defendPower <= 0.0) {
            return true; // an undefended target is always taken (no roll needed)
        }
        double lo = c.varianceBand().get(0);
        double hi = c.varianceBand().get(1);
        DeterministicRng rng = SeedDerivation.rng(gameSeed, tick, SaltDomain.COMBAT, battleId);
        double r = rng.nextInRange(lo, hi);
        double attackerScore = attackPower * r;
        double defenderScore = defendPower * (1.0 - r);
        return attackerScore >= defenderScore;
    }

    private static double fleetAttackPower(Fleet fleet, BalanceProfile.Combat c) {
        double base = 0.0;
        for (Ship ship : fleet.ships()) {
            base += attackOf(ship.spec(), c) * tierOf(ship.spec(), c) * ship.count();
        }
        return base * stanceMod(c.stanceAttackMod(), fleet.stance().name());
    }

    private static double fleetDefensePower(Fleet fleet, BalanceProfile.Combat c) {
        double base = 0.0;
        for (Ship ship : fleet.ships()) {
            base += defenseOf(ship.spec(), c) * tierOf(ship.spec(), c) * ship.count();
        }
        return base * stanceMod(c.stanceDefenseMod(), fleet.stance().name());
    }

    private static double attackOf(String spec, BalanceProfile.Combat c) {
        return c.shipAttack().getOrDefault(spec, 0.0);
    }

    private static double defenseOf(String spec, BalanceProfile.Combat c) {
        return c.shipDefense().getOrDefault(spec, 0.0);
    }

    private static double tierOf(String spec, BalanceProfile.Combat c) {
        return c.tierMultipliers().getOrDefault(spec, 1.0);
    }

    private static double stanceMod(java.util.Map<String, Double> mods, String stance) {
        return mods.getOrDefault(stance, 1.0);
    }

    /**
     * Apply a proportional loss to a fleet: each ship stack loses {@code round(count x
     * fraction)} ships (never below zero, never above the stack). Empty stacks are
     * dropped; a fully destroyed fleet becomes an empty-ship fleet (the resolver removes
     * it later).
     */
    private static Fleet applyLosses(Fleet fleet, double fraction) {
        if (fraction <= 0.0) {
            return fleet;
        }
        List<Ship> survivors = new ArrayList<>();
        for (Ship ship : fleet.ships()) {
            int destroyed = (int) Math.round(ship.count() * fraction);
            if (destroyed > ship.count()) {
                destroyed = ship.count();
            }
            int remaining = ship.count() - destroyed;
            if (remaining > 0) {
                survivors.add(new Ship(ship.spec(), remaining));
            }
        }
        return fleet.withShips(survivors);
    }

    /** Defending fleets of a faction other than the attacker, stationed at the system. */
    private static List<Fleet> garrisonFleets(GameState state, SystemId at, FactionId attacker) {
        List<Fleet> garrison = new ArrayList<>();
        for (FleetId fid : sortedFleetIds(state)) {
            Fleet f = state.fleets().get(fid);
            if (f == null || f.owner().equals(attacker)) {
                continue;
            }
            if (f.location().map(loc -> loc.equals(at)).orElse(false)) {
                garrison.add(f);
            }
        }
        return garrison;
    }

    private static boolean hasActiveDefensePlatform(ActiveSystem system) {
        for (Planet planet : system.planets()) {
            for (Building b : planet.buildings()) {
                if (b.type() == BuildingType.DEFENSE_PLATFORM && b.status() == BuildingStatus.ACTIVE) {
                    return true;
                }
            }
        }
        return false;
    }

    // ===== deterministic, replay-stable battle ids ============================

    /**
     * Stable per-battle key for a fleet-vs-fleet engagement: a pure function of the two
     * fleet ids and the tick, folded through the {@link SaltDomain} string mixer
     * (cross-JVM stable) and XOR-mixed with seed/tick so the id is unique per tick. The
     * attacker/defender order is preserved (an attack is directional).
     */
    private static long fleetBattleId(long gameSeed, long tick, FleetId attacker, FleetId defender) {
        String key = "f|" + attacker.value() + "|" + defender.value();
        return mixId(key, gameSeed, tick);
    }

    /** Stable per-battle key for a system assault: attacker fleet + target system + tick. */
    private static long systemBattleId(long gameSeed, long tick, FleetId attacker, SystemId system) {
        String key = "s|" + attacker.value() + "|" + system.value();
        return mixId(key, gameSeed, tick);
    }

    private static long mixId(String key, long gameSeed, long tick) {
        long base = SaltDomain.COMBAT.salt(key);
        return base ^ Long.rotateLeft(gameSeed, 17) ^ Long.rotateLeft(tick, 33);
    }

    private static List<FleetId> sortedFleetIds(GameState state) {
        List<FleetId> ids = new ArrayList<>(state.fleets().keySet());
        ids.sort(Comparator.comparing(FleetId::value));
        return ids;
    }
}
