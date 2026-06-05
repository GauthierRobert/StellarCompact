package com.stellarcompact.engine.kardashev;

import com.stellarcompact.engine.config.BalanceProfile;
import com.stellarcompact.engine.state.ActiveSystem;
import com.stellarcompact.engine.state.Building;
import com.stellarcompact.engine.state.BuildingStatus;
import com.stellarcompact.engine.state.Faction;
import com.stellarcompact.engine.state.FactionId;
import com.stellarcompact.engine.state.GameState;
import com.stellarcompact.engine.state.Planet;
import com.stellarcompact.engine.state.TechId;
import com.stellarcompact.engine.state.TechProgress;
import com.stellarcompact.engine.state.TechStatus;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Kardashev calculator (E12-06) — the central progression metric.
 *
 * <p>A faction's standing is the <em>energy it captures</em>, in watts, derived
 * each tick from the authoritative state:
 * <pre>
 *   W = (baseWattsPerSystem · ownedSystems + populationWattFactor · population
 *        + Σ wattsPerBuilding[ACTIVE energy buildings]) · Π techWattMultipliers
 *      + Σ wattsPerBuilding[ACTIVE megastructures]
 *   K = (log10(W) − 6) / 10        (clamped at 0)
 * </pre>
 * so Type I ≈ 10¹⁶ W (K=1), II ≈ 10²⁶ W (K=2), III ≈ 10³⁶ W (K=3). The watt
 * amounts are balance config ({@link BalanceProfile.Kardashev}); the formula is
 * the Kardashev definition.
 *
 * <p><b>Derived, not persisted.</b> K is recomputed from {@code GameState}; it is
 * never stored, so it adds no state-hash surface and the replay contract is
 * unchanged. <b>Pure + deterministic:</b> no I/O, no RNG, no wall-clock; systems
 * and techs are summed in a stable sorted order so the floating-point result is
 * identical across runs (mirrors the {@code StateHasher} discipline). Mirrors the
 * frontend reference model {@code frontend/.../empire/kardashev.ts}.
 */
public final class KardashevCalculator {

    private KardashevCalculator() {
    }

    /** Total captured power (watts) for a faction, derived from authoritative state. */
    public static double capturedWatts(GameState state, FactionId faction, BalanceProfile profile) {
        Faction f = state.factions().get(faction);
        if (f == null) {
            return 0.0;
        }
        BalanceProfile.Kardashev cfg = profile.kardashev();
        Map<String, Double> wattsPerBuilding = cfg.wattsPerBuilding();

        double planetaryBuildings = 0.0;
        double megastructures = 0.0;
        long ownedSystems = 0;
        long population = 0;

        for (ActiveSystem sys : ownedSystemsSorted(state, faction)) {
            ownedSystems++;
            population += sys.population();
            for (Planet planet : sys.planets()) {
                for (Building b : buildingsSorted(planet)) {
                    if (b.status() != BuildingStatus.ACTIVE) {
                        continue;
                    }
                    Double w = wattsPerBuilding.get(b.type().configKey());
                    if (w == null) {
                        continue;
                    }
                    if (b.type().isMegastructure()) {
                        megastructures += w;
                    } else {
                        planetaryBuildings += w;
                    }
                }
            }
        }

        double planetary = planetaryBuildings
                + cfg.baseWattsPerSystem() * ownedSystems
                + cfg.populationWattFactor() * population;

        // Multiplicative tech bonuses on the planetary grid (sorted ⇒ stable product).
        double multiplier = 1.0;
        for (Map.Entry<TechId, TechProgress> e : techSorted(f)) {
            if (e.getValue().status() != TechStatus.UNLOCKED) {
                continue;
            }
            Double m = cfg.techWattMultipliers().get(e.getKey().value());
            if (m != null) {
                multiplier *= m;
            }
        }
        planetary *= multiplier;

        return planetary + megastructures;
    }

    /** Continuous Kardashev value, clamped at 0. */
    public static double kValue(GameState state, FactionId faction, BalanceProfile profile) {
        return kFromWatts(capturedWatts(state, faction, profile));
    }

    /** Whole Kardashev tier (floor of {@link #kValue}). 0 = Type 0, 1 = Type I, … */
    public static int tier(GameState state, FactionId faction, BalanceProfile profile) {
        return (int) Math.floor(kValue(state, faction, profile));
    }

    /** Pure watts → continuous K. Definition: {@code (log10(W) − 6) / 10}, clamped at 0. */
    public static double kFromWatts(double watts) {
        if (watts <= 1.0) {
            return 0.0;
        }
        double k = (Math.log10(watts) - 6.0) / 10.0;
        return k < 0.0 ? 0.0 : k;
    }

    // ----- deterministic iteration helpers -----------------------------------

    private static List<ActiveSystem> ownedSystemsSorted(GameState state, FactionId faction) {
        List<ActiveSystem> owned = new ArrayList<>();
        for (ActiveSystem sys : state.systems().values()) {
            if (sys.owner().isPresent() && sys.owner().get().equals(faction)) {
                owned.add(sys);
            }
        }
        owned.sort(Comparator.comparing(s -> String.valueOf(s.id())));
        return owned;
    }

    private static List<Building> buildingsSorted(Planet planet) {
        List<Building> ordered = new ArrayList<>(planet.buildings());
        ordered.sort(Comparator.comparingInt(Building::slotIndex));
        return ordered;
    }

    private static List<Map.Entry<TechId, TechProgress>> techSorted(Faction faction) {
        List<Map.Entry<TechId, TechProgress>> entries = new ArrayList<>(faction.techProgress().entrySet());
        entries.sort(Comparator.comparing(e -> e.getKey().value()));
        return entries;
    }
}
