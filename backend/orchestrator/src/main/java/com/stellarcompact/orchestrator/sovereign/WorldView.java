package com.stellarcompact.orchestrator.sovereign;

import com.stellarcompact.engine.state.FactionId;
import com.stellarcompact.engine.state.FleetId;
import com.stellarcompact.engine.state.FleetStance;
import com.stellarcompact.engine.state.MarketOrderId;
import com.stellarcompact.engine.state.PlanetId;
import com.stellarcompact.engine.state.ResourceBundle;
import com.stellarcompact.engine.state.SystemId;
import com.stellarcompact.engine.state.TechId;
import com.stellarcompact.engine.state.TreatyId;
import com.stellarcompact.engine.state.TreatyStatus;
import com.stellarcompact.engine.state.TreatyType;

import java.util.List;
import java.util.Optional;

/**
 * One faction's entire perception for one tick - the input half of the Sovereign
 * contract ({@code WorldView -> AgentResponse}). See
 * {@code docs/specs/agent-io-schema.md} sections 1 and 7a, and
 * {@code .claude/skills/agent-sovereign}.
 *
 * <p><b>Scope of this card (E3-01).</b> This type fixes the stable
 * interface-boundary shape the Sovereign sees. The authoritative server-side
 * fog-of-war filter that builds a {@code WorldView} from {@code GameState} is card
 * E3-02 and is deliberately NOT implemented here. For exercising the bot,
 * {@code WorldViewProjection} (test scope) provides a clearly-marked test-only
 * full-state projection - a stand-in, never the production fog filter.
 *
 * <p><b>Compactness (skill rule 4).</b> A {@code WorldView} is serialised into an
 * LLM prompt, so it must stay token-small: own state in full, neighbours
 * fog-limited to ownership plus rough strength only (no hidden enemy state), public
 * ledgers only. New fields are appended (additive versioning, spec section 7a);
 * consumers ignore unknown fields.
 *
 * <p><b>Immutability.</b> The whole graph is deeply-immutable records with
 * defensively-copied lists, so a view handed to a slow or remote Sovereign cannot
 * be mutated under the orchestrator and is safe to share across virtual threads.
 */
public record WorldView(
        long tick,
        SelfView self,
        List<SystemView> ownSystems,
        List<FleetView> ownFleets,
        List<NeighbourView> neighbours,
        List<TreatyView> treaties,
        List<RepEntry> reputations,
        List<OfferView> pendingOffers,
        List<EventView> events,
        List<InboxMessage> inbox
) {
    public WorldView {
        if (self == null) {
            throw new IllegalArgumentException("WorldView.self must be set");
        }
        if (tick < 0) {
            throw new IllegalArgumentException("WorldView.tick must be >= 0");
        }
        ownSystems = copy(ownSystems);
        ownFleets = copy(ownFleets);
        neighbours = copy(neighbours);
        treaties = copy(treaties);
        reputations = copy(reputations);
        pendingOffers = copy(pendingOffers);
        events = copy(events);
        inbox = copy(inbox);
    }

    private static <T> List<T> copy(List<T> in) {
        return in == null ? List.of() : List.copyOf(in);
    }

    /** This faction's own state, in full (it is not fog-limited from itself). */
    public record SelfView(
            FactionId id,
            String name,
            double reputation,
            ResourceBundle stockpiles,
            List<TechId> techKnown
    ) {
        public SelfView {
            if (id == null) {
                throw new IllegalArgumentException("SelfView.id must be set");
            }
            if (stockpiles == null) {
                throw new IllegalArgumentException("SelfView.stockpiles must be set");
            }
            techKnown = techKnown == null ? List.of() : List.copyOf(techKnown);
        }
    }

    /**
     * An owned system, with just enough planet detail for development heuristics
     * (free build slots, shipyard presence). Always {@code owned == true} in this
     * card; the field exists so the shape can carry partially-revealed foreign
     * systems later without breaking consumers.
     */
    public record SystemView(
            SystemId id,
            String name,
            boolean owned,
            List<PlanetView> ownedPlanets,
            boolean hasShipyard
    ) {
        public SystemView {
            if (id == null) {
                throw new IllegalArgumentException("SystemView.id must be set");
            }
            ownedPlanets = ownedPlanets == null ? List.of() : List.copyOf(ownedPlanets);
        }
    }

    /**
     * A planet on an owned system - the minimum a Sovereign needs to decide what to
     * build: capacity ({@code slotsTotal}), how many slots are free, and the lowest
     * free slot index to target ({@code -1} when full).
     */
    public record PlanetView(
            PlanetId id,
            int slotsTotal,
            int freeSlots,
            int firstFreeSlot
    ) {
        public PlanetView {
            if (id == null) {
                throw new IllegalArgumentException("PlanetView.id must be set");
            }
        }

        /** @return true iff this planet has at least one free build slot. */
        public boolean hasFreeSlot() {
            return freeSlots > 0 && firstFreeSlot >= 0;
        }
    }

    /**
     * An own fleet. {@code location} is present iff parked at a system (empty while
     * en route, in which case {@code enRoute} is true). {@code totalShips} is the
     * summed ship count, a compact strength proxy.
     */
    public record FleetView(
            FleetId id,
            Optional<SystemId> location,
            boolean enRoute,
            FleetStance stance,
            int totalShips
    ) {
        public FleetView {
            if (id == null) {
                throw new IllegalArgumentException("FleetView.id must be set");
            }
            if (location == null) {
                throw new IllegalArgumentException("FleetView.location must be set (use Optional.empty())");
            }
        }

        /** @return true iff parked at a system and free to act/move. */
        public boolean isIdle() {
            return location.isPresent() && !enRoute;
        }
    }

    /**
     * A fog-limited view of a neighbouring system: ownership only (present iff the
     * system has been revealed to this faction), a rough strength bucket, and the
     * tick it was last seen. No hidden enemy state.
     *
     * <p><b>E10-01 — frontier vs revealed, and colonisable detail.</b> Two additive,
     * fog-safe fields let a Sovereign stop re-Exploring an already-known system and
     * start colonising reachable neutrals:
     * <ul>
     *   <li>{@code explored} — true iff this neighbour is already <em>revealed/known</em>
     *       to the actor (its ownership/onward lanes are in hand), so re-Exploring it
     *       would be a redundant no-op. Under the current fog model every neighbour the
     *       builder emits is sensor-revealed, hence {@code explored == true}; the field
     *       exists so the Explore heuristic is explicit and stays correct if a later
     *       card surfaces genuinely-unexplored frontier ids (then {@code false}).</li>
     *   <li>{@code colonisablePlanets} / {@code reachableViaFleet} — non-empty only for a
     *       <em>neutral</em> system the actor has an <b>idle fleet parked at</b>: the ids
     *       of that system's planets (so a {@code Colonize} can name one) and the id of
     *       the delivering fleet. This leaks only planet <em>ids</em> of a neutral the
     *       actor has physically reached — never owned/enemy planet rosters, economy or
     *       garrison — so fog-of-war stays intact. Empty list / empty fleet when the
     *       actor has no idle fleet present (then the neutral is not colonisable yet).</li>
     * </ul>
     */
    public record NeighbourView(
            SystemId systemId,
            Optional<FactionId> owner,
            int roughStrength,
            long lastSeenTick,
            boolean explored,
            List<PlanetId> colonisablePlanets,
            Optional<FleetId> reachableViaFleet
    ) {
        public NeighbourView {
            if (systemId == null) {
                throw new IllegalArgumentException("NeighbourView.systemId must be set");
            }
            if (owner == null) {
                throw new IllegalArgumentException("NeighbourView.owner must be set (use Optional.empty())");
            }
            colonisablePlanets = colonisablePlanets == null ? List.of() : List.copyOf(colonisablePlanets);
            if (reachableViaFleet == null) {
                throw new IllegalArgumentException(
                        "NeighbourView.reachableViaFleet must be set (use Optional.empty())");
            }
        }

        /**
         * Backwards-compatible constructor predating the E10-01 frontier/colonise fields:
         * a revealed neighbour ({@code explored == true}) with no colonisable detail.
         * Keeps pre-E10-01 positional callers/fixtures compiling (mirrors the additive
         * pattern used on {@code Faction.revealedIntel}).
         */
        public NeighbourView(SystemId systemId, Optional<FactionId> owner, int roughStrength,
                             long lastSeenTick) {
            this(systemId, owner, roughStrength, lastSeenTick, true, List.of(), Optional.empty());
        }

        /** @return true iff this neighbour is currently unowned (neutral). */
        public boolean isNeutral() {
            return owner.isEmpty();
        }

        /**
         * @return true iff this neutral neighbour can be colonised now: the actor has an
         * idle fleet parked at it and it exposes at least one nameable planet id.
         */
        public boolean isColonisable() {
            return isNeutral() && reachableViaFleet.isPresent() && !colonisablePlanets.isEmpty();
        }
    }

    /** A treaty involving this faction (public ledger). */
    public record TreatyView(
            TreatyId id,
            TreatyType type,
            List<FactionId> parties,
            TreatyStatus status
    ) {
        public TreatyView {
            if (id == null) {
                throw new IllegalArgumentException("TreatyView.id must be set");
            }
            parties = parties == null ? List.of() : List.copyOf(parties);
        }
    }

    /** A public reputation-ledger entry. */
    public record RepEntry(FactionId factionId, double reputation) {
        public RepEntry {
            if (factionId == null) {
                throw new IllegalArgumentException("RepEntry.factionId must be set");
            }
        }
    }

    /** A directed trade offer addressed to this faction. */
    public record OfferView(MarketOrderId id, FactionId from, long expiresTick) {
        public OfferView {
            if (id == null) {
                throw new IllegalArgumentException("OfferView.id must be set");
            }
            if (from == null) {
                throw new IllegalArgumentException("OfferView.from must be set");
            }
        }
    }

    /** A public event since the last tick (compact: type plus tick only this card). */
    public record EventView(String type, long tick) {
        public EventView {
            if (type == null || type.isBlank()) {
                throw new IllegalArgumentException("EventView.type must be non-blank");
            }
        }
    }

    /** A negotiation message received by this faction. */
    public record InboxMessage(FactionId from, String text, long tick) {
        public InboxMessage {
            if (from == null) {
                throw new IllegalArgumentException("InboxMessage.from must be set");
            }
            if (text == null) {
                throw new IllegalArgumentException("InboxMessage.text must be set");
            }
        }
    }
}
