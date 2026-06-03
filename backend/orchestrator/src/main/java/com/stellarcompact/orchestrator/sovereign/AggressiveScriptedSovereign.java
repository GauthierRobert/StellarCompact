package com.stellarcompact.orchestrator.sovereign;

import com.stellarcompact.engine.action.Action;
import com.stellarcompact.engine.action.AgentResponse;
import com.stellarcompact.engine.action.AttackTarget;
import com.stellarcompact.engine.state.BuildingType;
import com.stellarcompact.engine.state.FactionId;
import com.stellarcompact.engine.state.FleetId;
import com.stellarcompact.engine.state.SystemId;
import com.stellarcompact.engine.state.TreatyStatus;
import com.stellarcompact.engine.state.TreatyType;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * A deterministic, LLM-free <em>aggressive</em> {@link Sovereign} variant (E10-03) that
 * drives conflict so the combat / diplomacy / victory engine paths are exercised
 * headlessly. It is the war-making counterpart to {@link ScriptedSovereign}: the
 * 3-agent-sim findings (game-design 09 finding F5) showed every peaceful scripted match
 * ends in {@code TICK_LIMIT} with <b>zero</b> public events - no war, battle, capture or
 * victory path ever fires. This bot escalates to war on purpose.
 *
 * <p><b>Determinism (non-negotiable).</b> Same {@link WorldView} yields the same
 * {@link AgentResponse}, every run. No wall-clock, no {@code Math.random}, no unseeded
 * randomness, and <em>no mutable state between ticks</em> (the bot is a pure function of
 * the view, like {@link ScriptedSovereign}). Every candidate faction / system / planet is
 * <b>sorted by id</b> before a choice is made, so {@code HashMap}/{@code HashSet} iteration
 * order in the projected view can never leak into the decision. Two
 * {@code AggressiveScriptedSovereign}s built the same way are interchangeable and the
 * recorded action log is fully replayable.
 *
 * <p><b>Closed agent I/O.</b> It only ever emits valid sealed {@link Action} variants and
 * a well-formed {@link AgentResponse}, and falls back to the explicit {@link Action.Hold}
 * no-op. Every emitted action targets only actor-knowable assets (own systems/fleets, the
 * public reputation ledger, the public treaty ledger, and fog-revealed enemy-owned
 * neighbour system ids) and is shaped to pass the engine {@code ActionValidator} (the
 * engine still validates authoritatively; a rejected action is dropped by the
 * orchestrator - closed I/O, then one re-prompt / Hold).
 *
 * <p><b>The escalation ladder.</b> Unlike {@link ScriptedSovereign} (which emits at most
 * one primary action), the aggressive bot emits an <em>ordered batch</em> each tick so the
 * diplomatic state and the kinetic strike it gates can be submitted together (the resolver
 * resolves {@code DeclareWar} in the DIPLOMATIC_STATE step, step 1, before the COMBAT step,
 * step 4, so a war declared this tick is in force for an attack later in the same tick).
 * First-applicable rungs:
 * <ol>
 *   <li><b>Build military economy</b> - on the lowest-id owned planet with a free slot,
 *       queue the first affordable structure from a military-leaning preference: a
 *       {@link BuildingType#SHIPYARD} once it can afford it, otherwise a
 *       {@link BuildingType#MINE} to fund the shipyard. (Per-building cost lives in the
 *       balance profile, not the {@code WorldView}, so affordability is a conservative
 *       Minerals-floor proxy, as in {@link ScriptedSovereign}.)</li>
 *   <li><b>Build warships</b> - if an owned system has an active Shipyard, emit a
 *       {@link Action.BuildFleet} for a {@code corvette} there (the cheap line warship).</li>
 *   <li><b>Declare war</b> - on the lowest-id <em>reachable rival</em>: the lowest-id
 *       faction (other than self) that owns a fog-revealed neighbour system, subject to
 *       treaty legality - a rival behind an ACTIVE NonAggression / Alliance / Ceasefire
 *       treaty is skipped (the validator would reject {@code DeclareWar} as
 *       {@code TREATY_FORBIDS}). Re-declaring an existing war is idempotent in the engine,
 *       so emitting it every tick is safe and stateless.</li>
 *   <li><b>Attack</b> - with an idle combat fleet, assault the lowest-id enemy-owned
 *       neighbour system of that same rival ({@link Action.Attack} with an
 *       {@link AttackTarget.OnSystem}) to force a battle and capture. (Combat has no
 *       fleet-positioning gate yet - E1-10 TODO - so the assault needs only a war state
 *       plus an owned fleet, both of which the bot drives.)</li>
 *   <li><b>Hold</b> - if none of the above applies, the explicit no-op.</li>
 * </ol>
 *
 * <p><b>Why DeclareWar and Attack are emitted together, every tick.</b> The
 * {@code ActionValidator} runs against the <em>pre-tick</em> snapshot, where the freshly
 * declared war does not yet exist, so an {@code Attack} emitted on the same tick as the
 * first {@code DeclareWar} is rejected {@code NOT_AT_WAR} and dropped by the orchestrator.
 * On the next tick the war already exists (the prior {@code DeclareWar} resolved), so the
 * idempotent re-{@code DeclareWar} is a no-op and the {@code Attack} now validates and
 * resolves into a battle/capture. This two-tick escalation needs <em>no</em> bot memory -
 * it falls straight out of emitting the full ladder each tick and letting the engine's own
 * war-state gate sequence it. It keeps the bot a pure function of the view while still
 * driving war to battle to capture deterministically.
 *
 * <p><b>WorldView contract gap (reported, worked around).</b> The {@link WorldView} does
 * not surface war state (no "am I at war with X" field) nor an enemy <em>fleet</em> roster
 * (fog hides enemy fleets), so the bot cannot branch on "already at war" and cannot target
 * an enemy fleet by id. It works within what the view <em>does</em> expose - the public
 * reputation ledger (rival ids), the public treaty ledger (legality) and fog-revealed
 * enemy-owned neighbour <em>system</em> ids (assault targets) - and relies on the engine's
 * idempotent war-state plus validate-and-drop to sequence the strike. A test scenario
 * seeds a real combat fleet because {@code BuildFleet} resolution is still a stub
 * (E1-06 TODO), so ships built in-match do not yet materialise.
 */
public final class AggressiveScriptedSovereign implements Sovereign {

    /** Default Minerals floor before the bot will queue a {@link BuildingType#MINE}. */
    public static final double DEFAULT_MIN_MINERALS_TO_BUILD = 50.0;

    /**
     * Default Minerals floor before the bot prefers a {@link BuildingType#SHIPYARD} over a
     * {@link BuildingType#MINE}. A shipyard costs more (70 Minerals + 15 Tech in
     * {@code small-default}) than a mine (30 Minerals); building it only when comfortably
     * funded keeps the economy from stalling while still steering toward military.
     */
    public static final double DEFAULT_MIN_MINERALS_FOR_SHIPYARD = 70.0;

    /** The cheap line warship this bot builds (a config-defined ship-spec archetype key). */
    public static final String WARSHIP_SPEC = "corvette";

    private final FactionId factionId;
    private final double minMineralsToBuild;
    private final double minMineralsForShipyard;

    /**
     * @param factionId the seat this bot plays (never {@code null})
     */
    public AggressiveScriptedSovereign(FactionId factionId) {
        this(factionId, DEFAULT_MIN_MINERALS_TO_BUILD, DEFAULT_MIN_MINERALS_FOR_SHIPYARD);
    }

    /**
     * @param factionId              the seat this bot plays (never {@code null})
     * @param minMineralsToBuild     Minerals the faction must hold before queueing any build
     *                               (an affordability proxy; {@code >= 0})
     * @param minMineralsForShipyard Minerals the faction must hold before preferring a
     *                               Shipyard over a Mine ({@code >= minMineralsToBuild})
     */
    public AggressiveScriptedSovereign(FactionId factionId, double minMineralsToBuild,
                                       double minMineralsForShipyard) {
        if (factionId == null) {
            throw new IllegalArgumentException("AggressiveScriptedSovereign.factionId must be set");
        }
        if (minMineralsToBuild < 0) {
            throw new IllegalArgumentException(
                    "AggressiveScriptedSovereign.minMineralsToBuild must be >= 0");
        }
        if (minMineralsForShipyard < minMineralsToBuild) {
            throw new IllegalArgumentException(
                    "AggressiveScriptedSovereign.minMineralsForShipyard must be >= minMineralsToBuild");
        }
        this.factionId = factionId;
        this.minMineralsToBuild = minMineralsToBuild;
        this.minMineralsForShipyard = minMineralsForShipyard;
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
        // The bot is silent in negotiation (no messages); aggression is expressed through
        // the structured action ladder only. It emits an ORDERED batch so the diplomatic
        // declaration and the kinetic strike it gates ride the same tick (the resolver
        // sequences them by step). Each candidate is appended in a fixed, id-sorted order.
        List<Action> actions = new ArrayList<>();

        chooseBuild(view).ifPresent(actions::add);
        chooseBuildFleet(view).ifPresent(actions::add);

        // Pick the lowest-id reachable rival (legal to fight), then declare war on it and
        // assault its lowest-id revealed system. Both are derived from the same rival so
        // the war we declare is the war the attack needs.
        Optional<FactionId> rival = chooseRival(view);
        rival.map(Action.DeclareWar::new).ifPresent(actions::add);
        rival.flatMap(r -> chooseAttack(view, r)).ifPresent(actions::add);

        if (actions.isEmpty()) {
            actions.add(new Action.Hold());
        }
        return AgentResponse.now(List.of(), List.copyOf(actions));
    }

    /**
     * Ladder rung 1: queue a structure on the lowest-id owned planet with a free slot. The
     * bot prefers a {@link BuildingType#SHIPYARD} (the gateway to warships) once it holds
     * {@link #minMineralsForShipyard} Minerals, else a {@link BuildingType#MINE} to fund
     * one, and only ever builds when above {@link #minMineralsToBuild}. Deterministic:
     * owned systems and their planets are sorted by id, so the first free slot found is
     * canonical.
     */
    private Optional<Action> chooseBuild(WorldView view) {
        double minerals = view.self().stockpiles().minerals();
        if (minerals < minMineralsToBuild) {
            return Optional.empty();
        }
        BuildingType preferred = minerals >= minMineralsForShipyard
                ? BuildingType.SHIPYARD
                : BuildingType.MINE;
        List<WorldView.SystemView> systems = view.ownSystems().stream()
                .sorted(Comparator.comparing(s -> s.id().value()))
                .toList();
        for (WorldView.SystemView sys : systems) {
            // Do not build a second Shipyard on a system that already has one (the
            // BuildFleet rung consumes an existing shipyard; one suffices for warships) -
            // fall back to a Mine there instead.
            BuildingType type = (preferred == BuildingType.SHIPYARD && sys.hasShipyard())
                    ? BuildingType.MINE
                    : preferred;
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
     * Ladder rung 2: if any owned system has an (active) Shipyard, build a warship there.
     * Targets the lowest-id such system for determinism. The emitted
     * {@link Action.BuildFleet} is shaped to pass the validator's shipyard check; ship-tier
     * tech gating and per-ship cost are enforced authoritatively by the engine.
     */
    private Optional<Action> chooseBuildFleet(WorldView view) {
        return view.ownSystems().stream()
                .filter(WorldView.SystemView::hasShipyard)
                .min(Comparator.comparing(s -> s.id().value()))
                .map(sys -> new Action.BuildFleet(sys.id(), WARSHIP_SPEC));
    }

    /**
     * Choose the lowest-id reachable rival to make war on: the lowest-id faction (other
     * than self) that owns a fog-revealed neighbour system AND is not shielded by an ACTIVE
     * peace treaty this bot is party to (NonAggression / Alliance / Ceasefire). Reachable
     * is grounded in what the view exposes - an enemy-owned neighbour - so the war we
     * declare always has a concrete assault target, and we never emit a {@code DeclareWar}
     * the validator would reject for an active treaty.
     */
    private Optional<FactionId> chooseRival(WorldView view) {
        return revealedEnemyOwners(view).stream()
                .filter(owner -> !hasBindingPeace(view, owner))
                .min(Comparator.comparing(FactionId::value));
    }

    /**
     * Ladder rung 4: assault the lowest-id enemy-owned neighbour system belonging to
     * {@code rival}, using the bot's lowest-id idle combat fleet. Produces an
     * {@link Action.Attack} on an {@link AttackTarget.OnSystem}. Returns empty if the bot
     * has no idle fleet or the rival owns no revealed system (so nothing to strike). The
     * validator gates the strike on a positive war state; the paired {@link Action.DeclareWar}
     * supplies it (on this or, for the first strike, the next tick - see the class doc).
     */
    private Optional<Action> chooseAttack(WorldView view, FactionId rival) {
        Optional<FleetId> fleet = idleFleet(view);
        if (fleet.isEmpty()) {
            return Optional.empty();
        }
        Optional<SystemId> target = view.neighbours().stream()
                .filter(n -> n.owner().isPresent() && n.owner().get().equals(rival))
                .map(WorldView.NeighbourView::systemId)
                .min(Comparator.comparing(SystemId::value));
        return target.map(sys -> new Action.Attack(fleet.get(), new AttackTarget.OnSystem(sys)));
    }

    /** The lowest-id idle (parked, not en route) own fleet, if any. */
    private Optional<FleetId> idleFleet(WorldView view) {
        return view.ownFleets().stream()
                .filter(WorldView.FleetView::isIdle)
                .map(WorldView.FleetView::id)
                .min(Comparator.comparing(FleetId::value));
    }

    /**
     * The distinct owners of fog-revealed neighbour systems that are NOT this bot (its
     * rivals it can actually see and reach). Derived only from the neighbour ownership the
     * fog filter chose to surface - never hidden state.
     */
    private List<FactionId> revealedEnemyOwners(WorldView view) {
        List<FactionId> owners = new ArrayList<>();
        for (WorldView.NeighbourView n : view.neighbours()) {
            if (n.owner().isPresent() && !n.owner().get().equals(factionId)
                    && !owners.contains(n.owner().get())) {
                owners.add(n.owner().get());
            }
        }
        return owners;
    }

    /**
     * @return true iff this bot is party to an ACTIVE treaty with {@code other} that forbids
     * hostility (NonAggression / Alliance / Ceasefire) - the same set the engine validator
     * treats as a binding peace. Read from the public treaty ledger in the view; declaring
     * war or attacking through such a treaty would be rejected {@code TREATY_FORBIDS}, so the
     * bot does not emit it.
     */
    private boolean hasBindingPeace(WorldView view, FactionId other) {
        for (WorldView.TreatyView t : view.treaties()) {
            if (t.status() != TreatyStatus.ACTIVE) {
                continue;
            }
            boolean forbidsHostility = t.type() == TreatyType.NON_AGGRESSION
                    || t.type() == TreatyType.ALLIANCE
                    || t.type() == TreatyType.CEASEFIRE;
            if (forbidsHostility && t.parties().contains(factionId) && t.parties().contains(other)) {
                return true;
            }
        }
        return false;
    }
}
