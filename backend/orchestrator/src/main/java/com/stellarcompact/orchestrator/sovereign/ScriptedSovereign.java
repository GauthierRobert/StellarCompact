package com.stellarcompact.orchestrator.sovereign;

import com.stellarcompact.engine.action.Action;
import com.stellarcompact.engine.action.AgentResponse;
import com.stellarcompact.engine.state.BuildingType;
import com.stellarcompact.engine.state.FactionId;
import com.stellarcompact.engine.state.PlanetId;
import com.stellarcompact.engine.state.SystemId;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * A deterministic, LLM-free {@link Sovereign} for tests and empty seats (E3-01).
 *
 * <p><b>Determinism (non-negotiable).</b> Same {@link WorldView} <em>sequence</em>
 * ⇒ same {@link AgentResponse} sequence, every run. The bot uses <em>no</em>
 * wall-clock, no {@code Math.random}, no unseeded randomness. Where it chooses among
 * candidate systems/planets it first <b>sorts by id</b> so {@code HashMap}/{@code
 * HashSet} iteration order in the projected view can never leak into the decision.
 *
 * <p><b>Multi-tick plan memory</b> (E12-01, finding P7a). The bot carries a small,
 * bounded {@link PlanMemory} between ticks so it pursues a <em>scout → colonise →
 * fortify</em> plan instead of recomputing intent from zero each tick. The only
 * mutable state is a single committed expansion target (one {@link SystemId}) plus
 * the {@link Phase} it last acted in; everything else is still a pure function of the
 * {@link WorldView}. The memory lives <b>only in this Sovereign instance</b>, never
 * in the engine (the engine stays pure - rule 1). It is <b>reconciled against the
 * WorldView at the top of every {@code decide}</b> (target lost from view, taken by
 * another faction, or no longer a colonisable neutral ⇒ forget it and re-plan), so a
 * fresh instance fed the same view sequence and an existing instance converge:
 * committing to a target derivable from the view, then reconciling that same target
 * against the same view, is idempotent. Two bots built the same way and driven by the
 * same view sequence remain interchangeable and replay-stable.
 *
 * <p><b>Closed agent I/O.</b> It only ever emits valid sealed {@link Action}
 * variants and a well-formed {@link AgentResponse}, and it can always fall back to
 * the explicit {@link Action.Hold} no-op. Every emitted action targets only
 * actor-knowable, owned assets so it is shaped to pass the engine
 * {@code ActionValidator} (the engine still validates authoritatively).
 *
 * <p><b>Heuristic ladder</b> (first applicable wins; at most one primary action per
 * tick plus {@code Hold} when idle), see spec section 7b. The Colonise and Explore
 * rungs are <em>plan-aware</em> (E12-01): they steer toward the committed expansion
 * target from {@link PlanMemory} when one applies, falling back to the pre-E12-01
 * lowest-id choice otherwise.
 * <ol>
 *   <li><b>Build a Shipyard</b> (E11-05, finding L5 / decision P4) - when the faction's
 *       Minerals have piled up past {@link #shipEconomyMineralThreshold} (the
 *       unbounded-hoard signal the live sim flagged: no sink, so minerals climb
 *       forever) and it owns <em>no</em> {@link BuildingType#SHIPYARD} anywhere, queue
 *       one on the lowest free slot of the lowest-id owned planet. This rung sits
 *       <b>above</b> the economy build so a faction sitting on a mineral hoard finally
 *       spends a slot on the growth engine instead of yet-another mine. Gated on a high
 *       Minerals proxy (well above both the economy build floor and the Shipyard's
 *       config cost) so the early game still fills slots with economy first.</li>
 *   <li><b>Build economy</b> - on the lowest-id owned planet with a free slot, queue
 *       a building in the lowest free slot, but only when the faction holds at least
 *       {@link #minMineralsToBuild} Minerals (a conservative affordability proxy,
 *       since per-building cost lives in the balance profile, not in the
 *       {@code WorldView}). The building <em>type</em> is chosen from the whole
 *       {@link #BUILD_PREFERENCE} by a resource-pressure heuristic
 *       ({@link #chooseBuildType}): a {@link BuildingType#SOLAR_ARRAY} when Energy is
 *       under pressure, a {@link BuildingType#FARM} when Food is under pressure, else
 *       the default {@link BuildingType#MINE} (E10-02, finding F2 - see that method).</li>
 *   <li><b>Colonise</b> - else, if it has an idle fleet parked at a reachable neutral
 *       (unowned) neighbour with a nameable planet, {@link Action.Colonize} the
 *       lowest-id planet of the lowest-id such system via that fleet (E10-01: the
 *       branch deferred in E3-01, now that fog E3-02 + lanes E2-03 surface the
 *       neutral-planet detail and a delivering fleet). Colonising an already-reached
 *       neutral finishes expansion, so it ranks ahead of building <em>more</em> ships.</li>
 *   <li><b>Construct a colony ship</b> (E11-05) - else, if the faction owns a Shipyard,
 *       holds Minerals past {@link #shipEconomyMineralThreshold}, and has a reachable
 *       neutral (unowned) neighbour to expand toward, {@link Action.BuildFleet} a
 *       {@link #colonyShipSpec} at the lowest-id owned Shipyard system. This is the
 *       mineral <em>sink</em> and the growth path (P4): the hoard is spent producing
 *       fleets that the Colonise / Explore rungs then send to reachable neutrals. The
 *       spec is an ungated archetype so the emitted action passes the validator
 *       (no doctrine tech required).</li>
 *   <li><b>Explore</b> - else, if it has an idle fleet and a neutral neighbour that is
 *       <em>not already revealed/explored</em>, {@link Action.Explore} the lowest-id
 *       such system. A neighbour already present in the view is revealed, so this is
 *       skipped - the bot never re-Explores a known system (E10-01, finding F1: the
 *       old ladder re-explored the same revealed neutral every tick).</li>
 *   <li><b>Hold</b> - otherwise the explicit no-op. Never a redundant Explore.</li>
 * </ol>
 */
public final class ScriptedSovereign implements Sovereign {

    /** Default Minerals floor before the bot will queue any build. */
    public static final double DEFAULT_MIN_MINERALS_TO_BUILD = 50.0;

    /**
     * Default Minerals stockpile at/above which the bot pivots into the ship/colony
     * economy (E11-05, finding L5 / decision P4): build a {@link BuildingType#SHIPYARD}
     * if it lacks one, then construct colony ships to expand. Set well above the
     * {@link #DEFAULT_MIN_MINERALS_TO_BUILD} economy floor and the Shipyard's config
     * Minerals cost ({@code shipyard = 70} in the shipped profiles) so the early game
     * still fills planet slots with economy first; the pivot only fires once Minerals
     * have actually <em>piled up</em> - the unbounded-hoard symptom the live sim flagged
     * (8829 and climbing with no sink). A pure stockpile proxy, like the existing
     * {@link #minMineralsToBuild} / {@link #energyFloor} / {@link #foodFloor} floors,
     * because per-action cost lives in the balance profile, not in the {@code WorldView}
     * the bot decides from.
     */
    public static final double DEFAULT_SHIP_ECONOMY_MINERAL_THRESHOLD = 200.0;

    /**
     * Default colony-ship archetype the bot constructs as its mineral sink (E11-05).
     * {@code "freighter"} is an <em>ungated</em> hauler (no doctrine-tech prerequisite in
     * the shipped profiles' {@code tech.unlocks}, combat tier {@code 0.0}), so a
     * {@link Action.BuildFleet} naming it passes the engine validator from the start - no
     * research rung required first - and the resulting non-combatant fleet is the colony
     * deliverer the Colonise rung consumes. The concrete ship stats (cost, crew) live in
     * the balance profile keyed by this archetype, never here (rule 6); the bot only
     * names the archetype.
     */
    public static final String DEFAULT_COLONY_SHIP_SPEC = "freighter";

    /**
     * Default Energy stockpile at or below which the bot prefers a
     * {@link BuildingType#SOLAR_ARRAY} (E10-02, finding F2). Energy is the resource
     * mines drain (1.0/tick upkeep) yet the oceanic home biome yields none, and it is
     * also the market currency - so a faction that lets it hit 0 sits in permanent
     * deficit and cannot trade. Keeping the floor comfortably above 0 means the bot
     * builds the energy fix <em>before</em> it starves, not after.
     */
    public static final double DEFAULT_ENERGY_FLOOR = 50.0;

    /**
     * Default Food stockpile at or below which the bot prefers a
     * {@link BuildingType#FARM} (E10-02). Food trending negative shrinks population
     * (and thus production); a floor above 0 builds the fix before decline sets in.
     */
    public static final double DEFAULT_FOOD_FLOOR = 30.0;

    /**
     * Fixed, legible preference order for economy buildings, indexed by the build
     * heuristic ({@link #chooseBuildType}, E10-02). The three economy engines the
     * heuristic chooses among are read from this list by name so the intended priority
     * lives in one place: a {@link BuildingType#MINE} (the default), a
     * {@link BuildingType#SOLAR_ARRAY} (the Energy fix), and a {@link BuildingType#FARM}
     * (the Food fix). {@link BuildingType#RESEARCH_LAB} trails as documentation of
     * intended priority for later cards.
     */
    private static final List<BuildingType> BUILD_PREFERENCE = List.of(
            BuildingType.MINE,
            BuildingType.SOLAR_ARRAY,
            BuildingType.FARM,
            BuildingType.RESEARCH_LAB);

    /** Index of the default minerals engine in {@link #BUILD_PREFERENCE}. */
    private static final int PREF_MINE = 0;
    /** Index of the Energy fix in {@link #BUILD_PREFERENCE}. */
    private static final int PREF_SOLAR_ARRAY = 1;
    /** Index of the Food fix in {@link #BUILD_PREFERENCE}. */
    private static final int PREF_FARM = 2;

    private final FactionId factionId;
    private final double minMineralsToBuild;
    private final double energyFloor;
    private final double foodFloor;
    private final double shipEconomyMineralThreshold;
    private final String colonyShipSpec;

    /**
     * The bot's bounded multi-tick plan (E12-01, finding P7a). The <em>only</em>
     * mutable state on this Sovereign, and it lives here in the instance, never in
     * the engine. Reset/recovered by {@link #reconcile(WorldView)} at the top of every
     * {@link #decide(WorldView)} so a contradicted plan (target lost / taken / no
     * longer a colonisable neutral) is forgotten and re-planned.
     */
    private PlanMemory plan = PlanMemory.empty();

    /**
     * The phases of the bot's expansion plan, advanced tick-to-tick instead of being
     * recomputed from scratch (finding P7a). They are derived deterministically from
     * the {@link WorldView} and the committed target, so the enum is a legible label
     * for "what the bot is doing", not hidden authority:
     * <ol>
     *   <li>{@link #SCOUT} - the bot has committed to a neutral expansion target it has
     *       not yet physically reached: it heads toward it (Explore an unexplored hop,
     *       else lay down colony ships at the Shipyard to ferry there). It sticks with
     *       the <em>committed</em> target across ticks rather than re-choosing the
     *       lowest-id neutral every tick.</li>
     *   <li>{@link #COLONISE} - a fleet is parked at the committed target and it exposes
     *       a nameable planet: the bot colonises it, finishing expansion.</li>
     *   <li>{@link #FORTIFY} - no live expansion target (none committed, or the last one
     *       was colonised/invalidated): the bot develops its holdings (Shipyard, economy
     *       buildings, more ships) - the early-game / mineral-sink behaviour.</li>
     * </ol>
     */
    enum Phase {
        SCOUT, COLONISE, FORTIFY
    }

    /**
     * The bot's bounded, instance-local memory between ticks (finding P7a): the single
     * expansion target it has committed to ({@code empty} when none) and the
     * {@link Phase} it last acted in (for legibility / tests). Bounded to one target id
     * by construction - the bot pursues one expansion at a time, so memory cannot grow
     * with galaxy size. Immutable; {@link #decide(WorldView)} swaps in a new instance.
     */
    record PlanMemory(Optional<SystemId> coloniseTarget, Phase phase) {
        PlanMemory {
            if (coloniseTarget == null) {
                throw new IllegalArgumentException("PlanMemory.coloniseTarget must be set (use Optional.empty())");
            }
            if (phase == null) {
                throw new IllegalArgumentException("PlanMemory.phase must be set");
            }
        }

        static PlanMemory empty() {
            return new PlanMemory(Optional.empty(), Phase.FORTIFY);
        }

        PlanMemory withTarget(SystemId target, Phase nextPhase) {
            return new PlanMemory(Optional.of(target), nextPhase);
        }

        PlanMemory cleared(Phase nextPhase) {
            return new PlanMemory(Optional.empty(), nextPhase);
        }
    }

    /**
     * @param factionId the seat this bot plays (never {@code null})
     */
    public ScriptedSovereign(FactionId factionId) {
        this(factionId, DEFAULT_MIN_MINERALS_TO_BUILD, DEFAULT_ENERGY_FLOOR, DEFAULT_FOOD_FLOOR);
    }

    /**
     * Backwards-compatible 2-arg constructor: override only the Minerals build floor,
     * keeping the default Energy/Food pressure floors and the default ship economy.
     *
     * @param factionId          the seat this bot plays (never {@code null})
     * @param minMineralsToBuild Minerals the faction must hold before the bot queues
     *                           a build (an affordability proxy; {@code >= 0})
     */
    public ScriptedSovereign(FactionId factionId, double minMineralsToBuild) {
        this(factionId, minMineralsToBuild, DEFAULT_ENERGY_FLOOR, DEFAULT_FOOD_FLOOR);
    }

    /**
     * Backwards-compatible 4-arg constructor predating the E11-05 ship/colony economy:
     * keeps the default ship-economy Minerals threshold and colony-ship archetype.
     *
     * @param factionId          the seat this bot plays (never {@code null})
     * @param minMineralsToBuild Minerals the faction must hold before the bot queues
     *                           a build (an affordability proxy; {@code >= 0})
     * @param energyFloor        Energy stockpile at/below which the bot prefers a
     *                           {@link BuildingType#SOLAR_ARRAY} (E10-02; {@code >= 0})
     * @param foodFloor          Food stockpile at/below which the bot prefers a
     *                           {@link BuildingType#FARM} (E10-02; {@code >= 0})
     */
    public ScriptedSovereign(FactionId factionId, double minMineralsToBuild,
                             double energyFloor, double foodFloor) {
        this(factionId, minMineralsToBuild, energyFloor, foodFloor,
                DEFAULT_SHIP_ECONOMY_MINERAL_THRESHOLD, DEFAULT_COLONY_SHIP_SPEC);
    }

    /**
     * @param factionId                   the seat this bot plays (never {@code null})
     * @param minMineralsToBuild          Minerals the faction must hold before the bot
     *                                    queues a build (an affordability proxy; {@code >= 0})
     * @param energyFloor                 Energy stockpile at/below which the bot prefers a
     *                                    {@link BuildingType#SOLAR_ARRAY} (E10-02; {@code >= 0})
     * @param foodFloor                   Food stockpile at/below which the bot prefers a
     *                                    {@link BuildingType#FARM} (E10-02; {@code >= 0})
     * @param shipEconomyMineralThreshold Minerals at/above which the bot pivots into the
     *                                    ship/colony economy - build a {@link
     *                                    BuildingType#SHIPYARD} if it lacks one, then
     *                                    construct colony ships to expand (E11-05; {@code >= 0};
     *                                    typically &gt;= {@code minMineralsToBuild})
     * @param colonyShipSpec              the ungated colony-ship archetype key the bot
     *                                    constructs as its mineral sink (E11-05; non-blank)
     */
    public ScriptedSovereign(FactionId factionId, double minMineralsToBuild,
                             double energyFloor, double foodFloor,
                             double shipEconomyMineralThreshold, String colonyShipSpec) {
        if (factionId == null) {
            throw new IllegalArgumentException("ScriptedSovereign.factionId must be set");
        }
        if (minMineralsToBuild < 0) {
            throw new IllegalArgumentException("ScriptedSovereign.minMineralsToBuild must be >= 0");
        }
        if (energyFloor < 0) {
            throw new IllegalArgumentException("ScriptedSovereign.energyFloor must be >= 0");
        }
        if (foodFloor < 0) {
            throw new IllegalArgumentException("ScriptedSovereign.foodFloor must be >= 0");
        }
        if (shipEconomyMineralThreshold < 0) {
            throw new IllegalArgumentException(
                    "ScriptedSovereign.shipEconomyMineralThreshold must be >= 0");
        }
        if (colonyShipSpec == null || colonyShipSpec.isBlank()) {
            throw new IllegalArgumentException("ScriptedSovereign.colonyShipSpec must be non-blank");
        }
        this.factionId = factionId;
        this.minMineralsToBuild = minMineralsToBuild;
        this.energyFloor = energyFloor;
        this.foodFloor = foodFloor;
        this.shipEconomyMineralThreshold = shipEconomyMineralThreshold;
        this.colonyShipSpec = colonyShipSpec;
    }

    @Override
    public FactionId factionId() {
        return factionId;
    }

    @Override
    public AgentResponse decide(WorldView view) {
        if (view == null) {
            throw new IllegalArgumentException("decide.view must be set");
        }
        // (1) Reconcile the carried plan against this tick's perception: forget a target
        // the view contradicts (lost / taken / no longer a colonisable neutral) and, if
        // none is committed, commit to one derivable from the view. Pure in (plan, view),
        // so a fresh bot and an existing one converge on the same view sequence.
        PlanMemory reconciled = reconcile(view);

        // (2) Pick exactly one primary action via the ladder. The Colonise and Explore
        // rungs prefer the *committed* target so the bot follows a multi-tick plan
        // (scout -> colonise) instead of re-choosing the lowest-id neutral each tick.
        // The bot is silent in negotiation: it sends no messages.
        ChosenAction chosen = chooseAction(view, reconciled);

        // (3) Record the phase the chosen action belongs to so the plan advances tick to
        // tick. Targetless rungs (build / ship economy / hold) are FORTIFY and clear any
        // stale target the action did not consume.
        this.plan = chosen.nextPlan(reconciled);
        return AgentResponse.now(List.of(), List.of(chosen.action()));
    }

    /** The bot's current plan memory. Package-private for tests; do not expose to agents. */
    PlanMemory plan() {
        return plan;
    }

    /**
     * Reconcile the carried {@link PlanMemory} against this tick's {@link WorldView}
     * (finding P7a - "reset/recover sanely if the WorldView contradicts the plan").
     * Pure function of {@code (plan, view)} - the entire memory update derives from the
     * view, never wall-clock or iteration order, so the sequence stays replay-stable.
     *
     * <ul>
     *   <li><b>Invalidate</b> a committed target the view no longer supports: it dropped
     *       out of the neighbour set (lost to fog / consumed), or it is no longer a
     *       neutral (another faction - or this one - now owns it). Either way forget it.</li>
     *   <li><b>Recover / commit</b> when no live target is held: pick the canonical
     *       expansion target from the view - the lowest-id neutral neighbour (preferring
     *       one already colonisable, i.e. a fleet is parked at it) - so the bot has a
     *       single thing to pursue. Empty when the frontier offers nothing.</li>
     *   <li><b>Hold</b> an already-valid committed target unchanged, so the bot keeps
     *       pursuing the <em>same</em> system across ticks rather than re-deciding.</li>
     * </ul>
     */
    private PlanMemory reconcile(WorldView view) {
        Optional<SystemId> held = plan.coloniseTarget();
        if (held.isPresent() && isLiveTarget(view, held.get())) {
            return plan.withTarget(held.get(), plan.phase());
        }
        // No live target: commit to the canonical frontier target if one exists.
        return pickExpansionTarget(view)
                .map(t -> PlanMemory.empty().withTarget(t, Phase.SCOUT))
                .orElseGet(PlanMemory::empty);
    }

    /**
     * @return true iff {@code target} is still a sane expansion target this tick: it is
     * present in the neighbour set and still neutral (unowned). A target that fell out of
     * view or was taken by another faction fails this and is forgotten by {@link
     * #reconcile(WorldView)}.
     */
    private static boolean isLiveTarget(WorldView view, SystemId target) {
        return view.neighbours().stream()
                .filter(n -> n.systemId().equals(target))
                .anyMatch(WorldView.NeighbourView::isNeutral);
    }

    /**
     * The canonical neutral neighbour to commit to as the expansion target: a
     * colonisable one (a fleet already parked there) if any, else the lowest-id neutral
     * to scout toward. Sorted by id so the choice is independent of projected iteration
     * order. Empty when there is no neutral frontier at all.
     */
    private static Optional<SystemId> pickExpansionTarget(WorldView view) {
        Optional<SystemId> colonisable = view.neighbours().stream()
                .filter(WorldView.NeighbourView::isColonisable)
                .map(WorldView.NeighbourView::systemId)
                .min(Comparator.comparing(SystemId::value));
        if (colonisable.isPresent()) {
            return colonisable;
        }
        return view.neighbours().stream()
                .filter(WorldView.NeighbourView::isNeutral)
                .map(WorldView.NeighbourView::systemId)
                .min(Comparator.comparing(SystemId::value));
    }

    /**
     * The heuristic ladder, now plan-aware. Development rungs (Shipyard, economy build)
     * still rank first - a faction with idle slots or a mineral hoard should always be
     * investing - but the Colonise and Explore rungs are steered toward the
     * <em>committed</em> target so the bot follows one expansion through across ticks.
     * Each rung reports the {@link Phase} it represents so the plan can advance.
     */
    private ChosenAction chooseAction(WorldView view, PlanMemory reconciled) {
        Optional<SystemId> target = reconciled.coloniseTarget();
        return chooseShipyard(view).map(a -> ChosenAction.fortify(a))
                .or(() -> chooseBuild(view).map(ChosenAction::fortify))
                .or(() -> chooseColonise(view, target).map(a -> ChosenAction.colonise(a, target)))
                .or(() -> chooseConstructColonyShip(view).map(a -> ChosenAction.scout(a, target)))
                .or(() -> chooseExplore(view, target).map(a -> ChosenAction.scout(a, target)))
                .orElseGet(() -> ChosenAction.fortify(new Action.Hold()));
    }

    /**
     * One chosen primary action plus the {@link Phase} it belongs to, used to advance the
     * plan after the decision. A {@code SCOUT}/{@code COLONISE} action keeps the committed
     * target alive; a {@code FORTIFY} action clears any (now-spent or irrelevant) target so
     * a re-plan happens next tick.
     */
    private record ChosenAction(Action action, Phase phase, Optional<SystemId> target) {
        static ChosenAction fortify(Action action) {
            return new ChosenAction(action, Phase.FORTIFY, Optional.empty());
        }

        static ChosenAction scout(Action action, Optional<SystemId> target) {
            return new ChosenAction(action, Phase.SCOUT, target);
        }

        static ChosenAction colonise(Action action, Optional<SystemId> target) {
            return new ChosenAction(action, Phase.COLONISE, target);
        }

        PlanMemory nextPlan(PlanMemory reconciled) {
            return target.map(t -> reconciled.withTarget(t, phase))
                    .orElseGet(() -> reconciled.cleared(phase));
        }
    }

    /**
     * Ladder step 0 (E11-05, finding L5 / decision P4): once Minerals have piled up past
     * {@link #shipEconomyMineralThreshold} and the faction owns <em>no</em>
     * {@link BuildingType#SHIPYARD} anywhere, queue one on the lowest free slot of the
     * lowest-id owned planet. Ranked above the economy build so a mineral hoard buys the
     * growth engine rather than yet-another mine; gated on the high Minerals proxy so the
     * early game still fills slots with economy first. Deterministic: owned systems and
     * their planets are sorted by id before scanning, so the first free slot is canonical.
     */
    private Optional<Action> chooseShipyard(WorldView view) {
        if (view.self().stockpiles().minerals() < shipEconomyMineralThreshold) {
            return Optional.empty();
        }
        if (hasShipyard(view)) {
            return Optional.empty();
        }
        return firstFreeSlot(view).map(slot ->
                new Action.Build(slot.planet(), slot.index(), BuildingType.SHIPYARD));
    }

    /**
     * Ladder step 3 (E11-05): the mineral sink + growth path. When the faction owns a
     * Shipyard, holds Minerals past {@link #shipEconomyMineralThreshold}, and has a
     * reachable neutral (unowned) neighbour to expand toward, construct a
     * {@link #colonyShipSpec} at the lowest-id owned Shipyard system via
     * {@link Action.BuildFleet}. Spending the hoard on fleets is the whole point: the
     * Colonise / Explore rungs then send those fleets to reachable neutrals. Ranked
     * <em>below</em> Colonise so an already-reached neutral is settled before more ships
     * are laid down. The spec is ungated, so the action passes the validator from the
     * start. Deterministic: the Shipyard system is the lowest-id owned system carrying one.
     */
    private Optional<Action> chooseConstructColonyShip(WorldView view) {
        if (view.self().stockpiles().minerals() < shipEconomyMineralThreshold) {
            return Optional.empty();
        }
        boolean hasNeutralTarget = view.neighbours().stream()
                .anyMatch(WorldView.NeighbourView::isNeutral);
        if (!hasNeutralTarget) {
            return Optional.empty();
        }
        return shipyardSystem(view).map(system -> new Action.BuildFleet(system, colonyShipSpec));
    }

    /** @return true iff this faction owns at least one system with a Shipyard. */
    private static boolean hasShipyard(WorldView view) {
        return view.ownSystems().stream().anyMatch(WorldView.SystemView::hasShipyard);
    }

    /** The lowest-id owned system that carries a Shipyard, if any (deterministic). */
    private static Optional<SystemId> shipyardSystem(WorldView view) {
        return view.ownSystems().stream()
                .filter(WorldView.SystemView::hasShipyard)
                .map(WorldView.SystemView::id)
                .min(Comparator.comparing(SystemId::value));
    }

    /**
     * The canonical first free build slot across all owned planets: lowest-id owned
     * system, then lowest-id planet, then its lowest free slot index. Empty when every
     * owned planet is full. Shared by the Shipyard rung so its placement matches the
     * economy build rung exactly.
     */
    private static Optional<FreeSlot> firstFreeSlot(WorldView view) {
        List<WorldView.SystemView> systems = view.ownSystems().stream()
                .sorted(Comparator.comparing(s -> s.id().value()))
                .toList();
        for (WorldView.SystemView sys : systems) {
            List<WorldView.PlanetView> planets = sys.ownedPlanets().stream()
                    .sorted(Comparator.comparing(p -> p.id().value()))
                    .toList();
            for (WorldView.PlanetView planet : planets) {
                if (planet.hasFreeSlot()) {
                    return Optional.of(new FreeSlot(planet.id(), planet.firstFreeSlot()));
                }
            }
        }
        return Optional.empty();
    }

    /** A located free build slot: the planet to build on and the slot index to fill. */
    private record FreeSlot(PlanetId planet, int index) {
    }

    /**
     * Ladder step 1: queue a building on the lowest-id owned planet that has a free
     * slot, gated on a Minerals floor. The building <em>type</em> comes from
     * {@link #chooseBuildType} (E10-02); placement is unchanged from E10-01 -
     * deterministic, owned systems and their planets sorted by id before scanning, so
     * the first free slot found is canonical.
     */
    private Optional<Action> chooseBuild(WorldView view) {
        if (view.self().stockpiles().minerals() < minMineralsToBuild) {
            return Optional.empty();
        }
        BuildingType type = chooseBuildType(view);
        return firstFreeSlot(view).map(slot ->
                new Action.Build(slot.planet(), slot.index(), type));
    }

    /**
     * Pick <em>which</em> economy building to queue from the whole
     * {@link #BUILD_PREFERENCE} (E10-02, finding F2). A MINE-only bot starved Energy to
     * 0: mines carry 1.0 Energy/tick upkeep, the oceanic home biome yields no Energy,
     * and Energy is the market currency - so the faction sat in permanent deficit and
     * could not trade, never building the SOLAR_ARRAY that would fix it.
     *
     * <p><b>Resource-pressure heuristic (priority order):</b>
     * <ol>
     *   <li>{@link BuildingType#SOLAR_ARRAY} when Energy is under pressure - the
     *       stockpile is at or below {@link #energyFloor}. (Energy is checked first: it
     *       is the resource mines drain and the trade currency, so it is the F2 root
     *       cause and the most urgent to defend.)</li>
     *   <li>{@link BuildingType#FARM} when Food is under pressure - the stockpile is at
     *       or below {@link #foodFloor}, i.e. Food trends negative.</li>
     *   <li>{@link BuildingType#MINE} otherwise - the default minerals engine.</li>
     * </ol>
     *
     * <p><b>Production-vs-upkeep proxy.</b> The {@link WorldView} surfaces the faction's
     * stockpiles but not its per-resource production-vs-upkeep, so this derives the
     * "energy upkeep &ge; production" / "food trends negative" signals from the only
     * fog-safe quantity available: the current stockpile against a floor. A faction in
     * energy deficit bleeds its Energy stockpile toward 0 every tick, so a stockpile
     * at/below the floor is a deterministic stand-in for "upkeep is outpacing
     * production". This keeps the heuristic legible and needs no WorldView contract
     * change. Fully deterministic: same stockpiles ⇒ same type.
     */
    private BuildingType chooseBuildType(WorldView view) {
        double energy = view.self().stockpiles().energy();
        double food = view.self().stockpiles().food();
        if (energy <= energyFloor) {
            return BUILD_PREFERENCE.get(PREF_SOLAR_ARRAY);
        }
        if (food <= foodFloor) {
            return BUILD_PREFERENCE.get(PREF_FARM);
        }
        return BUILD_PREFERENCE.get(PREF_MINE);
    }

    /**
     * Ladder step 2 (E10-01): colonise a reachable neutral. A neutral neighbour is
     * colonisable when the actor has an idle fleet parked at it and the view surfaces
     * its planet ids ({@link WorldView.NeighbourView#isColonisable()}). Colonise the
     * lowest-id planet of the lowest-id such system via that fleet. Sorting by system
     * id then planet id keeps the choice canonical regardless of projected iteration
     * order. The emitted {@link Action.Colonize} is shaped to pass the validator: the
     * planet sits on a neutral host the fleet is stationed at, and the fleet is the
     * actor's own.
     */
    private Optional<Action> chooseColonise(WorldView view, Optional<SystemId> committedTarget) {
        // Plan-aware (E12-01): if the committed expansion target is colonisable this
        // tick, settle *it* rather than re-picking the lowest-id colonisable neutral, so
        // the bot follows the same plan through to completion. Falls back to the lowest-id
        // colonisable neutral when no target is committed or the committed one is not yet
        // reachable - preserving the pre-E12-01 behaviour exactly.
        Optional<WorldView.NeighbourView> committed = committedTarget.flatMap(t ->
                view.neighbours().stream()
                        .filter(n -> n.systemId().equals(t))
                        .filter(WorldView.NeighbourView::isColonisable)
                        .findFirst());
        return committed
                .or(() -> view.neighbours().stream()
                        .filter(WorldView.NeighbourView::isColonisable)
                        .min(Comparator.comparing(n -> n.systemId().value())))
                .flatMap(this::colonizeAt);
    }

    /** Build a {@link Action.Colonize} for the lowest-id planet on a colonisable neutral. */
    private Optional<Action> colonizeAt(WorldView.NeighbourView neutral) {
        Optional<PlanetId> target = neutral.colonisablePlanets().stream()
                .min(Comparator.comparing(PlanetId::value));
        return target.flatMap(planet ->
                neutral.reachableViaFleet().map(fleet -> new Action.Colonize(planet, fleet)));
    }

    /**
     * Ladder step 3: if any fleet is idle (parked, not en route), explore the lowest-id
     * neutral (unowned) neighbour that is <em>not already revealed/explored</em>.
     * Sorting neighbours by id keeps the target canonical regardless of projected
     * iteration order.
     *
     * <p><b>E10-01 (finding F1).</b> The old ladder explored the lowest-id neutral
     * neighbour every tick - but every neighbour the fog builder surfaces is already
     * revealed, so that re-explored a known system forever (a no-op the resolver still
     * had to slot). Filtering on {@code !explored()} means a neighbour already in the
     * view is never re-explored; under the current fog model (all surfaced neighbours
     * are revealed) this yields no Explore, so the bot Holds rather than busy-looping.
     */
    private Optional<Action> chooseExplore(WorldView view, Optional<SystemId> committedTarget) {
        boolean hasIdleFleet = view.ownFleets().stream().anyMatch(WorldView.FleetView::isIdle);
        if (!hasIdleFleet) {
            return Optional.empty();
        }
        Set<SystemId> unexplored = view.neighbours().stream()
                .filter(WorldView.NeighbourView::isNeutral)
                .filter(n -> !n.explored())
                .map(WorldView.NeighbourView::systemId)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        if (unexplored.isEmpty()) {
            return Optional.empty();
        }
        // Plan-aware (E12-01): scout the committed target first if it is still
        // unexplored, so the bot drives toward the system it is pursuing rather than the
        // lowest-id unexplored one. Otherwise fall back to the lowest-id unexplored
        // neutral (the pre-E12-01 choice).
        return committedTarget.filter(unexplored::contains)
                .or(() -> unexplored.stream().min(Comparator.comparing(SystemId::value)))
                .map(Action.Explore::new);
    }
}
