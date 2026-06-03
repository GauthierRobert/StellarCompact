package com.stellarcompact.engine.resolve;

import com.stellarcompact.engine.action.Action;
import com.stellarcompact.engine.config.BalanceProfile;
import com.stellarcompact.engine.state.ActiveSystem;
import com.stellarcompact.engine.state.Biome;
import com.stellarcompact.engine.state.Building;
import com.stellarcompact.engine.state.BuildingStatus;
import com.stellarcompact.engine.state.BuildingType;
import com.stellarcompact.engine.state.Faction;
import com.stellarcompact.engine.state.FactionId;
import com.stellarcompact.engine.state.GameState;
import com.stellarcompact.engine.state.Planet;
import com.stellarcompact.engine.state.PlanetId;
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
 * The DEVELOPMENT resolution step (board card E1-08; game-design 03 step 6
 * Construction, research, terraform progress; game-design 02 section 6 and 06 tech
 * web). Runs once per tick in its fixed slot (step 6, after combat/interdiction and
 * before colonisation), in two phases the Resolver drives:
 *
 * <ol>
 *   <li><b>Begin</b> ({@link #beginBuild} / {@link #beginResearch} /
 *       {@link #beginTerraform}) - folds the ordered Build / Research / Terraform
 *       action slice: queues a new {@link BuildingStatus#UNDER_CONSTRUCTION} building
 *       in a free slot, starts a {@link TechStatus#RESEARCHING} node (gated by the
 *       tech DAG prerequisites), or starts a terraform on an idle active Terraformer.
 *       Each begin escrows its one-off cost (Minerals(+Tech) to build, Tech to
 *       research) through the tick-wide {@link SpendLedger}; nothing debits a
 *       stockpile directly, so N begins in one tick cannot collectively overdraw.</li>
 *   <li><b>Advance</b> ({@link #advance}) - the passive per-tick progress sweep over
 *       every faction: each {@code UNDER_CONSTRUCTION} building, {@code RESEARCHING}
 *       tech node and in-progress Terraformer ticks its {@code progress} up by one;
 *       on reaching the configured time it completes - a building flips to
 *       {@link BuildingStatus#ACTIVE}, a tech to {@link TechStatus#UNLOCKED} (its
 *       multipliers/unlocks then apply via EconomyResolution and the validator tech
 *       gate), and a Terraformer steps the planet biome one rung toward habitable
 *       along the configured terraformChain.</li>
 * </ol>
 *
 * <p><b>Purity / determinism.</b> Pure arithmetic and config lookups only: no I/O,
 * no wall-clock, no randomness (construction/research/terraform progress is fully
 * determined by state + profile). Factions, systems, planets and tech nodes are
 * visited in a stable id-then-slot order so the advance fold is replay-stable
 * regardless of map iteration order. Every time/cost/chain comes from the
 * {@link BalanceProfile}; no gameplay constant is hardcoded (rule 6).
 */
final class DevelopmentResolution {

    private DevelopmentResolution() {
    }

    // ===== phase 1: begin (action-driven) =====================================

    /**
     * Queue one {@link Action.Build}: place a fresh {@code UNDER_CONSTRUCTION}
     * building of the requested type in the named free slot of an owned planet,
     * escrowing the configured Minerals(+Tech) cost. A defensive no-op (state
     * unchanged, no escrow) if the planet/slot is invalid or occupied - the validator
     * already rejects those.
     */
    static GameState beginBuild(GameState state, FactionId actor, Action.Build a,
                                BalanceProfile profile, SpendLedger ledger) {
        Located located = locate(state, a.planet());
        if (located == null || !owns(located.system(), actor)) {
            return state;
        }
        Planet planet = located.planet();
        if (a.slot() < 0 || a.slot() >= planet.slotsTotal() || slotOccupied(planet, a.slot())) {
            return state;
        }
        ResourceBundle cost = bundle(profile.construction().costs().get(a.buildingType().configKey()));
        if (!cost.equals(ResourceBundle.ZERO)) {
            ledger.escrow(actor, cost);
        }
        List<Building> buildings = new ArrayList<>(planet.buildings());
        buildings.add(new Building(a.slot(), a.buildingType(), BuildingStatus.UNDER_CONSTRUCTION, 0));
        return state.withSystem(withPlanet(located.system(),
                new Planet(planet.id(), planet.biome(), planet.slotsTotal(),
                        planet.population(), buildings)));
    }

    /**
     * Begin one {@link Action.Research}: mark the node {@code RESEARCHING}
     * (progress 0) for the actor and escrow its configured Tech cost. A defensive
     * no-op if the node is unknown, already researching/unlocked, or its DAG
     * prerequisites are not all unlocked - the validator gates these.
     */
    static GameState beginResearch(GameState state, FactionId actor, Action.Research a,
                                   BalanceProfile profile, SpendLedger ledger) {
        Faction faction = state.factions().get(actor);
        if (faction == null) {
            return state;
        }
        TechId techId = a.techId();
        TechProgress existing = faction.techProgress().get(techId);
        if (existing != null && existing.status() != TechStatus.LOCKED) {
            return state; // already researching or unlocked
        }
        Double techCost = profile.tech().costs().get(techId.value());
        if (techCost == null) {
            return state; // unknown node
        }
        if (!prerequisitesMet(faction, techId, profile)) {
            return state; // DAG gate
        }
        if (techCost > 0.0) {
            ledger.escrow(actor, new ResourceBundle(0, 0, 0, techCost, 0));
        }
        Map<TechId, TechProgress> next = new LinkedHashMap<>(faction.techProgress());
        next.put(techId, new TechProgress(techId, TechStatus.RESEARCHING, 0));
        return state.withFaction(faction.withTechProgress(next));
    }

    /**
     * Resolve one {@link Action.Terraform}: the agent's declared intent to terraform
     * the planet. A defensive no-op on state - terraform <em>progress</em> is driven
     * by the {@link #advance} sweep, not by this begin, so that construction, research
     * and terraform all share one symmetric "queue then advance per tick" model and a
     * Terraform action started at tick T behaves identically whether or not it is
     * re-issued on later ticks (the active Terraformer keeps stepping the biome on its
     * own). Validation (owned planet, active Terraformer, terraformable biome,
     * sustained Energy upkeep) is enforced by {@code ActionValidator}; the actual
     * biome step and the Energy upkeep accrue in the advance sweep / economy step.
     *
     * <p>Kept as an explicit handler (not folded into the no-op default) so the
     * Terraform variant has a documented home in the DEVELOPMENT step and so a future
     * card can attach one-off engage costs here through the ledger without moving the
     * seam.
     */
    static GameState beginTerraform(GameState state, FactionId actor, Action.Terraform a,
                                    BalanceProfile profile) {
        return state;
    }

    // ===== phase 2: advance (passive per-tick sweep) ==========================

    /**
     * The passive progress sweep: advance every in-flight building, tech node and
     * terraform by one tick across all factions, completing any that reach their
     * configured time. Visits factions, systems and planets in stable id order so
     * the fold is replay-stable.
     */
    static GameState advance(GameState state, BalanceProfile profile, long tick,
                             List<PublicEvent> events) {
        GameState next = advanceConstructionAndTerraform(state, profile);
        next = advanceResearch(next, profile, tick, events);
        return next;
    }

    private static GameState advanceConstructionAndTerraform(GameState state, BalanceProfile profile) {
        GameState next = state;
        for (SystemId sid : sortedSystemIds(state)) {
            ActiveSystem system = next.systems().get(sid);
            ActiveSystem updated = advanceSystem(system, profile);
            if (!updated.equals(system)) {
                next = next.withSystem(updated);
            }
        }
        return next;
    }

    private static ActiveSystem advanceSystem(ActiveSystem system, BalanceProfile profile) {
        List<Planet> planets = new ArrayList<>(system.planets().size());
        boolean changed = false;
        for (Planet planet : system.planets()) {
            Planet updated = advancePlanet(planet, profile);
            if (!updated.equals(planet)) {
                changed = true;
            }
            planets.add(updated);
        }
        if (!changed) {
            return system;
        }
        return new ActiveSystem(system.id(), system.name(), system.coords(), system.owner(),
                planets, system.population(), system.loyalty());
    }

    /**
     * Advance one planet in-flight buildings and any active terraform. Buildings are
     * advanced in stable slot order; the biome may step at most once per tick (the
     * configured one-step-toward-habitable rule).
     */
    private static Planet advancePlanet(Planet planet, BalanceProfile profile) {
        Map<String, Integer> buildTimes = profile.construction().buildTimes();
        List<Building> sorted = new ArrayList<>(planet.buildings());
        sorted.sort(Comparator.comparingInt(Building::slotIndex));

        List<Building> result = new ArrayList<>(sorted.size());
        Biome biome = planet.biome();
        boolean changed = false;

        for (Building b : sorted) {
            if (b.status() == BuildingStatus.UNDER_CONSTRUCTION) {
                int time = buildTimes.getOrDefault(b.type().configKey(), 0);
                int progress = b.progress() + 1;
                if (progress >= time) {
                    result.add(new Building(b.slotIndex(), b.type(), BuildingStatus.ACTIVE, 0));
                } else {
                    result.add(new Building(b.slotIndex(), b.type(), BuildingStatus.UNDER_CONSTRUCTION, progress));
                }
                changed = true;
            } else if (b.type() == BuildingType.TERRAFORMER
                    && b.status() == BuildingStatus.ACTIVE && nextBiome(biome, profile) != null) {
                // An active Terraformer on a still-terraformable planet advances the
                // biome one rung toward habitable over its configured step time. Once
                // the biome reaches a state with no further chain entry the terraformer
                // idles (the guard above), so progress stops accruing.
                int stepTime = buildTimes.getOrDefault("terraformerStep", 0);
                int progress = b.progress() + 1;
                if (progress >= stepTime) {
                    biome = nextBiome(biome, profile);    // step one rung toward habitable
                    result.add(new Building(b.slotIndex(), b.type(), BuildingStatus.ACTIVE, 0));
                } else {
                    result.add(new Building(b.slotIndex(), b.type(), BuildingStatus.ACTIVE, progress));
                }
                changed = true;
            } else {
                result.add(b);
            }
        }
        if (!changed) {
            return planet;
        }
        return new Planet(planet.id(), biome, planet.slotsTotal(), planet.population(), result);
    }

    private static GameState advanceResearch(GameState state, BalanceProfile profile, long tick,
                                             List<PublicEvent> events) {
        Map<String, Integer> times = profile.tech().times();
        GameState next = state;
        for (FactionId fid : sortedFactionIds(state)) {
            Faction faction = next.factions().get(fid);
            Map<TechId, TechProgress> updated = null;
            // Visit techs in stable id order so any TechUnlocked events emit in a
            // deterministic, replay-stable sequence (faction id, then tech id).
            for (TechId techId : sortedTechIds(faction)) {
                TechProgress tp = faction.techProgress().get(techId);
                if (tp.status() != TechStatus.RESEARCHING) {
                    continue;
                }
                int time = times.getOrDefault(techId.value(), 0);
                int progress = tp.progress() + 1;
                boolean completes = progress >= time;
                TechProgress advanced = completes
                        ? new TechProgress(techId, TechStatus.UNLOCKED, 0)
                        : new TechProgress(techId, TechStatus.RESEARCHING, progress);
                if (updated == null) {
                    updated = new LinkedHashMap<>(faction.techProgress());
                }
                updated.put(techId, advanced);
                if (completes) {
                    // E12-04 P7d: a completed tech is a public milestone. Emitted
                    // deterministically here (pure function of progress + config), in
                    // faction-id-then-tech-id order, on the tick the node unlocks.
                    events.add(new PublicEvent.TechUnlocked(fid, techId.value(), tick));
                }
            }
            if (updated != null) {
                next = next.withFaction(faction.withTechProgress(updated));
            }
        }
        return next;
    }

    // ===== tech DAG gate ======================================================

    /**
     * @return {@code true} iff every prerequisite tech of the node (per the
     * configured DAG edges) is {@code UNLOCKED} for the faction. A node with no
     * configured prerequisites is a root and is always satisfied.
     */
    static boolean prerequisitesMet(Faction faction, TechId techId, BalanceProfile profile) {
        List<String> prereqs = profile.tech().prereqs().get(techId.value());
        if (prereqs == null || prereqs.isEmpty()) {
            return true;
        }
        for (String required : prereqs) {
            TechProgress tp = faction.techProgress().get(new TechId(required));
            if (tp == null || tp.status() != TechStatus.UNLOCKED) {
                return false;
            }
        }
        return true;
    }

    /**
     * @return the tech id that gates the capability config key (a ship-spec or
     * building configKey), or {@code null} if no tech gates it (ungated, available
     * from the start). The inverse of the {@code tech.unlocks} index.
     */
    static TechId gatingTech(String capability, BalanceProfile profile) {
        for (Map.Entry<String, List<String>> e : profile.tech().unlocks().entrySet()) {
            if (e.getValue().contains(capability)) {
                return new TechId(e.getKey());
            }
        }
        return null;
    }

    /**
     * @return {@code true} iff the faction may use the capability: either no tech
     * gates it, or its gating tech is {@code UNLOCKED}. Used by the validator to
     * reject a gated Build/BuildFleet ({@code TECH_PREREQ_MISSING}).
     */
    static boolean capabilityUnlocked(Faction faction, String capability, BalanceProfile profile) {
        TechId gate = gatingTech(capability, profile);
        if (gate == null) {
            return true;
        }
        TechProgress tp = faction.techProgress().get(gate);
        return tp != null && tp.status() == TechStatus.UNLOCKED;
    }

    // ===== helpers (pure) =====================================================

    /** The biome one step toward habitable from the given biome, or null if none. */
    private static Biome nextBiome(Biome biome, BalanceProfile profile) {
        String nextKey = profile.construction().terraformChain().get(biome.configKey());
        if (nextKey == null) {
            return null;
        }
        for (Biome candidate : Biome.values()) {
            if (candidate.configKey().equals(nextKey)) {
                return candidate;
            }
        }
        return null; // misconfigured chain target: no such biome
    }

    private static List<SystemId> sortedSystemIds(GameState state) {
        List<SystemId> ids = new ArrayList<>(state.systems().keySet());
        ids.sort(Comparator.comparing(SystemId::value));
        return ids;
    }

    private static List<FactionId> sortedFactionIds(GameState state) {
        List<FactionId> ids = new ArrayList<>(state.factions().keySet());
        ids.sort(Comparator.comparing(FactionId::value));
        return ids;
    }

    private static List<TechId> sortedTechIds(Faction faction) {
        List<TechId> ids = new ArrayList<>(faction.techProgress().keySet());
        ids.sort(Comparator.comparing(TechId::value));
        return ids;
    }

    private static boolean owns(ActiveSystem system, FactionId actor) {
        return system.owner().isPresent() && system.owner().get().equals(actor);
    }

    private static boolean slotOccupied(Planet planet, int slot) {
        for (Building b : planet.buildings()) {
            if (b.slotIndex() == slot) {
                return true;
            }
        }
        return false;
    }

    private static ActiveSystem withPlanet(ActiveSystem system, Planet replacement) {
        List<Planet> planets = new ArrayList<>(system.planets().size());
        for (Planet p : system.planets()) {
            planets.add(p.id().equals(replacement.id()) ? replacement : p);
        }
        return new ActiveSystem(system.id(), system.name(), system.coords(), system.owner(),
                planets, system.population(), system.loyalty());
    }

    /** Locate a planet and its host system, or {@code null} if no such planet. */
    private static Located locate(GameState state, PlanetId planetId) {
        for (ActiveSystem system : state.systems().values()) {
            for (Planet planet : system.planets()) {
                if (planet.id().equals(planetId)) {
                    return new Located(system, planet);
                }
            }
        }
        return null;
    }

    /** Bridge a config ResourceBundle (possibly absent) to a state ResourceBundle. */
    private static ResourceBundle bundle(BalanceProfile.ResourceBundle b) {
        if (b == null) {
            return ResourceBundle.ZERO;
        }
        return new ResourceBundle(b.energy(), b.minerals(), b.food(), b.tech(), b.influence());
    }

    /** A planet paired with its host system, the unit the begin handlers act on. */
    private record Located(ActiveSystem system, Planet planet) {
    }
}
