package com.stellarcompact.engine.validation;

import com.stellarcompact.engine.action.Action;
import com.stellarcompact.engine.action.AttackTarget;
import com.stellarcompact.engine.action.BlockadeTarget;
import com.stellarcompact.engine.action.UnknownAction;
import com.stellarcompact.engine.config.BalanceProfile;
import com.stellarcompact.engine.map.LaneNetwork;
import com.stellarcompact.engine.state.ActiveSystem;
import com.stellarcompact.engine.state.BuildingStatus;
import com.stellarcompact.engine.state.BuildingType;
import com.stellarcompact.engine.state.Faction;
import com.stellarcompact.engine.state.FactionId;
import com.stellarcompact.engine.state.Fleet;
import com.stellarcompact.engine.state.GameState;
import com.stellarcompact.engine.state.MarketOrder;
import com.stellarcompact.engine.state.MarketOrderId;
import com.stellarcompact.engine.state.Planet;
import com.stellarcompact.engine.state.PlanetId;
import com.stellarcompact.engine.state.ResourceBundle;
import com.stellarcompact.engine.state.Route;
import com.stellarcompact.engine.state.SystemId;
import com.stellarcompact.engine.state.TechProgress;
import com.stellarcompact.engine.state.TechStatus;
import com.stellarcompact.engine.state.Treaty;
import com.stellarcompact.engine.state.TreatyId;
import com.stellarcompact.engine.state.TreatyStatus;
import com.stellarcompact.engine.state.TreatyType;

import java.util.List;
import java.util.Optional;

/**
 * The authoritative legality gate (E1-04, trust boundary): decides whether one
 * agent-proposed {@code Action} is {@link ValidationResult.Valid} or
 * {@link ValidationResult.Rejected} against authoritative {@link GameState} and
 * the active {@link BalanceProfile} (game-design 03 per-action rules; spec section
 * 4). Rejected actions never reach the resolver; the {@code Rejected.message} is
 * fed back verbatim on the Sovereign's single re-prompt (E4-04).
 *
 * <p>Purity and determinism: every method is a pure, side-effect-free function of
 * its arguments - no I/O, no wall-clock, no RNG, no mutation of {@code state}.
 * Dispatch is an exhaustive switch over the sealed {@link Action} with NO default,
 * so a 26th variant is a compile error until handled. {@link UnknownAction} is
 * rejected with {@link RejectionReason#UNKNOWN_ACTION}, never executed.
 *
 * <p>Security (untrusted boundary): no agent-supplied id/count/resource is taken
 * on faith - existence and ownership are checked against {@code state}. Rejection
 * messages reference only what the actor could legitimately know.
 *
 * <p>Deferred checks (need systems not yet built), all marked TODO inline:
 * lane graph / adjacency / pathfinding (E2-03, E1-09); the war-state record
 * (E1-09); the market offer record with expiry/addressee (E1-07); per-action cost
 * tables and the tech-prerequisite DAG (E1-06/E1-07).
 */
public final class ActionValidator {

    private ActionValidator() {
    }

    /** Hard text cap for {@code SendMessage.text} until the cap lives in config (TODO E1-07). */
    private static final int MESSAGE_TEXT_CAP = 4_000;

    /**
     * Validate a single action proposed by {@code actor} against authoritative state.
     *
     * @param state   authoritative snapshot (never mutated)
     * @param actor   the faction issuing the action (must exist in {@code state})
     * @param action  the agent-proposed action (closed sealed set)
     * @param profile active balance profile (source of every gameplay number)
     * @return {@link ValidationResult.Valid} or a {@link ValidationResult.Rejected}
     */
    public static ValidationResult validate(GameState state, FactionId actor, Action action,
                                            BalanceProfile profile) {
        // Legacy 4-arg entry point: no lane graph supplied. Adjacency/reachability
        // (F2) fall back to shape-only checks (pre-E1-09 behaviour); the war-state
        // gate (F1) still applies (it needs only GameState, not the network).
        return validate(state, actor, action, profile, LaneNetwork.EMPTY);
    }

    /**
     * Validate with an explicit {@link LaneNetwork} (E1-09). The network lets the
     * adjacency/reachability checks (spec section 4a, F2) become real lane checks for
     * {@code Explore}, {@code Colonize} and {@code MoveFleet}; pass
     * {@link LaneNetwork#EMPTY} (or use the 4-arg overload) when no lane graph is
     * available, in which case those checks degrade to shape-only.
     *
     * @param network the active-region lane graph (never {@code null}; use {@code EMPTY})
     */
    public static ValidationResult validate(GameState state, FactionId actor, Action action,
                                            BalanceProfile profile, LaneNetwork network) {
        if (state == null) {
            throw new IllegalArgumentException("validate: state must be set");
        }
        if (actor == null) {
            throw new IllegalArgumentException("validate: actor must be set");
        }
        if (action == null) {
            throw new IllegalArgumentException("validate: action must be set");
        }
        if (profile == null) {
            throw new IllegalArgumentException("validate: profile must be set");
        }
        if (network == null) {
            throw new IllegalArgumentException("validate: network must be set (use LaneNetwork.EMPTY)");
        }

        if (!state.factions().containsKey(actor)) {
            return ValidationResult.reject(RejectionReason.TARGET_UNKNOWN,
                    "Unknown acting faction '" + actor.value() + "'.");
        }

        // Exhaustive dispatch - NO default. A new Action variant breaks compilation.
        return switch (action) {
            case Action.Explore a -> validateExplore(state, actor, a, network);
            case Action.Colonize a -> validateColonize(state, actor, a, network);
            case Action.Build a -> validateBuild(state, actor, a, profile);
            case Action.Research a -> validateResearch(state, actor, a, profile);
            case Action.Terraform a -> validateTerraform(state, actor, a);
            case Action.BuildFleet a -> validateBuildFleet(state, actor, a, profile);
            case Action.MoveFleet a -> validateMoveFleet(state, actor, a, network);
            case Action.EstablishRoute a -> validateEstablishRoute(state, actor, a);
            case Action.SendMessage a -> validateSendMessage(state, actor, a);
            case Action.ProposeTrade a -> validateProposeTrade(state, actor, a);
            case Action.AcceptTrade a -> validateDirectedOffer(state, actor, a.offerId(), "accept");
            case Action.DeclineTrade a -> validateDirectedOffer(state, actor, a.offerId(), "decline");
            case Action.WithdrawTrade a -> validateWithdrawOffer(state, actor, a.offerId());
            case Action.ProposeTreaty a -> validateProposeTreaty(state, actor, a);
            case Action.AcceptTreaty a -> validateTreatyReference(state, actor, a.treatyId(), TreatyStatus.PROPOSED, "accept");
            case Action.DeclineTreaty a -> validateTreatyReference(state, actor, a.treatyId(), TreatyStatus.PROPOSED, "decline");
            case Action.BreakTreaty a -> validateTreatyReference(state, actor, a.treatyId(), TreatyStatus.ACTIVE, "break");
            case Action.Tribute a -> validateTribute(state, actor, a);
            case Action.DemandTribute a -> validateDemandTribute(state, actor, a);
            case Action.DeclareWar a -> validateDeclareWar(state, actor, a);
            case Action.Attack a -> validateAttack(state, actor, a);
            case Action.Blockade a -> validateBlockade(state, actor, a);
            case Action.Raid a -> validateRaid(state, actor, a);
            case Action.Espionage a -> validateEspionage(state, actor, a);
            case Action.Hold ignored -> ValidationResult.valid();
            case UnknownAction u -> ValidationResult.reject(RejectionReason.UNKNOWN_ACTION,
                    "Unrecognised action type '" + u.type()
                            + "'; not in the closed action set. Treated as Hold.");
        };
    }

    // ===== A. Environment actions =============================================

    private static ValidationResult validateExplore(GameState state, FactionId actor,
                                                    Action.Explore a, LaneNetwork network) {
        if (!state.systems().containsKey(a.targetSystem())) {
            return ValidationResult.reject(RejectionReason.TARGET_UNKNOWN,
                    "Cannot explore unknown system " + a.targetSystem().value() + ".");
        }
        // F2 (E1-09): NOT_ADJACENT - the target must be one lane hop from a system the
        // actor owns or holds a fleet in. Enforced only when a lane network is supplied;
        // with LaneNetwork.EMPTY this degrades to the existence check (pre-E1-09).
        if (!network.isEmpty()
                && !adjacentToActorPresence(state, actor, a.targetSystem(), network)) {
            return ValidationResult.reject(RejectionReason.NOT_ADJACENT,
                    "System " + a.targetSystem().value() + " is not one lane hop from any "
                            + "system you own or have a fleet in.");
        }
        return ValidationResult.valid();
    }

    private static ValidationResult validateColonize(GameState state, FactionId actor,
                                                     Action.Colonize a, LaneNetwork network) {
        ActiveSystem host = systemOfPlanet(state, a.planet());
        if (host == null) {
            return ValidationResult.reject(RejectionReason.TARGET_UNKNOWN,
                    "Cannot colonise unknown planet " + a.planet().value() + ".");
        }
        Fleet fleet = state.fleets().get(a.viaFleet());
        if (fleet == null) {
            return ValidationResult.reject(RejectionReason.TARGET_UNKNOWN,
                    "Colony fleet " + a.viaFleet().value() + " does not exist.");
        }
        if (!actor.equals(fleet.owner())) {
            return ValidationResult.reject(RejectionReason.NOT_OWNED,
                    "Fleet " + a.viaFleet().value() + " is not yours to colonise with.");
        }
        if (host.owner().isPresent() && !host.owner().get().equals(actor)) {
            return ValidationResult.reject(RejectionReason.NOT_OWNED,
                    "System " + host.id().value() + " belongs to another faction; "
                            + "you cannot colonise within it.");
        }
        // F2 (E1-09): the colony fleet must be positioned at the target system (the
        // colony ship delivers the colony - game-design 03 Colonize). A stationary
        // fleet whose location is the host system satisfies this; an en-route fleet, or
        // one parked elsewhere, cannot colonise. Enforced only with a lane network
        // available (it gates the same movement model); EMPTY degrades to shape-only.
        if (!network.isEmpty()) {
            if (fleet.enRoute() || fleet.location().isEmpty()
                    || !fleet.location().get().equals(host.id())) {
                return ValidationResult.reject(RejectionReason.NOT_ADJACENT,
                        "Fleet " + a.viaFleet().value() + " must be stationed at "
                                + host.id().value() + " to colonise planet "
                                + a.planet().value() + ".");
            }
        }
        // TODO(E1-07): biome-scaled colonisation-cost affordability (per-action cost index).
        return ValidationResult.valid();
    }

    private static ValidationResult validateBuild(GameState state, FactionId actor,
                                                  Action.Build a, BalanceProfile profile) {
        ActiveSystem host = systemOfPlanet(state, a.planet());
        if (host == null) {
            return ValidationResult.reject(RejectionReason.TARGET_UNKNOWN,
                    "Cannot build on unknown planet " + a.planet().value() + ".");
        }
        if (host.owner().isEmpty() || !host.owner().get().equals(actor)) {
            return ValidationResult.reject(RejectionReason.NOT_OWNED,
                    "Planet " + a.planet().value() + " is not owned by you.");
        }
        Planet planet = planet(host, a.planet());
        if (a.slot() >= planet.slotsTotal()) {
            return ValidationResult.reject(RejectionReason.NO_FREE_SLOT,
                    "Slot " + a.slot() + " is out of range on planet " + a.planet().value()
                            + " (it has " + planet.slotsTotal() + " slot(s)).");
        }
        boolean occupied = planet.buildings().stream().anyMatch(b -> b.slotIndex() == a.slot());
        if (occupied) {
            return ValidationResult.reject(RejectionReason.NO_FREE_SLOT,
                    "Slot " + a.slot() + " on planet " + a.planet().value()
                            + " is already occupied.");
        }
        Faction builder = state.factions().get(actor);
        ResourceBundle cost = buildCost(profile, a.buildingType());
        if (cost != null) {
            if (!builder.stockpiles().canAfford(cost)) {
                return ValidationResult.reject(RejectionReason.INSUFFICIENT_RESOURCES,
                        "Insufficient resources to build " + a.buildingType()
                                + "; need " + describe(cost) + ".");
            }
        }
        // E1-08: tech-gated buildings (e.g. Market hub, Monument) need their gating
        // tech UNLOCKED; the gate map lives in tech.unlocks (config), not in code.
        String capability = a.buildingType().configKey();
        if (!capabilityUnlocked(builder, capability, profile)) {
            return ValidationResult.reject(RejectionReason.TECH_PREREQ_MISSING,
                    "Building " + a.buildingType() + " requires unlocking "
                            + gatingTechName(capability, profile) + " first.");
        }
        return ValidationResult.valid();
    }

    private static ValidationResult validateResearch(GameState state, FactionId actor,
                                                     Action.Research a, BalanceProfile profile) {
        Faction f = state.factions().get(actor);
        TechProgress progress = f.techProgress().get(a.techId());
        if (progress != null && progress.status() == TechStatus.UNLOCKED) {
            return ValidationResult.reject(RejectionReason.MALFORMED,
                    "Tech " + a.techId().value() + " is already unlocked.");
        }
        boolean knownNode = progress != null
                || profile.tech().costs().containsKey(a.techId().value());
        if (!knownNode) {
            return ValidationResult.reject(RejectionReason.TARGET_UNKNOWN,
                    "Unknown tech " + a.techId().value() + ".");
        }
        Double techCost = profile.tech().costs().get(a.techId().value());
        if (techCost != null) {
            ResourceBundle cost = new ResourceBundle(0, 0, 0, techCost, 0);
            if (!f.stockpiles().canAfford(cost)) {
                return ValidationResult.reject(RejectionReason.INSUFFICIENT_RESOURCES,
                        "Insufficient Tech to research " + a.techId().value()
                                + "; need " + techCost + " Tech.");
            }
        }
        // E1-08: enforce the tech DAG prerequisite edges - every prerequisite of the
        // node must be UNLOCKED before research may begin (game-design 03 Research/06).
        if (!techPrerequisitesMet(f, a.techId().value(), profile)) {
            return ValidationResult.reject(RejectionReason.TECH_PREREQ_MISSING,
                    "Cannot research " + a.techId().value()
                            + " yet; unlock its prerequisites first: "
                            + missingPrereqs(f, a.techId().value(), profile) + ".");
        }
        return ValidationResult.valid();
    }

    private static ValidationResult validateTerraform(GameState state, FactionId actor,
                                                      Action.Terraform a) {
        ActiveSystem host = systemOfPlanet(state, a.planet());
        if (host == null) {
            return ValidationResult.reject(RejectionReason.TARGET_UNKNOWN,
                    "Cannot terraform unknown planet " + a.planet().value() + ".");
        }
        if (host.owner().isEmpty() || !host.owner().get().equals(actor)) {
            return ValidationResult.reject(RejectionReason.NOT_OWNED,
                    "Planet " + a.planet().value() + " is not owned by you.");
        }
        Planet planet = planet(host, a.planet());
        boolean hasTerraformer = planet.buildings().stream()
                .anyMatch(b -> b.type() == BuildingType.TERRAFORMER
                        && b.status() == BuildingStatus.ACTIVE);
        if (!hasTerraformer) {
            return ValidationResult.reject(RejectionReason.MALFORMED,
                    "Planet " + a.planet().value()
                            + " has no active Terraformer to terraform with.");
        }
        // TODO(E1-07): sustained Energy upkeep affordability once per-action upkeep
        // is indexed in the profile.
        return ValidationResult.valid();
    }

    private static ValidationResult validateBuildFleet(GameState state, FactionId actor,
                                                       Action.BuildFleet a, BalanceProfile profile) {
        ActiveSystem host = state.systems().get(a.system());
        if (host == null) {
            return ValidationResult.reject(RejectionReason.TARGET_UNKNOWN,
                    "Cannot build a fleet at unknown system " + a.system().value() + ".");
        }
        if (host.owner().isEmpty() || !host.owner().get().equals(actor)) {
            return ValidationResult.reject(RejectionReason.NOT_OWNED,
                    "System " + a.system().value() + " is not owned by you.");
        }
        boolean hasShipyard = host.planets().stream()
                .flatMap(p -> p.buildings().stream())
                .anyMatch(b -> b.type() == BuildingType.SHIPYARD
                        && b.status() == BuildingStatus.ACTIVE);
        if (!hasShipyard) {
            return ValidationResult.reject(RejectionReason.MALFORMED,
                    "System " + a.system().value() + " has no active Shipyard to build ships.");
        }
        // E1-08: ship tiers are gated by doctrine techs (corvette/cruiser/capital);
        // the gating map lives in tech.unlocks (config). An ungated spec is buildable.
        Faction shipwright = state.factions().get(actor);
        if (!capabilityUnlocked(shipwright, a.shipSpec(), profile)) {
            return ValidationResult.reject(RejectionReason.TECH_PREREQ_MISSING,
                    "Ship tier " + a.shipSpec() + " requires unlocking "
                            + gatingTechName(a.shipSpec(), profile) + " first.");
        }
        // TODO(E1-07): per-ship Minerals+Tech cost and population-to-crew checks need
        // a ship-spec index in the profile (shipSpec -> cost/crew).
        return ValidationResult.valid();
    }

    private static ValidationResult validateMoveFleet(GameState state, FactionId actor,
                                                      Action.MoveFleet a, LaneNetwork network) {
        Fleet fleet = state.fleets().get(a.fleet());
        if (fleet == null) {
            return ValidationResult.reject(RejectionReason.TARGET_UNKNOWN,
                    "Fleet " + a.fleet().value() + " does not exist.");
        }
        if (!actor.equals(fleet.owner())) {
            return ValidationResult.reject(RejectionReason.NOT_OWNED,
                    "Fleet " + a.fleet().value() + " is not under your control.");
        }
        if (!state.systems().containsKey(a.destination())) {
            return ValidationResult.reject(RejectionReason.TARGET_UNKNOWN,
                    "Move destination " + a.destination().value() + " is an unknown system.");
        }
        List<SystemId> path = a.path();
        if (path.isEmpty()) {
            return ValidationResult.reject(RejectionReason.NO_PATH,
                    "MoveFleet path is empty; supply the ordered systems to traverse to "
                            + a.destination().value() + ".");
        }
        if (!path.get(path.size() - 1).equals(a.destination())) {
            return ValidationResult.reject(RejectionReason.NO_PATH,
                    "MoveFleet path does not end at the declared destination "
                            + a.destination().value() + ".");
        }
        // F2 (E1-09): the path must start from the fleet's current location and every
        // consecutive hop must be a real lane. Enforced only with a lane network
        // available; with LaneNetwork.EMPTY only the shape checks above apply (pre-E1-09).
        if (!network.isEmpty()) {
            if (fleet.enRoute() || fleet.location().isEmpty()) {
                return ValidationResult.reject(RejectionReason.NO_PATH,
                        "Fleet " + a.fleet().value() + " is already in transit; it cannot "
                                + "be redirected mid-lane this tick.");
            }
            SystemId origin = fleet.location().get();
            // path[] is the ordered systems AFTER the origin (per MoveFleet.path);
            // pathTicks proves origin->path[0]->...->destination is a real lane walk.
            if (network.pathTicks(origin, path).isEmpty()) {
                return ValidationResult.reject(RejectionReason.NO_PATH,
                        "MoveFleet path from " + origin.value() + " to "
                                + a.destination().value() + " is not a connected sequence "
                                + "of lanes.");
            }
        }
        return ValidationResult.valid();
    }

    private static ValidationResult validateEstablishRoute(GameState state, FactionId actor,
                                                           Action.EstablishRoute a) {
        if (a.systemA().equals(a.systemB())) {
            return ValidationResult.reject(RejectionReason.MALFORMED,
                    "Route endpoints must be two distinct systems.");
        }
        ActiveSystem sa = state.systems().get(a.systemA());
        ActiveSystem sb = state.systems().get(a.systemB());
        if (sa == null) {
            return ValidationResult.reject(RejectionReason.TARGET_UNKNOWN,
                    "Route endpoint " + a.systemA().value() + " is an unknown system.");
        }
        if (sb == null) {
            return ValidationResult.reject(RejectionReason.TARGET_UNKNOWN,
                    "Route endpoint " + a.systemB().value() + " is an unknown system.");
        }
        boolean ownsAnEndpoint = isOwnedBy(sa, actor) || isOwnedBy(sb, actor);
        if (!ownsAnEndpoint) {
            return ValidationResult.reject(RejectionReason.NOT_OWNED,
                    "You must own at least one endpoint to establish a route between "
                            + a.systemA().value() + " and " + a.systemB().value() + ".");
        }
        // TODO(E2-03): lane reachability between endpoints, and for ALLIED kind an
        // alliance with the other endpoint owner.
        return ValidationResult.valid();
    }

    // ===== B. Inter-agent actions ============================================

    private static ValidationResult validateSendMessage(GameState state, FactionId actor,
                                                        Action.SendMessage a) {
        if (a.to().equals(actor)) {
            return ValidationResult.reject(RejectionReason.SELF_TARGET,
                    "Cannot send a message to yourself.");
        }
        if (!state.factions().containsKey(a.to())) {
            return ValidationResult.reject(RejectionReason.TARGET_UNKNOWN,
                    "Unknown message recipient " + a.to().value() + ".");
        }
        if (a.text().length() > MESSAGE_TEXT_CAP) {
            return ValidationResult.reject(RejectionReason.MALFORMED,
                    "Message exceeds the length cap of " + MESSAGE_TEXT_CAP + " characters.");
        }
        return ValidationResult.valid();
    }

    private static ValidationResult validateProposeTrade(GameState state, FactionId actor,
                                                         Action.ProposeTrade a) {
        if (a.to().equals(actor)) {
            return ValidationResult.reject(RejectionReason.SELF_TARGET,
                    "Cannot propose a trade to yourself.");
        }
        if (!state.factions().containsKey(a.to())) {
            return ValidationResult.reject(RejectionReason.TARGET_UNKNOWN,
                    "Unknown trade counterparty " + a.to().value() + ".");
        }
        if (isEmptyBundle(a.give()) && isEmptyBundle(a.receive())) {
            return ValidationResult.reject(RejectionReason.MALFORMED,
                    "A trade must offer or request at least one resource.");
        }
        Faction f = state.factions().get(actor);
        if (!f.stockpiles().canAfford(a.give())) {
            return ValidationResult.reject(RejectionReason.INSUFFICIENT_RESOURCES,
                    "You cannot back this offer; you do not hold " + describe(a.give()) + ".");
        }
        return ValidationResult.valid();
    }

    /**
     * Accept/Decline of a <em>directed</em> trade offer by id (E1-07 security
     * prereq, spec section 4a). A directed offer must (1) exist, (2) be addressed
     * to the actor - an offer with a different addressee, or an open order-book
     * order with no addressee at all, is not the actor's to accept/decline - and
     * (3) not be expired ({@code currentTick > expiresTick} -&gt;
     * {@link RejectionReason#OFFER_EXPIRED}). All facts referenced (the offer's id
     * the actor supplied, its addressee = the actor, its public expiry) are
     * actor-knowable; nothing hidden leaks.
     */
    private static ValidationResult validateDirectedOffer(GameState state, FactionId actor,
                                                          MarketOrderId offerId, String verb) {
        MarketOrder offer = state.marketOrders().get(offerId);
        if (offer == null) {
            return ValidationResult.reject(RejectionReason.TARGET_UNKNOWN,
                    "Trade offer " + offerId.value() + " does not exist.");
        }
        // Addressed-to-me gate. An open book order (no addressee) is matched by the
        // engine, not accepted/declined by id; either is rejected as not-yours.
        if (offer.addressee().isEmpty() || !offer.addressee().get().equals(actor)) {
            return ValidationResult.reject(RejectionReason.NOT_OWNED,
                    "Trade offer " + offerId.value() + " is not addressed to you to "
                            + verb + ".");
        }
        if (offer.isExpired(state.tick())) {
            return ValidationResult.reject(RejectionReason.OFFER_EXPIRED,
                    "Trade offer " + offerId.value() + " expired at tick "
                            + offer.expiresTick() + " (now tick " + state.tick() + ").");
        }
        return ValidationResult.valid();
    }

    /**
     * Withdraw of an offer the actor itself proposed. Checks existence and
     * proposer-identity (the order's {@code faction}), not addressee - a proposer
     * may withdraw its own open book order or its own directed offer. An expired
     * offer may still be withdrawn (it is a tidy-up, not an acceptance), so no
     * expiry gate here.
     */
    private static ValidationResult validateWithdrawOffer(GameState state, FactionId actor,
                                                          MarketOrderId offerId) {
        MarketOrder offer = state.marketOrders().get(offerId);
        if (offer == null) {
            return ValidationResult.reject(RejectionReason.TARGET_UNKNOWN,
                    "Trade offer " + offerId.value() + " does not exist.");
        }
        if (!actor.equals(offer.faction())) {
            return ValidationResult.reject(RejectionReason.NOT_OWNED,
                    "Trade offer " + offerId.value() + " was not proposed by you.");
        }
        return ValidationResult.valid();
    }

    private static ValidationResult validateProposeTreaty(GameState state, FactionId actor,
                                                          Action.ProposeTreaty a) {
        if (a.to().equals(actor)) {
            return ValidationResult.reject(RejectionReason.SELF_TARGET,
                    "Cannot propose a treaty to yourself.");
        }
        if (!state.factions().containsKey(a.to())) {
            return ValidationResult.reject(RejectionReason.TARGET_UNKNOWN,
                    "Unknown treaty counterparty " + a.to().value() + ".");
        }
        // TODO(E1-07): upfront-term affordability once treaty terms carry a typed,
        // priced shape (currently free-form Map<String,String>).
        return ValidationResult.valid();
    }

    /**
     * Shared existence/party/status check for the treaty-by-id actions. The actor
     * must be a party to the treaty, and the treaty must be in the status the verb
     * requires (Accept/Decline need PROPOSED; Break needs ACTIVE).
     */
    private static ValidationResult validateTreatyReference(GameState state, FactionId actor,
                                                            TreatyId treatyId,
                                                            TreatyStatus required, String verb) {
        Treaty treaty = state.treaties().get(treatyId);
        if (treaty == null) {
            return ValidationResult.reject(RejectionReason.TARGET_UNKNOWN,
                    "Treaty " + treatyId.value() + " does not exist.");
        }
        if (!treaty.parties().contains(actor)) {
            return ValidationResult.reject(RejectionReason.NOT_OWNED,
                    "You are not a party to treaty " + treatyId.value() + ".");
        }
        if (treaty.status() != required) {
            return ValidationResult.reject(RejectionReason.MALFORMED,
                    "Cannot " + verb + " treaty " + treatyId.value()
                            + ": it is " + treaty.status() + ", not " + required + ".");
        }
        return ValidationResult.valid();
    }

    private static ValidationResult validateTribute(GameState state, FactionId actor,
                                                    Action.Tribute a) {
        if (a.to().equals(actor)) {
            return ValidationResult.reject(RejectionReason.SELF_TARGET,
                    "Cannot pay tribute to yourself.");
        }
        if (!state.factions().containsKey(a.to())) {
            return ValidationResult.reject(RejectionReason.TARGET_UNKNOWN,
                    "Unknown tribute recipient " + a.to().value() + ".");
        }
        if (isEmptyBundle(a.resources())) {
            return ValidationResult.reject(RejectionReason.MALFORMED,
                    "Tribute must transfer at least one resource.");
        }
        Faction f = state.factions().get(actor);
        if (!f.stockpiles().canAfford(a.resources())) {
            return ValidationResult.reject(RejectionReason.INSUFFICIENT_RESOURCES,
                    "Insufficient resources to pay tribute of " + describe(a.resources()) + ".");
        }
        return ValidationResult.valid();
    }

    private static ValidationResult validateDemandTribute(GameState state, FactionId actor,
                                                          Action.DemandTribute a) {
        if (a.from().equals(actor)) {
            return ValidationResult.reject(RejectionReason.SELF_TARGET,
                    "Cannot demand tribute from yourself.");
        }
        if (!state.factions().containsKey(a.from())) {
            return ValidationResult.reject(RejectionReason.TARGET_UNKNOWN,
                    "Unknown tribute target " + a.from().value() + ".");
        }
        if (isEmptyBundle(a.resources())) {
            return ValidationResult.reject(RejectionReason.MALFORMED,
                    "A tribute demand must name at least one resource.");
        }
        // No affordability check on the demander - it is an ultimatum, the target
        // chooses to comply (game-design 03). Nothing about the target leaks here.
        return ValidationResult.valid();
    }

    // ===== C. Military actions ================================================

    private static ValidationResult validateDeclareWar(GameState state, FactionId actor,
                                                       Action.DeclareWar a) {
        if (a.target().equals(actor)) {
            return ValidationResult.reject(RejectionReason.SELF_TARGET,
                    "Cannot declare war on yourself.");
        }
        if (!state.factions().containsKey(a.target())) {
            return ValidationResult.reject(RejectionReason.TARGET_UNKNOWN,
                    "Unknown war target " + a.target().value() + ".");
        }
        Optional<Treaty> blocking = bindingPeaceTreaty(state, actor, a.target());
        if (blocking.isPresent()) {
            Treaty t = blocking.get();
            return ValidationResult.rejectTreaty(RejectionReason.TREATY_FORBIDS, t.id(),
                    "Treaty " + t.id().value() + " (" + t.type()
                            + ") forbids declaring war on " + a.target().value()
                            + "; break it first.");
        }
        return ValidationResult.valid();
    }

    private static ValidationResult validateAttack(GameState state, FactionId actor,
                                                   Action.Attack a) {
        Fleet fleet = state.fleets().get(a.fleet());
        if (fleet == null) {
            return ValidationResult.reject(RejectionReason.TARGET_UNKNOWN,
                    "Attacking fleet " + a.fleet().value() + " does not exist.");
        }
        if (!actor.equals(fleet.owner())) {
            return ValidationResult.reject(RejectionReason.NOT_OWNED,
                    "Fleet " + a.fleet().value() + " is not yours to attack with.");
        }
        FactionId targetOwner;
        switch (a.target()) {
            case AttackTarget.OnSystem s -> {
                ActiveSystem sys = state.systems().get(s.system());
                if (sys == null) {
                    return ValidationResult.reject(RejectionReason.TARGET_UNKNOWN,
                            "Attack target system " + s.system().value() + " is unknown.");
                }
                targetOwner = sys.owner().orElse(null);
            }
            case AttackTarget.OnFleet ft -> {
                Fleet target = state.fleets().get(ft.fleet());
                if (target == null) {
                    return ValidationResult.reject(RejectionReason.TARGET_UNKNOWN,
                            "Attack target fleet " + ft.fleet().value() + " is unknown.");
                }
                targetOwner = target.owner();
            }
        }
        if (actor.equals(targetOwner)) {
            return ValidationResult.reject(RejectionReason.SELF_TARGET,
                    "Cannot attack your own forces.");
        }
        if (targetOwner != null) {
            Optional<Treaty> blocking = bindingPeaceTreaty(state, actor, targetOwner);
            if (blocking.isPresent()) {
                Treaty t = blocking.get();
                return ValidationResult.rejectTreaty(RejectionReason.TREATY_FORBIDS, t.id(),
                        "Treaty " + t.id().value() + " (" + t.type()
                                + ") forbids attacking " + targetOwner.value()
                                + "; break it first.");
            }
            // F1 (E1-09): a kinetic strike on an OWNED target requires a positive war
            // state. Absence of a forbidding treaty is necessary but NOT sufficient -
            // peace-but-not-treaty is still peace. A neutral (unowned) target is exempt
            // (targetOwner == null), so this gate is only reached for owned targets.
            if (!state.atWar(actor, targetOwner)) {
                return ValidationResult.reject(RejectionReason.NOT_AT_WAR,
                        "You are not at war with " + targetOwner.value()
                                + "; declare war before attacking its forces.");
            }
        }
        // TODO(E1-10): fleet-positioned-at-target range gate (needs lane graph) lands
        // with combat in E1-10.
        return ValidationResult.valid();
    }

    private static ValidationResult validateBlockade(GameState state, FactionId actor,
                                                     Action.Blockade a) {
        Fleet fleet = state.fleets().get(a.fleet());
        if (fleet == null) {
            return ValidationResult.reject(RejectionReason.TARGET_UNKNOWN,
                    "Blockading fleet " + a.fleet().value() + " does not exist.");
        }
        if (!actor.equals(fleet.owner())) {
            return ValidationResult.reject(RejectionReason.NOT_OWNED,
                    "Fleet " + a.fleet().value() + " is not yours to blockade with.");
        }
        FactionId targetOwner;
        switch (a.target()) {
            case BlockadeTarget.OnRoute r -> {
                Route route = state.routes().get(r.route());
                if (route == null) {
                    return ValidationResult.reject(RejectionReason.TARGET_UNKNOWN,
                            "Blockade target route " + r.route().value() + " is unknown.");
                }
                targetOwner = route.owner();
            }
            case BlockadeTarget.OnSystem s -> {
                ActiveSystem sys = state.systems().get(s.system());
                if (sys == null) {
                    return ValidationResult.reject(RejectionReason.TARGET_UNKNOWN,
                            "Blockade target system " + s.system().value() + " is unknown.");
                }
                targetOwner = sys.owner().orElse(null);
            }
        }
        if (actor.equals(targetOwner)) {
            return ValidationResult.reject(RejectionReason.SELF_TARGET,
                    "Cannot blockade your own route or system.");
        }
        if (targetOwner != null) {
            Optional<Treaty> blocking = bindingPeaceTreaty(state, actor, targetOwner);
            if (blocking.isPresent()) {
                Treaty t = blocking.get();
                return ValidationResult.rejectTreaty(RejectionReason.TREATY_FORBIDS, t.id(),
                        "Treaty " + t.id().value() + " (" + t.type()
                                + ") forbids blockading " + targetOwner.value() + ".");
            }
            // F1 (E1-09): blockading an owned route/system requires a positive war
            // state (a neutral target is exempt - targetOwner == null).
            if (!state.atWar(actor, targetOwner)) {
                return ValidationResult.reject(RejectionReason.NOT_AT_WAR,
                        "You are not at war with " + targetOwner.value()
                                + "; declare war before blockading its assets.");
            }
        }
        // TODO(E1-11): fleet positioning on the route/system (needs lane graph) lands
        // with blockade effects in E1-11.
        return ValidationResult.valid();
    }

    private static ValidationResult validateRaid(GameState state, FactionId actor,
                                                 Action.Raid a) {
        Fleet fleet = state.fleets().get(a.fleet());
        if (fleet == null) {
            return ValidationResult.reject(RejectionReason.TARGET_UNKNOWN,
                    "Raiding fleet " + a.fleet().value() + " does not exist.");
        }
        if (!actor.equals(fleet.owner())) {
            return ValidationResult.reject(RejectionReason.NOT_OWNED,
                    "Fleet " + a.fleet().value() + " is not yours to raid with.");
        }
        Route route = state.routes().get(a.routeId());
        if (route == null) {
            return ValidationResult.reject(RejectionReason.TARGET_UNKNOWN,
                    "Raid target route " + a.routeId().value() + " is unknown.");
        }
        if (actor.equals(route.owner())) {
            return ValidationResult.reject(RejectionReason.SELF_TARGET,
                    "Cannot raid your own route.");
        }
        Optional<Treaty> blocking = bindingPeaceTreaty(state, actor, route.owner());
        if (blocking.isPresent()) {
            Treaty t = blocking.get();
            return ValidationResult.rejectTreaty(RejectionReason.TREATY_FORBIDS, t.id(),
                    "Treaty " + t.id().value() + " (" + t.type()
                            + ") forbids raiding " + route.owner().value() + ".");
        }
        // F1 (E1-09): a route always has an owner, so raiding always targets an owned
        // asset and therefore always requires a positive war state - there is no
        // neutral-target exemption for Raid.
        if (!state.atWar(actor, route.owner())) {
            return ValidationResult.reject(RejectionReason.NOT_AT_WAR,
                    "You are not at war with " + route.owner().value()
                            + "; declare war before raiding its route.");
        }
        // TODO(E1-11): fleet positioning on the route (needs lane graph) lands with
        // raid effects in E1-11.
        return ValidationResult.valid();
    }

    private static ValidationResult validateEspionage(GameState state, FactionId actor,
                                                      Action.Espionage a) {
        if (a.target().equals(actor)) {
            return ValidationResult.reject(RejectionReason.SELF_TARGET,
                    "Cannot run espionage against yourself.");
        }
        if (!state.factions().containsKey(a.target())) {
            return ValidationResult.reject(RejectionReason.TARGET_UNKNOWN,
                    "Unknown espionage target " + a.target().value() + ".");
        }
        // TODO(E1-07): Influence/Tech cost affordability and asset-near-target gating
        // once per-operation costs are indexed in the profile. No treaty gate:
        // espionage is covert and not blocked by a peace treaty (game-design 03 C).
        return ValidationResult.valid();
    }

    // ===== helpers (pure) =====================================================

    /** @return the system that contains {@code planetId}, or {@code null} if none. */
    private static ActiveSystem systemOfPlanet(GameState state, PlanetId planetId) {
        for (ActiveSystem s : state.systems().values()) {
            for (Planet p : s.planets()) {
                if (p.id().equals(planetId)) {
                    return s;
                }
            }
        }
        return null;
    }

    /** @return the planet record for {@code planetId} within {@code host} (must exist). */
    private static Planet planet(ActiveSystem host, PlanetId planetId) {
        for (Planet p : host.planets()) {
            if (p.id().equals(planetId)) {
                return p;
            }
        }
        throw new IllegalStateException("planet() called for a planet not in host system");
    }

    private static boolean isOwnedBy(ActiveSystem system, FactionId faction) {
        return system.owner().isPresent() && system.owner().get().equals(faction);
    }

    /**
     * @return {@code true} iff {@code target} is one lane hop (per the {@code network})
     * from at least one system the actor either owns or has a (stationary or en-route
     * origin) fleet in - the "presence" Explore's NOT_ADJACENT gate (F2) checks. Pure:
     * scans owned systems and own-fleet locations, both actor-knowable.
     */
    private static boolean adjacentToActorPresence(GameState state, FactionId actor,
                                                   SystemId target, LaneNetwork network) {
        for (ActiveSystem system : state.systems().values()) {
            if (isOwnedBy(system, actor) && network.adjacent(system.id(), target)) {
                return true;
            }
        }
        for (Fleet fleet : state.fleets().values()) {
            if (!actor.equals(fleet.owner())) {
                continue;
            }
            if (fleet.location().isPresent()
                    && network.adjacent(fleet.location().get(), target)) {
                return true;
            }
        }
        return false;
    }

    /**
     * @return an active treaty between {@code a} and {@code b} that forbids hostile
     * action (NON_AGGRESSION, ALLIANCE or CEASEFIRE), if any. Only ACTIVE treaties
     * bind. The treaty ledger is public, so naming it leaks nothing hidden.
     */
    private static Optional<Treaty> bindingPeaceTreaty(GameState state, FactionId a, FactionId b) {
        for (Treaty t : state.treaties().values()) {
            if (t.status() != TreatyStatus.ACTIVE) {
                continue;
            }
            boolean forbidsHostility = t.type() == TreatyType.NON_AGGRESSION
                    || t.type() == TreatyType.ALLIANCE
                    || t.type() == TreatyType.CEASEFIRE;
            if (forbidsHostility && t.parties().contains(a) && t.parties().contains(b)) {
                return Optional.of(t);
            }
        }
        return Optional.empty();
    }

    // ===== tech DAG gate (E1-08) ==============================================

    /**
     * @return {@code true} iff every prerequisite tech of {@code techKey} (per the
     * configured {@code tech.prereqs} DAG edges) is {@code UNLOCKED} for {@code f}. A
     * node with no configured prerequisites is a root and is always satisfied. The
     * tree shape is config (rule 6); this method hardcodes no edges.
     */
    private static boolean techPrerequisitesMet(Faction f, String techKey, BalanceProfile profile) {
        List<String> prereqs = profile.tech().prereqs().get(techKey);
        if (prereqs == null || prereqs.isEmpty()) {
            return true;
        }
        for (String required : prereqs) {
            if (!isUnlocked(f, required)) {
                return false;
            }
        }
        return true;
    }

    /** Human-readable list of the not-yet-unlocked prerequisites, for the re-prompt. */
    private static String missingPrereqs(Faction f, String techKey, BalanceProfile profile) {
        List<String> prereqs = profile.tech().prereqs().get(techKey);
        if (prereqs == null) {
            return "(none)";
        }
        StringBuilder sb = new StringBuilder();
        for (String required : prereqs) {
            if (!isUnlocked(f, required)) {
                if (sb.length() > 0) {
                    sb.append(", ");
                }
                sb.append(required);
            }
        }
        return sb.length() == 0 ? "(none)" : sb.toString();
    }

    /**
     * @return {@code true} iff {@code f} may use {@code capability} (a building or
     * ship-spec configKey): either no tech gates it in {@code tech.unlocks}, or its
     * gating tech is {@code UNLOCKED}.
     */
    private static boolean capabilityUnlocked(Faction f, String capability, BalanceProfile profile) {
        String gate = gatingTechName(capability, profile);
        return gate == null || isUnlocked(f, gate);
    }

    /** @return the tech that gates {@code capability} per {@code tech.unlocks}, or null if ungated. */
    private static String gatingTechName(String capability, BalanceProfile profile) {
        for (java.util.Map.Entry<String, List<String>> e : profile.tech().unlocks().entrySet()) {
            if (e.getValue().contains(capability)) {
                return e.getKey();
            }
        }
        return null;
    }

    /** @return {@code true} iff faction {@code f} has the named tech UNLOCKED. */
    private static boolean isUnlocked(Faction f, String techKey) {
        TechProgress tp = f.techProgress().get(new com.stellarcompact.engine.state.TechId(techKey));
        return tp != null && tp.status() == TechStatus.UNLOCKED;
    }

    private static boolean isEmptyBundle(ResourceBundle b) {
        return b.energy() == 0 && b.minerals() == 0 && b.food() == 0
                && b.tech() == 0 && b.influence() == 0;
    }

    /** @return the build cost for {@code type} from the profile, or {@code null} if absent. */
    private static ResourceBundle buildCost(BalanceProfile profile, BuildingType type) {
        BalanceProfile.ResourceBundle c = profile.construction().costs().get(type.configKey());
        if (c == null) {
            return null;
        }
        return new ResourceBundle(c.energy(), c.minerals(), c.food(), c.tech(), c.influence());
    }

    /** Compact, human-readable description of a non-zero resource cost for messages. */
    private static String describe(ResourceBundle b) {
        StringBuilder sb = new StringBuilder();
        appendIf(sb, "Energy", b.energy());
        appendIf(sb, "Minerals", b.minerals());
        appendIf(sb, "Food", b.food());
        appendIf(sb, "Tech", b.tech());
        appendIf(sb, "Influence", b.influence());
        return sb.length() == 0 ? "nothing" : sb.toString();
    }

    private static void appendIf(StringBuilder sb, String name, double v) {
        if (v != 0) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(v).append(' ').append(name);
        }
    }
}
