package com.stellarcompact.orchestrator.sovereign;

import com.stellarcompact.engine.state.GameState;
import com.stellarcompact.engine.state.SystemId;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static com.stellarcompact.orchestrator.sovereign.SovereignFixtures.ALPHA;
import static com.stellarcompact.orchestrator.sovereign.SovereignFixtures.ENEMY;
import static com.stellarcompact.orchestrator.sovereign.SovereignFixtures.FLEET_ENEMY_ADJ;
import static com.stellarcompact.orchestrator.sovereign.SovereignFixtures.FLEET_ENEMY_HIDDEN;
import static com.stellarcompact.orchestrator.sovereign.SovereignFixtures.FLEET_OWN;
import static com.stellarcompact.orchestrator.sovereign.SovereignFixtures.GAMMA;
import static com.stellarcompact.orchestrator.sovereign.SovereignFixtures.OFFER_TO_ALPHA;
import static com.stellarcompact.orchestrator.sovereign.SovereignFixtures.SYS_ADJ_ENEMY;
import static com.stellarcompact.orchestrator.sovereign.SovereignFixtures.SYS_ALLY;
import static com.stellarcompact.orchestrator.sovereign.SovereignFixtures.SYS_HIDDEN_ENEMY;
import static com.stellarcompact.orchestrator.sovereign.SovereignFixtures.SYS_OWN;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Security-critical fog-of-war tests for the authoritative {@link WorldViewBuilder}
 * (card E3-02, security-sensitive). These prove the builder is <b>default-deny</b>:
 * a faction sees its own state in full and only the explicitly-permitted slice of
 * everyone else's, with <em>no</em> leakage of hidden systems, hidden fleets, enemy
 * economy internals, third-party treaties or offers not addressed to it.
 *
 * <p>Scenario ({@link SovereignFixtures#fogScenario()}), from ALPHA's view:
 * ALPHA owns SYS_OWN; GAMMA is an active ally owning SYS_ALLY; ENEMY owns
 * SYS_ADJ_ENEMY (one lane hop from SYS_OWN) and SYS_HIDDEN_ENEMY (no lane, no ally
 * tie). Lane adjacency joins only SYS_OWN and SYS_ADJ_ENEMY.
 */
class WorldViewBuilderTest {

    // --- Positive: own state in full ------------------------------------------

    @Test
    void ownSystemsAndFleetsAndStockpilesAreVisibleInFull() {
        WorldView view = WorldViewBuilder.build(
                SovereignFixtures.fogScenario(), SovereignFixtures.fogAdjacency(), ALPHA);

        assertEquals(ALPHA, view.self().id());
        // Own stockpiles exactly (the observer is never fog-limited from itself).
        assertEquals(100.0, view.self().stockpiles().minerals());
        // Own system surfaced with its planet roster (free-slot detail present).
        Set<SystemId> ownIds = view.ownSystems().stream()
                .map(WorldView.SystemView::id).collect(Collectors.toSet());
        assertEquals(Set.of(SYS_OWN), ownIds);
        assertFalse(view.ownSystems().get(0).ownedPlanets().isEmpty(),
                "own system must carry full planet detail");
        // Own fleet surfaced; no foreign fleet ever is.
        Set<?> fleetIds = view.ownFleets().stream()
                .map(WorldView.FleetView::id).collect(Collectors.toSet());
        assertEquals(Set.of(FLEET_OWN), fleetIds);
    }

    // --- Positive: permitted visibility of others -----------------------------

    @Test
    void sensorAdjacentEnemySystemIsRevealedAsFogLimitedNeighbour() {
        WorldView view = WorldViewBuilder.build(
                SovereignFixtures.fogScenario(), SovereignFixtures.fogAdjacency(), ALPHA);

        WorldView.NeighbourView adj = neighbour(view, SYS_ADJ_ENEMY);
        assertEquals(Optional.of(ENEMY), adj.owner(), "sensor-revealed neighbour shows ownership");
        // It is fog-limited: a NeighbourView carries ownership + rough strength only.
        // There is no planet roster / economy field on NeighbourView at all, so the
        // enemy's internals cannot ride along.
    }

    @Test
    void alliedSystemIsRevealedViaActiveAllianceVision() {
        WorldView view = WorldViewBuilder.build(
                SovereignFixtures.fogScenario(), SovereignFixtures.fogAdjacency(), ALPHA);

        WorldView.NeighbourView ally = neighbour(view, SYS_ALLY);
        assertEquals(Optional.of(GAMMA), ally.owner(), "active ALLIANCE grants shared vision");
    }

    // --- Negative (LEAKAGE): hidden state must be absent -----------------------

    @Test
    void hiddenEnemySystemIsAbsentEntirely() {
        WorldView view = WorldViewBuilder.build(
                SovereignFixtures.fogScenario(), SovereignFixtures.fogAdjacency(), ALPHA);

        Set<SystemId> visibleSystemIds = allVisibleSystemIds(view);
        assertFalse(visibleSystemIds.contains(SYS_HIDDEN_ENEMY),
                "an un-scouted, non-adjacent, non-allied enemy system must NOT appear");
    }

    @Test
    void hiddenEnemyFleetOutOfSensorRangeIsAbsent() {
        WorldView view = WorldViewBuilder.build(
                SovereignFixtures.fogScenario(), SovereignFixtures.fogAdjacency(), ALPHA);

        // No foreign fleet of any kind is ever in the view - not the one in the
        // sensor-adjacent system, and certainly not the one in the hidden system.
        Set<?> fleetIds = view.ownFleets().stream()
                .map(WorldView.FleetView::id).collect(Collectors.toSet());
        assertFalse(fleetIds.contains(FLEET_ENEMY_HIDDEN), "hidden enemy fleet must not leak");
        assertFalse(fleetIds.contains(FLEET_ENEMY_ADJ),
                "even an adjacent enemy fleet is not surfaced - neighbours expose ownership only");
        assertTrue(view.ownFleets().stream().allMatch(f -> f.id().equals(FLEET_OWN)),
                "only own fleets are ever present");
    }

    @Test
    void enemyEconomyInternalsAreNeverInTheView() {
        WorldView view = WorldViewBuilder.build(
                SovereignFixtures.fogScenario(), SovereignFixtures.fogAdjacency(), ALPHA);

        // The view's self is ALPHA; no SelfView for any other faction exists, so
        // ENEMY's distinctive stockpiles (999 energy / 888 minerals ...) and its
        // secret UNLOCKED tech have no field to inhabit. Assert the only stockpile/
        // tech exposed is the observer's own.
        assertEquals(ALPHA, view.self().id());
        assertEquals(100.0, view.self().stockpiles().energy(),
                "only the observer's own energy is exposed, never the enemy's 999");
        // The reputation ledger is the ONLY foreign scalar - and it is public by design.
        // Confirm it carries reputations but no resource/tech detail (RepEntry has none).
        boolean enemyRepPresent = view.reputations().stream()
                .anyMatch(r -> r.factionId().equals(ENEMY));
        assertTrue(enemyRepPresent, "public reputation ledger is common knowledge");
    }

    @Test
    void revealedNeighbourCarriesNoPlanetOrEconomyDetail() {
        WorldView view = WorldViewBuilder.build(
                SovereignFixtures.fogScenario(), SovereignFixtures.fogAdjacency(), ALPHA);

        // SYS_HIDDEN_ENEMY holds a secret 4-slot planet with a SHIPYARD and population
        // 12345; SYS_ADJ_ENEMY (revealed) must surface NONE of that kind of detail.
        // NeighbourView structurally cannot hold planets, so the strongest assertion is
        // that the revealed neighbour is a NeighbourView (ownership-only) and the secret
        // system is absent from ownSystems (which is the only place planets live).
        boolean secretInOwnSystems = view.ownSystems().stream()
                .anyMatch(s -> s.id().equals(SYS_HIDDEN_ENEMY) || s.id().equals(SYS_ADJ_ENEMY));
        assertFalse(secretInOwnSystems,
                "no foreign system (revealed or hidden) may appear in ownSystems with planet detail");
    }

    @Test
    void onlyOffersAddressedToMeAreVisible() {
        WorldView view = WorldViewBuilder.build(
                SovereignFixtures.fogScenario(), SovereignFixtures.fogAdjacency(), ALPHA);

        Set<?> offerIds = view.pendingOffers().stream()
                .map(WorldView.OfferView::id).collect(Collectors.toSet());
        assertEquals(Set.of(OFFER_TO_ALPHA), offerIds,
                "an offer directed to ENEMY and an open book order must not leak to ALPHA");
    }

    @Test
    void onlyTreatiesIAmAPartyToAreVisible() {
        WorldView view = WorldViewBuilder.build(
                SovereignFixtures.fogScenario(), SovereignFixtures.fogAdjacency(), ALPHA);

        // ALPHA is in the ALLIANCE but NOT in the GAMMA-ENEMY non-aggression treaty.
        boolean seesThirdPartyTreaty = view.treaties().stream()
                .anyMatch(t -> t.parties().contains(ENEMY) && !t.parties().contains(ALPHA));
        assertFalse(seesThirdPartyTreaty, "a treaty ALPHA is not a party to must not leak");
        assertTrue(view.treaties().stream().allMatch(t -> t.parties().contains(ALPHA)),
                "every visible treaty must involve the observer");
    }

    // --- Fog source gating -----------------------------------------------------

    @Test
    void proposedAllianceDoesNotGrantVision() {
        WorldView view = WorldViewBuilder.build(
                SovereignFixtures.fogScenarioWithProposedAlliance(),
                SovereignFixtures.fogAdjacency(), ALPHA);

        Set<SystemId> visible = allVisibleSystemIds(view);
        assertFalse(visible.contains(SYS_ALLY),
                "only an ACTIVE alliance grants shared vision; a PROPOSED one must not");
    }

    @Test
    void withoutLaneGraphAdjacencyRevealsNothing() {
        // SystemAdjacency.NONE: no sensor reveal. Only own systems + allied-vision remain.
        WorldView view = WorldViewBuilder.build(SovereignFixtures.fogScenario(), ALPHA);

        Set<SystemId> visible = allVisibleSystemIds(view);
        assertTrue(visible.contains(SYS_OWN), "own system always visible");
        assertTrue(visible.contains(SYS_ALLY), "allied-vision system still visible without lanes");
        assertFalse(visible.contains(SYS_ADJ_ENEMY),
                "without a lane graph the adjacent enemy system must NOT be revealed (default-deny)");
        assertFalse(visible.contains(SYS_HIDDEN_ENEMY), "hidden system stays hidden");
    }

    // --- Determinism / robustness ---------------------------------------------

    @Test
    void buildIsDeterministicAndImmutable() {
        GameState state = SovereignFixtures.fogScenario();
        WorldView a = WorldViewBuilder.build(state, SovereignFixtures.fogAdjacency(), ALPHA);
        WorldView b = WorldViewBuilder.build(state, SovereignFixtures.fogAdjacency(), ALPHA);
        assertEquals(a, b, "same inputs must yield an equal WorldView");
        // The returned lists are unmodifiable (WorldView copies defensively).
        try {
            a.neighbours().add(null);
            assertFalse(true, "neighbours list must be unmodifiable");
        } catch (UnsupportedOperationException expected) {
            // good
        }
    }

    @Test
    void noneAdjacencyIsTheSingletonSentinel() {
        assertSame(List.of(), SystemAdjacency.NONE.neighbours(SYS_OWN),
                "NONE reveals no neighbours for any system");
    }

    // --- helpers ---------------------------------------------------------------

    private static WorldView.NeighbourView neighbour(WorldView view, SystemId id) {
        return view.neighbours().stream()
                .filter(n -> n.systemId().equals(id))
                .findFirst()
                .orElseThrow(() -> new AssertionError("expected neighbour " + id.value() + " to be present"));
    }

    private static Set<SystemId> allVisibleSystemIds(WorldView view) {
        Set<SystemId> ids = view.ownSystems().stream()
                .map(WorldView.SystemView::id).collect(Collectors.toCollection(java.util.HashSet::new));
        view.neighbours().forEach(n -> ids.add(n.systemId()));
        return ids;
    }
}
