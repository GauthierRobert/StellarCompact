package com.stellarcompact.engine.validation;

import com.stellarcompact.engine.action.Action;
import com.stellarcompact.engine.action.AttackTarget;
import com.stellarcompact.engine.action.BlockadeTarget;
import com.stellarcompact.engine.action.EspionageOperation;
import com.stellarcompact.engine.action.UnknownAction;
import com.stellarcompact.engine.config.BalanceProfile;
import com.stellarcompact.engine.state.Building;
import com.stellarcompact.engine.state.BuildingType;
import com.stellarcompact.engine.state.GameState;
import com.stellarcompact.engine.state.ResourceBundle;
import com.stellarcompact.engine.state.TechId;
import com.stellarcompact.engine.state.TechProgress;
import com.stellarcompact.engine.state.TechStatus;
import com.stellarcompact.engine.state.TreatyId;
import com.stellarcompact.engine.state.TreatyType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.stellarcompact.engine.validation.Fixtures.ALPHA;
import static com.stellarcompact.engine.validation.Fixtures.BETA;
import static com.stellarcompact.engine.validation.Fixtures.FLEET_A;
import static com.stellarcompact.engine.validation.Fixtures.FLEET_B;
import static com.stellarcompact.engine.validation.Fixtures.OFFER_1;
import static com.stellarcompact.engine.validation.Fixtures.PLANET_A;
import static com.stellarcompact.engine.validation.Fixtures.PLANET_B;
import static com.stellarcompact.engine.validation.Fixtures.ROUTE_B;
import static com.stellarcompact.engine.validation.Fixtures.SYS_A;
import static com.stellarcompact.engine.validation.Fixtures.SYS_B;
import static com.stellarcompact.engine.validation.Fixtures.SYS_NEUTRAL;
import static com.stellarcompact.engine.validation.Fixtures.TREATY_1;
import static com.stellarcompact.engine.validation.Fixtures.TECH_DRIVE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Per-variant accept + per-rejection-reason tests for {@link ActionValidator}.
 * Hand-authored states (see {@link Fixtures}); deterministic, no LLM, no RNG.
 *
 * <p>Each rejection assertion checks the stable {@link RejectionReason} code AND
 * that the human-readable message is non-blank (it is fed back verbatim to the
 * Sovereign on its single re-prompt, E4-04).
 */
class ActionValidatorTest {

    private static final GameState STATE = Fixtures.baseState();
    private static final BalanceProfile PROFILE = Fixtures.profile();

    // ---- helpers --------------------------------------------------------------

    private static ValidationResult validate(GameState state, Action action) {
        return ActionValidator.validate(state, ALPHA, action, PROFILE);
    }

    private static ValidationResult validate(Action action) {
        return validate(STATE, action);
    }

    private static void assertValid(ValidationResult r) {
        assertTrue(r.isValid(), () -> "expected Valid but was " + r);
    }

    private static ValidationResult.Rejected assertRejected(ValidationResult r, RejectionReason code) {
        assertFalse(r.isValid(), () -> "expected Rejected but was Valid");
        assertTrue(r instanceof ValidationResult.Rejected, "expected Rejected variant");
        ValidationResult.Rejected rej = (ValidationResult.Rejected) r;
        assertEquals(code, rej.code(), () -> "wrong reason code; message=" + rej.message());
        assertFalse(rej.message().isBlank(), "rejection message must be non-blank");
        return rej;
    }

    // ===== Meta ===============================================================

    @Test
    void holdIsAlwaysValid() {
        assertValid(validate(new Action.Hold()));
    }

    @Test
    void unknownActionRejected() {
        ValidationResult.Rejected r = assertRejected(
                validate(new UnknownAction("FutureMove")), RejectionReason.UNKNOWN_ACTION);
        assertTrue(r.message().contains("FutureMove"), "message should echo the unknown type");
    }

    @Test
    void unknownActorRejected() {
        ValidationResult r = ActionValidator.validate(STATE,
                new com.stellarcompact.engine.state.FactionId("ghost"),
                new Action.Hold(), PROFILE);
        assertRejected(r, RejectionReason.TARGET_UNKNOWN);
    }

    // ===== Explore ============================================================

    @Test
    void exploreKnownSystemValid() {
        assertValid(validate(new Action.Explore(SYS_NEUTRAL)));
    }

    @Test
    void exploreUnknownSystemRejected() {
        assertRejected(validate(new Action.Explore(
                new com.stellarcompact.engine.state.SystemId("nowhere"))),
                RejectionReason.TARGET_UNKNOWN);
    }

    // ===== Colonize ===========================================================

    @Test
    void colonizeNeutralWithOwnFleetValid() {
        // PLANET_A is in ALPHA-owned SYS_A; colonising own-owned is legal.
        assertValid(validate(new Action.Colonize(PLANET_A, FLEET_A)));
    }

    @Test
    void colonizeUnknownPlanetRejected() {
        assertRejected(validate(new Action.Colonize(
                new com.stellarcompact.engine.state.PlanetId("ghostPlanet"), FLEET_A)),
                RejectionReason.TARGET_UNKNOWN);
    }

    @Test
    void colonizeWithEnemyFleetRejectedNotOwned() {
        assertRejected(validate(new Action.Colonize(PLANET_A, FLEET_B)),
                RejectionReason.NOT_OWNED);
    }

    @Test
    void colonizeInEnemySystemRejectedNotOwned() {
        // PLANET_B sits in BETA-owned SYS_B.
        assertRejected(validate(new Action.Colonize(PLANET_B, FLEET_A)),
                RejectionReason.NOT_OWNED);
    }

    // ===== Build ==============================================================

    @Test
    void buildOnOwnedPlanetWithFreeSlotAndResourcesValid() {
        assertValid(validate(new Action.Build(PLANET_A, 0, BuildingType.MINE)));
    }

    @Test
    void buildOnUnknownPlanetRejected() {
        assertRejected(validate(new Action.Build(
                new com.stellarcompact.engine.state.PlanetId("ghost"), 0, BuildingType.MINE)),
                RejectionReason.TARGET_UNKNOWN);
    }

    @Test
    void buildOnEnemyPlanetRejectedNotOwned() {
        assertRejected(validate(new Action.Build(PLANET_B, 0, BuildingType.MINE)),
                RejectionReason.NOT_OWNED);
    }

    @Test
    void buildSlotOutOfRangeRejectedNoFreeSlot() {
        // PLANET_A has 3 slots (0..2); slot 9 is out of range.
        assertRejected(validate(new Action.Build(PLANET_A, 9, BuildingType.MINE)),
                RejectionReason.NO_FREE_SLOT);
    }

    @Test
    void buildOccupiedSlotRejectedNoFreeSlot() {
        GameState state = Fixtures.baseState(Map.of(), Fixtures.rich(), Map.of(),
                List.of(Fixtures.activeBuilding(1, BuildingType.FARM)));
        assertRejected(ActionValidator.validate(state, ALPHA,
                        new Action.Build(PLANET_A, 1, BuildingType.MINE), PROFILE),
                RejectionReason.NO_FREE_SLOT);
    }

    @Test
    void buildUnaffordableRejectedInsufficientResources() {
        GameState state = Fixtures.baseState(Map.of(), ResourceBundle.ZERO, Map.of(), List.of());
        ValidationResult.Rejected r = assertRejected(ActionValidator.validate(state, ALPHA,
                        new Action.Build(PLANET_A, 0, BuildingType.MINE), PROFILE),
                RejectionReason.INSUFFICIENT_RESOURCES);
        assertTrue(r.message().contains("Minerals"), "message should name the missing resource");
    }

    // ===== Research ===========================================================

    @Test
    void researchKnownAffordableTechValid() {
        assertValid(validate(new Action.Research(TECH_DRIVE)));
    }

    @Test
    void researchUnknownTechRejected() {
        assertRejected(validate(new Action.Research(new TechId("phlogiston"))),
                RejectionReason.TARGET_UNKNOWN);
    }

    @Test
    void researchAlreadyUnlockedRejectedMalformed() {
        Map<TechId, TechProgress> tech = Map.of(TECH_DRIVE,
                new TechProgress(TECH_DRIVE, TechStatus.UNLOCKED, 5));
        GameState state = Fixtures.baseState(Map.of(), Fixtures.rich(), tech, List.of());
        assertRejected(ActionValidator.validate(state, ALPHA,
                new Action.Research(TECH_DRIVE), PROFILE), RejectionReason.MALFORMED);
    }

    @Test
    void researchUnaffordableRejectedInsufficientResources() {
        GameState state = Fixtures.baseState(Map.of(), ResourceBundle.ZERO, Map.of(), List.of());
        assertRejected(ActionValidator.validate(state, ALPHA,
                new Action.Research(TECH_DRIVE), PROFILE),
                RejectionReason.INSUFFICIENT_RESOURCES);
    }

    // ===== Terraform ==========================================================

    @Test
    void terraformWithActiveTerraformerValid() {
        GameState state = Fixtures.baseState(Map.of(), Fixtures.rich(), Map.of(),
                List.of(Fixtures.activeBuilding(0, BuildingType.TERRAFORMER)));
        assertValid(ActionValidator.validate(state, ALPHA,
                new Action.Terraform(PLANET_A), PROFILE));
    }

    @Test
    void terraformUnknownPlanetRejected() {
        assertRejected(validate(new Action.Terraform(
                new com.stellarcompact.engine.state.PlanetId("ghost"))),
                RejectionReason.TARGET_UNKNOWN);
    }

    @Test
    void terraformEnemyPlanetRejectedNotOwned() {
        assertRejected(validate(new Action.Terraform(PLANET_B)), RejectionReason.NOT_OWNED);
    }

    @Test
    void terraformWithoutTerraformerRejectedMalformed() {
        assertRejected(validate(new Action.Terraform(PLANET_A)), RejectionReason.MALFORMED);
    }

    // ===== BuildFleet =========================================================

    @Test
    void buildFleetWithActiveShipyardValid() {
        GameState state = Fixtures.baseState(Map.of(), Fixtures.rich(), Map.of(),
                List.of(Fixtures.activeBuilding(0, BuildingType.SHIPYARD)));
        assertValid(ActionValidator.validate(state, ALPHA,
                new Action.BuildFleet(SYS_A, "cruiser"), PROFILE));
    }

    @Test
    void buildFleetUnknownSystemRejected() {
        assertRejected(validate(new Action.BuildFleet(
                new com.stellarcompact.engine.state.SystemId("ghost"), "cruiser")),
                RejectionReason.TARGET_UNKNOWN);
    }

    @Test
    void buildFleetEnemySystemRejectedNotOwned() {
        assertRejected(validate(new Action.BuildFleet(SYS_B, "cruiser")),
                RejectionReason.NOT_OWNED);
    }

    @Test
    void buildFleetWithoutShipyardRejectedMalformed() {
        assertRejected(validate(new Action.BuildFleet(SYS_A, "cruiser")),
                RejectionReason.MALFORMED);
    }

    // ===== MoveFleet ==========================================================

    @Test
    void moveFleetWellFormedPathValid() {
        assertValid(validate(new Action.MoveFleet(FLEET_A,
                List.of(SYS_NEUTRAL), SYS_NEUTRAL)));
    }

    @Test
    void moveUnknownFleetRejected() {
        assertRejected(validate(new Action.MoveFleet(
                new com.stellarcompact.engine.state.FleetId("ghost"),
                List.of(SYS_NEUTRAL), SYS_NEUTRAL)), RejectionReason.TARGET_UNKNOWN);
    }

    @Test
    void moveEnemyFleetRejectedNotOwned() {
        assertRejected(validate(new Action.MoveFleet(FLEET_B,
                List.of(SYS_NEUTRAL), SYS_NEUTRAL)), RejectionReason.NOT_OWNED);
    }

    @Test
    void moveToUnknownDestinationRejected() {
        com.stellarcompact.engine.state.SystemId ghost =
                new com.stellarcompact.engine.state.SystemId("ghost");
        assertRejected(validate(new Action.MoveFleet(FLEET_A, List.of(ghost), ghost)),
                RejectionReason.TARGET_UNKNOWN);
    }

    @Test
    void moveEmptyPathRejectedNoPath() {
        assertRejected(validate(new Action.MoveFleet(FLEET_A, List.of(), SYS_NEUTRAL)),
                RejectionReason.NO_PATH);
    }

    @Test
    void movePathNotEndingAtDestinationRejectedNoPath() {
        assertRejected(validate(new Action.MoveFleet(FLEET_A, List.of(SYS_B), SYS_NEUTRAL)),
                RejectionReason.NO_PATH);
    }

    // ===== EstablishRoute =====================================================

    @Test
    void establishRouteFromOwnedEndpointValid() {
        assertValid(validate(new Action.EstablishRoute(SYS_A, SYS_NEUTRAL,
                com.stellarcompact.engine.state.RouteKind.COMMERCIAL,
                List.of(com.stellarcompact.engine.state.PhysicalResource.MINERALS), 5.0)));
    }

    @Test
    void establishRouteSameEndpointsRejectedMalformed() {
        assertRejected(validate(new Action.EstablishRoute(SYS_A, SYS_A,
                com.stellarcompact.engine.state.RouteKind.COMMERCIAL,
                List.of(com.stellarcompact.engine.state.PhysicalResource.MINERALS), 5.0)),
                RejectionReason.MALFORMED);
    }

    @Test
    void establishRouteUnknownEndpointRejected() {
        assertRejected(validate(new Action.EstablishRoute(SYS_A,
                new com.stellarcompact.engine.state.SystemId("ghost"),
                com.stellarcompact.engine.state.RouteKind.COMMERCIAL,
                List.of(com.stellarcompact.engine.state.PhysicalResource.MINERALS), 5.0)),
                RejectionReason.TARGET_UNKNOWN);
    }

    @Test
    void establishRouteWithNoOwnedEndpointRejectedNotOwned() {
        // Both endpoints not owned by ALPHA: SYS_B (BETA) and SYS_NEUTRAL.
        assertRejected(validate(new Action.EstablishRoute(SYS_B, SYS_NEUTRAL,
                com.stellarcompact.engine.state.RouteKind.COMMERCIAL,
                List.of(com.stellarcompact.engine.state.PhysicalResource.MINERALS), 5.0)),
                RejectionReason.NOT_OWNED);
    }

    // ===== SendMessage ========================================================

    @Test
    void sendMessageToOtherFactionValid() {
        assertValid(validate(new Action.SendMessage(BETA, "greetings")));
    }

    @Test
    void sendMessageToSelfRejectedSelfTarget() {
        assertRejected(validate(new Action.SendMessage(ALPHA, "hi me")),
                RejectionReason.SELF_TARGET);
    }

    @Test
    void sendMessageToUnknownRejected() {
        assertRejected(validate(new Action.SendMessage(
                new com.stellarcompact.engine.state.FactionId("ghost"), "hi")),
                RejectionReason.TARGET_UNKNOWN);
    }

    @Test
    void sendMessageOverCapRejectedMalformed() {
        String huge = "x".repeat(5000);
        assertRejected(validate(new Action.SendMessage(BETA, huge)), RejectionReason.MALFORMED);
    }

    // ===== ProposeTrade =======================================================

    @Test
    void proposeTradeAffordableValid() {
        ResourceBundle give = new ResourceBundle(10, 0, 0, 0, 0);
        ResourceBundle recv = new ResourceBundle(0, 10, 0, 0, 0);
        assertValid(validate(new Action.ProposeTrade(BETA, give, recv, Optional.empty())));
    }

    @Test
    void proposeTradeToSelfRejectedSelfTarget() {
        ResourceBundle give = new ResourceBundle(10, 0, 0, 0, 0);
        assertRejected(validate(new Action.ProposeTrade(ALPHA, give,
                ResourceBundle.ZERO, Optional.empty())), RejectionReason.SELF_TARGET);
    }

    @Test
    void proposeTradeUnknownPartyRejected() {
        ResourceBundle give = new ResourceBundle(10, 0, 0, 0, 0);
        assertRejected(validate(new Action.ProposeTrade(
                new com.stellarcompact.engine.state.FactionId("ghost"), give,
                ResourceBundle.ZERO, Optional.empty())), RejectionReason.TARGET_UNKNOWN);
    }

    @Test
    void proposeTradeEmptyForEmptyRejectedMalformed() {
        assertRejected(validate(new Action.ProposeTrade(BETA, ResourceBundle.ZERO,
                ResourceBundle.ZERO, Optional.empty())), RejectionReason.MALFORMED);
    }

    @Test
    void proposeTradeUnaffordableGiveRejectedInsufficientResources() {
        GameState state = Fixtures.baseState(Map.of(), ResourceBundle.ZERO, Map.of(), List.of());
        ResourceBundle give = new ResourceBundle(10, 0, 0, 0, 0);
        assertRejected(ActionValidator.validate(state, ALPHA,
                new Action.ProposeTrade(BETA, give, ResourceBundle.ZERO, Optional.empty()),
                PROFILE), RejectionReason.INSUFFICIENT_RESOURCES);
    }

    // ===== Accept/Decline/Withdraw Trade ======================================

    @Test
    void acceptExistingOfferValid() {
        assertValid(validate(new Action.AcceptTrade(OFFER_1)));
    }

    @Test
    void acceptUnknownOfferRejected() {
        assertRejected(validate(new Action.AcceptTrade(
                new com.stellarcompact.engine.state.MarketOrderId("ghost"))),
                RejectionReason.TARGET_UNKNOWN);
    }

    @Test
    void declineExistingOfferValid() {
        assertValid(validate(new Action.DeclineTrade(OFFER_1)));
    }

    @Test
    void withdrawOwnOfferValid() {
        // OFFER_1 belongs to BETA in baseState; make ALPHA the proposer here.
        GameState state = Fixtures.baseState();
        ValidationResult r = ActionValidator.validate(state, BETA,
                new Action.WithdrawTrade(OFFER_1), PROFILE);
        assertValid(r);
    }

    @Test
    void withdrawOthersOfferRejectedNotOwned() {
        // ALPHA tries to withdraw BETA's offer.
        assertRejected(validate(new Action.WithdrawTrade(OFFER_1)), RejectionReason.NOT_OWNED);
    }

    // ===== Directed offer: addressee + expiry (E1-07 security prereq) ==========

    @Test
    void acceptOfferAddressedToMeValid() {
        // baseState OFFER_1: proposed by BETA, addressed to ALPHA, expires tick 1000
        // (state tick 5) -> ALPHA may accept.
        assertValid(validate(new Action.AcceptTrade(OFFER_1)));
    }

    @Test
    void acceptOfferAddressedToAnotherRejectedNotOwned() {
        // Offer addressed to BETA, but ALPHA (the actor) tries to accept it.
        GameState state = Fixtures.baseStateWithOffer(
                Fixtures.offer(OFFER_1, BETA, BETA, 1000L));
        assertRejected(ActionValidator.validate(state, ALPHA,
                new Action.AcceptTrade(OFFER_1), PROFILE), RejectionReason.NOT_OWNED);
    }

    @Test
    void acceptExpiredOfferRejectedOfferExpired() {
        // Offer addressed to ALPHA but expired at tick 4 (state tick is 5).
        GameState state = Fixtures.baseStateWithOffer(
                Fixtures.offer(OFFER_1, BETA, ALPHA, 4L));
        assertRejected(ActionValidator.validate(state, ALPHA,
                new Action.AcceptTrade(OFFER_1), PROFILE), RejectionReason.OFFER_EXPIRED);
    }

    @Test
    void declineOfferAddressedToAnotherRejectedNotOwned() {
        GameState state = Fixtures.baseStateWithOffer(
                Fixtures.offer(OFFER_1, BETA, BETA, 1000L));
        assertRejected(ActionValidator.validate(state, ALPHA,
                new Action.DeclineTrade(OFFER_1), PROFILE), RejectionReason.NOT_OWNED);
    }

    @Test
    void declineExpiredOfferRejectedOfferExpired() {
        GameState state = Fixtures.baseStateWithOffer(
                Fixtures.offer(OFFER_1, BETA, ALPHA, 4L));
        assertRejected(ActionValidator.validate(state, ALPHA,
                new Action.DeclineTrade(OFFER_1), PROFILE), RejectionReason.OFFER_EXPIRED);
    }

    @Test
    void acceptOpenBookOrderWithNoAddresseeRejectedNotOwned() {
        // An open order-book order (no addressee) is matched by the engine, not
        // accepted by id; ALPHA accepting it must be rejected.
        GameState state = Fixtures.baseStateWithOffer(new com.stellarcompact.engine.state.MarketOrder(
                OFFER_1, SYS_A, BETA, com.stellarcompact.engine.state.MarketSide.SELL,
                com.stellarcompact.engine.state.PhysicalResource.MINERALS, 10, 1.0, 0L, 1000L,
                Optional.empty(), com.stellarcompact.engine.state.OrderStatus.OPEN));
        assertRejected(ActionValidator.validate(state, ALPHA,
                new Action.AcceptTrade(OFFER_1), PROFILE), RejectionReason.NOT_OWNED);
    }

    @Test
    void withdrawExpiredOwnOfferStillValid() {
        // Proposer may tidy up an expired offer of its own (no expiry gate on Withdraw).
        GameState state = Fixtures.baseStateWithOffer(
                Fixtures.offer(OFFER_1, BETA, ALPHA, 4L));
        assertValid(ActionValidator.validate(state, BETA,
                new Action.WithdrawTrade(OFFER_1), PROFILE));
    }

    // ===== ProposeTreaty ======================================================

    @Test
    void proposeTreatyToOtherValid() {
        assertValid(validate(new Action.ProposeTreaty(BETA, TreatyType.TRADE_PACT,
                Map.of(), Optional.empty())));
    }

    @Test
    void proposeTreatyToSelfRejectedSelfTarget() {
        assertRejected(validate(new Action.ProposeTreaty(ALPHA, TreatyType.TRADE_PACT,
                Map.of(), Optional.empty())), RejectionReason.SELF_TARGET);
    }

    @Test
    void proposeTreatyUnknownPartyRejected() {
        assertRejected(validate(new Action.ProposeTreaty(
                new com.stellarcompact.engine.state.FactionId("ghost"),
                TreatyType.TRADE_PACT, Map.of(), Optional.empty())),
                RejectionReason.TARGET_UNKNOWN);
    }

    // ===== Accept/Decline/Break Treaty ========================================

    @Test
    void acceptProposedTreatyAsPartyValid() {
        GameState state = Fixtures.baseState(
                Map.of(TREATY_1, Fixtures.proposedTreaty(TreatyType.ALLIANCE, ALPHA, BETA)),
                Fixtures.rich(), Map.of(), List.of());
        assertValid(ActionValidator.validate(state, ALPHA,
                new Action.AcceptTreaty(TREATY_1), PROFILE));
    }

    @Test
    void acceptUnknownTreatyRejected() {
        assertRejected(validate(new Action.AcceptTreaty(
                new com.stellarcompact.engine.state.TreatyId("ghost"))),
                RejectionReason.TARGET_UNKNOWN);
    }

    @Test
    void acceptTreatyNotAPartyRejectedNotOwned() {
        // Treaty between BETA and GAMMA; ALPHA is not a party.
        com.stellarcompact.engine.state.Treaty t = new com.stellarcompact.engine.state.Treaty(
                TREATY_1, TreatyType.ALLIANCE,
                List.of(BETA, new com.stellarcompact.engine.state.FactionId("gamma")),
                Map.of(), 0L, 100L, com.stellarcompact.engine.state.TreatyStatus.PROPOSED);
        GameState s2 = Fixtures.baseState(Map.of(TREATY_1, t), Fixtures.rich(), Map.of(), List.of());
        assertRejected(ActionValidator.validate(s2, ALPHA,
                new Action.AcceptTreaty(TREATY_1), PROFILE), RejectionReason.NOT_OWNED);
    }

    @Test
    void acceptAlreadyActiveTreatyRejectedMalformed() {
        GameState state = Fixtures.baseState(
                Map.of(TREATY_1, Fixtures.activeTreaty(TreatyType.ALLIANCE, ALPHA, BETA)),
                Fixtures.rich(), Map.of(), List.of());
        assertRejected(ActionValidator.validate(state, ALPHA,
                new Action.AcceptTreaty(TREATY_1), PROFILE), RejectionReason.MALFORMED);
    }

    @Test
    void breakActiveTreatyAsPartyValid() {
        GameState state = Fixtures.baseState(
                Map.of(TREATY_1, Fixtures.activeTreaty(TreatyType.NON_AGGRESSION, ALPHA, BETA)),
                Fixtures.rich(), Map.of(), List.of());
        assertValid(ActionValidator.validate(state, ALPHA,
                new Action.BreakTreaty(TREATY_1), PROFILE));
    }

    @Test
    void breakProposedTreatyRejectedMalformed() {
        GameState state = Fixtures.baseState(
                Map.of(TREATY_1, Fixtures.proposedTreaty(TreatyType.NON_AGGRESSION, ALPHA, BETA)),
                Fixtures.rich(), Map.of(), List.of());
        assertRejected(ActionValidator.validate(state, ALPHA,
                new Action.BreakTreaty(TREATY_1), PROFILE), RejectionReason.MALFORMED);
    }

    @Test
    void declineProposedTreatyValid() {
        GameState state = Fixtures.baseState(
                Map.of(TREATY_1, Fixtures.proposedTreaty(TreatyType.TRADE_PACT, ALPHA, BETA)),
                Fixtures.rich(), Map.of(), List.of());
        assertValid(ActionValidator.validate(state, ALPHA,
                new Action.DeclineTreaty(TREATY_1), PROFILE));
    }

    // ===== Tribute / DemandTribute ============================================

    @Test
    void tributeAffordableValid() {
        assertValid(validate(new Action.Tribute(BETA, new ResourceBundle(5, 0, 0, 0, 0))));
    }

    @Test
    void tributeToSelfRejectedSelfTarget() {
        assertRejected(validate(new Action.Tribute(ALPHA, new ResourceBundle(5, 0, 0, 0, 0))),
                RejectionReason.SELF_TARGET);
    }

    @Test
    void tributeUnknownRecipientRejected() {
        assertRejected(validate(new Action.Tribute(
                new com.stellarcompact.engine.state.FactionId("ghost"),
                new ResourceBundle(5, 0, 0, 0, 0))), RejectionReason.TARGET_UNKNOWN);
    }

    @Test
    void tributeEmptyRejectedMalformed() {
        assertRejected(validate(new Action.Tribute(BETA, ResourceBundle.ZERO)),
                RejectionReason.MALFORMED);
    }

    @Test
    void tributeUnaffordableRejectedInsufficientResources() {
        GameState state = Fixtures.baseState(Map.of(), ResourceBundle.ZERO, Map.of(), List.of());
        assertRejected(ActionValidator.validate(state, ALPHA,
                new Action.Tribute(BETA, new ResourceBundle(5, 0, 0, 0, 0)), PROFILE),
                RejectionReason.INSUFFICIENT_RESOURCES);
    }

    @Test
    void demandTributeFromOtherValid() {
        assertValid(validate(new Action.DemandTribute(BETA,
                new ResourceBundle(5, 0, 0, 0, 0), Optional.empty())));
    }

    @Test
    void demandTributeFromSelfRejectedSelfTarget() {
        assertRejected(validate(new Action.DemandTribute(ALPHA,
                new ResourceBundle(5, 0, 0, 0, 0), Optional.empty())),
                RejectionReason.SELF_TARGET);
    }

    @Test
    void demandTributeUnknownTargetRejected() {
        assertRejected(validate(new Action.DemandTribute(
                new com.stellarcompact.engine.state.FactionId("ghost"),
                new ResourceBundle(5, 0, 0, 0, 0), Optional.empty())),
                RejectionReason.TARGET_UNKNOWN);
    }

    @Test
    void demandTributeEmptyRejectedMalformed() {
        assertRejected(validate(new Action.DemandTribute(BETA, ResourceBundle.ZERO,
                Optional.empty())), RejectionReason.MALFORMED);
    }

    // ===== DeclareWar =========================================================

    @Test
    void declareWarOnOtherWithNoTreatyValid() {
        assertValid(validate(new Action.DeclareWar(BETA)));
    }

    @Test
    void declareWarOnSelfRejectedSelfTarget() {
        assertRejected(validate(new Action.DeclareWar(ALPHA)), RejectionReason.SELF_TARGET);
    }

    @Test
    void declareWarUnknownTargetRejected() {
        assertRejected(validate(new Action.DeclareWar(
                new com.stellarcompact.engine.state.FactionId("ghost"))),
                RejectionReason.TARGET_UNKNOWN);
    }

    @Test
    void declareWarBlockedByNonAggressionRejectedTreatyForbidsWithId() {
        GameState state = Fixtures.baseState(
                Map.of(TREATY_1, Fixtures.activeTreaty(TreatyType.NON_AGGRESSION, ALPHA, BETA)),
                Fixtures.rich(), Map.of(), List.of());
        ValidationResult.Rejected r = assertRejected(ActionValidator.validate(state, ALPHA,
                new Action.DeclareWar(BETA), PROFILE), RejectionReason.TREATY_FORBIDS);
        assertEquals(Optional.of(TREATY_1), r.treatyId(),
                "TREATY_FORBIDS must carry the blocking treaty id as structured context");
        assertTrue(r.message().contains(TREATY_1.value()),
                "message must include the treaty id");
    }

    // ===== Attack =============================================================

    @Test
    void attackEnemySystemAtWarValid() {
        GameState war = Fixtures.baseStateAtWar(ALPHA, BETA);
        assertValid(ActionValidator.validate(war, ALPHA,
                new Action.Attack(FLEET_A, new AttackTarget.OnSystem(SYS_B)), PROFILE));
    }

    @Test
    void attackNeutralSystemValid() {
        // A neutral (unowned) target needs no war state.
        assertValid(validate(new Action.Attack(FLEET_A, new AttackTarget.OnSystem(SYS_NEUTRAL))));
    }

    @Test
    void attackEnemyFleetAtWarValid() {
        GameState war = Fixtures.baseStateAtWar(ALPHA, BETA);
        assertValid(ActionValidator.validate(war, ALPHA,
                new Action.Attack(FLEET_A, new AttackTarget.OnFleet(FLEET_B)), PROFILE));
    }

    @Test
    void attackOwnedTargetWithoutWarRejectedNotAtWar() {
        // E1-12: absence of a forbidding treaty is necessary but not sufficient - an
        // owned target requires a positive war state.
        assertRejected(validate(new Action.Attack(FLEET_A, new AttackTarget.OnSystem(SYS_B))),
                RejectionReason.NOT_AT_WAR);
    }

    @Test
    void attackUnknownFleetRejected() {
        assertRejected(validate(new Action.Attack(
                new com.stellarcompact.engine.state.FleetId("ghost"),
                new AttackTarget.OnSystem(SYS_B))), RejectionReason.TARGET_UNKNOWN);
    }

    @Test
    void attackWithEnemyFleetRejectedNotOwned() {
        assertRejected(validate(new Action.Attack(FLEET_B, new AttackTarget.OnSystem(SYS_B))),
                RejectionReason.NOT_OWNED);
    }

    @Test
    void attackUnknownTargetSystemRejected() {
        assertRejected(validate(new Action.Attack(FLEET_A, new AttackTarget.OnSystem(
                new com.stellarcompact.engine.state.SystemId("ghost")))),
                RejectionReason.TARGET_UNKNOWN);
    }

    @Test
    void attackOwnSystemRejectedSelfTarget() {
        assertRejected(validate(new Action.Attack(FLEET_A, new AttackTarget.OnSystem(SYS_A))),
                RejectionReason.SELF_TARGET);
    }

    @Test
    void attackOwnFleetRejectedSelfTarget() {
        assertRejected(validate(new Action.Attack(FLEET_A, new AttackTarget.OnFleet(FLEET_A))),
                RejectionReason.SELF_TARGET);
    }

    @Test
    void attackPeaceBoundEnemyRejectedTreatyForbidsWithId() {
        GameState state = Fixtures.baseState(
                Map.of(TREATY_1, Fixtures.activeTreaty(TreatyType.ALLIANCE, ALPHA, BETA)),
                Fixtures.rich(), Map.of(), List.of());
        ValidationResult.Rejected r = assertRejected(ActionValidator.validate(state, ALPHA,
                new Action.Attack(FLEET_A, new AttackTarget.OnSystem(SYS_B)), PROFILE),
                RejectionReason.TREATY_FORBIDS);
        assertEquals(Optional.of(TREATY_1), r.treatyId());
    }

    // ===== Blockade ===========================================================

    @Test
    void blockadeEnemyRouteAtWarValid() {
        GameState war = Fixtures.baseStateAtWar(ALPHA, BETA);
        assertValid(ActionValidator.validate(war, ALPHA,
                new Action.Blockade(FLEET_A, new BlockadeTarget.OnRoute(ROUTE_B)), PROFILE));
    }

    @Test
    void blockadeEnemySystemAtWarValid() {
        GameState war = Fixtures.baseStateAtWar(ALPHA, BETA);
        assertValid(ActionValidator.validate(war, ALPHA,
                new Action.Blockade(FLEET_A, new BlockadeTarget.OnSystem(SYS_B)), PROFILE));
    }

    @Test
    void blockadeOwnedTargetWithoutWarRejectedNotAtWar() {
        assertRejected(validate(new Action.Blockade(FLEET_A, new BlockadeTarget.OnRoute(ROUTE_B))),
                RejectionReason.NOT_AT_WAR);
    }

    @Test
    void blockadeUnknownRouteRejected() {
        assertRejected(validate(new Action.Blockade(FLEET_A, new BlockadeTarget.OnRoute(
                new com.stellarcompact.engine.state.RouteId("ghost")))),
                RejectionReason.TARGET_UNKNOWN);
    }

    @Test
    void blockadeWithEnemyFleetRejectedNotOwned() {
        assertRejected(validate(new Action.Blockade(FLEET_B, new BlockadeTarget.OnSystem(SYS_B))),
                RejectionReason.NOT_OWNED);
    }

    @Test
    void blockadeOwnSystemRejectedSelfTarget() {
        assertRejected(validate(new Action.Blockade(FLEET_A, new BlockadeTarget.OnSystem(SYS_A))),
                RejectionReason.SELF_TARGET);
    }

    @Test
    void blockadePeaceBoundEnemyRejectedTreatyForbids() {
        GameState state = Fixtures.baseState(
                Map.of(TREATY_1, Fixtures.activeTreaty(TreatyType.CEASEFIRE, ALPHA, BETA)),
                Fixtures.rich(), Map.of(), List.of());
        ValidationResult.Rejected r = assertRejected(ActionValidator.validate(state, ALPHA,
                new Action.Blockade(FLEET_A, new BlockadeTarget.OnRoute(ROUTE_B)), PROFILE),
                RejectionReason.TREATY_FORBIDS);
        assertEquals(Optional.of(TREATY_1), r.treatyId());
    }

    // ===== Raid ===============================================================

    @Test
    void raidEnemyRouteAtWarValid() {
        GameState war = Fixtures.baseStateAtWar(ALPHA, BETA);
        assertValid(ActionValidator.validate(war, ALPHA, new Action.Raid(FLEET_A, ROUTE_B), PROFILE));
    }

    @Test
    void raidOwnedRouteWithoutWarRejectedNotAtWar() {
        // A route always has an owner, so raiding always requires a positive war state.
        assertRejected(validate(new Action.Raid(FLEET_A, ROUTE_B)), RejectionReason.NOT_AT_WAR);
    }

    @Test
    void raidUnknownFleetRejected() {
        assertRejected(validate(new Action.Raid(
                new com.stellarcompact.engine.state.FleetId("ghost"), ROUTE_B)),
                RejectionReason.TARGET_UNKNOWN);
    }

    @Test
    void raidWithEnemyFleetRejectedNotOwned() {
        assertRejected(validate(new Action.Raid(FLEET_B, ROUTE_B)), RejectionReason.NOT_OWNED);
    }

    @Test
    void raidUnknownRouteRejected() {
        assertRejected(validate(new Action.Raid(FLEET_A,
                new com.stellarcompact.engine.state.RouteId("ghost"))),
                RejectionReason.TARGET_UNKNOWN);
    }

    @Test
    void raidPeaceBoundEnemyRejectedTreatyForbids() {
        GameState state = Fixtures.baseState(
                Map.of(TREATY_1, Fixtures.activeTreaty(TreatyType.NON_AGGRESSION, ALPHA, BETA)),
                Fixtures.rich(), Map.of(), List.of());
        ValidationResult.Rejected r = assertRejected(ActionValidator.validate(state, ALPHA,
                new Action.Raid(FLEET_A, ROUTE_B), PROFILE), RejectionReason.TREATY_FORBIDS);
        assertEquals(Optional.of(TREATY_1), r.treatyId());
    }

    // ===== Espionage ==========================================================

    @Test
    void espionageOnOtherValid() {
        assertValid(validate(new Action.Espionage(BETA, EspionageOperation.SCOUT)));
    }

    @Test
    void espionageOnSelfRejectedSelfTarget() {
        assertRejected(validate(new Action.Espionage(ALPHA, EspionageOperation.SABOTAGE)),
                RejectionReason.SELF_TARGET);
    }

    @Test
    void espionageUnknownTargetRejected() {
        assertRejected(validate(new Action.Espionage(
                new com.stellarcompact.engine.state.FactionId("ghost"),
                EspionageOperation.STEAL_INTEL)), RejectionReason.TARGET_UNKNOWN);
    }

    @Test
    void espionageNotBlockedByPeaceTreaty() {
        GameState state = Fixtures.baseState(
                Map.of(TREATY_1, Fixtures.activeTreaty(TreatyType.ALLIANCE, ALPHA, BETA)),
                Fixtures.rich(), Map.of(), List.of());
        assertValid(ActionValidator.validate(state, ALPHA,
                new Action.Espionage(BETA, EspionageOperation.SCOUT), PROFILE));
    }
}
