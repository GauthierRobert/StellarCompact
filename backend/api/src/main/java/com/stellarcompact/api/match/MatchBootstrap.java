package com.stellarcompact.api.match;

import com.stellarcompact.engine.config.BalanceProfile;
import com.stellarcompact.engine.map.LaneNetwork;
import com.stellarcompact.engine.state.ActiveSystem;
import com.stellarcompact.engine.state.Faction;
import com.stellarcompact.engine.state.FactionId;
import com.stellarcompact.engine.state.Fleet;
import com.stellarcompact.engine.state.FleetId;
import com.stellarcompact.engine.state.FleetStance;
import com.stellarcompact.engine.state.GameState;
import com.stellarcompact.engine.state.GameStatus;
import com.stellarcompact.engine.state.ResourceBundle;
import com.stellarcompact.engine.state.Ship;
import com.stellarcompact.engine.state.SystemId;
import com.stellarcompact.galaxy.gen.Biome;
import com.stellarcompact.galaxy.gen.Cell;
import com.stellarcompact.galaxy.gen.HomePlacement;
import com.stellarcompact.galaxy.gen.HomePlacementConfig;
import com.stellarcompact.galaxy.gen.HomePlacementGenerator;
import com.stellarcompact.galaxy.gen.Lane;
import com.stellarcompact.galaxy.gen.LaneGraph;
import com.stellarcompact.galaxy.gen.LaneGraphGenerator;
import com.stellarcompact.galaxy.gen.Star;
import com.stellarcompact.galaxy.gen.StarFieldGenerator;
import com.stellarcompact.orchestrator.promotion.PromotionService;
import com.stellarcompact.orchestrator.promotion.SystemAddress;
import com.stellarcompact.orchestrator.sovereign.SystemAdjacency;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Builds a small, deterministic, <em>galaxy-generated</em> starting {@link GameState}
 * (plus the matching {@link LaneNetwork} and fog {@link SystemAdjacency}) for a newly
 * created match (board cards E6-01, E11-01). A pure function of
 * {@code (seed, factionCount, balanceProfile)} - no I/O, no clock, no unseeded randomness
 * (principle 1) - so the same create request always seeds the same galaxy.
 *
 * <p><b>Why this replaced the hand-built stub.</b> The previous bootstrap wired each
 * faction into its own isolated 2-system pocket with a single 3-slot planet and
 * planet-less neutrals (4-agent live-sim findings L1/L2/L4 in
 * {@code docs/game-design/10-four-agent-live-sim-findings.md}): factions could never meet,
 * trade, fight or colonise, every match was a 4-way mirror that could not end. This builder
 * uses the real procedural pipeline (E2-01..E2-05) so a live match is a single
 * <em>connected</em> region with fairly-placed homes and neutral systems that actually
 * carry planets - the same shape the orchestrator's {@code GalaxyMatchScenario} proves
 * deterministic end to end.
 *
 * <p><b>How the galaxy is generated.</b>
 * <ol>
 *   <li>Materialise the stars of a bounded block of placement {@link Cell}s via
 *       {@link StarFieldGenerator}, expanding the block until there are enough stars for
 *       the requested faction count (never the whole catalog - rule 3).</li>
 *   <li>Build the natural-lane {@link LaneGraph} over that region with
 *       {@link LaneGraphGenerator} (connected by construction).</li>
 *   <li>Place one home star per faction with {@link HomePlacementGenerator} - the fairness
 *       guard (cradle biome + quality-tolerance band + min separation, E2-04/E10-05) - and
 *       fall back to a deterministic separated pick if the region cannot satisfy it, so the
 *       builder never fails to seat a match.</li>
 *   <li>{@code promote} each home star into an owned engine {@link ActiveSystem} (full
 *       planet roster from the generator) and a handful of nearby stars into
 *       <em>unowned</em> active systems with their rosters - real colonisation targets.</li>
 *   <li>Project the galaxy lanes between materialised active systems into an engine
 *       {@link LaneNetwork}, bridging any disconnected components so the whole active region
 *       is reachable (homes can reach each other and the neutrals); mirror it into the fog
 *       {@link SystemAdjacency}.</li>
 * </ol>
 *
 * <p>The match is built in {@link GameStatus#CREATED}; the service transitions it through
 * the guarded lifecycle (CREATED -&gt; LOBBY -&gt; RUNNING) on start.
 */
final class MatchBootstrap {

    /** Rich enough that the scripted bots' Mine build-floor clears comfortably. */
    private static final ResourceBundle RICH = new ResourceBundle(5000, 5000, 5000, 5000, 100);

    /** Placement-cell block grows from this origin until the region has enough stars. */
    private static final int CELL_ORIGIN = 0;
    /** Hard cap on the block half-width so generation stays bounded for any faction count. */
    private static final int CELL_RADIUS_CAP = 12;
    /** Cap for BFS hop searches when bridging disconnected active components. */
    private static final int BRIDGE_HOP_CAP = 32;

    private MatchBootstrap() {
    }

    /** The fully-assembled starting inputs for one match (state + lanes + fog adjacency). */
    record Bootstrap(GameState state, LaneNetwork lanes, SystemAdjacency adjacency) {
    }

    /** The seated faction ids for {@code factionCount} seats: {@code faction-1..N}. */
    static List<FactionId> factionIds(int factionCount) {
        List<FactionId> ids = new ArrayList<>(factionCount);
        for (int i = 1; i <= factionCount; i++) {
            ids.add(new FactionId("faction-" + i));
        }
        return List.copyOf(ids);
    }

    /**
     * Build the connected, galaxy-generated starting snapshot in {@link GameStatus#CREATED}
     * plus its {@link LaneNetwork} and {@link SystemAdjacency}. Pure in
     * {@code (seed, factionCount, profile)}.
     */
    static Bootstrap build(long seed, int factionCount, BalanceProfile profile) {
        List<FactionId> ids = factionIds(factionCount);
        List<Star> stars = regionStars(seed, factionCount);
        LaneGraph graph = LaneGraphGenerator.generate(seed, stars);

        List<Long> homeStars = placeHomes(seed, graph, factionCount, profile);
        List<Long> neutralStars = pickNeutrals(graph, homeStars, factionCount);

        PromotionService promotion = new PromotionService();
        Map<FactionId, Faction> factions = new LinkedHashMap<>();
        Map<SystemId, ActiveSystem> systems = new LinkedHashMap<>();
        Map<FleetId, Fleet> fleets = new LinkedHashMap<>();

        for (int i = 0; i < factionCount; i++) {
            FactionId fid = ids.get(i);
            SystemId home = SystemAddress.toSystemId(homeStars.get(i));
            systems.put(home, promotion.promote(seed, home, fid));
            factions.put(fid, new Faction(fid, "Sovereign-" + (i + 1), 0.0, RICH, Map.of()));
            FleetId fleet = new FleetId("fleet-" + (i + 1));
            fleets.put(fleet, new Fleet(fleet, fid, Optional.of(home), Optional.empty(),
                    FleetStance.BALANCED, List.of(new Ship("scout", 1))));
        }

        // Neutral active systems carry real planet rosters (so colonisation has targets):
        // promote to get the seed-derived roster, then re-key as unowned, unpopulated.
        FactionId rosterSeedOwner = ids.get(0);
        for (long starId : neutralStars) {
            SystemId sid = SystemAddress.toSystemId(starId);
            ActiveSystem owned = promotion.promote(seed, sid, rosterSeedOwner);
            systems.put(sid, new ActiveSystem(owned.id(), owned.name(), owned.coords(),
                    Optional.empty(), owned.planets(), 0L, PromotionService.FOUNDING_LOYALTY));
        }

        Set<SystemId> active = systems.keySet();
        LaneNetwork lanes = buildLaneNetwork(graph, active);
        SystemAdjacency adjacency = buildAdjacency(lanes, active);

        GameState state = new GameState(seed, 0L, GameStatus.CREATED, profile.name(),
                profile.version(), factions, systems, fleets, Map.of(), Map.of(), Map.of(),
                Set.of());
        return new Bootstrap(state, lanes, adjacency);
    }

    // ===== region + home placement =============================================

    /**
     * Materialise the region stars, growing the placement-cell block from {@link #CELL_ORIGIN}
     * until there are comfortably enough stars to seat {@code factionCount} homes plus a few
     * neutral colonisation targets, or the {@link #CELL_RADIUS_CAP} is hit. Sorted by id
     * (canonical order). The simulation only ever touches this bounded region (rule 3).
     */
    private static List<Star> regionStars(long seed, int factionCount) {
        int needed = factionCount * 3 + 4;
        List<Star> stars = List.of();
        for (int radius = 2; radius <= CELL_RADIUS_CAP; radius++) {
            List<Star> collected = new ArrayList<>();
            for (int cx = CELL_ORIGIN; cx <= CELL_ORIGIN + radius; cx++) {
                for (int cy = CELL_ORIGIN; cy <= CELL_ORIGIN + radius; cy++) {
                    collected.addAll(StarFieldGenerator.generate(seed, new Cell(cx, cy)));
                }
            }
            stars = collected;
            if (stars.size() >= needed) {
                break;
            }
        }
        stars = new ArrayList<>(stars);
        stars.sort(Comparator.comparingLong(Star::id));
        if (stars.size() < factionCount) {
            throw new IllegalStateException("generated region too sparse for " + factionCount
                    + " factions: only " + stars.size() + " stars");
        }
        return stars;
    }

    /**
     * Place one home star per faction. Prefer the fairness-guarded
     * {@link HomePlacementGenerator} (cradle biome, quality-tolerance band, min separation
     * from the active balance profile, with the requested {@code factionCount}); fall back to
     * a deterministic separated pick if the region cannot satisfy the guard, so a match is
     * always seatable. Returns one star id per faction, in faction-index order.
     */
    private static List<Long> placeHomes(long seed, LaneGraph graph, int factionCount,
                                         BalanceProfile profile) {
        BalanceProfile.HomePlacement hp = profile.homePlacement();
        try {
            HomePlacementConfig cfg = new HomePlacementConfig(
                    factionCount,
                    Math.max(1, hp.minSeparationHops()),
                    Math.max(0, hp.neighbourhoodHops()),
                    hp.qualityToleranceFraction(),
                    Biome.valueOf(hp.homeBiome().toUpperCase(Locale.ROOT)),
                    Math.max(1, hp.minHomePlanetCount()),
                    hp.minHomeBiomeYield());
            HomePlacement placement = HomePlacementGenerator.place(seed, graph, cfg);
            return placement.homeStarIds();
        } catch (RuntimeException e) {
            // Region cannot satisfy the fairness guard (too sparse / no cradle band): fall
            // back to a deterministic separated pick so the match still seats.
            return separatedPick(graph, factionCount, Math.max(1, hp.minSeparationHops()));
        }
    }

    /**
     * Deterministic fallback: greedily pick the lowest-id stars such that each new home is at
     * least {@code minSeparation} lane hops from every already-chosen home; if the region is
     * too tight to satisfy separation, top up with the lowest remaining ids so we always
     * return exactly {@code factionCount} distinct stars.
     */
    private static List<Long> separatedPick(LaneGraph graph, int factionCount, int minSeparation) {
        List<Long> ids = new ArrayList<>(graph.starIds());
        ids.sort(Comparator.naturalOrder());
        List<Long> chosen = new ArrayList<>();
        for (long candidate : ids) {
            if (chosen.size() == factionCount) {
                break;
            }
            boolean farEnough = chosen.stream()
                    .allMatch(h -> hops(graph, h, candidate, minSeparation) >= minSeparation);
            if (farEnough) {
                chosen.add(candidate);
            }
        }
        for (long candidate : ids) {
            if (chosen.size() == factionCount) {
                break;
            }
            if (!chosen.contains(candidate)) {
                chosen.add(candidate);
            }
        }
        return List.copyOf(chosen);
    }

    /**
     * Pick up to {@code factionCount} neutral colonisation-target stars: the lowest-id stars
     * that are not homes. They are wired into the connected active region by
     * {@link #buildLaneNetwork}, so each is reachable from some home.
     */
    private static List<Long> pickNeutrals(LaneGraph graph, List<Long> homeStars, int factionCount) {
        Set<Long> homes = new LinkedHashSet<>(homeStars);
        List<Long> ids = new ArrayList<>(graph.starIds());
        ids.sort(Comparator.naturalOrder());
        List<Long> neutrals = new ArrayList<>();
        for (long id : ids) {
            if (neutrals.size() == factionCount) {
                break;
            }
            if (!homes.contains(id)) {
                neutrals.add(id);
            }
        }
        return List.copyOf(neutrals);
    }

    // ===== lane network + adjacency ============================================

    /**
     * Project the galaxy {@link LaneGraph} into an engine {@link LaneNetwork} over the active
     * systems, keeping only lanes whose BOTH endpoints are materialised. Any active system
     * that ends up in a disconnected component is bridged to the main component with a lane
     * whose length is the galaxy hop distance, so the whole active region is reachable
     * (homes can reach one another and the neutrals - the precondition for movement, combat,
     * colonisation and domination victory).
     */
    private static LaneNetwork buildLaneNetwork(LaneGraph graph, Set<SystemId> active) {
        // Collect the real lanes between active systems.
        List<long[]> edges = new ArrayList<>(); // {starA, starB, lengthTicks}
        Map<SystemId, Long> starOf = new HashMap<>();
        for (SystemId id : active) {
            starOf.put(id, SystemAddress.toStarId(id));
        }
        for (Lane lane : graph.lanes()) {
            SystemId a = SystemAddress.toSystemId(lane.a());
            SystemId b = SystemAddress.toSystemId(lane.b());
            if (active.contains(a) && active.contains(b)) {
                edges.add(new long[]{lane.a(), lane.b(), lane.lengthTicks()});
            }
        }

        // Union-find over active systems to find disconnected components.
        Map<SystemId, SystemId> parent = new LinkedHashMap<>();
        for (SystemId id : active) {
            parent.put(id, id);
        }
        for (long[] e : edges) {
            union(parent, SystemAddress.toSystemId(e[0]), SystemAddress.toSystemId(e[1]));
        }

        // Bridge each extra component to the main component (the one holding the lowest-id
        // system) with a galaxy-hop-length lane, deterministically by lowest id.
        List<SystemId> sortedActive = new ArrayList<>(active);
        sortedActive.sort(Comparator.comparing(s -> starOf.get(s)));
        SystemId mainRep = find(parent, sortedActive.get(0));
        Map<SystemId, SystemId> componentRep = new LinkedHashMap<>();
        for (SystemId id : sortedActive) {
            componentRep.putIfAbsent(find(parent, id), id);
        }
        for (Map.Entry<SystemId, SystemId> entry : componentRep.entrySet()) {
            if (entry.getKey().equals(mainRep)) {
                continue;
            }
            SystemId from = entry.getValue();
            SystemId to = componentRep.get(mainRep);
            int len = Math.max(1, hops(graph, starOf.get(from), starOf.get(to), BRIDGE_HOP_CAP));
            edges.add(new long[]{starOf.get(from), starOf.get(to), len});
            union(parent, from, to);
        }

        LaneNetwork.Builder builder = LaneNetwork.builder();
        for (long[] e : edges) {
            builder.addLane(SystemAddress.toSystemId(e[0]), SystemAddress.toSystemId(e[1]),
                    (int) e[2]);
        }
        return builder.build();
    }

    /** The fog adjacency mirroring the {@link LaneNetwork} (sensor reveal between actives). */
    private static SystemAdjacency buildAdjacency(LaneNetwork lanes, Set<SystemId> active) {
        Map<SystemId, List<SystemId>> adj = new LinkedHashMap<>();
        for (SystemId id : active) {
            adj.put(id, lanes.neighbours(id));
        }
        return SystemAdjacency.of(adj);
    }

    // ===== small graph helpers =================================================

    private static SystemId find(Map<SystemId, SystemId> parent, SystemId x) {
        SystemId root = x;
        while (!parent.get(root).equals(root)) {
            root = parent.get(root);
        }
        // Path-compress.
        SystemId cur = x;
        while (!parent.get(cur).equals(root)) {
            SystemId next = parent.get(cur);
            parent.put(cur, root);
            cur = next;
        }
        return root;
    }

    private static void union(Map<SystemId, SystemId> parent, SystemId a, SystemId b) {
        SystemId ra = find(parent, a);
        SystemId rb = find(parent, b);
        if (!ra.equals(rb)) {
            // Deterministic: attach the higher-id root under the lower-id root.
            if (ra.value().compareTo(rb.value()) <= 0) {
                parent.put(rb, ra);
            } else {
                parent.put(ra, rb);
            }
        }
    }

    /** BFS hop distance {@code from -> to} over the galaxy graph, capped at {@code limit}. */
    private static int hops(LaneGraph graph, long from, long to, int limit) {
        if (from == to) {
            return 0;
        }
        Map<Long, Integer> seen = new HashMap<>();
        Deque<Long> frontier = new ArrayDeque<>();
        seen.put(from, 0);
        frontier.add(from);
        while (!frontier.isEmpty()) {
            long node = frontier.poll();
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
                    frontier.add(nb);
                }
            }
        }
        return limit; // >= limit hops (or unreachable within the cap)
    }
}
