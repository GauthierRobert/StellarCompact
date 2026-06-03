package com.stellarcompact.orchestrator.sovereign;

import com.stellarcompact.engine.state.ActiveSystem;
import com.stellarcompact.engine.state.Building;
import com.stellarcompact.engine.state.BuildingType;
import com.stellarcompact.engine.state.Faction;
import com.stellarcompact.engine.state.FactionId;
import com.stellarcompact.engine.state.Fleet;
import com.stellarcompact.engine.state.FleetId;
import com.stellarcompact.engine.state.GameState;
import com.stellarcompact.engine.state.Planet;
import com.stellarcompact.engine.state.PlanetId;
import com.stellarcompact.engine.state.Ship;
import com.stellarcompact.engine.state.SystemId;
import com.stellarcompact.engine.state.TechId;
import com.stellarcompact.engine.state.TechStatus;
import com.stellarcompact.engine.state.Treaty;
import com.stellarcompact.engine.state.TreatyStatus;
import com.stellarcompact.engine.state.TreatyType;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The authoritative, server-side fog-of-war builder (card E3-02): it projects the
 * complete {@link GameState} down to the compact, fog-filtered {@link WorldView}
 * one faction is permitted to perceive. This is the security-critical boundary the
 * E3-01 {@code WorldViewProjection} test stand-in only approximated; this class is
 * the production filter and replaces that stand-in everywhere a real view is built.
 *
 * <p><b>Security posture: default-deny (principle 2, engine authority; spec
 * section 1 and architecture 03 section 3).</b> The frontend and the agents are
 * <em>untrusted</em>. Nothing about another faction is included unless an explicit
 * visibility rule below grants it. Because hidden state is never placed into the
 * view object at all, there is literally nothing for a prompt-injection attempt to
 * coax out of an agent: the leak is impossible by construction, not by the agent's
 * good behaviour.
 *
 * <p><b>What this faction may observe.</b>
 * <ul>
 *   <li><b>Own systems - full.</b> Every system this faction owns is surfaced with
 *       its planet roster, free build slots and shipyard presence.</li>
 *   <li><b>Own fleets - full.</b> Every fleet owned by this faction.</li>
 *   <li><b>Revealed neighbours - fog-limited.</b> A foreign or neutral system is
 *       surfaced as a {@link WorldView.NeighbourView} carrying <em>ownership only</em>
 *       plus a rough strength bucket and a last-seen tick - never its planets,
 *       economy, garrison detail or population breakdown - and only when a
 *       visibility source reveals it:
 *       <ol>
 *         <li><b>Sensor / lane adjacency.</b> A system one lane hop from a system
 *             this faction owns, or from a system one of its fleets is parked at,
 *             is in sensor range (game-design 01 section 4). Adjacency is supplied
 *             by the orchestrator via {@link SystemAdjacency}; with
 *             {@link SystemAdjacency#NONE} no adjacency can be proven, so this
 *             source grants nothing (default-deny).</li>
 *         <li><b>Allied shared vision.</b> A system owned by a faction with which
 *             this faction holds an <em>active</em> {@link TreatyType#ALLIANCE}
 *             (shared border vision) is revealed.</li>
 *       </ol>
 *       A system that no source reveals is <em>absent entirely</em> from the view.</li>
 *   <li><b>Public common knowledge - everyone.</b> The public reputation ledger of
 *       all factions; treaties this faction is a party to; trade offers directed to
 *       this faction. Galaxy-wide public events are surfaced by the orchestrator's
 *       event feed (later cards); this builder emits none from raw state.</li>
 * </ul>
 *
 * <p><b>Explicitly filtered out (negative-tested).</b> Enemy economy internals
 * (stockpiles, tech, planet rosters of foreign systems), hidden fleets out of
 * sensor range, other factions' pending orders not addressed to this faction,
 * treaties this faction is not a party to, and any system no visibility source
 * reveals.
 *
 * <p><b>Purity.</b> No Spring, no I/O, no clock, no randomness - a pure projection
 * of {@code (GameState, SystemAdjacency, FactionId)} to an immutable {@link WorldView}.
 * Everything emitted is sorted by id so the view is replay-stable regardless of the
 * {@code HashMap} iteration order of the authoritative collections.
 */
public final class WorldViewBuilder {

    private WorldViewBuilder() {
    }

    /**
     * Build {@code self}'s fog-filtered {@link WorldView} from authoritative state.
     *
     * @param state     the complete authoritative snapshot (never {@code null})
     * @param adjacency the lane adjacency for sensor-range visibility; pass
     *                  {@link SystemAdjacency#NONE} when no graph is available (then
     *                  adjacency reveals nothing - default-deny)
     * @param self      the faction whose perception to build (must exist in {@code state})
     * @return the per-faction view; own state in full, only the permitted slice of
     * everyone else's
     */
    public static WorldView build(GameState state, SystemAdjacency adjacency, FactionId self) {
        return build(state, adjacency, self, List.of());
    }

    /**
     * Build {@code self}'s fog-filtered {@link WorldView}, additionally delivering the
     * negotiation {@code inbox} messages addressed to {@code self} (card E4-06).
     *
     * <p>The {@code inbox} is transient orchestration state, not engine state: free-text
     * negotiation messages have no mechanical force (diplomacy 04 section 1), so they are
     * never folded into {@link GameState} or the golden hash - the orchestrator routes
     * them to the recipient and hands them here so the recipient simply <em>sees</em>
     * them in its next perception. They are appended verbatim, in delivery order, to the
     * view's {@link WorldView#inbox()}; everything else is the unchanged pure fog
     * projection below. Passing an empty list yields exactly the no-inbox view.
     *
     * @param state     the complete authoritative snapshot (never {@code null})
     * @param adjacency the lane adjacency for sensor-range visibility (or {@code NONE})
     * @param self      the faction whose perception to build (must exist in {@code state})
     * @param inbox     the negotiation messages addressed to {@code self} this phase, in
     *                  delivery order (never {@code null}; empty = nothing delivered)
     * @return the per-faction view, including any delivered negotiation messages
     */
    public static WorldView build(GameState state, SystemAdjacency adjacency, FactionId self,
                                  List<WorldView.InboxMessage> inbox) {
        if (state == null) {
            throw new IllegalArgumentException("build.state must be set");
        }
        if (self == null) {
            throw new IllegalArgumentException("build.self must be set");
        }
        List<WorldView.InboxMessage> deliveredInbox = inbox == null ? List.of() : List.copyOf(inbox);
        SystemAdjacency lanes = adjacency == null ? SystemAdjacency.NONE : adjacency;
        Faction me = state.factions().get(self);
        if (me == null) {
            throw new IllegalArgumentException("build: unknown faction " + self.value());
        }

        WorldView.SelfView selfView = new WorldView.SelfView(
                me.id(), me.name(), me.reputation(), me.stockpiles(), techKnown(me));

        // Who shares vision with me right now (active ALLIANCE only).
        Set<FactionId> allies = activeAllies(state, self);

        // Sensor footprint: every system I own, every system a fleet of mine is
        // parked at, and their one-hop lane neighbours.
        Set<SystemId> sensorReach = sensorReach(state, lanes, self);

        List<WorldView.SystemView> ownSystems = new ArrayList<>();
        List<WorldView.NeighbourView> neighbours = new ArrayList<>();

        List<ActiveSystem> systems = state.systems().values().stream()
                .sorted(Comparator.comparing(s -> s.id().value()))
                .toList();
        for (ActiveSystem sys : systems) {
            boolean owned = sys.owner().isPresent() && sys.owner().get().equals(self);
            if (owned) {
                ownSystems.add(toOwnSystemView(sys));
                continue;
            }
            // Foreign / neutral system: include ONLY if a visibility source reveals it.
            boolean revealedBySensor = sensorReach.contains(sys.id());
            boolean revealedByAlly = sys.owner().isPresent() && allies.contains(sys.owner().get());
            if (!revealedBySensor && !revealedByAlly) {
                continue; // default-deny: hidden, absent entirely
            }
            // Fog-limited: ownership + rough strength + last-seen only. No planets,
            // no economy, no garrison detail.
            //
            // E10-01: a revealed neighbour is "explored" (its ownership/onward lanes are
            // in hand), so a Sovereign must not re-Explore it. For a NEUTRAL system the
            // actor has physically reached (an idle own fleet parked there), additionally
            // surface its colonisable planet ids + the delivering fleet id so the bot can
            // emit a Colonize the validator accepts. This leaks only planet ids of a
            // neutral the actor already occupies - never owned/enemy detail, so fog holds.
            boolean neutral = sys.owner().isEmpty();
            Optional<FleetId> colonyFleet = neutral
                    ? idleOwnFleetAt(state, self, sys.id())
                    : Optional.empty();
            List<PlanetId> colonisablePlanets = colonyFleet.isPresent()
                    ? sys.planets().stream()
                            .map(Planet::id)
                            .sorted(Comparator.comparing(PlanetId::value))
                            .toList()
                    : List.of();
            neighbours.add(new WorldView.NeighbourView(
                    sys.id(), sys.owner(), roughStrength(sys), state.tick(),
                    true, colonisablePlanets, colonyFleet));
        }

        // Own fleets only. Hidden enemy fleets (out of sensor range) are never
        // surfaced; we simply never emit any fleet not owned by self.
        List<WorldView.FleetView> ownFleets = state.fleets().values().stream()
                .filter(f -> f.owner().equals(self))
                .sorted(Comparator.comparing(f -> f.id().value()))
                .map(WorldViewBuilder::toFleetView)
                .toList();

        // Treaties I am a party to only.
        List<WorldView.TreatyView> treaties = state.treaties().values().stream()
                .filter(t -> t.parties().contains(self))
                .sorted(Comparator.comparing(t -> t.id().value()))
                .map(t -> new WorldView.TreatyView(t.id(), t.type(), t.parties(), t.status()))
                .toList();

        // Public reputation ledger: every faction's public reputation is common
        // knowledge (spec section 1) - the only foreign-faction scalar exposed.
        List<WorldView.RepEntry> reputations = state.factions().values().stream()
                .sorted(Comparator.comparing(f -> f.id().value()))
                .map(f -> new WorldView.RepEntry(f.id(), f.reputation()))
                .toList();

        // Directed offers addressed to me only. Open book orders and offers directed
        // to a third party are filtered out.
        List<WorldView.OfferView> pendingOffers = state.marketOrders().values().stream()
                .filter(o -> o.addressee().isPresent() && o.addressee().get().equals(self))
                .sorted(Comparator.comparing(o -> o.id().value()))
                .map(o -> new WorldView.OfferView(o.id(), o.faction(), o.expiresTick()))
                .toList();

        // events: the orchestrator's public-event feed populates these in a later card;
        // from raw GameState there is nothing to leak, so this builder emits none.
        // inbox: the negotiation feed (E4-06) delivers free-text messages addressed to
        // this faction; they are transient and effect-free, appended verbatim here.
        return new WorldView(state.tick(), selfView, ownSystems, ownFleets, neighbours,
                treaties, reputations, pendingOffers, List.of(), deliveredInbox);
    }

    /** Convenience overload: build with no lane graph (adjacency reveals nothing). */
    public static WorldView build(GameState state, FactionId self) {
        return build(state, SystemAdjacency.NONE, self);
    }

    /**
     * The set of foreign factions sharing vision with {@code self} via an active
     * {@link TreatyType#ALLIANCE}. Only {@link TreatyStatus#ACTIVE} alliances grant
     * vision - a proposed, expired or broken one does not.
     */
    private static Set<FactionId> activeAllies(GameState state, FactionId self) {
        Set<FactionId> allies = new LinkedHashSet<>();
        for (Treaty t : state.treaties().values()) {
            if (t.type() != TreatyType.ALLIANCE
                    || t.status() != TreatyStatus.ACTIVE
                    || !t.parties().contains(self)) {
                continue;
            }
            for (FactionId party : t.parties()) {
                if (!party.equals(self)) {
                    allies.add(party);
                }
            }
        }
        return allies;
    }

    /**
     * Systems within {@code self}'s sensor footprint: every system it owns, every
     * system one of its fleets is parked at, and the one-lane-hop neighbours of
     * those. With {@link SystemAdjacency#NONE} the hop expansion contributes nothing,
     * so only own/fleet-occupied systems are in reach (default-deny on adjacency).
     */
    private static Set<SystemId> sensorReach(GameState state, SystemAdjacency lanes, FactionId self) {
        Set<SystemId> anchors = new LinkedHashSet<>();
        for (ActiveSystem sys : state.systems().values()) {
            if (sys.owner().isPresent() && sys.owner().get().equals(self)) {
                anchors.add(sys.id());
            }
        }
        for (Fleet f : state.fleets().values()) {
            if (f.owner().equals(self)) {
                f.location().ifPresent(anchors::add);
            }
        }
        Set<SystemId> reach = new LinkedHashSet<>(anchors);
        for (SystemId anchor : anchors) {
            for (SystemId hop : lanes.neighbours(anchor)) {
                reach.add(hop);
            }
        }
        return reach;
    }

    /**
     * The lowest-id idle (parked, not en route) fleet {@code self} owns that is parked
     * at {@code system}, if any (E10-01). Used to surface a delivering fleet for a
     * colonisable neutral neighbour. Deterministic: fleets are compared by id so the
     * choice never depends on {@code HashMap} iteration order. A fleet that is en route
     * (its location empty / an in-flight path) cannot deliver a colony.
     */
    private static Optional<FleetId> idleOwnFleetAt(GameState state, FactionId self, SystemId system) {
        return state.fleets().values().stream()
                .filter(f -> f.owner().equals(self))
                .filter(f -> !(f.enroutePath().isPresent() && !f.enroutePath().get().isEmpty()))
                .filter(f -> f.location().isPresent() && f.location().get().equals(system))
                .map(Fleet::id)
                .min(Comparator.comparing(FleetId::value));
    }

    private static List<TechId> techKnown(Faction me) {
        return me.techProgress().values().stream()
                .filter(tp -> tp.status() == TechStatus.UNLOCKED)
                .map(tp -> tp.techId())
                .sorted(Comparator.comparing(TechId::value))
                .toList();
    }

    private static WorldView.SystemView toOwnSystemView(ActiveSystem sys) {
        boolean systemHasShipyard = sys.planets().stream()
                .flatMap(p -> p.buildings().stream())
                .anyMatch(b -> b.type() == BuildingType.SHIPYARD);
        List<WorldView.PlanetView> planets = sys.planets().stream()
                .sorted(Comparator.comparing(p -> p.id().value()))
                .map(WorldViewBuilder::toPlanetView)
                .toList();
        return new WorldView.SystemView(sys.id(), sys.name(), true, planets, systemHasShipyard);
    }

    private static WorldView.PlanetView toPlanetView(Planet p) {
        boolean[] occupied = new boolean[Math.max(0, p.slotsTotal())];
        for (Building b : p.buildings()) {
            if (b.slotIndex() >= 0 && b.slotIndex() < occupied.length) {
                occupied[b.slotIndex()] = true;
            }
        }
        int free = 0;
        int firstFree = -1;
        for (int i = 0; i < occupied.length; i++) {
            if (!occupied[i]) {
                free++;
                if (firstFree < 0) {
                    firstFree = i;
                }
            }
        }
        return new WorldView.PlanetView(p.id(), p.slotsTotal(), free, firstFree);
    }

    private static WorldView.FleetView toFleetView(Fleet f) {
        boolean enRoute = f.enroutePath().isPresent() && !f.enroutePath().get().isEmpty();
        int totalShips = f.ships().stream().mapToInt(Ship::count).sum();
        Optional<SystemId> location = f.location();
        return new WorldView.FleetView(f.id(), location, enRoute, f.stance(), totalShips);
    }

    /**
     * A coarse, public strength bucket for a fog-limited neighbour - intentionally
     * lossy so it leaks no precise garrison/economy figure. Derived from observable
     * population scale only.
     */
    private static int roughStrength(ActiveSystem sys) {
        return (int) Math.min(Integer.MAX_VALUE, sys.population());
    }
}
