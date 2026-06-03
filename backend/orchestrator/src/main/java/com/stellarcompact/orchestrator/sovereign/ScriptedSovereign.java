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

    /** Default Minerals floor before the bot will queue any build. */
    public static final double DEFAULT_MIN_MINERALS_TO_BUILD = 50.0;

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

    /**
     * @param factionId the seat this bot plays (never {@code null})
     */
    public ScriptedSovereign(FactionId factionId) {
        this(factionId, DEFAULT_MIN_MINERALS_TO_BUILD, DEFAULT_ENERGY_FLOOR, DEFAULT_FOOD_FLOOR);
    }

    /**
     * Backwards-compatible 2-arg constructor: override only the Minerals build floor,
     * keeping the default Energy/Food pressure floors.
     *
     * @param factionId          the seat this bot plays (never {@code null})
     * @param minMineralsToBuild Minerals the faction must hold before the bot queues
     *                           a build (an affordability proxy; {@code >= 0})
     */
    public ScriptedSovereign(FactionId factionId, double minMineralsToBuild) {
        this(factionId, minMineralsToBuild, DEFAULT_ENERGY_FLOOR, DEFAULT_FOOD_FLOOR);
    }

    /**
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
        this.factionId = factionId;
        this.minMineralsToBuild = minMineralsToBuild;
        this.energyFloor = energyFloor;
        this.foodFloor = foodFloor;
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
