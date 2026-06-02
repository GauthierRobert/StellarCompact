package com.stellarcompact.orchestrator.match;

import com.stellarcompact.engine.config.BalanceProfile;
import com.stellarcompact.engine.config.BalanceProfileLoader;
import com.stellarcompact.engine.map.LaneNetwork;
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
import com.stellarcompact.galaxy.gen.LaneGraph;
import com.stellarcompact.galaxy.gen.LaneGraphGenerator;
import com.stellarcompact.galaxy.gen.Lane;
import com.stellarcompact.galaxy.gen.Star;
import com.stellarcompact.galaxy.gen.StarFieldGenerator;
import com.stellarcompact.orchestrator.promotion.PromotionService;
import com.stellarcompact.orchestrator.promotion.SystemAddress;
import com.stellarcompact.orchestrator.sovereign.SystemAdjacency;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * A small, fully galaxy-generated match scenario for the headless runner +
 * determinism-replay test (board card E3-03). Built end-to-end from the {@code galaxy}
 * module so the determinism proof spans both pure libraries the engine plus the galaxy
 * exactly as the M1 capstone requires.
 *
 * <p><b>How the galaxy is generated (rule 3 / E2-01..E2-05).</b>
 * <ol>
 *   <li>Materialise the stars of a small, fixed block of placement {@link Cell}s via
 *       {@link StarFieldGenerator} (a bounded region, never the whole catalog).</li>
 *   <li>Build the natural-lane {@link LaneGraph} over that region with
 *       {@link LaneGraphGenerator} (connected by construction).</li>
 *   <li>Pick two well-separated home stars deterministically: the lowest-id star and the
 *       lowest-id star at least {@link #MIN_HOME_SEPARATION} lane hops away, so the two
 *       factions never start boxed together. (This scenario does its own simple seeded
 *       separated pick rather than {@code HomePlacementGenerator}, which additionally
 *       requires a cradle biome per home - irrelevant here since the bots' build/explore
 *       heuristics drive the match, and it keeps the scenario robust for any seed.)</li>
 *   <li>{@code promote} each home star into an engine {@link ActiveSystem} via the same
 *       {@link PromotionService} bridge the orchestrator uses (E2-05), keyed by the
 *       {@link SystemAddress} encoding so the system carries its star id.</li>
 *   <li>Project the galaxy {@link LaneGraph} for those active systems into an engine
 *       {@link LaneNetwork} and a fog {@link SystemAdjacency} (only edges between
 *       materialised active systems are kept - the simulation touches only active
 *       systems).</li>
 * </ol>
 *
 * <p><b>What the match does.</b> Two factions start rich (so the scripted bots' Mine
 * build-floor clears) with a parked fleet each at their home. The {@code ScriptedSovereign}
 * ladder builds economy on its home planets and, when a neutral neighbouring active system
 * is reachable, explores it. The factions never attack, so no domination victory fires;
 * the match therefore runs to the tick limit - the card's "victory or tick limit". The
 * point of the scenario is reproducibility, not a contrived war.
 *
 * <p><b>Determinism.</b> The whole scenario is a pure function of {@link #SEED}: the star
 * field, the lane graph, the home pick, the promotions and the seeding here are all
 * seed-derived and sorted, so rebuilding it from scratch yields a byte-identical
 * {@link GameState}, {@link LaneNetwork} and {@link SystemAdjacency} every time - which is
 * what lets two independent live runs (and the replay) agree.
 *
 * <p>TEST scope; reads only the shipped {@code small-default} balance profile from the
 * classpath, otherwise pure (no clock, no randomness).
 */
final class GalaxyMatchScenario {

    private GalaxyMatchScenario() {
    }

    static final long SEED = 0x57E11A2C0FFEEL;
    static final FactionId ALPHA = new FactionId("alpha");
    static final FactionId BETA = new FactionId("beta");

    // Stable synthetic ids for the neutral scenery systems each faction explores toward
    // (not galaxy-derived, so clearly the scenario's own scenery, distinct from promoted homes).
    static final SystemId NEUTRAL_NEAR_ALPHA = new SystemId("neutral-near-alpha");
    static final SystemId NEUTRAL_NEAR_BETA = new SystemId("neutral-near-beta");

    /** Minimum lane hops required between the two chosen home systems. */
    static final int MIN_HOME_SEPARATION = 2;

    /** A generous tick budget; the peaceful bots never conclude, so the runner hits this. */
    static final int TICK_LIMIT = 12;

    // The small fixed region: a 3x3 block of placement cells in a dense inner area.
    private static final int CELL_MIN = 0;
    private static final int CELL_MAX = 2;

    // ===== balance profile (the shipped small-default; rule 6) =================

    static BalanceProfile profile() {
        try (InputStream in = GalaxyMatchScenario.class.getResourceAsStream("/balance/small-default.json")) {
            if (in == null) {
                throw new IllegalStateException("missing /balance/small-default.json on the test classpath");
            }
            return BalanceProfileLoader.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // ===== the generated galaxy ================================================

    /** The materialised region stars, in canonical (ascending id) order. */
    private static List<Star> regionStars() {
        List<Star> stars = new ArrayList<>();
        for (int cx = CELL_MIN; cx <= CELL_MAX; cx++) {
            for (int cy = CELL_MIN; cy <= CELL_MAX; cy++) {
                stars.addAll(StarFieldGenerator.generate(SEED, new Cell(cx, cy)));
            }
        }
        stars.sort(Comparator.comparingLong(Star::id));
        if (stars.size() < 4) {
            throw new IllegalStateException("region too sparse for the scenario: " + stars.size()
                    + " stars (need >= 4). Adjust the cell block or seed.");
        }
        return stars;
    }

    /** The natural-lane graph over the region (E2-03), connected by construction. */
    static LaneGraph laneGraph() {
        return LaneGraphGenerator.generate(SEED, regionStars());
    }

    /**
     * Pick two well-separated home star ids deterministically: anchor on the lowest-id
     * star, then the lowest-id star at least {@link #MIN_HOME_SEPARATION} hops from it.
     * Falls back to the two farthest-by-id stars if no pair meets the separation (a tiny
     * region) so the scenario never fails to seat both factions.
     */
    static List<Long> homeStarIds() {
        LaneGraph graph = laneGraph();
        List<Long> ids = new ArrayList<>(graph.starIds());
        ids.sort(Comparator.naturalOrder());
        long anchor = ids.get(0);
        for (long candidate : ids) {
            if (candidate == anchor) {
                continue;
            }
            if (hops(graph, anchor, candidate, MIN_HOME_SEPARATION) >= MIN_HOME_SEPARATION) {
                return List.of(anchor, candidate);
            }
        }
        // Fallback: the two lowest distinct ids (connected graph guarantees a path).
        return List.of(ids.get(0), ids.get(1));
    }

    /** BFS hop distance from->to, capped at {@code limit} (returns limit if >= limit). */
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
        return limit; // >= limit hops (or unreachable within the cap)
    }

    /**
     * Project the galaxy {@link LaneGraph} into the engine {@link LaneNetwork}, keeping
     * only lanes whose BOTH endpoints are materialised active systems (the simulation
     * touches only active systems). System ids are the {@link SystemAddress} encoding of
     * the star ids.
     */
    static LaneNetwork laneNetwork() {
        Set<SystemId> active = activeSystemIds();
        LaneGraph graph = laneGraph();
        LaneNetwork.Builder builder = LaneNetwork.builder();
        boolean any = false;
        for (Lane lane : graph.lanes()) {
            SystemId a = SystemAddress.toSystemId(lane.a());
            SystemId b = SystemAddress.toSystemId(lane.b());
            if (active.contains(a) && active.contains(b)) {
                builder.addLane(a, b, lane.lengthTicks());
                any = true;
            }
        }
        // Guarantee the two homes are joined even if no proximity lane sits between the
        // exact active subset: bridge them with the galaxy hop count so movement/explore
        // adjacency exists. (Connected region graph => a path always exists.)
        List<Long> homes = homeStarIds();
        SystemId ha = SystemAddress.toSystemId(homes.get(0));
        SystemId hb = SystemAddress.toSystemId(homes.get(1));
        LaneNetwork partial = any ? builder.build() : null;
        if (partial == null || !partial.adjacent(ha, hb)) {
            int len = Math.max(1, hops(graph, homes.get(0), homes.get(1), 8));
            builder.addLane(ha, hb, len);
        }
        // Wire each home to its neutral scenery system so the bots' Explore branch is
        // reachable (a one-hop lane). These neutral ids are the scenario's own scenery.
        builder.addLane(ha, NEUTRAL_NEAR_ALPHA, 1);
        builder.addLane(hb, NEUTRAL_NEAR_BETA, 1);
        return builder.build();
    }

    /** The fog adjacency mirroring {@link #laneNetwork()} (sensor reveal between actives). */
    static SystemAdjacency adjacency() {
        LaneNetwork lanes = laneNetwork();
        Map<SystemId, List<SystemId>> adj = new LinkedHashMap<>();
        for (SystemId id : activeSystemIds()) {
            adj.put(id, lanes.neighbours(id));
        }
        return SystemAdjacency.of(adj);
    }

    /** The set of system ids that are materialised as active (the two homes + neutrals). */
    private static Set<SystemId> activeSystemIds() {
        return initialState().systems().keySet();
    }

    // ===== initial state =======================================================

    private static ResourceBundle rich() {
        // Wealthy enough that the scripted bots' Mine build-floor clears comfortably.
        return new ResourceBundle(5000, 5000, 5000, 5000, 100);
    }

    private static Faction faction(FactionId id) {
        return new Faction(id, "Sovereign-" + id.value(), 0.0, rich(),
                Map.<TechId, TechProgress>of());
    }

    private static Fleet parkedFleet(FleetId id, FactionId owner, SystemId at) {
        return new Fleet(id, owner, Optional.of(at), Optional.empty(), FleetStance.BALANCED,
                List.of(new Ship("scout", 1)));
    }

    /** A neutral (unowned) active system adjacent to a home, so the bot can Explore it. */
    private static ActiveSystem neutral(SystemId id) {
        return new ActiveSystem(id, "neutral-" + id.value(), new Coords(id.value().length(), 7),
                Optional.empty(), List.of(), 0L, 1.0);
    }

    /**
     * The starting snapshot: two promoted home systems (full planet rosters from the
     * generator), one neutral active system reachable from each home, a parked fleet and
     * rich stockpiles per faction. RUNNING, on the shipped {@code small-default} profile.
     */
    static GameState initialState() {
        PromotionService promotion = new PromotionService();
        List<Long> homes = homeStarIds();
        SystemId alphaHome = SystemAddress.toSystemId(homes.get(0));
        SystemId betaHome = SystemAddress.toSystemId(homes.get(1));

        Map<FactionId, Faction> factions = new LinkedHashMap<>();
        factions.put(ALPHA, faction(ALPHA));
        factions.put(BETA, faction(BETA));

        Map<SystemId, ActiveSystem> systems = new LinkedHashMap<>();
        systems.put(alphaHome, promotion.promote(SEED, alphaHome, ALPHA));
        systems.put(betaHome, promotion.promote(SEED, betaHome, BETA));

        // A neutral active system for each faction to explore toward.
        systems.put(NEUTRAL_NEAR_ALPHA, neutral(NEUTRAL_NEAR_ALPHA));
        systems.put(NEUTRAL_NEAR_BETA, neutral(NEUTRAL_NEAR_BETA));

        Map<FleetId, Fleet> fleets = new LinkedHashMap<>();
        fleets.put(new FleetId("fleet-alpha"), parkedFleet(new FleetId("fleet-alpha"), ALPHA, alphaHome));
        fleets.put(new FleetId("fleet-beta"), parkedFleet(new FleetId("fleet-beta"), BETA, betaHome));

        return new GameState(SEED, 0L, GameStatus.RUNNING, "small-default", 1,
                factions, systems, fleets,
                Map.<TreatyId, Treaty>of(), Map.<RouteId, Route>of(),
                Map.<MarketOrderId, MarketOrder>of(), Set.<WarState>of());
    }
}
