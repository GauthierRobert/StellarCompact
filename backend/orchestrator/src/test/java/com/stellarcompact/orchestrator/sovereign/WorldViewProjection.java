package com.stellarcompact.orchestrator.sovereign;

import com.stellarcompact.engine.state.ActiveSystem;
import com.stellarcompact.engine.state.Building;
import com.stellarcompact.engine.state.BuildingType;
import com.stellarcompact.engine.state.Faction;
import com.stellarcompact.engine.state.FactionId;
import com.stellarcompact.engine.state.Fleet;
import com.stellarcompact.engine.state.GameState;
import com.stellarcompact.engine.state.Planet;
import com.stellarcompact.engine.state.Ship;
import com.stellarcompact.engine.state.SystemId;
import com.stellarcompact.engine.state.TechId;
import com.stellarcompact.engine.state.TechStatus;
import com.stellarcompact.engine.state.Treaty;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * TEST-ONLY stand-in for card E3-02's authoritative fog-of-war WorldView builder.
 *
 * <p><b>This is NOT the production fog filter.</b> It is a deliberately simple
 * "project {@link GameState} into a {@link WorldView} for one faction" helper so the
 * {@link ScriptedSovereign} bot can be exercised before E3-02 lands. It performs a
 * mild fog approximation - own systems/fleets in full, every non-owned active system
 * surfaced as a neighbour carrying ownership only - but it does <em>not</em> do
 * lane-bounded visibility, last-seen decay, market top-of-book, or any of the real
 * server-side filtering. E3-02 owns the authoritative builder; do not promote this
 * into production code.
 *
 * <p>Determinism: the projection sorts everything it emits by id, so a bot fed this
 * view sees a stable ordering and the determinism tests are meaningful.
 */
final class WorldViewProjection {

    private WorldViewProjection() {
    }

    /** Project {@code state} into the per-faction view {@code self} would perceive. */
    static WorldView project(GameState state, FactionId self) {
        Faction me = state.factions().get(self);
        if (me == null) {
            throw new IllegalArgumentException("project: unknown faction " + self.value());
        }

        WorldView.SelfView selfView = new WorldView.SelfView(
                me.id(), me.name(), me.reputation(), me.stockpiles(), techKnown(me));

        List<WorldView.SystemView> ownSystems = new ArrayList<>();
        List<WorldView.NeighbourView> neighbours = new ArrayList<>();
        List<ActiveSystem> systems = state.systems().values().stream()
                .sorted(Comparator.comparing(s -> s.id().value()))
                .toList();
        for (ActiveSystem sys : systems) {
            boolean owned = sys.owner().isPresent() && sys.owner().get().equals(self);
            if (owned) {
                ownSystems.add(toSystemView(sys));
            } else {
                neighbours.add(new WorldView.NeighbourView(
                        sys.id(), sys.owner(), roughStrength(sys), state.tick()));
            }
        }

        List<WorldView.FleetView> ownFleets = state.fleets().values().stream()
                .filter(f -> f.owner().equals(self))
                .sorted(Comparator.comparing(f -> f.id().value()))
                .map(WorldViewProjection::toFleetView)
                .toList();

        List<WorldView.TreatyView> treaties = state.treaties().values().stream()
                .filter(t -> t.parties().contains(self))
                .sorted(Comparator.comparing(t -> t.id().value()))
                .map(t -> new WorldView.TreatyView(t.id(), t.type(), t.parties(), t.status()))
                .toList();

        List<WorldView.RepEntry> reputations = state.factions().values().stream()
                .sorted(Comparator.comparing(f -> f.id().value()))
                .map(f -> new WorldView.RepEntry(f.id(), f.reputation()))
                .toList();

        List<WorldView.OfferView> pendingOffers = state.marketOrders().values().stream()
                .filter(o -> o.addressee().isPresent() && o.addressee().get().equals(self))
                .sorted(Comparator.comparing(o -> o.id().value()))
                .map(o -> new WorldView.OfferView(o.id(), o.faction(), o.expiresTick()))
                .toList();

        // events / inbox: this stand-in surfaces none (E1-14 / negotiation feed land later).
        return new WorldView(state.tick(), selfView, ownSystems, ownFleets, neighbours,
                treaties, reputations, pendingOffers, List.of(), List.of());
    }

    private static List<TechId> techKnown(Faction me) {
        return me.techProgress().values().stream()
                .filter(tp -> tp.status() == TechStatus.UNLOCKED)
                .map(tp -> tp.techId())
                .sorted(Comparator.comparing(TechId::value))
                .toList();
    }

    private static WorldView.SystemView toSystemView(ActiveSystem sys) {
        boolean systemHasShipyard = sys.planets().stream()
                .flatMap(p -> p.buildings().stream())
                .anyMatch(b -> b.type() == BuildingType.SHIPYARD);
        List<WorldView.PlanetView> planets = sys.planets().stream()
                .sorted(Comparator.comparing(p -> p.id().value()))
                .map(WorldViewProjection::toPlanetView)
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

    /** Crude public strength proxy for a neighbour: total garrisoned population scale. */
    private static int roughStrength(ActiveSystem sys) {
        return (int) Math.min(Integer.MAX_VALUE, sys.population());
    }
}
