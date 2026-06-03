package com.stellarcompact.orchestrator.sovereign;

import com.stellarcompact.engine.config.BalanceProfile;
import com.stellarcompact.engine.state.ActiveSystem;
import com.stellarcompact.engine.state.Biome;
import com.stellarcompact.engine.state.Building;
import com.stellarcompact.engine.state.BuildingStatus;
import com.stellarcompact.engine.state.BuildingType;
import com.stellarcompact.engine.state.Coords;
import com.stellarcompact.engine.state.Faction;
import com.stellarcompact.engine.state.FactionId;
import com.stellarcompact.engine.state.Fleet;
import com.stellarcompact.engine.state.FleetId;
import com.stellarcompact.engine.state.FleetStance;
import com.stellarcompact.engine.state.GameState;
import com.stellarcompact.engine.state.GameStatus;
import com.stellarcompact.engine.state.MarketOrder;
import com.stellarcompact.engine.state.MarketOrderId;
import com.stellarcompact.engine.state.MarketSide;
import com.stellarcompact.engine.state.OrderStatus;
import com.stellarcompact.engine.state.PhysicalResource;
import com.stellarcompact.engine.state.Planet;
import com.stellarcompact.engine.state.PlanetId;
import com.stellarcompact.engine.state.ResourceBundle;
import com.stellarcompact.engine.state.Ship;
import com.stellarcompact.engine.state.SystemId;
import com.stellarcompact.engine.state.TechId;
import com.stellarcompact.engine.state.TechProgress;
import com.stellarcompact.engine.state.TreatyId;
import com.stellarcompact.engine.state.Treaty;
import com.stellarcompact.engine.state.TreatyStatus;
import com.stellarcompact.engine.state.TreatyType;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Hand-authored, deterministic GameState + BalanceProfile builders for the
 * Sovereign tests. No randomness, no I/O. Mirrors the engine test fixtures but
 * lives in this module so the orchestrator tests are self-contained.
 */
final class SovereignFixtures {

    static final FactionId ALPHA = new FactionId("alpha");
    static final FactionId BETA = new FactionId("beta");
    static final SystemId SYS_A = new SystemId("sysA");
    static final SystemId SYS_B = new SystemId("sysB");
    static final SystemId SYS_NEUTRAL = new SystemId("sysN");
    static final PlanetId PLANET_A = new PlanetId("planetA");
    static final PlanetId PLANET_B = new PlanetId("planetB");
    static final PlanetId PLANET_N1 = new PlanetId("planetN1");
    static final PlanetId PLANET_N2 = new PlanetId("planetN2");
    static final FleetId FLEET_A = new FleetId("fleetA");

    // --- Fog-of-war scenario ids (E3-02) ----------------------------------------
    // ALPHA is the observer. GAMMA is an ally (active ALLIANCE), ENEMY a hostile.
    static final FactionId GAMMA = new FactionId("gamma");
    static final FactionId ENEMY = new FactionId("enemy");
    // SYS_OWN: ALPHA's home. SYS_ADJ_ENEMY: an ENEMY system one lane hop from SYS_OWN
    // (revealed by sensor). SYS_ALLY: a GAMMA system (revealed by allied vision).
    // SYS_HIDDEN_ENEMY: an ENEMY system with NO lane to ALPHA and no ally tie -> hidden.
    static final SystemId SYS_OWN = new SystemId("sysOwn");
    static final SystemId SYS_ADJ_ENEMY = new SystemId("sysAdjEnemy");
    static final SystemId SYS_ALLY = new SystemId("sysAlly");
    static final SystemId SYS_HIDDEN_ENEMY = new SystemId("sysHiddenEnemy");
    static final PlanetId PLANET_OWN = new PlanetId("planetOwn");
    static final PlanetId PLANET_SECRET = new PlanetId("planetSecret");
    static final FleetId FLEET_OWN = new FleetId("fleetOwn");
    static final FleetId FLEET_ENEMY_HIDDEN = new FleetId("fleetEnemyHidden");
    static final FleetId FLEET_ENEMY_ADJ = new FleetId("fleetEnemyAdj");
    static final TreatyId TREATY_ALLIANCE = new TreatyId("treatyAlliance");
    static final TreatyId TREATY_THIRD_PARTY = new TreatyId("treatyThirdParty");
    static final MarketOrderId OFFER_TO_ALPHA = new MarketOrderId("offerToAlpha");
    static final MarketOrderId OFFER_TO_ENEMY = new MarketOrderId("offerToEnemy");
    static final MarketOrderId OPEN_ORDER = new MarketOrderId("openOrder");

    private SovereignFixtures() {
    }

    static ResourceBundle rich() {
        return new ResourceBundle(1000, 1000, 1000, 1000, 1000);
    }

    static ResourceBundle poor() {
        return new ResourceBundle(0, 0, 0, 0, 0);
    }

    static Faction faction(FactionId id, ResourceBundle stockpiles) {
        return new Faction(id, "F-" + id.value(), 0.0, stockpiles, Map.<TechId, TechProgress>of());
    }

    static Planet emptyPlanet(PlanetId id, int slots) {
        return new Planet(id, Biome.TERRAN, slots, 100, List.of());
    }

    static Planet fullPlanet(PlanetId id) {
        // A single-slot planet whose only slot is occupied -> no free slot.
        return new Planet(id, Biome.TERRAN, 1, 100,
                List.of(new Building(0, BuildingType.MINE, BuildingStatus.ACTIVE, 0)));
    }

    static ActiveSystem ownedSystem(SystemId id, FactionId owner, List<Planet> planets) {
        return new ActiveSystem(id, "name-" + id.value(), new Coords(0, 0),
                Optional.of(owner), planets, 100, 1.0);
    }

    static ActiveSystem neutralSystem(SystemId id) {
        return new ActiveSystem(id, "name-" + id.value(), new Coords(1, 1),
                Optional.empty(), List.of(), 0, 1.0);
    }

    /** A neutral (unowned) system carrying a colonisable planet roster. */
    static ActiveSystem neutralSystemWithPlanets(SystemId id, List<Planet> planets) {
        return new ActiveSystem(id, "name-" + id.value(), new Coords(1, 1),
                Optional.empty(), planets, 0, 1.0);
    }

    static Fleet fleetAt(FleetId id, FactionId owner, SystemId at) {
        return new Fleet(id, owner, Optional.of(at), Optional.empty(),
                FleetStance.DEFENSIVE, List.of(new Ship("scout", 1)));
    }

    private static GameState build(Map<FactionId, Faction> factions,
                                   Map<SystemId, ActiveSystem> systems,
                                   Map<FleetId, Fleet> fleets) {
        return new GameState(
                42L, 5L, GameStatus.RUNNING, "small-default", 1,
                factions, systems, fleets,
                Map.<TreatyId, Treaty>of(),
                Map.of(),
                Map.of(),
                java.util.Set.of());
    }

    /** ALPHA rich, owns SYS_A with a 3-slot empty planet and a fleet; BETA owns SYS_B. */
    static GameState richTwoFactionState() {
        return build(
                Map.of(ALPHA, faction(ALPHA, rich()), BETA, faction(BETA, rich())),
                Map.of(SYS_A, ownedSystem(SYS_A, ALPHA, List.of(emptyPlanet(PLANET_A, 3))),
                        SYS_B, ownedSystem(SYS_B, BETA, List.of(emptyPlanet(PLANET_B, 3)))),
                Map.of(FLEET_A, fleetAt(FLEET_A, ALPHA, SYS_A)));
    }

    /** ALPHA owns one full-slot planet, has no fleet, no neutral neighbour -> Hold. */
    static GameState idleState() {
        return build(
                Map.of(ALPHA, faction(ALPHA, rich())),
                Map.of(SYS_A, ownedSystem(SYS_A, ALPHA, List.of(fullPlanet(PLANET_A)))),
                Map.of());
    }

    /**
     * ALPHA poor (below build floor), idle fleet parked at its OWN system, and a
     * neutral neighbour it has merely revealed (no fleet there). Post-E10-01 the bot
     * neither builds (poor), colonises (no fleet at the neutral) nor explores (the
     * neutral is already revealed/explored) -> explicit Hold.
     */
    static GameState poorWithRevealedNeutralNeighbour() {
        return build(
                Map.of(ALPHA, faction(ALPHA, poor())),
                Map.of(SYS_A, ownedSystem(SYS_A, ALPHA, List.of(emptyPlanet(PLANET_A, 3))),
                        SYS_NEUTRAL, neutralSystem(SYS_NEUTRAL)),
                Map.of(FLEET_A, fleetAt(FLEET_A, ALPHA, SYS_A)));
    }

    /**
     * ALPHA poor (so the build step is skipped) with an idle fleet PARKED AT a neutral
     * system that carries two planets. The frontier is exhausted (the neutral is
     * already revealed) but it is reachable, so the bot colonises the lowest-id planet
     * of that neutral via the fleet (E10-01).
     */
    static GameState idleFleetParkedAtColonisableNeutral() {
        return build(
                Map.of(ALPHA, faction(ALPHA, poor())),
                Map.of(SYS_A, ownedSystem(SYS_A, ALPHA, List.of(emptyPlanet(PLANET_A, 3))),
                        SYS_NEUTRAL, neutralSystemWithPlanets(SYS_NEUTRAL,
                                List.of(emptyPlanet(PLANET_N2, 2), emptyPlanet(PLANET_N1, 2)))),
                Map.of(FLEET_A, fleetAt(FLEET_A, ALPHA, SYS_NEUTRAL)));
    }

    // === E10-02 build-ladder (F2) scenarios =====================================
    // All three hold ample Minerals (>= the build floor) and a 3-slot empty planet,
    // so the build step fires; only Energy/Food differ, so the chosen building TYPE
    // is the only thing under test. ResourceBundle order is (energy, minerals, food,
    // tech, influence).

    /**
     * ALPHA can afford to build (ample Minerals, a free slot) but its Energy stockpile
     * is at/below the default Energy floor while Food is plentiful. The build-type
     * heuristic must pick a SOLAR_ARRAY - the energy fix, finding F2 - over a MINE.
     */
    static GameState energyStarvedBuildState() {
        return build(
                Map.of(ALPHA, faction(ALPHA, new ResourceBundle(0, 1000, 1000, 0, 0))),
                Map.of(SYS_A, ownedSystem(SYS_A, ALPHA, List.of(emptyPlanet(PLANET_A, 3)))),
                Map.of());
    }

    /**
     * ALPHA can afford to build, Energy is comfortably above the floor, but Food is
     * at/below the default Food floor. The heuristic must pick a FARM over a MINE.
     */
    static GameState foodStarvedBuildState() {
        return build(
                Map.of(ALPHA, faction(ALPHA, new ResourceBundle(1000, 1000, 0, 0, 0))),
                Map.of(SYS_A, ownedSystem(SYS_A, ALPHA, List.of(emptyPlanet(PLANET_A, 3)))),
                Map.of());
    }

    /**
     * ALPHA can afford to build and both Energy and Food are comfortably above their
     * floors, so neither resource is under pressure: the heuristic falls through to the
     * default MINE (the minerals engine).
     */
    static GameState comfortableBuildState() {
        return build(
                Map.of(ALPHA, faction(ALPHA, new ResourceBundle(1000, 1000, 1000, 0, 0))),
                Map.of(SYS_A, ownedSystem(SYS_A, ALPHA, List.of(emptyPlanet(PLANET_A, 3)))),
                Map.of());
    }

    /** A spread of distinct states to prove the bot never throws on varied input. */
    static List<GameState> assortedStates() {
        return List.of(
                richTwoFactionState(),
                idleState(),
                poorWithRevealedNeutralNeighbour(),
                idleFleetParkedAtColonisableNeutral(),
                energyStarvedBuildState(),
                foodStarvedBuildState(),
                comfortableBuildState());
    }

    // === Fog-of-war scenario (E3-02) ============================================

    /** Faction with explicit stockpiles + one UNLOCKED tech (so economy leakage is visible). */
    static Faction factionWithTech(FactionId id, ResourceBundle stockpiles, double reputation,
                                   TechId tech) {
        Map<TechId, TechProgress> progress = tech == null
                ? Map.of()
                : Map.of(tech, new TechProgress(tech, com.stellarcompact.engine.state.TechStatus.UNLOCKED, 0));
        return new Faction(id, "F-" + id.value(), reputation, stockpiles, progress);
    }

    static ActiveSystem populatedSystem(SystemId id, FactionId owner, List<Planet> planets, long pop) {
        return new ActiveSystem(id, "name-" + id.value(), new Coords(2, 2),
                Optional.of(owner), planets, pop, 1.0);
    }

    static Fleet fleetAtFor(FleetId id, FactionId owner, SystemId at, int ships) {
        return new Fleet(id, owner, Optional.of(at), Optional.empty(),
                FleetStance.DEFENSIVE, List.of(new Ship("cruiser", ships)));
    }

    static Treaty alliance(TreatyId id, FactionId a, FactionId b) {
        return new Treaty(id, TreatyType.ALLIANCE, List.of(a, b), Map.of(), 0L, 1000L,
                TreatyStatus.ACTIVE);
    }

    static Treaty nonAggression(TreatyId id, FactionId a, FactionId b, TreatyStatus status) {
        return new Treaty(id, TreatyType.NON_AGGRESSION, List.of(a, b), Map.of(), 0L, 1000L, status);
    }

    static MarketOrder directedOffer(MarketOrderId id, FactionId from, FactionId to) {
        return new MarketOrder(id, SYS_OWN, from, MarketSide.SELL, PhysicalResource.MINERALS,
                10.0, 5.0, 0L, 1000L, Optional.of(to), OrderStatus.OPEN);
    }

    static MarketOrder openOrder(MarketOrderId id, FactionId from) {
        return new MarketOrder(id, SYS_OWN, from, MarketSide.SELL, PhysicalResource.ENERGY,
                10.0, 5.0, 0L, 1000L, Optional.empty(), OrderStatus.OPEN);
    }

    /**
     * The fog-of-war scenario, from ALPHA's perspective:
     * <ul>
     *   <li>ALPHA owns SYS_OWN (a 3-slot planet) and has FLEET_OWN parked there.</li>
     *   <li>GAMMA is ALPHA's ally (active ALLIANCE) and owns SYS_ALLY -> allied vision.</li>
     *   <li>ENEMY owns SYS_ADJ_ENEMY (one lane hop from SYS_OWN, so sensor-revealed)
     *       and SYS_HIDDEN_ENEMY (no lane, no ally tie -> must stay hidden), the latter
     *       holding a secret planet roster + a hidden fleet (FLEET_ENEMY_HIDDEN).</li>
     *   <li>A directed offer to ALPHA, a directed offer to ENEMY, and an open order -
     *       only the first may appear in ALPHA's view.</li>
     *   <li>A NON_AGGRESSION treaty between GAMMA and ENEMY that ALPHA is NOT party to.</li>
     * </ul>
     * Lane adjacency for this scenario: SYS_OWN &lt;-&gt; SYS_ADJ_ENEMY only. Supply it
     * via {@link #fogAdjacency()} to the builder.
     */
    static GameState fogScenario() {
        TechId secretTech = new TechId("warpDrive");
        Map<FactionId, Faction> factions = Map.of(
                ALPHA, factionWithTech(ALPHA, new ResourceBundle(100, 100, 100, 100, 100), 0.5, null),
                GAMMA, factionWithTech(GAMMA, new ResourceBundle(200, 200, 200, 200, 200), 0.7, null),
                ENEMY, factionWithTech(ENEMY, new ResourceBundle(999, 888, 777, 666, 555), -0.9, secretTech));

        Map<SystemId, ActiveSystem> systems = Map.of(
                SYS_OWN, ownedSystem(SYS_OWN, ALPHA, List.of(emptyPlanet(PLANET_OWN, 3))),
                SYS_ALLY, populatedSystem(SYS_ALLY, GAMMA, List.of(emptyPlanet(new PlanetId("planetAlly"), 2)), 50),
                SYS_ADJ_ENEMY, populatedSystem(SYS_ADJ_ENEMY, ENEMY, List.of(emptyPlanet(new PlanetId("planetAdj"), 2)), 70),
                SYS_HIDDEN_ENEMY, populatedSystem(SYS_HIDDEN_ENEMY, ENEMY,
                        List.of(new Planet(PLANET_SECRET, Biome.TERRAN, 4, 9999,
                                List.of(new Building(0, BuildingType.SHIPYARD, BuildingStatus.ACTIVE, 0)))),
                        12345));

        Map<FleetId, Fleet> fleets = Map.of(
                FLEET_OWN, fleetAtFor(FLEET_OWN, ALPHA, SYS_OWN, 3),
                FLEET_ENEMY_ADJ, fleetAtFor(FLEET_ENEMY_ADJ, ENEMY, SYS_ADJ_ENEMY, 9),
                FLEET_ENEMY_HIDDEN, fleetAtFor(FLEET_ENEMY_HIDDEN, ENEMY, SYS_HIDDEN_ENEMY, 50));

        Map<TreatyId, Treaty> treaties = Map.of(
                TREATY_ALLIANCE, alliance(TREATY_ALLIANCE, ALPHA, GAMMA),
                TREATY_THIRD_PARTY, nonAggression(TREATY_THIRD_PARTY, GAMMA, ENEMY, TreatyStatus.ACTIVE));

        Map<MarketOrderId, MarketOrder> orders = Map.of(
                OFFER_TO_ALPHA, directedOffer(OFFER_TO_ALPHA, ENEMY, ALPHA),
                OFFER_TO_ENEMY, directedOffer(OFFER_TO_ENEMY, GAMMA, ENEMY),
                OPEN_ORDER, openOrder(OPEN_ORDER, GAMMA));

        return new GameState(
                42L, 5L, GameStatus.RUNNING, "small-default", 1,
                factions, systems, fleets, treaties, Map.of(), orders, java.util.Set.of());
    }

    /** Lane adjacency for {@link #fogScenario()}: SYS_OWN &lt;-&gt; SYS_ADJ_ENEMY only. */
    static SystemAdjacency fogAdjacency() {
        return SystemAdjacency.of(Map.of(
                SYS_OWN, List.of(SYS_ADJ_ENEMY),
                SYS_ADJ_ENEMY, List.of(SYS_OWN)));
    }

    /**
     * A variant of {@link #fogScenario()} where the ALPHA-GAMMA alliance is only
     * PROPOSED (not ACTIVE), so allied vision must NOT apply and SYS_ALLY stays
     * hidden. Everything else is identical.
     */
    static GameState fogScenarioWithProposedAlliance() {
        GameState base = fogScenario();
        Map<TreatyId, Treaty> treaties = new java.util.LinkedHashMap<>(base.treaties());
        treaties.put(TREATY_ALLIANCE,
                new Treaty(TREATY_ALLIANCE, TreatyType.ALLIANCE, List.of(ALPHA, GAMMA), Map.of(),
                        0L, 1000L, TreatyStatus.PROPOSED));
        return new GameState(
                base.gameSeed(), base.tick(), base.status(), base.balanceProfileName(),
                base.balanceProfileVersion(), base.factions(), base.systems(), base.fleets(),
                treaties, base.routes(), base.marketOrders(), base.wars());
    }

    static BalanceProfile profile() {
        return new BalanceProfile(
                "small-default", 1,
                new BalanceProfile.Resources(Map.of(), Map.of(), 0.1),
                new BalanceProfile.Population(1.0, 0.5, 0.1, 5, Map.of("mine", 1)),
                new BalanceProfile.Market("priceTimePriority", 0.5, "ENERGY"),
                new BalanceProfile.Construction(
                        Map.of("mine", 3),
                        Map.of("mine", new BalanceProfile.ResourceBundle(0, 50, 0, 0, 0)),
                        Map.of("toxic", "arid")),
                new BalanceProfile.Combat(Map.of("scout", 1.0), List.of(0.8, 1.2), 1.2, 0.3, 0.1),
                new BalanceProfile.Movement(0.5, true),
                new BalanceProfile.Tech(Map.of("warpDrive", 100.0), Map.of("warpDrive", 5),
                        Map.of("warpDrive", 1.5), Map.of(), Map.of()),
                new BalanceProfile.Diplomacy(
                        new BalanceProfile.Reputation(1.0, 1.0, 1.0, 1.0), Map.of("nonAggression", 1.0)),
                new BalanceProfile.Victory(
                        new BalanceProfile.Domination(0.6),
                        new BalanceProfile.Economic(1000, 50),
                        new BalanceProfile.Diplomatic(0.6),
                        new BalanceProfile.Survival(1000),
                        new BalanceProfile.Wonder(3, 50),
                        new BalanceProfile.ScoreWeights(1, 1, 1, 1, 1, 1, 1)),
                new BalanceProfile.Tick(1000, 2, 5000));
    }
}
