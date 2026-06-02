package com.stellarcompact.engine.resolve;

import com.stellarcompact.engine.config.BalanceProfile;
import com.stellarcompact.engine.state.ActiveSystem;
import com.stellarcompact.engine.state.Building;
import com.stellarcompact.engine.state.BuildingStatus;
import com.stellarcompact.engine.state.BuildingType;
import com.stellarcompact.engine.state.Faction;
import com.stellarcompact.engine.state.FactionId;
import com.stellarcompact.engine.state.Fleet;
import com.stellarcompact.engine.state.GameState;
import com.stellarcompact.engine.state.Planet;
import com.stellarcompact.engine.state.Route;
import com.stellarcompact.engine.state.RouteStatus;
import com.stellarcompact.engine.state.Ship;
import com.stellarcompact.engine.state.TechProgress;
import com.stellarcompact.engine.state.TechStatus;
import com.stellarcompact.engine.state.Treaty;
import com.stellarcompact.engine.state.TreatyStatus;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Config-weighted match scoring (board card E1-15; game-design 07 section 2). Even a
 * match with no clean victory (tick limit reached) produces a ranking, so every Sovereign
 * always gets a score that feeds tournament brackets and the small-to-large progression
 * gating.
 *
 * <p>Pure and deterministic. A score is a pure function of the snapshot plus the
 * {@link BalanceProfile.ScoreWeights}; no randomness, no clock, no I/O. Factions are
 * ranked in a total order (score desc, then faction id asc) so ties break stably and the
 * ranking is replay-identical. Scores are transient output (like {@link PublicEvent}s),
 * never written back onto {@link GameState}, so computing them does not perturb the
 * golden state hash.
 *
 * <p>The seven weighted factors (game-design 07 section 2). For a faction the raw factors
 * are: systems controlled, total Influence, economic output (summed physical stockpiles
 * Energy+Minerals+Food+Tech), tech depth (UNLOCKED nodes), reputation, military strength
 * (sum of ship tier multipliers across its fleets), and diplomatic centrality (ACTIVE
 * treaties + owned non-SUSPENDED routes it anchors). Each is scaled by its config weight
 * and summed; the weights live in the profile (rule 6), nothing is hardcoded here.
 */
public final class Scoring {

    private Scoring() {
    }

    /**
     * One faction final standing: its id and weighted {@link #score}. Immutable;
     * ranked by score descending then id ascending for a stable total order.
     */
    public record FactionScore(FactionId faction, double score) {
        public FactionScore {
            if (faction == null) {
                throw new IllegalArgumentException("FactionScore.faction must be set");
            }
        }
    }

    /** The stable ranking comparator: score descending, then faction id ascending. */
    private static final Comparator<FactionScore> RANK =
            Comparator.comparingDouble(FactionScore::score).reversed()
                    .thenComparing(fs -> fs.faction().value());

    /**
     * @return every faction {@link FactionScore} for {@code state}, ranked in the stable
     * total order (highest score first; faction id breaks ties). The leader is element 0.
     */
    public static List<FactionScore> rank(GameState state, BalanceProfile profile) {
        List<FactionScore> scores = new ArrayList<>();
        for (FactionId fid : state.factions().keySet()) {
            scores.add(new FactionScore(fid, score(state, fid, profile)));
        }
        scores.sort(RANK);
        return List.copyOf(scores);
    }

    /**
     * @return the weighted score of {@code faction} in {@code state} under the profile
     * {@link BalanceProfile.ScoreWeights}. Absent factions score {@code 0}.
     */
    public static double score(GameState state, FactionId faction, BalanceProfile profile) {
        Faction f = state.factions().get(faction);
        if (f == null) {
            return 0.0;
        }
        BalanceProfile.ScoreWeights w = profile.victory().scoreWeights();
        return w.systems() * systemsControlled(state, faction)
                + w.influence() * f.stockpiles().influence()
                + w.economy() * economicOutput(f)
                + w.tech() * techDepth(f)
                + w.reputation() * f.reputation()
                + w.military() * militaryStrength(state, faction, profile)
                + w.centrality() * diplomaticCentrality(state, faction);
    }

    // ===== raw factors (pure) =================================================

    static long systemsControlled(GameState state, FactionId faction) {
        long owned = 0;
        for (ActiveSystem s : state.systems().values()) {
            if (ownedBy(s, faction)) {
                owned++;
            }
        }
        return owned;
    }

    /** Economic output proxy: the summed physical stockpiles (Influence excluded). */
    private static double economicOutput(Faction f) {
        return f.stockpiles().energy() + f.stockpiles().minerals()
                + f.stockpiles().food() + f.stockpiles().tech();
    }

    /** Tech depth: the count of UNLOCKED tech nodes. */
    private static long techDepth(Faction f) {
        long depth = 0;
        for (TechProgress tp : f.techProgress().values()) {
            if (tp.status() == TechStatus.UNLOCKED) {
                depth++;
            }
        }
        return depth;
    }

    /**
     * Military strength: the sum over the faction fleets of {@code count x tierMultiplier}
     * for every ship stack (the same per-spec tier multiplier the combat model uses). A
     * spec absent from {@code combat.tierMultipliers} contributes the neutral {@code 1.0}.
     */
    private static double militaryStrength(GameState state, FactionId faction,
                                           BalanceProfile profile) {
        double strength = 0.0;
        for (Fleet fleet : state.fleets().values()) {
            if (!fleet.owner().equals(faction)) {
                continue;
            }
            for (Ship ship : fleet.ships()) {
                double tier = profile.combat().tierMultipliers()
                        .getOrDefault(ship.spec(), 1.0);
                strength += ship.count() * tier;
            }
        }
        return strength;
    }

    /**
     * Diplomatic centrality: how many agreements/routes the faction anchors - its ACTIVE
     * treaties plus its owned non-SUSPENDED routes (the "treaties/routes it anchors" of
     * game-design 07 section 2).
     */
    private static long diplomaticCentrality(GameState state, FactionId faction) {
        long centrality = 0;
        for (Treaty t : state.treaties().values()) {
            if (t.status() == TreatyStatus.ACTIVE && t.involves(faction)) {
                centrality++;
            }
        }
        for (Route r : state.routes().values()) {
            if (r.owner().equals(faction) && r.status() != RouteStatus.SUSPENDED) {
                centrality++;
            }
        }
        return centrality;
    }

    static boolean ownedBy(ActiveSystem system, FactionId faction) {
        Optional<FactionId> owner = system.owner();
        return owner.isPresent() && owner.get().equals(faction);
    }

    /** @return true iff the system has an ACTIVE Monument building (Wonder stage marker). */
    static boolean hasActiveMonument(ActiveSystem system) {
        for (Planet planet : system.planets()) {
            for (Building b : planet.buildings()) {
                if (b.type() == BuildingType.MONUMENT && b.status() == BuildingStatus.ACTIVE) {
                    return true;
                }
            }
        }
        return false;
    }
}
