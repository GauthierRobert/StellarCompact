package com.stellarcompact.orchestrator.match;

import com.stellarcompact.engine.config.BalanceProfile;
import com.stellarcompact.engine.map.LaneNetwork;
import com.stellarcompact.engine.resolve.PublicEvent;
import com.stellarcompact.engine.resolve.SubmittedAction;
import com.stellarcompact.engine.state.ActiveSystem;
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
import com.stellarcompact.engine.state.ResourceBundle;
import com.stellarcompact.engine.state.Route;
import com.stellarcompact.engine.state.RouteId;
import com.stellarcompact.engine.state.Ship;
import com.stellarcompact.engine.state.SystemId;
import com.stellarcompact.engine.state.TechId;
import com.stellarcompact.engine.state.TechProgress;
import com.stellarcompact.engine.state.Treaty;
import com.stellarcompact.engine.state.TreatyId;
import com.stellarcompact.engine.state.WarState;
import com.stellarcompact.galaxy.gen.Cell;
import com.stellarcompact.galaxy.gen.Lane;
import com.stellarcompact.galaxy.gen.LaneGraph;
import com.stellarcompact.galaxy.gen.LaneGraphGenerator;
import com.stellarcompact.galaxy.gen.Star;
import com.stellarcompact.galaxy.gen.StarFieldGenerator;
import com.stellarcompact.orchestrator.promotion.PromotionService;
import com.stellarcompact.orchestrator.promotion.SystemAddress;
import com.stellarcompact.orchestrator.sovereign.ScriptedSovereign;
import com.stellarcompact.orchestrator.sovereign.Sovereign;
import com.stellarcompact.orchestrator.sovereign.SystemAdjacency;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * GOAL DRIVER (not a permanent unit test): "simulate a match for 1h between 3 agents
 * and evaluate the changes to make after." Reuses the deterministic
 * {@link HeadlessMatchRunner} (E3-03) so a literal hour of wall-clock is unnecessary —
 * the small-galaxy tick cadence is {@code TickProperties.DEFAULT_INTERVAL = 5s}, so one
 * game-hour is {@code 3600s / 5s = }{@value #ONE_GAME_HOUR_TICKS} resolved ticks, which
 * the pure single-threaded runner executes in milliseconds.
 *
 * <p>It seats THREE {@link ScriptedSovereign}s on three well-separated, galaxy-generated
 * home systems, runs the full hour, verifies the run is deterministic by replaying the
 * recorded {@code (seed, action log)}, and writes a before/after evaluation report to
 * {@code target/three-agent-hour-report.txt} (and stdout) for the post-match analysis.
 */
class ThreeAgentHourMatchTest {

    /** One game-hour at the small-galaxy 5s cadence (TickProperties.DEFAULT_INTERVAL). */
    static final int ONE_GAME_HOUR_TICKS = 720;

    private static final long SEED = 0x3A9E27C0FFEE3L;
    private static final FactionId ALPHA = new FactionId("alpha");
    private static final FactionId BETA = new FactionId("beta");
    private static final FactionId GAMMA = new FactionId("gamma");
    private static final List<FactionId> SEATS = List.of(ALPHA, BETA, GAMMA);

    private static final int CELL_MIN = 0;
    private static final int CELL_MAX = 4; // 5x5 region — generous, so 3 separated homes exist
    private static final int MIN_HOME_SEPARATION = 2;

    @Test
    void simulateOneHourThreeAgentMatchAndWriteEvaluation() throws IOException {
        GameState initial = initialState();
        BalanceProfile profile = GalaxyMatchScenario.profile();
        LaneNetwork lanes = laneNetwork();
        SystemAdjacency adjacency = adjacency();

        List<Sovereign> bots = SEATS.stream()
                .map(id -> (Sovereign) new ScriptedSovereign(id))
                .toList();

        MatchRecord record = HeadlessMatchRunner.run(
                initial, bots, profile, lanes, adjacency, ONE_GAME_HOUR_TICKS);

        // Determinism proof: replay the recorded log reproduces every tick hash.
        List<String> replayed = HeadlessMatchRunner.replay(record, initialState(), profile, lanes);
        assertEquals(record.perTickHashes(), replayed,
                "replay of the recorded (seed, action log) must reproduce every tick hash");
        assertNotNull(record.outcome());

        String report = buildReport(initial, record);
        Path out = Path.of("target", "three-agent-hour-report.txt");
        Files.createDirectories(out.getParent());
        Files.writeString(out, report);
        System.out.println(report);
    }

    // ===== report =============================================================

    private static String buildReport(GameState initial, MatchRecord record) {
        GameState fin = record.finalState();
        StringBuilder b = new StringBuilder();
        b.append("============================================================\n");
        b.append(" 3-AGENT 1-HOUR MATCH — EVALUATION REPORT\n");
        b.append("============================================================\n");
        b.append("seed              : ").append(Long.toHexString(record.gameSeed())).append('\n');
        b.append("balance profile   : ").append(record.profileName()).append('\n');
        b.append("game-hour mapping  : ").append(ONE_GAME_HOUR_TICKS)
                .append(" ticks @ 5s/tick = 3600s (1h real-time)\n");
        b.append("outcome           : ").append(record.outcome()).append('\n');
        b.append("ticks resolved    : ").append(record.tickCount()).append('\n');
        b.append("final status      : ").append(fin.status()).append('\n');
        b.append("final hash        : ").append(record.perTickHashes().isEmpty() ? "-"
                : record.perTickHashes().get(record.perTickHashes().size() - 1)).append('\n');
        b.append("determinism       : VERIFIED (replay reproduced all ")
                .append(record.tickCount()).append(" tick hashes)\n\n");

        // Activity profile across the hour.
        long actingTicks = record.ticks().stream().filter(t -> !t.actions().isEmpty()).count();
        long lastActingTick = record.ticks().stream()
                .filter(t -> !t.actions().isEmpty())
                .mapToLong(MatchRecord.TickRecord::tick)
                .max().orElse(-1);
        long totalActions = record.ticks().stream()
                .mapToLong(t -> t.actions().size()).sum();
        b.append("ACTIVITY\n");
        b.append("  ticks with >=1 validated action : ").append(actingTicks)
                .append(" / ").append(record.tickCount()).append('\n');
        b.append("  last acting tick (plateau point): ").append(lastActingTick).append('\n');
        b.append("  total validated actions         : ").append(totalActions).append('\n');

        // Action-type distribution (what the agents actually managed to do).
        Map<String, Long> byType = new TreeMap<>();
        for (MatchRecord.TickRecord t : record.ticks()) {
            for (SubmittedAction sa : t.actions()) {
                byType.merge(sa.action().getClass().getSimpleName(), 1L, Long::sum);
            }
        }
        b.append("  validated action mix            : ")
                .append(byType.isEmpty() ? "(none)" : byType).append('\n');

        // NOTE: Hold is never *submitted* (the orchestrator submits only primary actions;
        // a Hold is the bot idling). So "ticks with 0 actions" == every seat Held.
        long idleTicks = record.tickCount() - actingTicks;
        b.append("  fully-idle ticks (all 3 Held)   : ").append(idleTicks).append('\n');

        // Public events.
        Map<String, Long> events = new TreeMap<>();
        for (PublicEvent e : record.eventLog()) {
            events.merge(e.type(), 1L, Long::sum);
        }
        b.append("  public events                   : ")
                .append(events.isEmpty() ? "(none)" : events).append("\n\n");

        // Per-faction before/after.
        b.append("PER-FACTION (initial -> final)\n");
        for (FactionId id : SEATS) {
            Faction f0 = initial.factions().get(id);
            Faction f1 = fin.factions().get(id);
            b.append("  ").append(id.value()).append('\n');
            b.append("    reputation   : ").append(fmt(f0.reputation()))
                    .append(" -> ").append(fmt(f1.reputation())).append('\n');
            b.append("    stockpiles   : ").append(res(f0.stockpiles()))
                    .append("\n                 -> ").append(res(f1.stockpiles())).append('\n');
            int ownedSystems0 = ownedSystems(initial, id);
            int ownedSystems1 = ownedSystems(fin, id);
            int planets0 = ownedPlanets(initial, id);
            int planets1 = ownedPlanets(fin, id);
            int buildings0 = ownedBuildings(initial, id);
            int buildings1 = ownedBuildings(fin, id);
            int freeSlots1 = freeSlots(fin, id);
            int fleets1 = (int) fin.fleets().values().stream().filter(fl -> fl.owner().equals(id)).count();
            long idleFleets1 = fin.fleets().values().stream()
                    .filter(fl -> fl.owner().equals(id))
                    .filter(fl -> fl.location().isPresent() && fl.enroutePath().isEmpty())
                    .count();
            b.append("    owned systems: ").append(ownedSystems0).append(" -> ").append(ownedSystems1).append('\n');
            b.append("    owned planets: ").append(planets0).append(" -> ").append(planets1).append('\n');
            b.append("    buildings    : ").append(buildings0).append(" -> ").append(buildings1)
                    .append("  (free planet slots remaining: ").append(freeSlots1).append(")\n");
            b.append("    fleets       : ").append(fleets1).append(" (idle/parked: ").append(idleFleets1).append(")\n");
        }
        b.append("\nGALAXY\n");
        b.append("  active systems total : ").append(fin.systems().size()).append('\n');
        long neutral = fin.systems().values().stream().filter(s -> s.owner().isEmpty()).count();
        b.append("  still-neutral systems: ").append(neutral).append('\n');
        b.append("============================================================\n");
        return b.toString();
    }

    private static int ownedSystems(GameState s, FactionId id) {
        return (int) s.systems().values().stream()
                .filter(sys -> sys.owner().map(o -> o.equals(id)).orElse(false)).count();
    }

    private static int ownedPlanets(GameState s, FactionId id) {
        return s.systems().values().stream()
                .filter(sys -> sys.owner().map(o -> o.equals(id)).orElse(false))
                .mapToInt(sys -> sys.planets().size()).sum();
    }

    private static int ownedBuildings(GameState s, FactionId id) {
        return s.systems().values().stream()
                .filter(sys -> sys.owner().map(o -> o.equals(id)).orElse(false))
                .flatMap(sys -> sys.planets().stream())
                .mapToInt(p -> p.buildings().size()).sum();
    }

    private static int freeSlots(GameState s, FactionId id) {
        return s.systems().values().stream()
                .filter(sys -> sys.owner().map(o -> o.equals(id)).orElse(false))
                .flatMap(sys -> sys.planets().stream())
                .mapToInt(p -> Math.max(0, p.slotsTotal() - p.buildings().size())).sum();
    }

    private static String res(ResourceBundle r) {
        return "E=" + fmt(r.energy()) + " M=" + fmt(r.minerals()) + " F=" + fmt(r.food())
                + " I=" + fmt(r.influence());
    }

    private static String fmt(double d) {
        return String.format("%.1f", d);
    }

    // ===== 3-faction galaxy scenario (pure function of SEED) ===================

    private static List<Star> regionStars() {
        List<Star> stars = new ArrayList<>();
        for (int cx = CELL_MIN; cx <= CELL_MAX; cx++) {
            for (int cy = CELL_MIN; cy <= CELL_MAX; cy++) {
                stars.addAll(StarFieldGenerator.generate(SEED, new Cell(cx, cy)));
            }
        }
        stars.sort(Comparator.comparingLong(Star::id));
        if (stars.size() < 6) {
            throw new IllegalStateException("region too sparse for a 3-faction scenario: "
                    + stars.size() + " stars (need >= 6).");
        }
        return stars;
    }

    private static LaneGraph laneGraph() {
        return LaneGraphGenerator.generate(SEED, regionStars());
    }

    /** Three well-separated home star ids; falls back to lowest distinct ids if sparse. */
    private static List<Long> homeStarIds() {
        LaneGraph graph = laneGraph();
        List<Long> ids = new ArrayList<>(graph.starIds());
        ids.sort(Comparator.naturalOrder());
        List<Long> homes = new ArrayList<>();
        homes.add(ids.get(0));
        for (long candidate : ids) {
            if (homes.contains(candidate)) {
                continue;
            }
            boolean farEnough = homes.stream()
                    .allMatch(h -> hops(graph, h, candidate, MIN_HOME_SEPARATION) >= MIN_HOME_SEPARATION);
            if (farEnough) {
                homes.add(candidate);
                if (homes.size() == 3) {
                    return homes;
                }
            }
        }
        for (long candidate : ids) {
            if (homes.size() == 3) {
                break;
            }
            if (!homes.contains(candidate)) {
                homes.add(candidate);
            }
        }
        return homes;
    }

    private static int hops(LaneGraph graph, long from, long to, int limit) {
        if (from == to) {
            return 0;
        }
        Map<Long, Integer> seen = new LinkedHashMap<>();
        List<Long> frontier = new ArrayList<>();
        seen.put(from, 0);
        frontier.add(from);
        while (!frontier.isEmpty()) {
            List<Long> next = new ArrayList<>();
            for (long node : frontier) {
                int d = seen.get(node);
                if (d >= limit) {
                    continue;
                }
                for (long nb : graph.neighbours(node)) {
                    if (nb == to) {
                        return d + 1;
                    }
                    if (!seen.containsKey(nb)) {
                        seen.put(nb, d + 1);
                        next.add(nb);
                    }
                }
            }
            frontier = next;
        }
        return limit;
    }

    private static SystemId neutralFor(FactionId id) {
        return new SystemId("neutral-near-" + id.value());
    }

    private static LaneNetwork laneNetwork() {
        LaneGraph graph = laneGraph();
        List<Long> homes = homeStarIds();
        List<SystemId> homeIds = homes.stream().map(SystemAddress::toSystemId).toList();
        LaneNetwork.Builder builder = LaneNetwork.builder();

        // Keep real lanes whose both endpoints are home systems.
        for (Lane lane : graph.lanes()) {
            SystemId a = SystemAddress.toSystemId(lane.a());
            SystemId b = SystemAddress.toSystemId(lane.b());
            if (homeIds.contains(a) && homeIds.contains(b)) {
                builder.addLane(a, b, lane.lengthTicks());
            }
        }
        // Chain the three homes so the region is connected even without proximity lanes.
        for (int i = 0; i + 1 < homes.size(); i++) {
            SystemId a = homeIds.get(i);
            SystemId c = homeIds.get(i + 1);
            int len = Math.max(1, hops(graph, homes.get(i), homes.get(i + 1), 8));
            builder.addLane(a, c, len);
        }
        // One neutral scenery system per home so each bot's Explore branch is reachable.
        for (SystemId home : homeIds) {
            builder.addLane(home, neutralForHome(home), 1);
        }
        return builder.build();
    }

    private static SystemId neutralForHome(SystemId home) {
        if (home.equals(SystemAddress.toSystemId(homeStarIds().get(0)))) {
            return neutralFor(ALPHA);
        }
        if (home.equals(SystemAddress.toSystemId(homeStarIds().get(1)))) {
            return neutralFor(BETA);
        }
        return neutralFor(GAMMA);
    }

    private static SystemAdjacency adjacency() {
        LaneNetwork lanes = laneNetwork();
        Map<SystemId, List<SystemId>> adj = new LinkedHashMap<>();
        for (SystemId id : initialState().systems().keySet()) {
            adj.put(id, lanes.neighbours(id));
        }
        return SystemAdjacency.of(adj);
    }

    private static ResourceBundle rich() {
        return new ResourceBundle(5000, 5000, 5000, 5000, 100);
    }

    private static Faction faction(FactionId id) {
        return new Faction(id, "Sovereign-" + id.value(), 0.0, rich(),
                Map.<TechId, TechProgress>of());
    }

    private static Fleet parkedFleet(FactionId owner, SystemId at) {
        return new Fleet(new FleetId("fleet-" + owner.value()), owner, Optional.of(at),
                Optional.empty(), FleetStance.BALANCED, List.of(new Ship("scout", 1)));
    }

    private static ActiveSystem neutral(SystemId id) {
        return new ActiveSystem(id, "neutral-" + id.value(), new Coords(id.value().length(), 7),
                Optional.empty(), List.of(), 0L, 1.0);
    }

    static GameState initialState() {
        PromotionService promotion = new PromotionService();
        List<Long> homes = homeStarIds();

        Map<FactionId, Faction> factions = new LinkedHashMap<>();
        Map<SystemId, ActiveSystem> systems = new LinkedHashMap<>();
        Map<FleetId, Fleet> fleets = new LinkedHashMap<>();

        for (int i = 0; i < SEATS.size(); i++) {
            FactionId id = SEATS.get(i);
            SystemId home = SystemAddress.toSystemId(homes.get(i));
            factions.put(id, faction(id));
            systems.put(home, promotion.promote(SEED, home, id));
            SystemId nb = neutralFor(id);
            systems.put(nb, neutral(nb));
            fleets.put(new FleetId("fleet-" + id.value()), parkedFleet(id, home));
        }

        return new GameState(SEED, 0L, GameStatus.RUNNING, "small-default", 1,
                factions, systems, fleets,
                Map.<TreatyId, Treaty>of(), Map.<RouteId, Route>of(),
                Map.<MarketOrderId, MarketOrder>of(), java.util.Set.<WarState>of());
    }
}
