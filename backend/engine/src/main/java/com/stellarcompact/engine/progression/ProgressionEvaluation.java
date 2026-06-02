package com.stellarcompact.engine.progression;

import com.stellarcompact.engine.config.BalanceProfile;
import com.stellarcompact.engine.resolve.Scoring;
import com.stellarcompact.engine.state.Faction;
import com.stellarcompact.engine.state.FactionId;
import com.stellarcompact.engine.state.GameState;
import com.stellarcompact.engine.state.GameStatus;
import com.stellarcompact.engine.state.ResourceBundle;

import java.util.List;
import java.util.Map;

/**
 * The pure small-&gt;large progression logic (E9-01; game-design 07 section 6, 06 section
 * 5). It does three things, all as pure functions of a snapshot + the active
 * {@link BalanceProfile} (no clock, no I/O, no randomness; rule 1 + rule 6 - every number
 * comes from {@code progression.*}):
 *
 * <ol>
 *   <li><b>Derive standing</b> ({@link #standingOf}). From a <em>concluded</em> match,
 *       project each faction's final {@link StandingRecord} (its identity, score,
 *       reputation, placement) off the {@code Scoring} ranking. This is the durable
 *       result the progression loop carries.</li>
 *   <li><b>Gate the seat</b> ({@link #earnsSeat}, {@link #admits}). Completing a small
 *       galaxy earns standing; a destination LARGE profile admits a Sovereign only when
 *       its prior standing clears the configured seat threshold (or, optionally, it won).
 *       A faction below the bar is denied a large-galaxy seat.</li>
 *   <li><b>Carry only identity + reputation</b> ({@link #carry}, {@link #seedFaction}).
 *       Crossing the boundary yields a {@link CarriedIdentity} that holds identity and a
 *       config-weighted slice of reputation - never material state. Seeding a faction
 *       into the new match RESETS its stockpiles/tech to the destination profile's
 *       starter loadout, so no resources, tech or fleets leak across matches.</li>
 * </ol>
 *
 * <p>This class is the engine's pure progression kernel. Persisting standing records
 * between matches, and actually constructing the next match's {@code GameState} from
 * carried identities, are orchestration concerns (the api/persistence modules call into
 * these pure functions); the engine itself stays free of that wiring.
 */
public final class ProgressionEvaluation {

    private ProgressionEvaluation() {
    }

    // ===== 1. derive standing from a concluded match ==========================

    /**
     * @return the {@link GalaxySizeClass} this profile configures (its
     * {@code progression.sizeClass}).
     */
    public static GalaxySizeClass sizeClassOf(BalanceProfile profile) {
        return GalaxySizeClass.fromConfig(profile.progression().sizeClass());
    }

    /**
     * Project the final {@link StandingRecord} for {@code faction} from a concluded
     * match. Placement is the faction's 1-based rank in the stable {@code Scoring}
     * ranking (1 = top); score and reputation are read off the final snapshot.
     *
     * @param state   the match snapshot; must be {@link GameStatus#CONCLUDED}
     * @param faction the faction whose standing to derive
     * @param profile the active profile (source of score weights and the tier)
     * @return the faction's standing in this concluded match
     * @throws IllegalArgumentException if the match is not concluded or the faction is
     *                                  absent
     */
    public static StandingRecord standingOf(GameState state, FactionId faction,
                                            BalanceProfile profile) {
        requireConcluded(state);
        Faction f = state.factions().get(faction);
        if (f == null) {
            throw new IllegalArgumentException(
                    "standingOf: faction " + faction.value() + " absent from match");
        }
        List<Scoring.FactionScore> ranking = Scoring.rank(state, profile);
        int placement = placementOf(ranking, faction);
        double score = scoreOf(ranking, faction);
        return new StandingRecord(
                f.id(),
                f.name(),
                sizeClassOf(profile),
                score,
                f.reputation(),
                placement,
                ranking.size());
    }

    /**
     * @return every faction's {@link StandingRecord} for the concluded match, in the
     * stable {@code Scoring} ranking order (placement 1 first). The full result table the
     * progression loop and tournament scaffolding consume.
     */
    public static List<StandingRecord> standings(GameState state, BalanceProfile profile) {
        requireConcluded(state);
        List<Scoring.FactionScore> ranking = Scoring.rank(state, profile);
        GalaxySizeClass tier = sizeClassOf(profile);
        java.util.ArrayList<StandingRecord> out = new java.util.ArrayList<>(ranking.size());
        int matchSize = ranking.size();
        for (int i = 0; i < ranking.size(); i++) {
            Scoring.FactionScore fs = ranking.get(i);
            Faction f = state.factions().get(fs.faction());
            out.add(new StandingRecord(
                    f.id(), f.name(), tier, fs.score(), f.reputation(), i + 1, matchSize));
        }
        return List.copyOf(out);
    }

    // ===== 2. gate the large-galaxy seat ======================================

    /**
     * @return {@code true} iff a {@code standing} earned in a SMALL galaxy qualifies the
     * Sovereign for a seat in a galaxy governed by {@code destination}. The gate
     * (game-design 07 section 6 "completing small galaxies earns a seat"):
     * <ul>
     *   <li>standing must come from a {@link GalaxySizeClass#SMALL} match (you graduate
     *       <em>from</em> small);</li>
     *   <li>and either the standing's {@code score} is &gt;= the destination's
     *       {@code progression.seatThresholdScore}, or
     *       {@code progression.winGrantsSeat} is on and the Sovereign won (placed 1st).</li>
     * </ul>
     * A faction below the threshold (and not a winner, when {@code winGrantsSeat}) is
     * denied; at or above is admitted.
     */
    public static boolean earnsSeat(StandingRecord standing, BalanceProfile destination) {
        if (standing == null || standing.sizeClass() != GalaxySizeClass.SMALL) {
            return false;
        }
        BalanceProfile.Progression cfg = destination.progression();
        if (cfg.winGrantsSeat() && standing.won()) {
            return true;
        }
        return standing.score() >= cfg.seatThresholdScore();
    }

    /**
     * @return {@code true} iff a faction holding {@code standing} should be admitted into
     * the {@code destination} match. A LARGE destination gates entry on
     * {@link #earnsSeat}; any other tier (a SMALL/funnel galaxy) is open entry - the
     * funnel never gates, only the persistent campaign does.
     */
    public static boolean admits(StandingRecord standing, BalanceProfile destination) {
        if (sizeClassOf(destination) != GalaxySizeClass.LARGE) {
            return true;
        }
        return earnsSeat(standing, destination);
    }

    // ===== 3. carry ONLY identity + reputation ================================

    /**
     * Build the {@link CarriedIdentity} a Sovereign takes into a {@code destination}
     * match from its prior {@code standing}. Carries the identity (faction id + name) and
     * a {@code reputationCarryWeight}-scaled slice of the prior reputation - and nothing
     * material. With weight {@code 0.0} the carried reputation is {@code 0.0} (a clean,
     * identity-only start); with weight {@code 1.0} reputation crosses intact.
     */
    public static CarriedIdentity carry(StandingRecord standing, BalanceProfile destination) {
        if (standing == null) {
            throw new IllegalArgumentException("carry: standing must be set");
        }
        double weight = destination.progression().reputationCarryWeight();
        return new CarriedIdentity(
                standing.faction(),
                standing.displayName(),
                standing.reputation() * weight);
    }

    /**
     * Materialise the starting {@link Faction} for a carried-over Sovereign in a new
     * match. The result carries the Sovereign's identity (id + name) and its carried
     * reputation, but its MATERIAL state is reset to the destination profile's
     * {@code progression.starterStockpile} with an EMPTY tech DAG and no revealed-intel -
     * i.e. exactly the starter loadout every faction in that match gets, never the prior
     * match's resources, tech or fleets. (Fleets/systems are not faction-local state and
     * are simply never created for a carried identity here; the orchestrator places the
     * fresh, seeded home exactly as for a brand-new faction.)
     *
     * <p>This is the enforcement point of the no-material-advantage rule: identity and a
     * weighted reputation persist; everything material is the fresh starter.
     *
     * @param identity the carried identity (from {@link #carry})
     * @param destination the new match's profile (source of the starter loadout)
     * @return the seeded starting faction for the new match
     */
    public static Faction seedFaction(CarriedIdentity identity, BalanceProfile destination) {
        if (identity == null) {
            throw new IllegalArgumentException("seedFaction: identity must be set");
        }
        ResourceBundle starter = toState(destination.progression().starterStockpile());
        return new Faction(
                identity.faction(),
                identity.displayName(),
                identity.carriedReputation(),
                starter,
                Map.of());
    }

    /**
     * Convenience for the common path: derive the standing for {@code faction} from the
     * concluded {@code source} match, then seed a fresh starting faction for the
     * {@code destination} match - identity + weighted reputation only, material state
     * reset to the destination starter. Throws if the faction is not admitted to the
     * destination (callers should gate with {@link #admits} first; this guard makes the
     * no-illegal-entry rule impossible to bypass by accident).
     */
    public static Faction carryOver(GameState source, FactionId faction,
                                    BalanceProfile sourceProfile, BalanceProfile destination) {
        StandingRecord standing = standingOf(source, faction, sourceProfile);
        if (!admits(standing, destination)) {
            throw new IllegalStateException(
                    "carryOver: faction " + faction.value()
                            + " is not admitted to the destination galaxy (insufficient standing)");
        }
        return seedFaction(carry(standing, destination), destination);
    }

    // ===== helpers ============================================================

    private static void requireConcluded(GameState state) {
        if (state == null) {
            throw new IllegalArgumentException("progression: state must be set");
        }
        if (state.status() != GameStatus.CONCLUDED) {
            throw new IllegalArgumentException(
                    "progression: standing can only be derived from a CONCLUDED match, was "
                            + state.status());
        }
    }

    private static int placementOf(List<Scoring.FactionScore> ranking, FactionId faction) {
        for (int i = 0; i < ranking.size(); i++) {
            if (ranking.get(i).faction().equals(faction)) {
                return i + 1;
            }
        }
        throw new IllegalArgumentException(
                "faction " + faction.value() + " not present in ranking");
    }

    private static double scoreOf(List<Scoring.FactionScore> ranking, FactionId faction) {
        for (Scoring.FactionScore fs : ranking) {
            if (fs.faction().equals(faction)) {
                return fs.score();
            }
        }
        return 0.0;
    }

    /** Map a config-module ResourceBundle (the starter loadout) into the state bundle. */
    private static ResourceBundle toState(BalanceProfile.ResourceBundle cfg) {
        return new ResourceBundle(
                cfg.energy(), cfg.minerals(), cfg.food(), cfg.tech(), cfg.influence());
    }
}
