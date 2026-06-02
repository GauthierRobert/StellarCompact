package com.stellarcompact.engine.resolve;

import com.stellarcompact.engine.action.Action;
import com.stellarcompact.engine.action.UnknownAction;
import com.stellarcompact.engine.config.BalanceProfile;
import com.stellarcompact.engine.state.Faction;
import com.stellarcompact.engine.state.FactionId;
import com.stellarcompact.engine.state.GameState;
import com.stellarcompact.engine.state.ResourceBundle;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The deterministic, pure referee (E1-05): folds one tick of validated actions into
 * the next {@link GameState}. Same seed plus same ordered, validated action stream
 * produces a byte-identical next state (skill {@code game-engine-determinism}).
 *
 * <p><b>Single-threaded and pure.</b> {@link #resolve} performs no I/O, reads no
 * wall-clock, draws no unseeded randomness and starts no thread/parallel stream.
 * All concurrency lives in the orchestrator, never here. RNG, when later steps need
 * it, is derived from gameSeed XOR tick XOR localSalt via rng/SeedDerivation; this
 * skeleton stub handlers draw none.
 *
 * <p><b>Fixed resolution order (the deliverable of this card).</b> The resolver
 * orders every {@link SubmittedAction} by (step, actor, submissionOrder) - the
 * deterministic key of game-design 03 - then walks the eleven {@link ResolutionStep}
 * values in declaration order, applying the slice of ordered actions belonging to
 * each step, runs the three passive steps (production/upkeep, influence, public
 * events) in their fixed slots even though no action triggers them, and finally
 * applies the {@link SpendLedger} in one authoritative debit pass.
 *
 * <p><b>Stubs (later cards).</b> The gameplay math of every step is intentionally a
 * STUB here: each handler returns the state unchanged (a documented no-op). This
 * card delivers the deterministic ordering, the eleven-step wiring, and the
 * atomic-debit seam - not the per-step rules, which land in E1-06..E1-14. The
 * exhaustive switch over the sealed {@link Action} set (no default) guarantees a new
 * variant is a compile error until routed.
 */
public final class Resolver {

    /**
     * Canonical resolution ordering: step first (fixed category order), then by
     * faction id string, then by the agent submission index. Total and
     * replay-stable - no map iteration order participates.
     */
    static final Comparator<SubmittedAction> ORDER =
            Comparator.<SubmittedAction>comparingInt(sa -> sa.step().ordinal())
                    .thenComparing(sa -> sa.actor().value())
                    .thenComparingInt(SubmittedAction::submissionOrder);

    private Resolver() {
    }

    /**
     * Resolve one tick: apply {@code validatedActions} to {@code state} under
     * {@code profile}, producing the next snapshot.
     *
     * @param state            authoritative pre-tick snapshot (never mutated in place)
     * @param validatedActions the tick actions, already Valid (any order; this
     *                         method imposes the canonical order itself)
     * @param profile          the active balance profile - the source of every
     *                         gameplay number (rule 6); no constant is hardcoded here
     * @param seed             the per-match gameSeed, root of all seeded RNG
     * @return the next {@link GameState}
     */
    public static GameState resolve(GameState state, List<SubmittedAction> validatedActions,
                                    BalanceProfile profile, long seed) {
        if (state == null) {
            throw new IllegalArgumentException("resolve: state must be set");
        }
        if (validatedActions == null) {
            throw new IllegalArgumentException("resolve: validatedActions must be set");
        }
        if (profile == null) {
            throw new IllegalArgumentException("resolve: profile must be set");
        }

        // 1. Impose the deterministic order. Drop actions that drive no step
        //    (Hold / UnknownAction / soft diplomacy) - they reach no handler.
        List<SubmittedAction> ordered = new ArrayList<>();
        for (SubmittedAction sa : validatedActions) {
            if (sa.step() != null) {
                ordered.add(sa);
            }
        }
        ordered.sort(ORDER);

        // The one tick-wide escrow ledger. Every step that spends MUST route its
        // debit through this ledger (never debit a Faction directly); it is applied
        // once, atomically, after all steps - see settleSpends below.
        SpendLedger ledger = new SpendLedger();
        StepContext ctx = new StepContext(profile, seed, state.tick(), ledger);

        // 2. Walk the eleven fixed resolution steps in declaration order. Each step
        //    consumes exactly the ordered slice of actions assigned to it.
        GameState next = state;
        for (ResolutionStep step : ResolutionStep.values()) {
            List<SubmittedAction> slice = sliceFor(ordered, step);
            next = applyStep(step, next, slice, ctx);
        }

        // 3. Single authoritative debit pass: apply all escrowed spends atomically.
        return settleSpends(next, ledger);
    }

    /** The already-ordered slice of actions belonging to one step. */
    private static List<SubmittedAction> sliceFor(List<SubmittedAction> ordered,
                                                  ResolutionStep step) {
        List<SubmittedAction> slice = new ArrayList<>();
        for (SubmittedAction sa : ordered) {
            if (sa.step() == step) {
                slice.add(sa);
            }
        }
        return slice;
    }

    /**
     * Dispatch one resolution step to its handler. The action-driven steps (1-8)
     * each fold their ordered slice; the passive steps (9-11) take no actions and
     * run a tick-wide pass. Every branch is a STUB returning state unchanged.
     */
    private static GameState applyStep(ResolutionStep step, GameState state,
                                       List<SubmittedAction> slice, StepContext ctx) {
        return switch (step) {
            case DIPLOMATIC_STATE -> resolveActionDriven(state, slice, ctx);
            case ESPIONAGE -> resolveActionDriven(state, slice, ctx);
            case MOVEMENT -> resolveActionDriven(state, slice, ctx);
            case COMBAT -> resolveActionDriven(state, slice, ctx);
            case INTERDICTION -> resolveActionDriven(state, slice, ctx);
            case DEVELOPMENT -> resolveActionDriven(state, slice, ctx);
            case COLONISATION -> resolveActionDriven(state, slice, ctx);
            case MARKET -> resolveActionDriven(state, slice, ctx);
            case PRODUCTION -> resolveProduction(state, ctx);
            case INFLUENCE -> resolveInfluence(state, ctx);
            case EVENTS -> resolveEvents(state, ctx);
        };
    }

    /**
     * Fold an action-driven step ordered slice, dispatching each action to its
     * per-action handler through the exhaustive sealed-{@link Action} switch.
     */
    private static GameState resolveActionDriven(GameState state, List<SubmittedAction> slice,
                                                 StepContext ctx) {
        GameState next = state;
        for (SubmittedAction sa : slice) {
            next = applyAction(next, sa, ctx);
        }
        return next;
    }

    /**
     * The exhaustive per-action dispatch (no default). Adding a 26th {@link Action}
     * variant breaks compilation here until it is routed - the closed-set determinism
     * guarantee. Every arm is a STUB (returns state unchanged) for this skeleton
     * card; later cards fill the bodies and route any spend through the ledger.
     */
    private static GameState applyAction(GameState state, SubmittedAction sa, StepContext ctx) {
        Action action = sa.action();
        FactionId actor = sa.actor();
        return switch (action) {
            case Action.BreakTreaty a -> stubBreakTreaty(state, actor, a, ctx);
            case Action.AcceptTreaty a -> stubAcceptTreaty(state, actor, a, ctx);
            case Action.DeclineTreaty a -> stubDeclineTreaty(state, actor, a, ctx);
            case Action.ProposeTreaty a -> stubProposeTreaty(state, actor, a, ctx);
            case Action.DeclareWar a -> stubDeclareWar(state, actor, a, ctx);

            case Action.Espionage a -> stubEspionage(state, actor, a, ctx);

            case Action.MoveFleet a -> stubMoveFleet(state, actor, a, ctx);

            case Action.Attack a -> stubAttack(state, actor, a, ctx);

            case Action.Blockade a -> stubBlockade(state, actor, a, ctx);
            case Action.Raid a -> stubRaid(state, actor, a, ctx);

            case Action.Build a -> stubBuild(state, actor, a, ctx);
            case Action.Research a -> stubResearch(state, actor, a, ctx);
            case Action.Terraform a -> stubTerraform(state, actor, a, ctx);
            case Action.BuildFleet a -> stubBuildFleet(state, actor, a, ctx);
            case Action.Explore a -> stubExplore(state, actor, a, ctx);
            case Action.EstablishRoute a -> stubEstablishRoute(state, actor, a, ctx);

            case Action.Colonize a -> stubColonize(state, actor, a, ctx);

            case Action.ProposeTrade a -> stubProposeTrade(state, actor, a, ctx);
            case Action.AcceptTrade a -> stubAcceptTrade(state, actor, a, ctx);
            case Action.DeclineTrade a -> stubDeclineTrade(state, actor, a, ctx);
            case Action.WithdrawTrade a -> stubWithdrawTrade(state, actor, a, ctx);

            // Soft diplomacy and no-ops are filtered before dispatch (step() == null),
            // but the switch must stay exhaustive over the sealed set: route them to
            // the inert no-op so adding a variant remains a compile error.
            case Action.SendMessage ignored -> state;
            case Action.Tribute ignored -> state;
            case Action.DemandTribute ignored -> state;
            case Action.Hold ignored -> state;
            case UnknownAction ignored -> state;
        };
    }

    /**
     * The single authoritative debit pass (E1-05 security prereq). After every step
     * has accrued its spends into the {@link SpendLedger}, this is the ONLY place a
     * faction stockpile is debited, and it subtracts the summed accrued total so N
     * spends in one tick cannot collectively overdraw undetected. A no-op while the
     * spending steps are still stubbed (the ledger is empty), but the seam is wired
     * and live for E1-06/E1-07 to populate.
     */
    private static GameState settleSpends(GameState state, SpendLedger ledger) {
        if (ledger.isEmpty()) {
            return state;
        }
        GameState next = state;
        for (FactionId id : ledger.touched()) {
            Faction faction = state.factions().get(id);
            if (faction == null) {
                continue;
            }
            ResourceBundle settled = ledger.settle(id, faction.stockpiles());
            if (!settled.equals(faction.stockpiles())) {
                next = next.withFaction(faction.withStockpiles(settled));
            }
        }
        return next;
    }

    // ===== per-action STUB handlers (bodies arrive in later cards) =============
    // Each is a documented no-op: returns state unchanged. They exist now so the
    // dispatch switch is exhaustive and the wiring is testable. Any future spend
    // MUST go through ctx.ledger().escrow(actor, cost), never a direct Faction debit.

    private static GameState stubBreakTreaty(GameState s, FactionId a, Action.BreakTreaty x, StepContext c) {
        return s; // TODO(E1-08): terminate treaty, announce, apply reputation penalty.
    }

    private static GameState stubAcceptTreaty(GameState s, FactionId a, Action.AcceptTreaty x, StepContext c) {
        return s; // TODO(E1-08): activate the proposed treaty and announce it.
    }

    private static GameState stubDeclineTreaty(GameState s, FactionId a, Action.DeclineTreaty x, StepContext c) {
        return s; // TODO(E1-08): reject the proposed treaty.
    }

    private static GameState stubProposeTreaty(GameState s, FactionId a, Action.ProposeTreaty x, StepContext c) {
        return s; // TODO(E1-08): record the pending treaty for the counterparty.
    }

    private static GameState stubDeclareWar(GameState s, FactionId a, Action.DeclareWar x, StepContext c) {
        return s; // TODO(E1-09): set war state; apply unprovoked-war reputation cost.
    }

    private static GameState stubEspionage(GameState s, FactionId a, Action.Espionage x, StepContext c) {
        return s; // TODO(E1-12): seeded espionage roll; escrow Influence/Tech cost.
    }

    private static GameState stubMoveFleet(GameState s, FactionId a, Action.MoveFleet x, StepContext c) {
        return s; // TODO(E1-09): advance along path; mid-transit interception.
    }

    private static GameState stubAttack(GameState s, FactionId a, Action.Attack x, StepContext c) {
        return s; // TODO(E1-10): combat resolution; system capture on assault win.
    }

    private static GameState stubBlockade(GameState s, FactionId a, Action.Blockade x, StepContext c) {
        return s; // TODO(E1-11): choke route/system market throughput.
    }

    private static GameState stubRaid(GameState s, FactionId a, Action.Raid x, StepContext c) {
        return s; // TODO(E1-11): seeded shipment steal.
    }

    private static GameState stubBuild(GameState s, FactionId a, Action.Build x, StepContext c) {
        return s; // TODO(E1-06): queue construction; escrow Minerals(+Tech) via ledger.
    }

    private static GameState stubResearch(GameState s, FactionId a, Action.Research x, StepContext c) {
        return s; // TODO(E1-06): begin research; escrow Tech cost via ledger.
    }

    private static GameState stubTerraform(GameState s, FactionId a, Action.Terraform x, StepContext c) {
        return s; // TODO(E1-06): advance terraforming a step; sustained Energy upkeep.
    }

    private static GameState stubBuildFleet(GameState s, FactionId a, Action.BuildFleet x, StepContext c) {
        return s; // TODO(E1-06): queue ship build; escrow Minerals+Tech; reserve crew.
    }

    private static GameState stubExplore(GameState s, FactionId a, Action.Explore x, StepContext c) {
        return s; // TODO(E1-06): reveal target system and its onward lanes.
    }

    private static GameState stubEstablishRoute(GameState s, FactionId a, Action.EstablishRoute x, StepContext c) {
        return s; // TODO(E1-07): create recurring route; reserve logistics capacity.
    }

    private static GameState stubColonize(GameState s, FactionId a, Action.Colonize x, StepContext c) {
        return s; // TODO(E1-06): establish colony; escrow biome cost; seeded attrition.
    }

    private static GameState stubProposeTrade(GameState s, FactionId a, Action.ProposeTrade x, StepContext c) {
        return s; // TODO(E1-07): create pending directed offer (addressee + expiresTick).
    }

    private static GameState stubAcceptTrade(GameState s, FactionId a, Action.AcceptTrade x, StepContext c) {
        return s; // TODO(E1-07): atomic escrowed settlement of the accepted offer.
    }

    private static GameState stubDeclineTrade(GameState s, FactionId a, Action.DeclineTrade x, StepContext c) {
        return s; // TODO(E1-07): mark the offer declined.
    }

    private static GameState stubWithdrawTrade(GameState s, FactionId a, Action.WithdrawTrade x, StepContext c) {
        return s; // TODO(E1-07): cancel own pending offer, releasing any escrow.
    }

    // ===== passive STUB steps (no triggering action) ===========================

    private static GameState resolveProduction(GameState s, StepContext c) {
        // E1-06: production, upkeep, deficit attrition, population dynamics. Net
        // resource flow is credited/escrowed into the ledger (settled in the single
        // authoritative pass); structural (population, ship) changes apply here.
        return EconomyResolution.resolve(s, c.profile(), c.ledger());
    }

    private static GameState resolveInfluence(GameState s, StepContext c) {
        return s; // TODO(E1-13): Influence accrual and decay.
    }

    private static GameState resolveEvents(GameState s, StepContext c) {
        return s; // TODO(E1-14): emit the tick public events for the WorldView feed.
    }

    /**
     * Per-tick handler context: the immutable inputs every step shares - the active
     * {@link BalanceProfile} (every number comes from here, rule 6), the gameSeed
     * plus tick for deriving seeded RNG, and the tick-wide {@link SpendLedger} escrow
     * seam. One record keeps handler signatures stable as bodies land in later cards.
     */
    record StepContext(BalanceProfile profile, long seed, long tick, SpendLedger ledger) {
        StepContext {
            if (profile == null) {
                throw new IllegalArgumentException("StepContext.profile must be set");
            }
            if (ledger == null) {
                throw new IllegalArgumentException("StepContext.ledger must be set");
            }
        }
    }
}
