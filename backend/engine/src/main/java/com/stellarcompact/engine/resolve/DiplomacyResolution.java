package com.stellarcompact.engine.resolve;

import com.stellarcompact.engine.action.Action;
import com.stellarcompact.engine.config.BalanceProfile;
import com.stellarcompact.engine.state.Faction;
import com.stellarcompact.engine.state.FactionId;
import com.stellarcompact.engine.state.GameState;
import com.stellarcompact.engine.state.ResourceBundle;
import com.stellarcompact.engine.state.Treaty;
import com.stellarcompact.engine.state.TreatyId;
import com.stellarcompact.engine.state.TreatyStatus;
import com.stellarcompact.engine.state.TreatyType;

import java.util.List;

/**
 * The DIPLOMATIC_STATE resolution step (board card E1-12; game-design 04, 03 step 1 -
 * the first, "state changes resolve first" slot). Folds the diplomatic-state action
 * slice into the next snapshot: treaty lifecycle transitions, the public reputation
 * ledger, war declarations and resource tribute - each a pure, copy-on-write
 * transformation of {@link GameState}.
 *
 * <p><b>What lands here (engine enforcement, game-design 04 section 2-3).</b>
 * <ul>
 *   <li>{@link Action.ProposeTreaty} - mints a PROPOSED {@link Treaty} addressed to the
 *       counterparty, with a deterministic id (see {@link #mintTreatyId}). No reputation
 *       move; an unaccepted proposal binds no one.</li>
 *   <li>{@link Action.AcceptTreaty} - flips a PROPOSED treaty to ACTIVE (now
 *       engine-enforced; the kinetic validators read its {@code parties} to refuse
 *       illegal actions). No immediate reputation gain - the {@code gainHonourTreaty}
 *       reward is for honouring a treaty <em>to term</em>, applied when it expires.</li>
 *   <li>{@link Action.DeclineTreaty} - closes a PROPOSED treaty (status EXPIRED, "ended
 *       without taking effect"); declining an offer is not betrayal, so no penalty.</li>
 *   <li>{@link Action.BreakTreaty} - terminates an ACTIVE treaty (status BROKEN) and
 *       docks the breaker's reputation by
 *       {@code penaltyBreakTreaty x treatyWeight x remainingDuration} (the
 *       weight-x-remaining-duration rule of game-design 04 section 3); all three numbers
 *       come from {@code BalanceProfile.Diplomacy} (rule 6).</li>
 *   <li>{@link Action.DeclareWar} - records a {@link com.stellarcompact.engine.state.WarState}
 *       (the positive gate kinetic actions require) and, for a <em>new</em> war, docks the
 *       declarer's reputation by {@code penaltyUnprovokedWar}. Re-declaring an existing war
 *       is idempotent and incurs no second penalty.</li>
 *   <li>{@link Action.Tribute} - transfers {@code resources} from the payer to the
 *       recipient. The transfer routes through the tick-wide {@link SpendLedger} (escrow
 *       the payer, credit the recipient) - the same atomic spend/credit discipline every
 *       other resource-moving step uses, never a direct stockpile mutation.</li>
 * </ul>
 *
 * <p><b>Public events.</b> BreakTreaty and DeclareWar are "announced galaxy-wide"
 * (game-design 04 section 5). There is no event-emission seam in the engine yet - the
 * EVENTS step (E1-14/E1-16) is still a documented stub - so this card records the
 * <em>state</em> the event will be derived from (the BROKEN treaty, the WarState) and
 * leaves a TODO; it does NOT invent an event system.
 *
 * <p><b>Purity / determinism.</b> Pure arithmetic, config lookups and copy-on-write
 * snapshot helpers only - no I/O, no wall-clock, no RNG (treaty ids are minted from the
 * deterministic action context, not a random source). The {@link Resolver} feeds this
 * step its already-(step, actor, submissionOrder)-ordered slice, so folding is
 * replay-stable regardless of input order.
 */
final class DiplomacyResolution {

    private DiplomacyResolution() {
    }

    /**
     * Fold the DIPLOMATIC_STATE action slice (already in canonical order) into the next
     * snapshot, routing every tribute transfer through {@code ledger}.
     *
     * @param state  the snapshot entering the diplomatic-state step
     * @param slice  the ordered diplomatic-state actions for this tick
     * @param tick   the current tick (for war start, remaining-duration, id minting)
     * @param profile the active balance profile (every reputation number, rule 6)
     * @param ledger  the tick-wide escrow seam tribute transfers route through
     * @return the snapshot after the diplomatic-state effects
     */
    static GameState resolve(GameState state, List<SubmittedAction> slice, long tick,
                             BalanceProfile profile, SpendLedger ledger) {
        GameState next = state;
        for (SubmittedAction sa : slice) {
            next = apply(next, sa, tick, profile, ledger);
        }
        return next;
    }

    private static GameState apply(GameState state, SubmittedAction sa, long tick,
                                   BalanceProfile profile, SpendLedger ledger) {
        FactionId actor = sa.actor();
        return switch (sa.action()) {
            case Action.ProposeTreaty a -> proposeTreaty(state, actor, a, tick, sa.submissionOrder());
            case Action.AcceptTreaty a -> acceptTreaty(state, a);
            case Action.DeclineTreaty a -> declineTreaty(state, a);
            case Action.BreakTreaty a -> breakTreaty(state, actor, a, tick, profile);
            case Action.DeclareWar a -> declareWar(state, actor, a, tick, profile);
            case Action.Tribute a -> tribute(state, actor, a, ledger);
            // The slice only ever contains the DIPLOMATIC_STATE-category actions above
            // (the resolver grouped by step); any other action reaching here would be a
            // mis-routing bug, so leave the snapshot untouched.
            default -> state;
        };
    }

    // ===== treaty lifecycle ====================================================

    private static GameState proposeTreaty(GameState state, FactionId actor,
                                           Action.ProposeTreaty a, long tick, int submissionOrder) {
        TreatyId id = mintTreatyId(actor, a.to(), a.treatyType(), tick, submissionOrder);
        // duration is in ticks from now; absent => open-ended (Long.MAX_VALUE).
        long expiresTick = a.duration().map(d -> tick + d).orElse(Long.MAX_VALUE);
        Treaty treaty = new Treaty(id, a.treatyType(), List.of(actor, a.to()), a.terms(),
                tick, expiresTick, TreatyStatus.PROPOSED);
        return state.withTreaty(treaty);
    }

    private static GameState acceptTreaty(GameState state, Action.AcceptTreaty a) {
        Treaty treaty = state.treaties().get(a.treatyId());
        if (treaty == null || treaty.status() != TreatyStatus.PROPOSED) {
            return state; // validator guarantees this, but stay defensive (pure no-op)
        }
        return state.withTreaty(treaty.withStatus(TreatyStatus.ACTIVE));
    }

    private static GameState declineTreaty(GameState state, Action.DeclineTreaty a) {
        Treaty treaty = state.treaties().get(a.treatyId());
        if (treaty == null || treaty.status() != TreatyStatus.PROPOSED) {
            return state;
        }
        // Declining a PROPOSED treaty closes it without it ever binding: EXPIRED (ended,
        // not pending) is the no-penalty terminal; declining is not a betrayal.
        return state.withTreaty(treaty.withStatus(TreatyStatus.EXPIRED));
    }

    private static GameState breakTreaty(GameState state, FactionId actor,
                                         Action.BreakTreaty a, long tick, BalanceProfile profile) {
        Treaty treaty = state.treaties().get(a.treatyId());
        if (treaty == null || treaty.status() != TreatyStatus.ACTIVE) {
            return state;
        }
        GameState next = state.withTreaty(treaty.withStatus(TreatyStatus.BROKEN));
        // Reputation penalty = penaltyBreakTreaty x treatyWeight x remainingDuration.
        double weight = treatyWeight(profile, treaty.type());
        long remaining = Math.max(0L, treaty.expiresTick() - tick);
        double penalty = profile.diplomacy().reputation().penaltyBreakTreaty() * weight * remaining;
        next = adjustReputation(next, actor, -penalty);
        // TODO(E1-16): emit a galaxy-wide "treaty broken" public event for the WorldView
        // feed once the EVENTS step has an event-emission seam. State (BROKEN treaty) is
        // recorded here; no event system is invented by this card.
        return next;
    }

    // ===== war declaration =====================================================

    private static GameState declareWar(GameState state, FactionId actor,
                                        Action.DeclareWar a, long tick, BalanceProfile profile) {
        if (state.atWar(actor, a.target())) {
            return state; // idempotent: an existing war is neither re-recorded nor re-penalised
        }
        GameState next = state.withWar(actor, a.target(), tick);
        double penalty = profile.diplomacy().reputation().penaltyUnprovokedWar();
        next = adjustReputation(next, actor, -penalty);
        // TODO(E1-16): emit a galaxy-wide "war declared" public event once the EVENTS
        // step has an emission seam. The WarState is recorded here.
        return next;
    }

    // ===== tribute transfer ====================================================

    private static GameState tribute(GameState state, FactionId actor,
                                     Action.Tribute a, SpendLedger ledger) {
        // Route the transfer through the tick-wide ledger: debit the payer, credit the
        // recipient. The single authoritative settlement (Resolver.settleSpends) applies
        // both atomically, so the same spend/credit discipline that guards every other
        // resource move guards tribute too (no direct stockpile mutation here).
        ResourceBundle resources = a.resources();
        ledger.escrow(actor, resources);
        ledger.credit(a.to(), resources);
        return state;
    }

    // ===== helpers (pure) ======================================================

    /**
     * @return the per-type enforcement weight from {@code diplomacy.treatyEnforcement}
     * (rule 6: never hardcoded). A type missing from the map contributes the neutral
     * {@code 1.0} so a partial profile stays usable.
     */
    private static double treatyWeight(BalanceProfile profile, TreatyType type) {
        Double w = profile.diplomacy().treatyEnforcement().get(type.configKey());
        return w == null ? 1.0 : w;
    }

    /** Copy-on-write: apply {@code delta} to {@code id}'s public reputation. */
    private static GameState adjustReputation(GameState state, FactionId id, double delta) {
        if (delta == 0.0) {
            return state;
        }
        Faction f = state.factions().get(id);
        if (f == null) {
            return state;
        }
        return state.withFaction(f.withReputation(f.reputation() + delta));
    }

    /**
     * Deterministically mint a treaty id from the proposal's context. The id is a pure
     * function of (proposer, addressee, type, tick, submissionOrder) - no RNG, no
     * wall-clock - so the same action stream mints the same id on replay. The
     * submissionOrder disambiguates several proposals by the same faction in one tick.
     */
    private static TreatyId mintTreatyId(FactionId from, FactionId to, TreatyType type,
                                         long tick, int submissionOrder) {
        return new TreatyId("treaty:" + from.value() + ":" + to.value() + ":"
                + type.configKey() + ":" + tick + ":" + submissionOrder);
    }
}
