package com.stellarcompact.engine.resolve;

import com.stellarcompact.engine.action.Action;
import com.stellarcompact.engine.action.UnknownAction;
import com.stellarcompact.engine.config.BalanceProfile;
import com.stellarcompact.engine.map.LaneNetwork;
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
        // Legacy 4-arg entry point: no lane graph supplied. Movement/interception
        // become inert (a fleet cannot move on an unproven path), every other step is
        // unaffected. Existing callers/tests that pre-date E1-09 keep their behaviour.
        return resolve(state, validatedActions, profile, seed, LaneNetwork.EMPTY);
    }

    /**
     * Resolve one tick with an explicit {@link LaneNetwork} (E1-09). The network is
     * the static-per-match lane graph the MOVEMENT step needs to compute travel ETAs,
     * advance fleets and detect interception; it is passed alongside the profile
     * rather than embedded in {@link GameState} (heavy static map kept out of the
     * per-tick snapshot/hash). Pass {@link LaneNetwork#EMPTY} for a match with no lane
     * graph (movement then no-ops).
     *
     * @param network the active-region lane graph (never {@code null}; use {@code EMPTY})
     */
    public static GameState resolve(GameState state, List<SubmittedAction> validatedActions,
                                    BalanceProfile profile, long seed, LaneNetwork network) {
        if (state == null) {
            throw new IllegalArgumentException("resolve: state must be set");
        }
        if (validatedActions == null) {
            throw new IllegalArgumentException("resolve: validatedActions must be set");
        }
        if (profile == null) {
            throw new IllegalArgumentException("resolve: profile must be set");
        }
        if (network == null) {
            throw new IllegalArgumentException("resolve: network must be set (use LaneNetwork.EMPTY)");
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
        // Battles interception triggers in MOVEMENT, handed to COMBAT (E1-10). Mutable
        // scratch local to this single-threaded resolve; never shared.
        List<PendingBattle> pendingBattles = new ArrayList<>();
        StepContext ctx = new StepContext(profile, seed, state.tick(), ledger, network, pendingBattles);

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
            case DIPLOMATIC_STATE -> resolveDiplomacy(state, slice, ctx);
            case ESPIONAGE -> resolveEspionage(state, slice, ctx);
            case MOVEMENT -> resolveMovement(state, slice, ctx);
            case COMBAT -> resolveCombat(state, slice, ctx);
            case INTERDICTION -> resolveInterdiction(state, slice, ctx);
            case DEVELOPMENT -> resolveDevelopment(state, slice, ctx);
            case COLONISATION -> resolveActionDriven(state, slice, ctx);
            case MARKET -> resolveMarket(state, slice, ctx);
            case PRODUCTION -> resolveProduction(state, ctx);
            case INFLUENCE -> resolveInfluence(state, ctx);
            case EVENTS -> resolveEvents(state, ctx);
        };
    }

    /**
     * The MARKET step (E1-07). First folds the trade-action slice (the directed
     * {@code ProposeTrade}/{@code AcceptTrade}/... handlers - settlement of accepted
     * directed offers is wired through the same ledger), then runs the per-hub order
     * book matcher over the resting {@link com.stellarcompact.engine.state.MarketOrder}
     * book by price-time priority, escrowing every fill through the tick-wide ledger.
     * Matching follows the action handlers so any orders an action placed/withdrew
     * this tick are reflected before crossing.
     */
    private static GameState resolveMarket(GameState state, List<SubmittedAction> slice,
                                           StepContext ctx) {
        GameState afterActions = resolveActionDriven(state, slice, ctx);
        return MarketResolution.resolve(afterActions, ctx.profile(), ctx.ledger());
    }

    /**
     * The MOVEMENT step (E1-09). First folds the {@code MoveFleet} action slice (the
     * "launch" phase: a stationary fleet begins its validated lane path, escrowing the
     * whole journey's Energy cost through the tick-wide ledger), then runs the passive
     * per-tick advance sweep over every en-route fleet (decrement ETA, arrive / chain
     * lanes / park, and detect mid-transit interception). Any battles interception
     * triggers are accumulated into the context's {@link PendingBattle} list for the
     * COMBAT step to resolve (E1-10). The launches precede the advance so a fleet that
     * launches this tick also takes its first travel tick this tick (its ETA already
     * reflects the lane length).
     */
    private static GameState resolveMovement(GameState state, List<SubmittedAction> slice,
                                             StepContext ctx) {
        GameState afterLaunch = state;
        for (SubmittedAction sa : slice) {
            if (sa.action() instanceof Action.MoveFleet move) {
                afterLaunch = MovementResolution.launch(afterLaunch, sa.actor(), move,
                        ctx.network(), ctx.profile(), ctx.ledger());
            }
        }
        MovementResolution.AdvanceResult advanced =
                MovementResolution.advance(afterLaunch, ctx.network(), ctx.profile());
        ctx.pendingBattles().addAll(advanced.battles());
        return advanced.state();
    }

    /**
     * The COMBAT step (E1-10; game-design 05 sections 1-2,6). Two consumers run through
     * the same {@link CombatResolution} primitives, in a fixed order for determinism:
     * <ol>
     *   <li>the forced interception engagements (E1-09) the MOVEMENT step accumulated in
     *       {@code ctx.pendingBattles()} - resolved first, in their already-deterministic
     *       order, each seeded from {@code gameSeed XOR tick XOR battleId}; then</li>
     *   <li>the ordered {@code Attack} action slice - each a fleet-vs-fleet engagement or
     *       a system assault that captures the system on a win.</li>
     * </ol>
     * Power, the seeded variance band, the proportional loss fractions and the occupation
     * penalty all come from {@code BalanceProfile.Combat} (rule 6). Combat applies its
     * losses/capture directly to the snapshot (no resource spend, so nothing routes
     * through the ledger). No fight is ever resolved on an unvalidated path - interception
     * battles arise only from validated movement, and {@code Attack} actions are
     * validator-gated (war-state + reachability).
     */
    private static GameState resolveCombat(GameState state, List<SubmittedAction> slice,
                                           StepContext ctx) {
        GameState next = state;
        for (PendingBattle pb : ctx.pendingBattles()) {
            next = CombatResolution.resolveInterception(next, pb, ctx.seed(), ctx.tick(), ctx.profile());
        }
        return CombatResolution.resolve(next, slice, ctx.seed(), ctx.tick(), ctx.profile());
    }

    /**
     * The INTERDICTION step (E1-11; game-design 05 section 5, 02 section 5). Folds the
     * ordered {@code Blockade}/{@code Raid} action slice through
     * {@link InterdictionResolution}: a {@code Blockade} flips the targeted route (or every
     * route touching the targeted system) to
     * {@link com.stellarcompact.engine.state.RouteStatus#BLOCKADED} - a deterministic
     * throughput choke whose magnitude is {@code market.blockadeThroughputFactor} (config);
     * a {@code Raid} steals a seeded fraction of a route's cargo
     * ({@code volume x market.raidStealFraction x roll}, the roll drawn from
     * {@code gameSeed XOR tick XOR SaltDomain.RAID.salt(routeId)}), escrowing the debit off
     * the route owner and the credit to the raider through the tick-wide ledger. Both are
     * validator-gated by war/treaty state (E1-09 F1); no territory is captured.
     */
    private static GameState resolveInterdiction(GameState state, List<SubmittedAction> slice,
                                                 StepContext ctx) {
        return InterdictionResolution.resolve(state, slice, ctx.seed(), ctx.tick(),
                ctx.profile(), ctx.ledger());
    }

    /**
     * The ESPIONAGE step (E1-13; game-design 03 step 2). Folds the ordered
     * {@code Espionage} action slice through {@link EspionageResolution}: each op runs a
     * seeded success roll and a seeded detection roll (keyed by
     * {@code gameSeed XOR tick XOR opId}), applies its effect on success (reveal intel /
     * steal a tech or resources / disable a building / incite unrest), escrows its
     * Influence/Tech cost through the tick-wide ledger win-or-lose, and on detection
     * docks the actor reputation. Counter-intel tech on the target shifts both odds.
     * Mirrors {@code resolveDevelopment}/{@code resolveMarket}: the step owns its own
     * fold rather than the per-action dispatch, so the {@code Espionage} arm of
     * {@link #applyAction} is unreachable (kept only to keep the switch exhaustive).
     */
    private static GameState resolveEspionage(GameState state, List<SubmittedAction> slice,
                                              StepContext ctx) {
        return EspionageResolution.resolve(state, slice, ctx.seed(), ctx.tick(),
                ctx.profile(), ctx.ledger());
    }

    /**
     * The DIPLOMATIC_STATE step (E1-12; game-design 03 step 1, 04). Folds the ordered
     * diplomatic-state slice via {@link DiplomacyResolution}: treaty lifecycle
     * (propose/accept/decline/break), the reputation ledger, war declarations (recording
     * a {@link com.stellarcompact.engine.state.WarState}) and resource tribute (routed
     * through the tick-wide {@link SpendLedger}). It resolves first, before any kinetic
     * step, so a war declared or a treaty broken this tick is in force for the actions
     * that follow.
     */
    private static GameState resolveDiplomacy(GameState state, List<SubmittedAction> slice,
                                              StepContext ctx) {
        return DiplomacyResolution.resolve(state, slice, ctx.tick(), ctx.profile(), ctx.ledger());
    }

    /**
     * The DEVELOPMENT step (E1-08). First folds the Build/Research/Terraform action
     * slice ("begin" phase: queue a building, start a tech, start a terraform - each
     * escrowing its one-off cost through the tick-wide ledger), then runs the passive
     * per-tick progress sweep ("advance" phase) over every faction so in-flight
     * construction, research and terraforming tick forward and complete on schedule.
     * The begins precede the advance so a freshly-queued item does not also advance in
     * the same tick it was started - its first progress tick is the next tick, exactly
     * as the build-time/research-time contracts read.
     */
    private static GameState resolveDevelopment(GameState state, List<SubmittedAction> slice,
                                                StepContext ctx) {
        GameState afterActions = resolveActionDriven(state, slice, ctx);
        return DevelopmentResolution.advance(afterActions, ctx.profile());
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
            // Diplomatic-state actions (treaty lifecycle, war, tribute) are handled by
            // resolveDiplomacy / DiplomacyResolution for the DIPLOMATIC_STATE step (E1-12),
            // not through this per-action dispatch, so these arms are unreachable. They
            // remain only to keep the sealed-Action switch exhaustive (a new variant must
            // still break compilation here).
            case Action.BreakTreaty ignored -> state;
            case Action.AcceptTreaty ignored -> state;
            case Action.DeclineTreaty ignored -> state;
            case Action.ProposeTreaty ignored -> state;
            case Action.DeclareWar ignored -> state;
            case Action.Tribute ignored -> state;

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

    /**
     * E1-13: Espionage is handled by {@link #resolveEspionage} via
     * {@link EspionageResolution} (seeded success/detection, effects, cost escrow,
     * reputation penalty), not through this per-action stub, so this arm is unreachable
     * for the ESPIONAGE step. It remains only to keep the sealed-{@link Action} switch
     * exhaustive (a new variant must still break compilation here).
     */
    private static GameState stubEspionage(GameState s, FactionId a, Action.Espionage x, StepContext c) {
        return s;
    }

    /**
     * E1-09: MoveFleet is handled by {@link #resolveMovement} (launch + advance with
     * the lane network), not through this per-action stub, so this arm is unreachable
     * for the MOVEMENT step. It remains only to keep the sealed-{@link Action} switch
     * exhaustive (a new variant must still break compilation here).
     */
    private static GameState stubMoveFleet(GameState s, FactionId a, Action.MoveFleet x, StepContext c) {
        return s;
    }

    /**
     * E1-10: Attack is handled by {@link #resolveCombat} via {@link CombatResolution}
     * (power model, seeded variance band, proportional losses, system capture), not
     * through this per-action stub, so this arm is unreachable for the COMBAT step. It
     * remains only to keep the sealed-{@link Action} switch exhaustive (a new variant
     * must still break compilation here).
     */
    private static GameState stubAttack(GameState s, FactionId a, Action.Attack x, StepContext c) {
        return s;
    }

    /**
     * E1-11: Blockade is handled by {@link #resolveInterdiction} via
     * {@link InterdictionResolution} (route/system throughput choke), not through this
     * per-action stub, so this arm is unreachable for the INTERDICTION step. It remains
     * only to keep the sealed-{@link Action} switch exhaustive (a new variant must still
     * break compilation here).
     */
    private static GameState stubBlockade(GameState s, FactionId a, Action.Blockade x, StepContext c) {
        return s;
    }

    /**
     * E1-11: Raid is handled by {@link #resolveInterdiction} via
     * {@link InterdictionResolution} (seeded shipment steal escrowed through the ledger),
     * not through this per-action stub, so this arm is unreachable for the INTERDICTION
     * step. It remains only to keep the sealed-{@link Action} switch exhaustive (a new
     * variant must still break compilation here).
     */
    private static GameState stubRaid(GameState s, FactionId a, Action.Raid x, StepContext c) {
        return s;
    }

    /**
     * E1-08: queue construction. Delegates to {@link DevelopmentResolution#beginBuild},
     * which places the UNDER_CONSTRUCTION building and escrows its Minerals(+Tech)
     * cost through the ledger (never a direct Faction debit). The building advances to
     * ACTIVE over its configured build time in the DEVELOPMENT advance sweep.
     */
    private static GameState stubBuild(GameState s, FactionId a, Action.Build x, StepContext c) {
        return DevelopmentResolution.beginBuild(s, a, x, c.profile(), c.ledger());
    }

    /**
     * E1-08: begin research. Delegates to {@link DevelopmentResolution#beginResearch},
     * which marks the (DAG-prerequisite-satisfied) tech RESEARCHING and escrows its
     * Tech cost through the ledger. The node unlocks over its configured research time
     * in the DEVELOPMENT advance sweep, applying its multipliers/unlocks thereafter.
     */
    private static GameState stubResearch(GameState s, FactionId a, Action.Research x, StepContext c) {
        return DevelopmentResolution.beginResearch(s, a, x, c.profile(), c.ledger());
    }

    /**
     * E1-08: begin terraforming. Delegates to {@link DevelopmentResolution#beginTerraform},
     * which starts a biome step on the planet idle active Terraformer. The biome
     * advances one rung toward habitable over the configured terraformerStep ticks in
     * the DEVELOPMENT advance sweep; the Terraformer sustained Energy upkeep is charged
     * by the economy step each tick it runs.
     */
    private static GameState stubTerraform(GameState s, FactionId a, Action.Terraform x, StepContext c) {
        return DevelopmentResolution.beginTerraform(s, a, x, c.profile());
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
    record StepContext(BalanceProfile profile, long seed, long tick, SpendLedger ledger,
                       LaneNetwork network, List<PendingBattle> pendingBattles) {
        StepContext {
            if (profile == null) {
                throw new IllegalArgumentException("StepContext.profile must be set");
            }
            if (ledger == null) {
                throw new IllegalArgumentException("StepContext.ledger must be set");
            }
            if (network == null) {
                throw new IllegalArgumentException("StepContext.network must be set (use LaneNetwork.EMPTY)");
            }
            if (pendingBattles == null) {
                throw new IllegalArgumentException("StepContext.pendingBattles must be set");
            }
        }
    }
}
