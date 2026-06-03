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

/**
 * A deterministic, LLM-free {@link Sovereign} for tests and empty seats (E3-01).
 *
 * <p><b>Determinism (non-negotiable).</b> Same {@link WorldView} ⇒ same
 * {@link AgentResponse}, every run. The bot uses <em>no</em> wall-clock, no
 * {@code Math.random}, no unseeded randomness. Where it chooses among candidate
 * systems/planets it first <b>sorts by id</b> so {@code HashMap}/{@code HashSet}
 * iteration order in the projected view can never leak into the decision. It holds
 * no mutable state between ticks, so two {@code ScriptedSovereign}s built the same
 * way are interchangeable and replay-stable.
 *
 * <p><b>Closed agent I/O.</b> It only ever emits valid sealed {@link Action}
 * variants and a well-formed {@link AgentResponse}, and it can always fall back to
 * the explicit {@link Action.Hold} no-op. Every emitted action targets only
 * actor-knowable, owned assets so it is shaped to pass the engine
 * {@code ActionValidator} (the engine still validates authoritatively).
 *
 * <p><b>Heuristic ladder</b> (first applicable wins; at most one primary action per
 * tick plus {@code Hold} when idle), see spec section 7b:
 * <ol>
 *   <li><b>Build economy</b> - on the lowest-id owned planet with a free slot, queue
 *       a {@link BuildingType#MINE} in the lowest free slot, but only when the
 *       faction holds at least {@link #minMineralsToBuild} Minerals (a conservative
 *       affordability proxy, since per-building cost lives in the balance profile,
 *       not in the {@code WorldView}).</li>
 *   <li><b>Colonise</b> - else, if it has an idle fleet parked at a reachable neutral
 *       (unowned) neighbour with a nameable planet, {@link Action.Colonize} the
 *       lowest-id planet of the lowest-id such system via that fleet (E10-01: the
 *       branch deferred in E3-01, now that fog E3-02 + lanes E2-03 surface the
 *       neutral-planet detail and a delivering fleet).</li>
 *   <li><b>Explore</b> - else, if it has an idle fleet and a neutral neighbour that is
 *       <em>not already revealed/explored</em>, {@link Action.Explore} the lowest-id
 *       such system. A neighbour already present in the view is revealed, so this is
 *       skipped - the bot never re-Explores a known system (E10-01, finding F1: the
 *       old ladder re-explored the same revealed neutral every tick).</li>
 *   <li><b>Hold</b> - otherwise the explicit no-op. Never a redundant Explore.</li>
 * </ol>
 */
public final class ScriptedSovereign implements Sovereign {

    /** Default Minerals floor before the bot will queue a {@link BuildingType#MINE}. */
    public static final double DEFAULT_MIN_MINERALS_TO_BUILD = 50.0;

    /**
     * Fixed, legible preference order for economy buildings. Only the first entry is
     * used by the current ladder; the ordered list documents the intended priority
     * and keeps the choice deterministic if later cards widen the build heuristic.
     */
    private static final List<BuildingType> BUILD_PREFERENCE = List.of(
            BuildingType.MINE,
            BuildingType.SOLAR_ARRAY,
            BuildingType.FARM,
            BuildingType.RESEARCH_LAB);

    private final FactionId factionId;
    private final double minMineralsToBuild;

    /**
     * @param factionId the seat this bot plays (never {@code null})
     */
    public ScriptedSovereign(FactionId factionId) {
        this(factionId, DEFAULT_MIN_MINERALS_TO_BUILD);
    }

    /**
     * @param factionId          the seat this bot plays (never {@code null})
     * @param minMineralsToBuild Minerals the faction must hold before the bot queues
     *                           a build (an affordability proxy; {@code >= 0})
     */
    public ScriptedSovereign(FactionId factionId, double minMineralsToBuild) {
        if (factionId == null) {
            throw new IllegalArgumentException("ScriptedSovereign.factionId must be set");
        }
        if (minMineralsToBuild < 0) {
            throw new IllegalArgumentException("ScriptedSovereign.minMineralsToBuild must be >= 0");
        }
        this.factionId = factionId;
        this.minMineralsToBuild = minMineralsToBuild;
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
        // The bot is silent in negotiation: it sends no messages. Pick exactly one
        // primary action via the ladder, defaulting to the explicit Hold no-op.
        Action action = chooseBuild(view)
                .or(() -> chooseColonise(view))
                .or(() -> chooseExplore(view))
                .orElseGet(Action.Hold::new);
        return AgentResponse.now(List.of(), List.of(action));
    }

    /**
     * Ladder step 1: queue a Mine on the lowest-id owned planet that has a free slot,
     * gated on a Minerals floor. Deterministic: owned systems and their planets are
     * sorted by id before scanning, so the first free slot found is canonical.
     */
    private Optional<Action> chooseBuild(WorldView view) {
        if (view.self().stockpiles().minerals() < minMineralsToBuild) {
            return Optional.empty();
        }
        BuildingType type = BUILD_PREFERENCE.get(0);
        List<WorldView.SystemView> systems = view.ownSystems().stream()
                .sorted(Comparator.comparing(s -> s.id().value()))
                .toList();
        for (WorldView.SystemView sys : systems) {
            List<WorldView.PlanetView> planets = sys.ownedPlanets().stream()
                    .sorted(Comparator.comparing(p -> p.id().value()))
                    .toList();
            for (WorldView.PlanetView planet : planets) {
                if (planet.hasFreeSlot()) {
                    return Optional.of(new Action.Build(planet.id(), planet.firstFreeSlot(), type));
                }
            }
        }
        return Optional.empty();
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
    private Optional<Action> chooseColonise(WorldView view) {
        return view.neighbours().stream()
                .filter(WorldView.NeighbourView::isColonisable)
                .min(Comparator.comparing(n -> n.systemId().value()))
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
    private Optional<Action> chooseExplore(WorldView view) {
        boolean hasIdleFleet = view.ownFleets().stream().anyMatch(WorldView.FleetView::isIdle);
        if (!hasIdleFleet) {
            return Optional.empty();
        }
        return view.neighbours().stream()
                .filter(WorldView.NeighbourView::isNeutral)
                .filter(n -> !n.explored())
                .map(WorldView.NeighbourView::systemId)
                .min(Comparator.comparing(SystemId::value))
                .map(Action.Explore::new);
    }
}
