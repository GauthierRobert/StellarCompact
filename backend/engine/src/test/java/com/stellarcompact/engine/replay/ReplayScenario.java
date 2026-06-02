package com.stellarcompact.engine.replay;

import com.stellarcompact.engine.action.Action;
import com.stellarcompact.engine.action.AttackTarget;
import com.stellarcompact.engine.action.BlockadeTarget;
import com.stellarcompact.engine.action.EspionageOperation;
import com.stellarcompact.engine.config.BalanceProfile;
import com.stellarcompact.engine.config.BalanceProfileLoader;
import com.stellarcompact.engine.map.LaneNetwork;
import com.stellarcompact.engine.resolve.SubmittedAction;
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
import com.stellarcompact.engine.state.Route;
import com.stellarcompact.engine.state.RouteId;
import com.stellarcompact.engine.state.RouteKind;
import com.stellarcompact.engine.state.RouteStatus;
import com.stellarcompact.engine.state.Ship;
import com.stellarcompact.engine.state.SystemId;
import com.stellarcompact.engine.state.TechId;
import com.stellarcompact.engine.state.TechProgress;
import com.stellarcompact.engine.state.TechStatus;
import com.stellarcompact.engine.state.Treaty;
import com.stellarcompact.engine.state.TreatyId;
import com.stellarcompact.engine.state.TreatyStatus;
import com.stellarcompact.engine.state.TreatyType;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The hand-authored, multi-faction, many-tick replay scenario for board card E1-17 - the
 * shared fixture the end-to-end determinism test (and future E3-03 headless runner) drives
 * through {@link ReplayHarness}. TEST scope only; pure builders, no randomness, no I/O
 * beyond reading the shipped {@code small-default} balance profile from the classpath.
 *
 * <p>What the scenario exercises (one moving part per subsystem, so every E1 resolution
 * step contributes to the per-tick hash and/or the public-event stream):
 * <ul>
 *   <li>Economy (E1-06) - every faction owns at least one colonised, built-up system, so
 *       production / upkeep / population dynamics move stockpiles and populations every tick
 *       (the bulk of the per-tick hash churn).</li>
 *   <li>Market (E1-07) - a resting SELL is crossed by a later BUY at a hub and settles through
 *       the ledger, so order-book matching and escrow run.</li>
 *   <li>Development (E1-08) - a Build and a Research begin then advance to completion over
 *       subsequent ticks, and a Terraform steps a toxic world (in-flight progress is hashed
 *       state).</li>
 *   <li>Movement + interception (E1-09) - delta launches a fleet down a contested lane held by
 *       a hostile blocker, forcing a mid-transit interception battle.</li>
 *   <li>Combat + capture (E1-10) - alpha assaults beta front then capital, capturing them
 *       (SystemCaptured) and eventually eliminating beta.</li>
 *   <li>Interdiction (E1-11) - gamma raids and blockades a beta-owned route (seeded steal
 *       through the ledger; route flips to BLOCKADED).</li>
 *   <li>Diplomacy (E1-12) - alpha+gamma sign an ALLIANCE (TreatySigned + AllianceFormed),
 *       alpha breaks a NAP (TreatyBroken) and declares war on beta (WarDeclared) and pays
 *       tribute - moving the reputation ledger and the war set.</li>
 *   <li>Espionage (E1-13) - gamma/delta run seeded SCOUT / STEAL_INTEL / SABOTAGE /
 *       INCITE_UNREST ops on beta, cost escrowed win-or-lose (the seeded rolls are part of the
 *       determinism surface).</li>
 *   <li>Influence (E1-14) - capitals, an active monument, owned routes and active treaties
 *       accrue Influence each tick, then it decays - a live every-tick hash contributor.</li>
 *   <li>Public events (E1-16) - all of the above emit their galaxy-wide events.</li>
 *   <li>Victory / lifecycle (E1-15; game-design 07 section 5) - the shipped profile has
 *       DOMINATION active with elimination on; alpha's captures eliminate beta and drive the
 *       alpha+gamma alliance over the domination threshold, concluding the match
 *       (VictoryAchieved + RUNNING -&gt; CONCLUDED) - the lifecycle transition the card asks
 *       the scenario to approach.</li>
 * </ul>
 *
 * <p>Faction ids are deliberately {@code alpha < beta < gamma < delta} so the resolver
 * (step, actor, submissionOrder) ordering and every per-faction fold has a clear, stable
 * tie-break. {@link #SEED} was chosen so the seeded combat / espionage rolls produce a
 * lively-but-decisive match within the tick budget.
 */
final class ReplayScenario {

    private ReplayScenario() {
    }

    // ===== identities ==========================================================

    static final FactionId ALPHA = new FactionId("alpha");
    static final FactionId BETA = new FactionId("beta");
    static final FactionId GAMMA = new FactionId("gamma");
    static final FactionId DELTA = new FactionId("delta");

    static final SystemId ALPHA_HOME = new SystemId("sys-alpha-home");
    static final SystemId ALPHA_FWD = new SystemId("sys-alpha-fwd");
    static final SystemId BETA_FRONT = new SystemId("sys-beta-front");
    static final SystemId BETA_HOME = new SystemId("sys-beta-home");
    static final SystemId GAMMA_HOME = new SystemId("sys-gamma-home");
    static final SystemId GAMMA_HUB = new SystemId("sys-gamma-hub");
    static final SystemId DELTA_HOME = new SystemId("sys-delta-home");
    static final SystemId FRONTIER = new SystemId("sys-frontier");

    static final FleetId ALPHA_STRIKE = new FleetId("fleet-alpha-strike");
    static final FleetId BETA_GARRISON = new FleetId("fleet-beta-garrison");
    static final FleetId GAMMA_RAIDER = new FleetId("fleet-gamma-raider");
    static final FleetId DELTA_EXPED = new FleetId("fleet-delta-exped");
    static final FleetId DELTA_RUNNER = new FleetId("fleet-delta-runner");

    static final RouteId BETA_ROUTE = new RouteId("route-beta-commerce");
    static final RouteId GAMMA_ROUTE = new RouteId("route-gamma-commerce");

    static final TreatyId AG_ALLIANCE = new TreatyId("treaty-alpha-gamma-alliance");
    static final TreatyId AB_NAP = new TreatyId("treaty-alpha-beta-nap");

    static final MarketOrderId SELL_MINERALS = new MarketOrderId("order-gamma-sell");
    static final MarketOrderId BUY_MINERALS = new MarketOrderId("order-delta-buy");

    static final PlanetId ALPHA_P = new PlanetId("planet-alpha-home");
    static final PlanetId ALPHA_FWD_P = new PlanetId("planet-alpha-fwd");
    static final PlanetId BETA_FRONT_P = new PlanetId("planet-beta-front");
    static final PlanetId BETA_HOME_P = new PlanetId("planet-beta-home");
    static final PlanetId GAMMA_P = new PlanetId("planet-gamma-home");
    static final PlanetId GAMMA_HUB_P = new PlanetId("planet-gamma-hub");
    static final PlanetId DELTA_P = new PlanetId("planet-delta-home");

    static final TechId IMPROVED_EXTRACTION = new TechId("improvedExtraction");
    static final TechId DIPLOMATIC_CORPS = new TechId("diplomaticCorps");

    static final long SEED = 0xC0FFEE123L;
    static final long START_TICK = 0L;
    static final int TICKS = 14;

    // ===== balance profile (the shipped small-default; rule 6) =================

    static BalanceProfile profile() {
        try (InputStream in = ReplayScenario.class.getResourceAsStream("/balance/small-default.json")) {
            if (in == null) {
                throw new IllegalStateException("missing /balance/small-default.json");
            }
            return BalanceProfileLoader.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // ===== lane network ========================================================

    /**
     * The static per-match lane graph. The alpha-fwd to beta-front lane (length 2) is the
     * contested lane delta's expeditionary fleet runs while gamma's raider holds beta-front
     * hostile, forcing interception. Built fresh each call so the two independent replay runs
     * project the same geometry from scratch.
     */
    static LaneNetwork lanes() {
        return LaneNetwork.builder()
                .addLane(ALPHA_HOME, ALPHA_FWD, 1)
                .addLane(ALPHA_FWD, BETA_FRONT, 2)
                .addLane(BETA_FRONT, BETA_HOME, 2)
                .addLane(GAMMA_HOME, GAMMA_HUB, 1)
                .addLane(GAMMA_HUB, BETA_FRONT, 2)
                .addLane(DELTA_HOME, FRONTIER, 1)
                .addLane(FRONTIER, BETA_FRONT, 2)
                .build();
    }

    // ===== initial state =======================================================

    private static ResourceBundle stock(double e, double m, double f, double t, double inf) {
        return new ResourceBundle(e, m, f, t, inf);
    }

    private static Building active(int slot, BuildingType type) {
        return new Building(slot, type, BuildingStatus.ACTIVE, 0);
    }

    private static Faction faction(FactionId id, ResourceBundle stockpiles,
                                   Map<TechId, TechProgress> tech) {
        return new Faction(id, "Sovereign-" + id.value(), 0.0, stockpiles, tech);
    }

    /** A producing colony: a planet with active buildings, population, full loyalty. */
    private static ActiveSystem colony(SystemId id, FactionId owner, PlanetId pid, Biome biome,
                                       long planetPop, List<Building> buildings, long sysPop) {
        Planet planet = new Planet(pid, biome, 6, planetPop, buildings);
        return new ActiveSystem(id, "sys-" + id.value(), new Coords(id.value().length(), 1),
                Optional.of(owner), List.of(planet), sysPop, 1.0);
    }

    private static Fleet parked(FleetId id, FactionId owner, SystemId at, FleetStance stance,
                                List<Ship> ships) {
        return new Fleet(id, owner, Optional.of(at), Optional.empty(), stance, ships);
    }

    /**
     * The rich starting snapshot. Wealthy stockpiles so spends (build/research/terraform/trade/
     * espionage/tribute) clear and the focus stays on determinism, not bankruptcy edge cases.
     */
    static GameState initialState() {
        Map<TechId, TechProgress> gammaTech = Map.of(
                IMPROVED_EXTRACTION, new TechProgress(IMPROVED_EXTRACTION, TechStatus.UNLOCKED, 0));
        Map<FactionId, Faction> factions = new LinkedHashMap<>();
        factions.put(ALPHA, faction(ALPHA, stock(5000, 5000, 5000, 5000, 200), Map.of()));
        factions.put(BETA, faction(BETA, stock(5000, 5000, 5000, 5000, 50), Map.of()));
        factions.put(GAMMA, faction(GAMMA, stock(5000, 5000, 5000, 5000, 100), gammaTech));
        factions.put(DELTA, faction(DELTA, stock(5000, 5000, 5000, 5000, 50), Map.of()));

        Map<SystemId, ActiveSystem> systems = new LinkedHashMap<>();
        // alpha: a monument capital (Influence + a Wonder stage) plus a forward base.
        systems.put(ALPHA_HOME, colony(ALPHA_HOME, ALPHA, ALPHA_P, Biome.ARID, 4,
                List.of(active(0, BuildingType.MINE), active(1, BuildingType.FARM),
                        active(2, BuildingType.MONUMENT), active(3, BuildingType.SHIPYARD)), 4));
        systems.put(ALPHA_FWD, colony(ALPHA_FWD, ALPHA, ALPHA_FWD_P, Biome.TERRAN, 2,
                List.of(active(0, BuildingType.SOLAR_ARRAY), active(1, BuildingType.FARM)), 2));
        // beta: a lightly-held front (no garrison) alpha captures first, then the capital.
        systems.put(BETA_FRONT, colony(BETA_FRONT, BETA, BETA_FRONT_P, Biome.TERRAN, 3,
                List.of(active(0, BuildingType.MINE), active(1, BuildingType.FARM)), 3));
        systems.put(BETA_HOME, colony(BETA_HOME, BETA, BETA_HOME_P, Biome.ARID, 5,
                List.of(active(0, BuildingType.MINE), active(1, BuildingType.FARM),
                        active(2, BuildingType.RESEARCH_LAB)), 5));
        // gamma: home + a market hub for the order book.
        systems.put(GAMMA_HOME, colony(GAMMA_HOME, GAMMA, GAMMA_P, Biome.ARID, 3,
                List.of(active(0, BuildingType.MINE), active(1, BuildingType.FARM)), 3));
        systems.put(GAMMA_HUB, colony(GAMMA_HUB, GAMMA, GAMMA_HUB_P, Biome.TERRAN, 2,
                List.of(active(0, BuildingType.MARKET_HUB), active(1, BuildingType.FARM)), 2));
        // delta: home with a terraformable toxic world it will improve.
        systems.put(DELTA_HOME, colony(DELTA_HOME, DELTA, DELTA_P, Biome.TOXIC, 1,
                List.of(active(0, BuildingType.TERRAFORMER), active(1, BuildingType.FARM)), 1));
        // a neutral frontier (no owner) so habitable-system count exceeds the controlled count.
        systems.put(FRONTIER, new ActiveSystem(FRONTIER, "sys-frontier", new Coords(9, 9),
                Optional.empty(), List.of(), 0, 1.0));

        Map<FleetId, Fleet> fleets = new LinkedHashMap<>();
        fleets.put(ALPHA_STRIKE, parked(ALPHA_STRIKE, ALPHA, ALPHA_FWD, FleetStance.AGGRESSIVE,
                List.of(new Ship("cruiser", 6), new Ship("corvette", 4))));
        fleets.put(BETA_GARRISON, parked(BETA_GARRISON, BETA, BETA_HOME, FleetStance.DEFENSIVE,
                List.of(new Ship("corvette", 2))));
        // gamma's raider sits at beta-front (the contested lane target) to intercept traffic.
        fleets.put(GAMMA_RAIDER, parked(GAMMA_RAIDER, GAMMA, BETA_FRONT, FleetStance.AGGRESSIVE,
                List.of(new Ship("corvette", 3))));
        fleets.put(DELTA_EXPED, parked(DELTA_EXPED, DELTA, FRONTIER, FleetStance.EVASIVE,
                List.of(new Ship("corvette", 2))));
        fleets.put(DELTA_RUNNER, parked(DELTA_RUNNER, DELTA, DELTA_HOME, FleetStance.BALANCED,
                List.of(new Ship("scout", 1))));

        Map<RouteId, Route> routes = new LinkedHashMap<>();
        routes.put(BETA_ROUTE, new Route(BETA_ROUTE, BETA, BETA_FRONT, BETA_HOME,
                RouteKind.COMMERCIAL, List.of(PhysicalResource.MINERALS), 80.0, RouteStatus.ACTIVE));
        routes.put(GAMMA_ROUTE, new Route(GAMMA_ROUTE, GAMMA, GAMMA_HOME, GAMMA_HUB,
                RouteKind.COMMERCIAL, List.of(PhysicalResource.MINERALS), 60.0, RouteStatus.ACTIVE));

        Map<TreatyId, Treaty> treaties = new LinkedHashMap<>();
        // a PROPOSED alpha+gamma alliance gamma accepts on tick 1 (TreatySigned + AllianceFormed).
        treaties.put(AG_ALLIANCE, new Treaty(AG_ALLIANCE, TreatyType.ALLIANCE,
                List.of(ALPHA, GAMMA), Map.of(), START_TICK, Long.MAX_VALUE, TreatyStatus.PROPOSED));
        // an ACTIVE alpha+beta non-aggression pact alpha breaks before going to war.
        treaties.put(AB_NAP, new Treaty(AB_NAP, TreatyType.NON_AGGRESSION,
                List.of(ALPHA, BETA), Map.of(), START_TICK, 200L, TreatyStatus.ACTIVE));

        // market book: a resting gamma SELL @2.0 (maker, placed earlier) crossed by a delta
        // BUY @3.0 (taker). They cross on the first resolved tick and clear at the resting 2.0,
        // settling minerals to delta and energy (the currency) to gamma through the ledger -
        // exercising the per-hub order-book matcher and escrow end-to-end.
        Map<MarketOrderId, MarketOrder> book = new LinkedHashMap<>();
        book.put(SELL_MINERALS, new MarketOrder(SELL_MINERALS, GAMMA_HUB, GAMMA, MarketSide.SELL,
                PhysicalResource.MINERALS, 40, 2.0, START_TICK, 1_000L, Optional.empty(),
                OrderStatus.OPEN));
        book.put(BUY_MINERALS, new MarketOrder(BUY_MINERALS, GAMMA_HUB, DELTA, MarketSide.BUY,
                PhysicalResource.MINERALS, 40, 3.0, START_TICK + 1, 1_000L, Optional.empty(),
                OrderStatus.OPEN));

        return new GameState(SEED, START_TICK, GameStatus.RUNNING, "small-default", 1,
                factions, systems, fleets, treaties, routes, book, Set.of());
    }

    // ===== the recorded action log (keyed by absolute tick) ====================

    private static SubmittedAction sa(FactionId actor, Action action, int order) {
        return new SubmittedAction(actor, action, order);
    }

    /**
     * The full, hand-authored action log: an ordered batch per tick. The resolver imposes its
     * own canonical (step, actor, submissionOrder) order, so the within-batch order is only the
     * per-faction tie-break.
     */
    static Map<Long, List<SubmittedAction>> actionLog() {
        Map<Long, List<SubmittedAction>> log = new LinkedHashMap<>();

        // tick 1: diplomacy sign + development begins + terraform.
        log.put(1L, List.of(
                sa(GAMMA, new Action.AcceptTreaty(AG_ALLIANCE), 0),
                sa(ALPHA, new Action.Build(ALPHA_FWD_P, 2, BuildingType.MINE), 0),
                sa(ALPHA, new Action.Research(DIPLOMATIC_CORPS), 1),
                sa(DELTA, new Action.Terraform(DELTA_P), 0)));

        // tick 2: break NAP, declare war, launch the contested-lane fleet.
        log.put(2L, List.of(
                sa(ALPHA, new Action.BreakTreaty(AB_NAP), 0),
                sa(ALPHA, new Action.DeclareWar(BETA), 1),
                sa(DELTA, new Action.MoveFleet(DELTA_EXPED, List.of(BETA_FRONT), BETA_FRONT), 0)));

        // tick 3: capture beta-front, interdict beta's route, scout, tribute, establish a route.
        log.put(3L, List.of(
                sa(ALPHA, new Action.Attack(ALPHA_STRIKE, new AttackTarget.OnSystem(BETA_FRONT)), 0),
                sa(ALPHA, new Action.Tribute(GAMMA, stock(0, 100, 0, 0, 0)), 1),
                sa(GAMMA, new Action.Raid(GAMMA_RAIDER, BETA_ROUTE), 0),
                sa(GAMMA, new Action.Blockade(GAMMA_RAIDER, new BlockadeTarget.OnRoute(BETA_ROUTE)), 1),
                sa(GAMMA, new Action.Espionage(BETA, EspionageOperation.SCOUT), 2),
                sa(DELTA, new Action.EstablishRoute(DELTA_HOME, FRONTIER, RouteKind.COMMERCIAL,
                        List.of(PhysicalResource.MINERALS), 30.0), 0)));

        // tick 4: a crossing BUY settles against gamma's resting SELL; two more seeded ops.
        log.put(4L, List.of(
                sa(DELTA, new Action.Espionage(BETA, EspionageOperation.STEAL_INTEL), 0),
                sa(GAMMA, new Action.Espionage(BETA, EspionageOperation.SABOTAGE), 0)));

        // tick 5: assault the beta capital (capture -> beta eliminated -> domination victory),
        // plus a final unrest op.
        log.put(5L, List.of(
                sa(ALPHA, new Action.Attack(ALPHA_STRIKE, new AttackTarget.OnSystem(BETA_HOME)), 0),
                sa(GAMMA, new Action.Espionage(BETA, EspionageOperation.INCITE_UNREST), 0)));

        return log;
    }

    /** All four faction ids in sorted order (the resolver per-faction fold order). */
    static List<FactionId> factionIds() {
        return List.of(ALPHA, BETA, GAMMA, DELTA);
    }
}
