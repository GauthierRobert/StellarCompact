package com.stellarcompact.engine.resolve;

import com.stellarcompact.engine.config.BalanceProfile;
import com.stellarcompact.engine.state.ActiveSystem;
import com.stellarcompact.engine.state.Biome;
import com.stellarcompact.engine.state.Building;
import com.stellarcompact.engine.state.BuildingStatus;
import com.stellarcompact.engine.state.BuildingType;
import com.stellarcompact.engine.state.Faction;
import com.stellarcompact.engine.state.FactionId;
import com.stellarcompact.engine.state.Fleet;
import com.stellarcompact.engine.state.GameState;
import com.stellarcompact.engine.state.Planet;
import com.stellarcompact.engine.state.ResourceBundle;
import com.stellarcompact.engine.state.Ship;
import com.stellarcompact.engine.state.SystemId;
import com.stellarcompact.engine.state.TechId;
import com.stellarcompact.engine.state.TechProgress;
import com.stellarcompact.engine.state.TechStatus;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * The passive PRODUCTION resolution step (board card E1-06; game-design
 * {@code 02-economy} sections 2-3). Runs once per tick, in its fixed slot, after
 * every action-driven step. Folds the per-tick economy for every faction:
 *
 * <ol>
 *   <li><b>Production.</b> Each colonised planet yields, per active building,
 *       {@code base(biome) x tech x population} for the resource that building
 *       produces, summed across the faction systems.</li>
 *   <li><b>Upkeep.</b> Active buildings and the faction fleets consume resources
 *       per tick (mostly Energy and Food).</li>
 *   <li><b>Deficit -&gt; attrition.</b> If post-flow stockpile cannot cover upkeep
 *       in some resource, the faction is in deficit: the stockpile is floored at
 *       zero (never negative) and a fraction of its ship strength is lost. A Food
 *       deficit additionally shrinks population.</li>
 *   <li><b>Population dynamics.</b> A Food surplus grows each colony toward its
 *       (building-raised) cap; a Food shortfall shrinks it (famine).</li>
 * </ol>
 *
 * <p><b>Purity / determinism.</b> Pure arithmetic only: no I/O, no wall-clock, no
 * randomness (the economy flow is fully determined by state + profile, so it needs
 * no seeded RNG). Factions, systems and ships are visited in a stable
 * id-then-list order so the fold is replay-stable regardless of map iteration
 * order. Every number is read from the {@link BalanceProfile}; no gameplay
 * constant is hardcoded here (rule 6).
 *
 * <p><b>Settle seam.</b> Resource inflow (production) and outflow (upkeep) are NOT
 * applied to stockpiles directly. They are routed through the tick-wide
 * {@link SpendLedger}: production via {@link SpendLedger#credit}, upkeep via
 * {@link SpendLedger#escrow}. The resolver single authoritative settle pass then
 * applies {@code stockpile + credited - accrued}, floored at zero, so the economy
 * cannot overdraw and the no-negative-balance invariant (E1-04/E1-05) holds even
 * when build/trade spends from earlier steps share the same tick. Population and
 * ship attrition - which are structural, not stockpile - are applied here directly
 * onto the immutable state via copy-on-write.
 */
final class EconomyResolution {

    private EconomyResolution() {
    }

    /**
     * Apply one tick of production, upkeep, deficit attrition and population
     * dynamics to {@code state}, escrowing/crediting net resource flow through
     * {@code ledger} (settled later by the resolver) and writing structural
     * (population, ship) changes directly.
     *
     * @param state   the pre-step snapshot (already folded by steps 1-8)
     * @param profile the active balance profile - source of every number
     * @param ledger  the tick-wide escrow/credit seam (never {@code null})
     * @return the next snapshot with population/ship attrition applied; resource
     * flow is queued in {@code ledger} for the resolver settle pass
     */
    static GameState resolve(GameState state, BalanceProfile profile, SpendLedger ledger) {
        BalanceProfile.Population popCfg = profile.population();
        BalanceProfile.Production prodCfg = profile.production();

        // Visit factions in a stable id order so the fold is replay-stable.
        List<FactionId> factionOrder = new ArrayList<>(state.factions().keySet());
        factionOrder.sort(Comparator.comparing(FactionId::value));

        GameState next = state;
        for (FactionId fid : factionOrder) {
            Faction faction = state.factions().get(fid);

            // 1+2. Gross production (inflow) and building upkeep, summed over owned
            //       systems. Production is computed GROSS here; the energy-deficit
            //       brownout (F2) scales it below, once upkeep is known.
            ResourceBundle production = ResourceBundle.ZERO;
            ResourceBundle upkeep = ResourceBundle.ZERO;

            List<SystemId> systemOrder = ownedSystems(state, fid);
            for (SystemId sid : systemOrder) {
                ActiveSystem system = state.systems().get(sid);
                for (Planet planet : system.planets()) {
                    production = production.plus(planetProduction(planet, faction, profile));
                    upkeep = upkeep.plus(planetUpkeep(planet, profile));
                }
            }

            // Fleet upkeep (ships consume Energy/Food per spec), stable fleet order.
            upkeep = upkeep.plus(fleetUpkeep(state, fid, profile));

            // E10-04 / F2 energy brownout. A faction is in ENERGY deficit this tick when
            // its pre-production Energy (current stockpile + any Energy already credited
            // this tick by an earlier step) cannot cover its accrued Energy upkeep
            // (prior escrow + this step's upkeep). A starved economy must throttle
            // output, so its WHOLE gross production is scaled by energyBrownoutFactor -
            // making Energy a real constraint, not a cosmetic one. Brownout is decided
            // on PRE-production energy on purpose: the faction's own fresh production
            // cannot bootstrap it out of the brownout within the same tick (it must
            // build a SOLAR_ARRAY / stop starving energy to recover next tick).
            double energyAvailable = faction.stockpiles().energy() + ledger.creditedTo(fid).energy();
            double energyOwed = ledger.accrued(fid).energy() + upkeep.energy();
            boolean energyDeficit = energyAvailable < energyOwed;
            if (energyDeficit && prodCfg.energyBrownoutFactor() < 1.0) {
                production = production.scale(prodCfg.energyBrownoutFactor());
            }

            // Route net resource flow through the authoritative settle seam.
            if (!production.equals(ResourceBundle.ZERO)) {
                ledger.credit(fid, production);
            }
            if (!upkeep.equals(ResourceBundle.ZERO)) {
                ledger.escrow(fid, upkeep);
            }

            // 3. Deficit detection at the seam: does post-flow stockpile cover the
            //    accrued upkeep + any earlier build/trade spend this tick? The
            //    ledger collective view is exactly the overdraft test we need.
            ResourceBundle afterInflow = faction.stockpiles().plus(ledger.creditedTo(fid));
            boolean deficit = ledger.wouldOverdraw(fid, afterInflow);
            boolean foodDeficit = afterInflow.food() < ledger.accrued(fid).food();

            // 3a. Ship attrition on any resource deficit (fleets lose strength).
            if (deficit) {
                next = attriteFleets(next, fid, profile.resources().deficitAttritionRate());
            }

            // 4. Population dynamics per owned planet (growth on Food surplus,
            //    famine decline on Food deficit), clamped to [0, cap].
            for (SystemId sid : systemOrder) {
                ActiveSystem system = next.systems().get(sid);
                ActiveSystem updated = applyPopulation(system, popCfg, foodDeficit);
                if (!updated.equals(system)) {
                    next = next.withSystem(updated);
                }
            }
        }
        return next;
    }

    /** Owned systems for a faction, in stable {@link SystemId} order. */
    private static List<SystemId> ownedSystems(GameState state, FactionId fid) {
        List<SystemId> owned = new ArrayList<>();
        for (Map.Entry<SystemId, ActiveSystem> e : state.systems().entrySet()) {
            if (e.getValue().owner().isPresent() && e.getValue().owner().get().equals(fid)) {
                owned.add(e.getKey());
            }
        }
        owned.sort(Comparator.comparing(SystemId::value));
        return owned;
    }

    // ===== production ==========================================================

    /**
     * Production for one planet: for each ACTIVE building that produces a resource,
     * {@code base(biome, resource) x techMultiplier(faction, resource) x
     * populationFactor(planet)}. Buildings with no passive output (market hub,
     * shipyard, defence platform, terraformer) contribute nothing.
     */
    private static ResourceBundle planetProduction(Planet planet, Faction faction,
                                                   BalanceProfile profile) {
        ResourceBundle biomeYield = biomeYield(planet.biome(), profile);
        double popFactor = 1.0 + planet.population() * profile.population().productionPerPop();
        BalanceProfile.Production prodCfg = profile.production();

        EnumMap<OutputResource, Double> tech = techMultipliers(faction, profile);

        // E10-04 / F3 mineral sink: the per-planet mine taper. Visit buildings in
        // stable slot order so the taper assignment (which mine is the k-th) is
        // deterministic; count ACTIVE mines as we go and decay each mine past the
        // soft cap by mineTaperFactor^(index past cap). With a factor < 1 the
        // geometric sum converges, so total mine yield per planet is bounded no
        // matter how many mines exist - minerals can no longer hoard without bound.
        double energy = 0, minerals = 0, food = 0, techOut = 0, influence = 0;
        int mineIndex = 0;
        for (Building b : orderedBuildings(planet)) {
            if (b.status() != BuildingStatus.ACTIVE) {
                continue; // idle / under-construction buildings produce nothing
            }
            OutputResource out = outputOf(b.type());
            if (out == null) {
                continue;
            }
            double base = component(biomeYield, out);
            double yield = base * popFactor * tech.getOrDefault(out, 1.0);
            if (b.type() == BuildingType.MINE) {
                yield *= mineTaper(mineIndex, prodCfg);
                mineIndex++;
            }
            switch (out) {
                case ENERGY -> energy += yield;
                case MINERALS -> minerals += yield;
                case FOOD -> food += yield;
                case TECH -> techOut += yield;
                case INFLUENCE -> influence += yield;
            }
        }
        return new ResourceBundle(energy, minerals, food, techOut, influence);
    }

    /**
     * The mine-yield multiplier for the {@code mineIndex}-th ACTIVE mine on a planet
     * (0-based). Mines within the soft cap yield full base ({@code 1.0}); the k-th
     * mine past the cap yields {@code mineTaperFactor^(k+1)}. A factor of {@code 1.0}
     * (the inert default) means no taper at all. Pure, allocation-free.
     */
    private static double mineTaper(int mineIndex, BalanceProfile.Production prodCfg) {
        int past = mineIndex - prodCfg.mineSoftCapPerPlanet();
        if (past < 0) {
            return 1.0; // within the soft cap: full yield
        }
        return Math.pow(prodCfg.mineTaperFactor(), past + 1);
    }

    /** Planet buildings in a stable slot order so the mine taper is deterministic. */
    private static List<Building> orderedBuildings(Planet planet) {
        List<Building> ordered = new ArrayList<>(planet.buildings());
        ordered.sort(Comparator.comparingInt(Building::slotIndex));
        return ordered;
    }

    /**
     * The product of every UNLOCKED tech multiplier the faction holds, grouped by
     * the output resource it boosts. Multipliers and their association live in
     * config / this documented rule mapping; absent techs default to x1.
     */
    private static EnumMap<OutputResource, Double> techMultipliers(Faction faction,
                                                                   BalanceProfile profile) {
        EnumMap<OutputResource, Double> result = new EnumMap<>(OutputResource.class);
        Map<String, Double> cfg = profile.tech().multipliers();
        for (Map.Entry<TechId, TechProgress> e : faction.techProgress().entrySet()) {
            if (e.getValue().status() != TechStatus.UNLOCKED) {
                continue;
            }
            String key = e.getKey().value();
            Double mult = cfg.get(key);
            OutputResource boosts = techBoosts(key);
            if (mult == null || boosts == null) {
                continue; // tech with no production multiplier (e.g. combat doctrine)
            }
            result.merge(boosts, mult, (a, b) -> a * b);
        }
        return result;
    }

    /**
     * Which production resource a tech node boosts. This is a game rule (the tech
     * tree shape), not a tunable number - the magnitudes are in
     * {@code tech.multipliers}. Unlisted techs boost no passive production.
     */
    private static OutputResource techBoosts(String techKey) {
        return switch (techKey) {
            case "improvedExtraction" -> OutputResource.MINERALS;
            case "hydroponics" -> OutputResource.FOOD;
            case "marketNetworks", "diplomaticCorps" -> OutputResource.INFLUENCE;
            default -> null;
        };
    }

    /** Maps a building to the single resource it passively produces, or null. */
    private static OutputResource outputOf(BuildingType type) {
        return switch (type) {
            case MINE -> OutputResource.MINERALS;
            case SOLAR_ARRAY -> OutputResource.ENERGY;
            case FARM -> OutputResource.FOOD;
            case RESEARCH_LAB -> OutputResource.TECH;
            case MONUMENT -> OutputResource.INFLUENCE;
            case MARKET_HUB, SHIPYARD, DEFENSE_PLATFORM, TERRAFORMER -> null;
        };
    }

    private static double component(ResourceBundle b, OutputResource out) {
        return switch (out) {
            case ENERGY -> b.energy();
            case MINERALS -> b.minerals();
            case FOOD -> b.food();
            case TECH -> b.tech();
            case INFLUENCE -> b.influence();
        };
    }

    /** Closed set of passively-produced resources (the four physical + Influence). */
    private enum OutputResource { ENERGY, MINERALS, FOOD, TECH, INFLUENCE }

    // ===== upkeep ==============================================================

    /** Upkeep of every ACTIVE building on a planet (idle buildings cost nothing). */
    private static ResourceBundle planetUpkeep(Planet planet, BalanceProfile profile) {
        ResourceBundle total = ResourceBundle.ZERO;
        Map<String, BalanceProfile.ResourceBundle> upkeep = profile.resources().upkeep();
        for (Building b : planet.buildings()) {
            if (b.status() != BuildingStatus.ACTIVE) {
                continue;
            }
            BalanceProfile.ResourceBundle cost = upkeep.get(b.type().configKey());
            if (cost != null) {
                total = total.plus(toState(cost));
            }
        }
        return total;
    }

    /** Upkeep of all the faction ships, keyed by ship spec, stable fleet order. */
    private static ResourceBundle fleetUpkeep(GameState state, FactionId fid,
                                              BalanceProfile profile) {
        Map<String, BalanceProfile.ResourceBundle> upkeep = profile.resources().upkeep();
        List<Fleet> fleets = ownedFleets(state, fid);
        ResourceBundle total = ResourceBundle.ZERO;
        for (Fleet fleet : fleets) {
            for (Ship ship : fleet.ships()) {
                BalanceProfile.ResourceBundle cost = upkeep.get(ship.spec());
                if (cost != null && ship.count() > 0) {
                    total = total.plus(toState(cost).scale(ship.count()));
                }
            }
        }
        return total;
    }

    private static List<Fleet> ownedFleets(GameState state, FactionId fid) {
        List<Fleet> owned = new ArrayList<>();
        for (Fleet f : state.fleets().values()) {
            if (f.owner().equals(fid)) {
                owned.add(f);
            }
        }
        owned.sort(Comparator.comparing(f -> f.id().value()));
        return owned;
    }

    // ===== attrition ===========================================================

    /**
     * On a resource deficit, every ship stack of the faction loses
     * {@code ceil(count x rate)} ships (fleets lose strength - economy 02 section
     * 2). Deterministic: a fixed fraction, no roll. Stacks that reach zero are kept
     * as an empty stack; fleet pruning is a later card concern.
     */
    private static GameState attriteFleets(GameState state, FactionId fid, double rate) {
        GameState next = state;
        for (Fleet fleet : ownedFleets(state, fid)) {
            List<Ship> reduced = new ArrayList<>(fleet.ships().size());
            boolean changed = false;
            for (Ship ship : fleet.ships()) {
                int loss = (int) Math.ceil(ship.count() * rate);
                int remaining = Math.max(0, ship.count() - loss);
                if (remaining != ship.count()) {
                    changed = true;
                }
                reduced.add(new Ship(ship.spec(), remaining));
            }
            if (changed) {
                next = next.withFleet(new Fleet(fleet.id(), fleet.owner(), fleet.location(),
                        fleet.enroutePath(), fleet.stance(), reduced));
            }
        }
        return next;
    }

    // ===== population ==========================================================

    /**
     * Population dynamics for one system planets (economy 02 section 3). Because
     * Food is pooled at faction level, we model per-planet pressure simply: a
     * faction Food deficit causes famine decline on every planet; otherwise each
     * colony grows by {@code growthPerFoodSurplus} of its current size, capped at
     * {@code baseCap + sum(capByBuilding)} for its active buildings.
     */
    private static ActiveSystem applyPopulation(ActiveSystem system,
                                                BalanceProfile.Population popCfg,
                                                boolean foodDeficit) {
        List<Planet> planets = new ArrayList<>(system.planets().size());
        boolean changed = false;
        long systemPop = 0;
        for (Planet planet : system.planets()) {
            long cap = popCap(planet, popCfg);
            long pop = planet.population();
            long newPop;
            if (foodDeficit) {
                long decline = (long) Math.ceil(pop * popCfg.declinePerFoodDeficit());
                newPop = Math.max(0, pop - decline);
            } else {
                // Growth proportional to current size; a fresh colony seeds one
                // settler (pop+1) so it can take root.
                long growth = (long) Math.floor((pop + 1) * popCfg.growthPerFoodSurplus());
                newPop = Math.min(cap, pop + Math.max(0, growth));
            }
            if (newPop != pop) {
                changed = true;
            }
            planets.add(new Planet(planet.id(), planet.biome(), planet.slotsTotal(),
                    newPop, planet.buildings()));
            systemPop += newPop;
        }
        if (!changed) {
            return system;
        }
        return new ActiveSystem(system.id(), system.name(), system.coords(), system.owner(),
                planets, systemPop, system.loyalty());
    }

    /** Effective population cap: base plus the contribution of active buildings. */
    private static long popCap(Planet planet, BalanceProfile.Population popCfg) {
        long cap = popCfg.baseCap();
        Map<String, Integer> byBuilding = popCfg.capByBuilding();
        for (Building b : planet.buildings()) {
            if (b.status() != BuildingStatus.ACTIVE) {
                continue;
            }
            Integer add = byBuilding.get(b.type().configKey());
            if (add != null) {
                cap += add;
            }
        }
        return cap;
    }

    // ===== config <-> state bundle bridge ======================================

    /**
     * The biome yield bundle from the profile, bridging the {@code Biome} configKey
     * to the profile key (the shipped profiles use {@code "gas"} for the gas giant).
     * Missing biomes (none in a valid profile) default to all-zero.
     */
    private static ResourceBundle biomeYield(Biome biome, BalanceProfile profile) {
        Map<String, BalanceProfile.ResourceBundle> yields = profile.resources().biomeYields();
        BalanceProfile.ResourceBundle y = yields.get(biome.configKey());
        if (y == null && biome == Biome.GAS_GIANT) {
            y = yields.get("gas"); // shipped-profile key for the gas giant biome
        }
        return y == null ? ResourceBundle.ZERO : toState(y);
    }

    /** Convert a config ResourceBundle to a state ResourceBundle (decoupled types). */
    private static ResourceBundle toState(BalanceProfile.ResourceBundle b) {
        return new ResourceBundle(b.energy(), b.minerals(), b.food(), b.tech(), b.influence());
    }
}
