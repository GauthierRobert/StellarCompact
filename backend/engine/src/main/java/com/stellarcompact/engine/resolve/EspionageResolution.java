package com.stellarcompact.engine.resolve;

import com.stellarcompact.engine.action.Action;
import com.stellarcompact.engine.action.EspionageOperation;
import com.stellarcompact.engine.config.BalanceProfile;
import com.stellarcompact.engine.rng.DeterministicRng;
import com.stellarcompact.engine.rng.SaltDomain;
import com.stellarcompact.engine.rng.SeedDerivation;
import com.stellarcompact.engine.state.ActiveSystem;
import com.stellarcompact.engine.state.Building;
import com.stellarcompact.engine.state.BuildingStatus;
import com.stellarcompact.engine.state.Faction;
import com.stellarcompact.engine.state.FactionId;
import com.stellarcompact.engine.state.GameState;
import com.stellarcompact.engine.state.Planet;
import com.stellarcompact.engine.state.ResourceBundle;
import com.stellarcompact.engine.state.SystemId;
import com.stellarcompact.engine.state.TechId;
import com.stellarcompact.engine.state.TechProgress;
import com.stellarcompact.engine.state.TechStatus;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The ESPIONAGE resolution step (board card E1-13; game-design 03 section C
 * "Espionage", 06 section 3 tech theft / counter-intel). Runs once per tick in its
 * fixed slot (step 2, right after diplomatic state changes and before fleet
 * movement), folding the ordered {@code Espionage} action slice the {@link Resolver}
 * hands it. Each {@link Action.Espionage} is one covert operation against a rival:
 * {@code SCOUT} (reveal intel), {@code STEAL_INTEL} (steal a tech or resources),
 * {@code SABOTAGE} (disable a building) or {@code INCITE_UNREST} (reduce a colony's
 * population/loyalty).
 *
 * <p><b>Two seeded rolls per operation (the replay contract).</b> Every op draws
 * exactly one generator, seeded from {@code gameSeed XOR tick XOR localSalt} where the
 * salt is {@code SaltDomain.ESPIONAGE.salt(opId)} and {@code opId} is a pure function
 * of {@code (actor, target, operation, submissionOrder)}. From that single stream it
 * draws, in fixed order, a success roll then a detection roll, so the outcome of one
 * op is independent of any other and identical for the same {@code (seed, tick, opId)}.
 * No wall-clock, no unseeded randomness.
 *
 * <p><b>Counter-intelligence (config, game-design 06).</b> When the target faction has
 * unlocked the configured counter-intel tech
 * ({@link BalanceProfile.Espionage#counterIntelTech()} - "Intelligence Agency"), the
 * op's success odds drop by {@code counterIntelSuccessPenalty} and its detection odds
 * rise by {@code counterIntelDetectionBonus} (both clamped into [0,1]). Counter-intel
 * is therefore a defence worth buying, exactly as the tech doc intends.
 *
 * <p><b>Detection costs reputation.</b> Independently of success, a detected op is
 * attributed to the actor and costs it
 * {@code diplomacy.reputation.espionageDetectedPenalty} reputation. Reputation lives on
 * {@link Faction#reputation()} and is applied through {@link Faction#withReputation};
 * this reuses the existing reputation field, not a new subsystem.
 *
 * <p><b>Cost (escrowed).</b> Each op escrows its configured Influence/Tech cost through
 * the tick-wide {@link SpendLedger} - win or lose, a run is a run. Nothing debits a
 * stockpile directly, so the single authoritative settlement still holds.
 *
 * <p><b>Purity / determinism.</b> Pure arithmetic, config lookups and the two seeded
 * rolls. Targets, systems, planets and tech ids are scanned in stable id order so the
 * chosen building/tech and every effect are replay-stable regardless of map iteration
 * order. Every number comes from {@link BalanceProfile.Espionage} /
 * {@link BalanceProfile.Reputation}; none is hardcoded (rule 6).
 */
final class EspionageResolution {

    private EspionageResolution() {
    }

    /**
     * Resolve the ordered {@code Espionage} action slice for the ESPIONAGE step. Each
     * action is applied in the slice order the {@link Resolver} already imposed
     * ({@code (actor, submissionOrder)}). Non-espionage actions in the slice are ignored
     * (the slice is the espionage slice, but the guard keeps this total). The per-op
     * {@code submissionOrder} is the index within this ordered slice, so it is a stable
     * disambiguator for two otherwise-identical ops the same faction submits.
     */
    static GameState resolve(GameState state, List<SubmittedAction> slice,
                             long gameSeed, long tick, BalanceProfile profile, SpendLedger ledger) {
        GameState next = state;
        int order = 0;
        for (SubmittedAction sa : slice) {
            if (sa.action() instanceof Action.Espionage op) {
                next = resolveOne(next, sa.actor(), op, order, gameSeed, tick, profile, ledger);
            }
            order++;
        }
        return next;
    }

    // ===== one Espionage operation ============================================

    private static GameState resolveOne(GameState state, FactionId actor, Action.Espionage op,
                                        int submissionOrder, long gameSeed, long tick,
                                        BalanceProfile profile, SpendLedger ledger) {
        Faction actorFaction = state.factions().get(actor);
        Faction target = state.factions().get(op.target());
        if (actorFaction == null || target == null || actor.equals(op.target())) {
            return state; // validator already enforces existence + non-self; belt-and-braces
        }

        BalanceProfile.Espionage esp = profile.espionage();
        String key = op.operationType().configKey();

        // The cost is paid whether or not the op succeeds (escrowed, not direct-debit).
        ResourceBundle cost = costOf(esp, key);
        if (cost != null) {
            ledger.escrow(actor, cost);
        }

        boolean counterIntel = targetHasCounterIntel(target, esp);
        double successOdds = clamp01(odds(esp.successBase(), key)
                - (counterIntel ? esp.counterIntelSuccessPenalty() : 0.0));
        double detectionOdds = clamp01(odds(esp.detectionBase(), key)
                + (counterIntel ? esp.counterIntelDetectionBonus() : 0.0));

        long opId = opId(gameSeed, tick, actor, op.target(), op.operationType(), submissionOrder);
        DeterministicRng rng = SeedDerivation.rng(gameSeed, tick, SaltDomain.ESPIONAGE, opId);
        double successRoll = rng.nextDouble();   // first draw: success
        double detectionRoll = rng.nextDouble(); // second draw: detection
        boolean succeeded = successRoll < successOdds;
        boolean detected = detectionRoll < detectionOdds;

        GameState next = state;
        if (succeeded) {
            next = applyEffect(next, actor, op.target(), op.operationType(), esp);
        }
        if (detected) {
            next = applyDetectionPenalty(next, actor, profile);
        }
        return next;
    }

    // ===== per-operation effects (applied only on success) ====================

    private static GameState applyEffect(GameState state, FactionId actor, FactionId targetId,
                                         EspionageOperation operation, BalanceProfile.Espionage esp) {
        return switch (operation) {
            case SCOUT -> applyScout(state, actor, targetId);
            case STEAL_INTEL -> applyStealIntel(state, actor, targetId, esp);
            case SABOTAGE -> applySabotage(state, targetId);
            case INCITE_UNREST -> applyInciteUnrest(state, targetId, esp);
        };
    }

    /** SCOUT: record that the actor now sees the target's hidden details. */
    private static GameState applyScout(GameState state, FactionId actor, FactionId targetId) {
        Faction target = state.factions().get(targetId);
        return state.withFaction(target.withRevealedTo(actor));
    }

    /**
     * STEAL_INTEL: transfer one UNLOCKED tech from target to actor if any exists (one
     * the actor does not already hold UNLOCKED); otherwise fall back to stealing a
     * configured fraction of the target's stockpile. The tech pick is the lowest tech-id
     * string so it is replay-stable and map-order-independent.
     */
    private static GameState applyStealIntel(GameState state, FactionId actor, FactionId targetId,
                                             BalanceProfile.Espionage esp) {
        Faction actorFaction = state.factions().get(actor);
        Faction target = state.factions().get(targetId);
        TechId stolen = stealableTech(target, actorFaction);
        if (stolen != null) {
            Map<TechId, TechProgress> actorTech = new LinkedHashMap<>(actorFaction.techProgress());
            actorTech.put(stolen, new TechProgress(stolen, TechStatus.UNLOCKED, 0));
            return state.withFaction(actorFaction.withTechProgress(actorTech));
        }
        // Resource fallback: move a fraction of the target's stockpile to the actor.
        double f = clamp01(esp.stealResourceFraction());
        if (f <= 0.0) {
            return state;
        }
        ResourceBundle loot = target.stockpiles().scale(f);
        Faction newTarget = target.withStockpiles(target.stockpiles().minus(loot));
        Faction newActor = actorFaction.withStockpiles(actorFaction.stockpiles().plus(loot));
        return state.withFaction(newTarget).withFaction(newActor);
    }

    /**
     * SABOTAGE: disable (flip to IDLE) the first ACTIVE building in the target's
     * territory, scanned in stable system-then-planet-then-slot order. No-op if the
     * target owns no active building.
     */
    private static GameState applySabotage(GameState state, FactionId targetId) {
        for (SystemId sysId : sortedSystemIds(state)) {
            ActiveSystem sys = state.systems().get(sysId);
            if (sys == null || sys.owner().isEmpty() || !sys.owner().get().equals(targetId)) {
                continue;
            }
            for (int pi = 0; pi < sys.planets().size(); pi++) {
                Planet planet = sys.planets().get(pi);
                for (int bi = 0; bi < planet.buildings().size(); bi++) {
                    Building b = planet.buildings().get(bi);
                    if (b.status() == BuildingStatus.ACTIVE) {
                        Building idled = new Building(b.slotIndex(), b.type(),
                                BuildingStatus.IDLE, b.progress());
                        return state.withSystem(withReplacedBuilding(sys, pi, bi, idled));
                    }
                }
            }
        }
        return state;
    }

    /**
     * INCITE_UNREST: reduce the population and loyalty of the first colony the target
     * owns (stable system order). Population drops by {@code unrestPopulationLoss}
     * (floored at 0), loyalty by {@code unrestLoyaltyLoss} (floored at 0).
     */
    private static GameState applyInciteUnrest(GameState state, FactionId targetId,
                                               BalanceProfile.Espionage esp) {
        for (SystemId sysId : sortedSystemIds(state)) {
            ActiveSystem sys = state.systems().get(sysId);
            if (sys == null || sys.owner().isEmpty() || !sys.owner().get().equals(targetId)) {
                continue;
            }
            long newPop = Math.max(0L, sys.population() - esp.unrestPopulationLoss());
            double newLoyalty = Math.max(0.0, sys.loyalty() - esp.unrestLoyaltyLoss());
            if (newPop == sys.population() && newLoyalty == sys.loyalty()) {
                return state; // nothing to take
            }
            ActiveSystem struck = new ActiveSystem(sys.id(), sys.name(), sys.coords(),
                    sys.owner(), sys.planets(), newPop, newLoyalty);
            return state.withSystem(struck);
        }
        return state;
    }

    private static GameState applyDetectionPenalty(GameState state, FactionId actor,
                                                   BalanceProfile profile) {
        Faction actorFaction = state.factions().get(actor);
        double penalty = profile.diplomacy().reputation().espionageDetectedPenalty();
        double newRep = actorFaction.reputation() - penalty;
        return state.withFaction(actorFaction.withReputation(newRep));
    }

    // ===== helpers (pure) =====================================================

    /**
     * @return the lowest-id tech that {@code target} has UNLOCKED but {@code actor} does
     * not yet hold UNLOCKED, or {@code null} if there is nothing to steal. Lowest-id
     * gives a replay-stable, map-order-independent pick.
     */
    private static TechId stealableTech(Faction target, Faction actor) {
        List<TechId> candidates = new ArrayList<>();
        for (Map.Entry<TechId, TechProgress> e : target.techProgress().entrySet()) {
            if (e.getValue().status() != TechStatus.UNLOCKED) {
                continue;
            }
            TechProgress have = actor.techProgress().get(e.getKey());
            if (have != null && have.status() == TechStatus.UNLOCKED) {
                continue; // actor already has it
            }
            candidates.add(e.getKey());
        }
        if (candidates.isEmpty()) {
            return null;
        }
        candidates.sort(Comparator.comparing(TechId::value));
        return candidates.get(0);
    }

    private static boolean targetHasCounterIntel(Faction target, BalanceProfile.Espionage esp) {
        String techKey = esp.counterIntelTech();
        if (techKey == null || techKey.isBlank()) {
            return false;
        }
        TechProgress tp = target.techProgress().get(new TechId(techKey));
        return tp != null && tp.status() == TechStatus.UNLOCKED;
    }

    private static ResourceBundle costOf(BalanceProfile.Espionage esp, String key) {
        BalanceProfile.ResourceBundle c = esp.cost().get(key);
        if (c == null) {
            return null;
        }
        return new ResourceBundle(c.energy(), c.minerals(), c.food(), c.tech(), c.influence());
    }

    private static double odds(Map<String, Double> table, String key) {
        return table.getOrDefault(key, 0.0);
    }

    private static double clamp01(double v) {
        if (v < 0.0) {
            return 0.0;
        }
        return Math.min(v, 1.0);
    }

    /**
     * Stable per-operation key: a pure function of actor, target, operation and the
     * op's slice position, folded through the {@link SaltDomain} string mixer
     * (cross-JVM stable) and XOR-mixed with seed/tick so the id is unique per tick.
     * The submission order disambiguates two identical ops the same faction submits.
     */
    private static long opId(long gameSeed, long tick, FactionId actor, FactionId target,
                             EspionageOperation operation, int submissionOrder) {
        String key = "e|" + actor.value() + "|" + target.value() + "|"
                + operation.name() + "|" + submissionOrder;
        long base = SaltDomain.ESPIONAGE.salt(key);
        return base ^ Long.rotateLeft(gameSeed, 17) ^ Long.rotateLeft(tick, 33);
    }

    private static List<SystemId> sortedSystemIds(GameState state) {
        List<SystemId> ids = new ArrayList<>(state.systems().keySet());
        ids.sort(Comparator.comparing(SystemId::value));
        return ids;
    }

    /** Copy-on-write: {@code system} with one building at (planetIndex, buildingIndex) replaced. */
    private static ActiveSystem withReplacedBuilding(ActiveSystem system, int planetIndex,
                                                     int buildingIndex, Building replacement) {
        List<Planet> planets = new ArrayList<>(system.planets());
        Planet planet = planets.get(planetIndex);
        List<Building> buildings = new ArrayList<>(planet.buildings());
        buildings.set(buildingIndex, replacement);
        planets.set(planetIndex, new Planet(planet.id(), planet.biome(),
                planet.slotsTotal(), planet.population(), buildings));
        return new ActiveSystem(system.id(), system.name(), system.coords(),
                system.owner(), planets, system.population(), system.loyalty());
    }
}
