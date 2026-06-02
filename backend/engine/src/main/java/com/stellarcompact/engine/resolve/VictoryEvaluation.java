package com.stellarcompact.engine.resolve;

import com.stellarcompact.engine.config.BalanceProfile;
import com.stellarcompact.engine.state.ActiveSystem;
import com.stellarcompact.engine.state.Building;
import com.stellarcompact.engine.state.BuildingStatus;
import com.stellarcompact.engine.state.BuildingType;
import com.stellarcompact.engine.state.Faction;
import com.stellarcompact.engine.state.FactionId;
import com.stellarcompact.engine.state.GameState;
import com.stellarcompact.engine.state.GameStatus;
import com.stellarcompact.engine.state.LifecycleTransitions;
import com.stellarcompact.engine.state.Planet;
import com.stellarcompact.engine.state.Treaty;
import com.stellarcompact.engine.state.TreatyStatus;
import com.stellarcompact.engine.state.TreatyType;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The end-of-tick victory / elimination / lifecycle evaluation (board card E1-15;
 * game-design 07). A passive, snapshot-driven pass that runs inside the EVENTS step, so
 * any {@link PublicEvent} it emits lands on the same tick, in the fixed final slot, after
 * every state-changing step. It does two things, in this fixed order:
 *
 * <ol>
 *   <li><b>Elimination and vassalage</b> (section 3). When
 *       {@code victory.eliminationEnabled}, a faction that controls zero systems (lost its
 *       capital and all colonies) and is NOT a protected vassal is eliminated; emit
 *       {@link PublicEvent.FactionEliminated}. A vassal (the junior party of an ACTIVE
 *       {@link TreatyType#VASSALAGE} treaty) is never eliminated by this rule - it
 *       capitulated to stay in the game (game-design 07 section 3).</li>
 *   <li><b>Victory</b> (section 1). When {@code victory.conditionEnabled} and the match is
 *       still {@link GameStatus#RUNNING}, evaluate the single {@code victory.active}
 *       condition. On a win, emit {@link PublicEvent.VictoryAchieved} for the winner (and
 *       each alliance partner who shares the win) and transition RUNNING to CONCLUDED via
 *       {@link LifecycleTransitions}.</li>
 * </ol>
 *
 * <p>Pure and deterministic. No randomness, no clock, no I/O: victory and elimination are
 * pure functions of the snapshot plus the {@link BalanceProfile.Victory} config (rule 6 -
 * every threshold is config). Factions and treaties are visited in a stable id order so
 * the emitted events and the chosen winner are replay-identical regardless of map
 * iteration order. Only {@link GameState#status()} can change here (RUNNING to CONCLUDED);
 * everything else is event emission, so the golden state hash is unperturbed except for
 * the one deliberate status flip on a concluding tick.
 *
 * <p>Multi-tick conditions. The snapshot carries no per-faction tick counters, so the
 * "hold top Influence for N ticks" (economic) and "hold the Wonder for N ticks" (wonder)
 * variants are evaluated on their threshold being met in-snapshot (reach the Influence
 * target; complete the Wonder stages). The counter-gated variants are a later refinement
 * once the snapshot grows the counters; keeping them snapshot-evaluable now preserves the
 * small, hash-stable state.
 */
final class VictoryEvaluation {

    private VictoryEvaluation() {
    }

    /**
     * Run the elimination then victory pass. Appends any {@link PublicEvent} to
     * {@code events} (the EVENTS-step collector) and returns the next snapshot, which
     * differs from {@code state} only if the match concluded this tick.
     *
     * @param state   the snapshot after every state-changing step this tick
     * @param profile the active balance profile - source of every threshold (rule 6)
     * @param events  the per-tick public-event collector (appended in deterministic order)
     * @return the snapshot, transitioned to CONCLUDED iff the active condition fired
     */
    static GameState evaluate(GameState state, BalanceProfile profile, List<PublicEvent> events) {
        BalanceProfile.Victory cfg = profile.victory();
        long tick = state.tick();

        GameState next = state;
        if (cfg.eliminationEnabled()) {
            for (FactionId fid : sortedFactionIds(next)) {
                if (isEliminated(next, fid)) {
                    events.add(new PublicEvent.FactionEliminated(fid, tick));
                }
            }
        }

        // Victory only fires for a live (RUNNING) match.
        if (cfg.conditionEnabled() && next.status() == GameStatus.RUNNING) {
            List<FactionId> winners = evaluateWinners(next, profile, cfg);
            if (!winners.isEmpty()) {
                for (FactionId winner : winners) {
                    events.add(new PublicEvent.VictoryAchieved(winner, tick));
                }
                next = LifecycleTransitions.transition(next, GameStatus.CONCLUDED);
            }
        }
        return next;
    }

    // ===== elimination and vassalage (section 3) ===============================

    /**
     * @return true iff {@code fid} is eliminated: it controls zero systems and is not a
     * protected vassal. Capitals are modelled as owned systems in this minimal model, as
     * {@code InfluenceResolution} documents.
     */
    static boolean isEliminated(GameState state, FactionId fid) {
        if (isVassal(state, fid)) {
            return false;
        }
        return Scoring.systemsControlled(state, fid) == 0;
    }

    /**
     * @return true iff {@code fid} is a junior party (a vassal) of any ACTIVE
     * {@link TreatyType#VASSALAGE} treaty. Vassalage parties are ordered
     * {@code [suzerain, vassal...]} (the proposer/suzerain is first, as
     * {@code DiplomacyResolution} mints them), so any party after index 0 is a vassal.
     */
    static boolean isVassal(GameState state, FactionId fid) {
        for (Treaty t : state.treaties().values()) {
            if (t.type() != TreatyType.VASSALAGE || t.status() != TreatyStatus.ACTIVE) {
                continue;
            }
            List<FactionId> parties = t.parties();
            for (int i = 1; i < parties.size(); i++) {
                if (parties.get(i).equals(fid)) {
                    return true;
                }
            }
        }
        return false;
    }

    // ===== victory conditions (section 1) ======================================

    /**
     * Evaluate the single active victory condition. Returns the winners: a single faction
     * for a solo win, or the whole alliance for a shared (alliance) win; empty if no one
     * has won this tick. The list is in stable id order.
     */
    private static List<FactionId> evaluateWinners(GameState state, BalanceProfile profile,
                                                   BalanceProfile.Victory cfg) {
        return switch (cfg.active()) {
            case DOMINATION -> domination(state, cfg);
            case ECONOMIC -> economic(state, cfg);
            case DIPLOMATIC -> diplomatic(state, cfg);
            case SURVIVAL -> survival(state, profile, cfg);
            case WONDER -> wonder(state, cfg);
        };
    }

    /**
     * Domination: a faction (or its alliance) controls at least {@code systemPct} of the
     * habitable systems. Evaluated per alliance group so allied control aggregates; on a
     * win the whole group shares (the alliance victory-sharing rule).
     */
    private static List<FactionId> domination(GameState state, BalanceProfile.Victory cfg) {
        long habitable = habitableSystemCount(state);
        if (habitable == 0) {
            return List.of();
        }
        double required = cfg.domination().systemPct() * habitable;
        for (List<FactionId> group : allianceGroups(state)) {
            long controlled = 0;
            for (FactionId member : group) {
                controlled += Scoring.systemsControlled(state, member);
            }
            if (controlled >= required) {
                return group;
            }
        }
        return List.of();
    }

    /**
     * Economic / Prestige: a faction reaches {@code influenceTarget} total Influence. The
     * hold-top-for-N-ticks alternative is the counter-gated refinement; the Influence
     * target is the snapshot-evaluable trigger.
     */
    private static List<FactionId> economic(GameState state, BalanceProfile.Victory cfg) {
        double target = cfg.economic().influenceTarget();
        if (target <= 0.0) {
            return List.of();
        }
        for (FactionId fid : sortedFactionIds(state)) {
            Faction f = state.factions().get(fid);
            if (f.stockpiles().influence() >= target) {
                return List.of(fid);
            }
        }
        return List.of();
    }

    /**
     * Diplomatic: an alliance controls at least {@code allianceMajorityPct} of the
     * habitable systems. Only multi-member alliance groups qualify (a lone faction wins by
     * Domination, not Diplomatic); on a win the whole alliance shares.
     */
    private static List<FactionId> diplomatic(GameState state, BalanceProfile.Victory cfg) {
        long habitable = habitableSystemCount(state);
        if (habitable == 0) {
            return List.of();
        }
        double required = cfg.diplomatic().allianceMajorityPct() * habitable;
        for (List<FactionId> group : allianceGroups(state)) {
            if (group.size() < 2) {
                continue; // a real alliance, not a solo faction
            }
            long controlled = 0;
            for (FactionId member : group) {
                controlled += Scoring.systemsControlled(state, member);
            }
            if (controlled >= required) {
                return group;
            }
        }
        return List.of();
    }

    /**
     * Survival / Last Standing: win when exactly one faction still holds a capital (all
     * rivals eliminated or vassalised), OR the {@code tickLimit} is reached, in which case
     * the highest-ranked surviving faction wins (the ranking always produces a result). A
     * surviving faction is one controlling at least one system.
     */
    private static List<FactionId> survival(GameState state, BalanceProfile profile,
                                            BalanceProfile.Victory cfg) {
        List<FactionId> withCapital = new ArrayList<>();
        for (FactionId fid : sortedFactionIds(state)) {
            if (Scoring.systemsControlled(state, fid) > 0) {
                withCapital.add(fid);
            }
        }
        if (withCapital.size() == 1) {
            return List.of(withCapital.get(0));
        }
        if (state.tick() >= cfg.survival().tickLimit() && !withCapital.isEmpty()) {
            // Tick limit reached: the leader on the weighted ranking among survivors wins.
            return List.of(leaderAmong(state, profile, withCapital));
        }
        return List.of();
    }

    /**
     * Wonder: a faction completed the galaxy Wonder - it holds at least
     * {@code wonder.stages} ACTIVE Monument buildings across its systems (the multi-stage
     * Monument project). The hold-for-N-ticks requirement is the counter-gated refinement;
     * completing the stages is the snapshot-evaluable trigger.
     */
    private static List<FactionId> wonder(GameState state, BalanceProfile.Victory cfg) {
        int stages = cfg.wonder().stages();
        if (stages <= 0) {
            return List.of();
        }
        for (FactionId fid : sortedFactionIds(state)) {
            if (activeMonuments(state, fid) >= stages) {
                return List.of(fid);
            }
        }
        return List.of();
    }

    // ===== alliance grouping ===================================================

    /**
     * Partition the factions into alliance groups: the transitive closure of ACTIVE
     * {@link TreatyType#ALLIANCE} treaties. A faction in no alliance is its own singleton
     * group. Groups are returned in a stable order, and each group members are in sorted
     * id order, so the winner choice is replay-identical.
     */
    static List<List<FactionId>> allianceGroups(GameState state) {
        List<FactionId> all = sortedFactionIds(state);
        Set<FactionId> assigned = new LinkedHashSet<>();
        List<List<FactionId>> groups = new ArrayList<>();
        for (FactionId seed : all) {
            if (assigned.contains(seed)) {
                continue;
            }
            Set<FactionId> group = new LinkedHashSet<>();
            List<FactionId> frontier = new ArrayList<>();
            frontier.add(seed);
            group.add(seed);
            while (!frontier.isEmpty()) {
                FactionId cur = frontier.remove(frontier.size() - 1);
                for (FactionId ally : alliesOf(state, cur)) {
                    if (group.add(ally)) {
                        frontier.add(ally);
                    }
                }
            }
            assigned.addAll(group);
            List<FactionId> sorted = new ArrayList<>(group);
            sorted.sort(Comparator.comparing(FactionId::value));
            groups.add(List.copyOf(sorted));
        }
        return groups;
    }

    private static List<FactionId> alliesOf(GameState state, FactionId fid) {
        List<FactionId> allies = new ArrayList<>();
        for (Treaty t : state.treaties().values()) {
            if (t.type() == TreatyType.ALLIANCE && t.status() == TreatyStatus.ACTIVE
                    && t.involves(fid)) {
                for (FactionId p : t.parties()) {
                    if (!p.equals(fid)) {
                        allies.add(p);
                    }
                }
            }
        }
        return allies;
    }

    // ===== shared counters (pure) ==============================================

    private static long habitableSystemCount(GameState state) {
        // In this minimal model every active system is a controllable (habitable) system;
        // counted toward domination/diplomatic regardless of owner.
        return state.systems().size();
    }

    private static long activeMonuments(GameState state, FactionId fid) {
        long monuments = 0;
        for (ActiveSystem system : state.systems().values()) {
            if (!Scoring.ownedBy(system, fid)) {
                continue;
            }
            for (Planet planet : system.planets()) {
                for (Building b : planet.buildings()) {
                    if (b.type() == BuildingType.MONUMENT && b.status() == BuildingStatus.ACTIVE) {
                        monuments++;
                    }
                }
            }
        }
        return monuments;
    }

    /**
     * @return the highest-ranked faction among {@code candidates} by the weighted
     * {@link Scoring} ranking (score desc, faction id asc on a tie). {@code candidates}
     * is non-empty.
     */
    private static FactionId leaderAmong(GameState state, BalanceProfile profile,
                                         List<FactionId> candidates) {
        // Reuse the canonical scoring ranking; pick the first candidate in that order.
        for (Scoring.FactionScore fs : Scoring.rank(state, profile)) {
            if (candidates.contains(fs.faction())) {
                return fs.faction();
            }
        }
        return candidates.get(0);
    }

    private static List<FactionId> sortedFactionIds(GameState state) {
        List<FactionId> ids = new ArrayList<>(state.factions().keySet());
        ids.sort(Comparator.comparing(FactionId::value));
        return ids;
    }
}
