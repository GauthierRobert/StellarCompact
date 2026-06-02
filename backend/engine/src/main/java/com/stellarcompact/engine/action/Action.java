package com.stellarcompact.engine.action;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.stellarcompact.engine.state.BuildingType;
import com.stellarcompact.engine.state.FactionId;
import com.stellarcompact.engine.state.FleetId;
import com.stellarcompact.engine.state.MarketOrderId;
import com.stellarcompact.engine.state.PhysicalResource;
import com.stellarcompact.engine.state.PlanetId;
import com.stellarcompact.engine.state.ResourceBundle;
import com.stellarcompact.engine.state.RouteId;
import com.stellarcompact.engine.state.RouteKind;
import com.stellarcompact.engine.state.SystemId;
import com.stellarcompact.engine.state.TechId;
import com.stellarcompact.engine.state.TreatyId;
import com.stellarcompact.engine.state.TreatyType;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The closed action set - the entire contract between a Sovereign and the engine
 * (game-design 03; spec docs/specs/agent-io-schema.md section 3). Every move a
 * Sovereign can make is exactly one of these 25 tagged variants; anything else
 * cannot happen.
 *
 * <p><b>Closed by design.</b> This is a {@code sealed interface}: the validator
 * (E1-04) and resolver (E1-05) switch over it with no {@code default} branch, so
 * adding a variant is a compile error until every site handles it. That is the
 * determinism / engine-authority contract - see
 * {@code .claude/skills/game-engine-determinism}.
 *
 * <p><b>Untrusted boundary (this card is security-sensitive).</b> {@code Action}
 * is the shape of raw agent output. The variants are deliberately narrow: they
 * carry only typed ids, enums and {@link ResourceBundle}s the agent is allowed to
 * name, never engine-internal state, so a malicious agent cannot smuggle authority
 * through the schema. Jackson is locked to this closed permit set via
 * {@code @JsonSubTypes}; the discriminator cannot be spoofed into instantiating an
 * arbitrary type. <em>Shape</em> only is enforced here - whether an id exists, is
 * owned, affordable, etc. is E1-04's job.
 *
 * <p><b>Forward compatibility (spec section 6).</b> A newer agent may emit a
 * future variant an older engine does not know. {@code defaultImpl =
 * UnknownAction.class} makes an unrecognised {@code type} deserialise to the inert
 * {@link UnknownAction} sentinel rather than throwing, so parsing a mixed
 * {@code actions[]} array never crashes; the validator simply treats the unknown
 * as a Hold/drop. Adding a variant is a minor schema-version bump.
 *
 * <p><b>Immutability.</b> Every variant is an immutable {@code record}; the few
 * collection-bearing payloads ({@code MoveFleet.path}, {@code EstablishRoute
 * .resources}, {@code ProposeTreaty.terms}) defensive-copy in their compact
 * constructors so a held action is copy-on-write and safe to share across the
 * deterministic resolution.
 *
 * <p>The discriminator property is {@code type} and its values are the variant
 * simple names ({@code Explore}, {@code Colonize}, ...), matching the spec.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY,
        property = "type", defaultImpl = UnknownAction.class)
@JsonSubTypes({
        @JsonSubTypes.Type(value = Action.Explore.class, name = "Explore"),
        @JsonSubTypes.Type(value = Action.Colonize.class, name = "Colonize"),
        @JsonSubTypes.Type(value = Action.Build.class, name = "Build"),
        @JsonSubTypes.Type(value = Action.Research.class, name = "Research"),
        @JsonSubTypes.Type(value = Action.Terraform.class, name = "Terraform"),
        @JsonSubTypes.Type(value = Action.BuildFleet.class, name = "BuildFleet"),
        @JsonSubTypes.Type(value = Action.MoveFleet.class, name = "MoveFleet"),
        @JsonSubTypes.Type(value = Action.EstablishRoute.class, name = "EstablishRoute"),
        @JsonSubTypes.Type(value = Action.SendMessage.class, name = "SendMessage"),
        @JsonSubTypes.Type(value = Action.ProposeTrade.class, name = "ProposeTrade"),
        @JsonSubTypes.Type(value = Action.AcceptTrade.class, name = "AcceptTrade"),
        @JsonSubTypes.Type(value = Action.DeclineTrade.class, name = "DeclineTrade"),
        @JsonSubTypes.Type(value = Action.WithdrawTrade.class, name = "WithdrawTrade"),
        @JsonSubTypes.Type(value = Action.ProposeTreaty.class, name = "ProposeTreaty"),
        @JsonSubTypes.Type(value = Action.AcceptTreaty.class, name = "AcceptTreaty"),
        @JsonSubTypes.Type(value = Action.DeclineTreaty.class, name = "DeclineTreaty"),
        @JsonSubTypes.Type(value = Action.BreakTreaty.class, name = "BreakTreaty"),
        @JsonSubTypes.Type(value = Action.Tribute.class, name = "Tribute"),
        @JsonSubTypes.Type(value = Action.DemandTribute.class, name = "DemandTribute"),
        @JsonSubTypes.Type(value = Action.DeclareWar.class, name = "DeclareWar"),
        @JsonSubTypes.Type(value = Action.Attack.class, name = "Attack"),
        @JsonSubTypes.Type(value = Action.Blockade.class, name = "Blockade"),
        @JsonSubTypes.Type(value = Action.Raid.class, name = "Raid"),
        @JsonSubTypes.Type(value = Action.Espionage.class, name = "Espionage"),
        @JsonSubTypes.Type(value = Action.Hold.class, name = "Hold")
})
public sealed interface Action permits
        Action.Explore, Action.Colonize, Action.Build, Action.Research, Action.Terraform,
        Action.BuildFleet, Action.MoveFleet, Action.EstablishRoute, Action.SendMessage,
        Action.ProposeTrade, Action.AcceptTrade, Action.DeclineTrade, Action.WithdrawTrade,
        Action.ProposeTreaty, Action.AcceptTreaty, Action.DeclineTreaty, Action.BreakTreaty,
        Action.Tribute, Action.DemandTribute, Action.DeclareWar, Action.Attack,
        Action.Blockade, Action.Raid, Action.Espionage, Action.Hold,
        UnknownAction {

    /**
     * Stable wire discriminator: the variant simple name (e.g. {@code "Explore"}).
     *
     * <p>{@code @JsonIgnore} so Jackson does not treat this accessor as a duplicate
     * {@code type} bean property - the discriminator is injected/consumed by
     * {@code @JsonTypeInfo} ({@code As.PROPERTY}) instead. The annotation is on the
     * interface declaration and is honoured for every record override.
     */
    @JsonIgnore
    String type();

    // ---- A. Environment actions (agent <-> world) -----------------------------

    /** Reveal an adjacent system and its onward lanes (game-design 03 A). */
    record Explore(SystemId targetSystem) implements Action {
        public Explore {
            requireNonNull(targetSystem, "Explore.targetSystem");
        }

        @Override
        public String type() {
            return "Explore";
        }
    }

    /** Establish a colony on {@code planet}, delivered by {@code viaFleet}. */
    record Colonize(PlanetId planet, FleetId viaFleet) implements Action {
        public Colonize {
            requireNonNull(planet, "Colonize.planet");
            requireNonNull(viaFleet, "Colonize.viaFleet");
        }

        @Override
        public String type() {
            return "Colonize";
        }
    }

    /** Queue a {@code buildingType} in a free {@code slot} on an owned {@code planet}. */
    record Build(PlanetId planet, int slot, BuildingType buildingType) implements Action {
        public Build {
            requireNonNull(planet, "Build.planet");
            requireNonNull(buildingType, "Build.buildingType");
            if (slot < 0) {
                throw new IllegalArgumentException("Build.slot must be >= 0");
            }
        }

        @Override
        public String type() {
            return "Build";
        }
    }

    /** Begin researching {@code techId}. */
    record Research(TechId techId) implements Action {
        public Research {
            requireNonNull(techId, "Research.techId");
        }

        @Override
        public String type() {
            return "Research";
        }
    }

    /** Advance the biome of an owned {@code planet} one step toward habitable. */
    record Terraform(PlanetId planet) implements Action {
        public Terraform {
            requireNonNull(planet, "Terraform.planet");
        }

        @Override
        public String type() {
            return "Terraform";
        }
    }

    /**
     * Build ships of {@code shipSpec} at an owned {@code system} with a Shipyard.
     * {@code shipSpec} is a config-defined archetype key (e.g. {@code "scout"});
     * its stats live in the balance profile, never here (rule 6).
     */
    record BuildFleet(SystemId system, String shipSpec) implements Action {
        public BuildFleet {
            requireNonNull(system, "BuildFleet.system");
            if (shipSpec == null || shipSpec.isBlank()) {
                throw new IllegalArgumentException("BuildFleet.shipSpec must be non-blank");
            }
        }

        @Override
        public String type() {
            return "BuildFleet";
        }
    }

    /**
     * Move {@code fleet} to {@code destination} along {@code path} (ordered systems,
     * defensively copied). The resolver advances movement and may force
     * interception combat mid-transit (game-design 03/05).
     */
    record MoveFleet(FleetId fleet, List<SystemId> path, SystemId destination) implements Action {
        public MoveFleet {
            requireNonNull(fleet, "MoveFleet.fleet");
            requireNonNull(destination, "MoveFleet.destination");
            requireNonNull(path, "MoveFleet.path");
            path = List.copyOf(path);
        }

        @Override
        public String type() {
            return "MoveFleet";
        }
    }

    /**
     * Create a recurring trade route between {@code systemA} and {@code systemB}
     * carrying {@code resources} (defensively copied) at {@code volume} per cycle.
     */
    record EstablishRoute(
            SystemId systemA,
            SystemId systemB,
            RouteKind kind,
            List<PhysicalResource> resources,
            double volume
    ) implements Action {
        public EstablishRoute {
            requireNonNull(systemA, "EstablishRoute.systemA");
            requireNonNull(systemB, "EstablishRoute.systemB");
            requireNonNull(kind, "EstablishRoute.kind");
            requireNonNull(resources, "EstablishRoute.resources");
            resources = List.copyOf(resources);
            if (volume < 0) {
                throw new IllegalArgumentException("EstablishRoute.volume must be >= 0");
            }
        }

        @Override
        public String type() {
            return "EstablishRoute";
        }
    }

    // ---- B. Inter-agent actions (agent <-> agent) -----------------------------

    /** Free-form, non-binding message to {@code to} (length-capped at validation). */
    record SendMessage(FactionId to, String text) implements Action {
        public SendMessage {
            requireNonNull(to, "SendMessage.to");
            requireNonNull(text, "SendMessage.text");
        }

        @Override
        public String type() {
            return "SendMessage";
        }
    }

    /**
     * Offer {@code give} in exchange for {@code receive} to faction {@code to},
     * optionally expiring after {@code expiresIn} ticks (empty = engine default).
     */
    record ProposeTrade(
            FactionId to,
            ResourceBundle give,
            ResourceBundle receive,
            Optional<Integer> expiresIn
    ) implements Action {
        public ProposeTrade {
            requireNonNull(to, "ProposeTrade.to");
            requireNonNull(give, "ProposeTrade.give");
            requireNonNull(receive, "ProposeTrade.receive");
            requireNonNull(expiresIn, "ProposeTrade.expiresIn (use Optional.empty())");
        }

        @Override
        public String type() {
            return "ProposeTrade";
        }
    }

    /** Accept a pending trade offer by id - triggers escrowed settlement. */
    record AcceptTrade(MarketOrderId offerId) implements Action {
        public AcceptTrade {
            requireNonNull(offerId, "AcceptTrade.offerId");
        }

        @Override
        public String type() {
            return "AcceptTrade";
        }
    }

    /** Decline a pending trade offer by id. */
    record DeclineTrade(MarketOrderId offerId) implements Action {
        public DeclineTrade {
            requireNonNull(offerId, "DeclineTrade.offerId");
        }

        @Override
        public String type() {
            return "DeclineTrade";
        }
    }

    /** Withdraw a trade offer this faction previously proposed. */
    record WithdrawTrade(MarketOrderId offerId) implements Action {
        public WithdrawTrade {
            requireNonNull(offerId, "WithdrawTrade.offerId");
        }

        @Override
        public String type() {
            return "WithdrawTrade";
        }
    }

    /**
     * Propose a {@code treatyType} to faction {@code to} with free-form
     * {@code terms} (defensively copied), optionally limited to {@code duration}
     * ticks (empty = open-ended).
     */
    record ProposeTreaty(
            FactionId to,
            TreatyType treatyType,
            Map<String, String> terms,
            Optional<Integer> duration
    ) implements Action {
        public ProposeTreaty {
            requireNonNull(to, "ProposeTreaty.to");
            requireNonNull(treatyType, "ProposeTreaty.treatyType");
            requireNonNull(terms, "ProposeTreaty.terms");
            requireNonNull(duration, "ProposeTreaty.duration (use Optional.empty())");
            terms = Map.copyOf(terms);
        }

        @Override
        public String type() {
            return "ProposeTreaty";
        }
    }

    /** Accept a pending treaty by id - becomes engine-enforced and is announced. */
    record AcceptTreaty(TreatyId treatyId) implements Action {
        public AcceptTreaty {
            requireNonNull(treatyId, "AcceptTreaty.treatyId");
        }

        @Override
        public String type() {
            return "AcceptTreaty";
        }
    }

    /** Decline a pending treaty by id. */
    record DeclineTreaty(TreatyId treatyId) implements Action {
        public DeclineTreaty {
            requireNonNull(treatyId, "DeclineTreaty.treatyId");
        }

        @Override
        public String type() {
            return "DeclineTreaty";
        }
    }

    /** Terminate an active treaty - announced galaxy-wide with a reputation penalty. */
    record BreakTreaty(TreatyId treatyId) implements Action {
        public BreakTreaty {
            requireNonNull(treatyId, "BreakTreaty.treatyId");
        }

        @Override
        public String type() {
            return "BreakTreaty";
        }
    }

    /** Transfer {@code resources} to faction {@code to} (goodwill / appeasement / dues). */
    record Tribute(FactionId to, ResourceBundle resources) implements Action {
        public Tribute {
            requireNonNull(to, "Tribute.to");
            requireNonNull(resources, "Tribute.resources");
        }

        @Override
        public String type() {
            return "Tribute";
        }
    }

    /**
     * Issue an ultimatum to faction {@code from} demanding {@code resources},
     * with an optional free-form {@code orElse} consequence (empty = none stated).
     */
    record DemandTribute(
            FactionId from,
            ResourceBundle resources,
            Optional<String> orElse
    ) implements Action {
        public DemandTribute {
            requireNonNull(from, "DemandTribute.from");
            requireNonNull(resources, "DemandTribute.resources");
            requireNonNull(orElse, "DemandTribute.orElse (use Optional.empty())");
        }

        @Override
        public String type() {
            return "DemandTribute";
        }
    }

    // ---- C. Military actions (agent <-> agent, kinetic) -----------------------

    /** Declare war on {@code target}, enabling Attack/Blockade/Raid against it. */
    record DeclareWar(FactionId target) implements Action {
        public DeclareWar {
            requireNonNull(target, "DeclareWar.target");
        }

        @Override
        public String type() {
            return "DeclareWar";
        }
    }

    /** Engage {@code target} (a system assault or a fleet engagement) with {@code fleet}. */
    record Attack(FleetId fleet, AttackTarget target) implements Action {
        public Attack {
            requireNonNull(fleet, "Attack.fleet");
            requireNonNull(target, "Attack.target");
        }

        @Override
        public String type() {
            return "Attack";
        }
    }

    /** Choke a {@code target} (a route or a system market) with {@code fleet}. */
    record Blockade(FleetId fleet, BlockadeTarget target) implements Action {
        public Blockade {
            requireNonNull(fleet, "Blockade.fleet");
            requireNonNull(target, "Blockade.target");
        }

        @Override
        public String type() {
            return "Blockade";
        }
    }

    /** Intercept a shipment on {@code routeId} with {@code fleet}, stealing cargo. */
    record Raid(FleetId fleet, RouteId routeId) implements Action {
        public Raid {
            requireNonNull(fleet, "Raid.fleet");
            requireNonNull(routeId, "Raid.routeId");
        }

        @Override
        public String type() {
            return "Raid";
        }
    }

    /** Run a (seeded) {@code operationType} espionage op against faction {@code target}. */
    record Espionage(FactionId target, EspionageOperation operationType) implements Action {
        public Espionage {
            requireNonNull(target, "Espionage.target");
            requireNonNull(operationType, "Espionage.operationType");
        }

        @Override
        public String type() {
            return "Espionage";
        }
    }

    // ---- D. Meta / passive ----------------------------------------------------

    /** The explicit no-op - the default for a silent or timed-out Sovereign. */
    record Hold() implements Action {
        @Override
        public String type() {
            return "Hold";
        }
    }

    // ---- shared helper --------------------------------------------------------

    /**
     * Null-guard used by the variant compact constructors. Kept private to the
     * interface so the closed boundary stays self-contained (no dependency on a
     * utility class).
     */
    private static void requireNonNull(Object value, String field) {
        if (value == null) {
            throw new IllegalArgumentException(field + " must be set");
        }
    }
}
