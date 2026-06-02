package com.stellarcompact.galaxy.gen;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The pure, deterministic function {@code (gameSeed, LaneGraph, HomePlacementConfig)
 * -> HomePlacement}: E2-04's home-system chooser, layered on E2-02 system rosters and
 * the E2-03 lane graph (game-design 01 section 6: "Each Sovereign starts on one home
 * system ... placed by the engine with a minimum separation and balanced local
 * resource potential, so no Sovereign begins boxed-in or starved. Placement is seeded
 * and reproducible.").
 *
 * <p><strong>Determinism contract (principle 1).</strong> Placement reads only its
 * arguments. No I/O, no Spring, no wall-clock, no {@code Math.random}, no shared
 * mutable RNG, no mutable statics. Every system roster is regenerated from the seed via
 * {@link SystemGenerator}; every ordering decision is a total order with a
 * {@link SeedHash}-derived tiebreak ({@link Salt#HOME_TIEBREAK}); the graph is walked
 * in its own deterministic adjacency order. So the same {@code (gameSeed, graph,
 * config)} yields a byte-identical {@link HomePlacement} - or the same
 * {@link HomePlacementException} - on every call and across JVMs.
 *
 * <h2>Algorithm</h2>
 * <ol>
 *   <li><strong>Candidates.</strong> A star is a home candidate iff its own system
 *       carries at least one {@link HomePlacementConfig#homeBiome()} planet - the
 *       cradle world the faction is seeded onto. Stars without a cradle are never
 *       homes (so the starting roster guarantee is structural, not luck).</li>
 *   <li><strong>Neighbourhood quality.</strong> Each candidate is scored by a BFS out
 *       to {@link HomePlacementConfig#neighbourhoodHops()} lane hops, summing every
 *       reachable system's {@link #systemQuality} (colonisable build capacity +
 *       resource accessibility). This is the "balanced local resource potential" the
 *       design asks for: it captures habitable/colonisable bodies and resource
 *       accessibility within K hops, not just the home star itself.</li>
 *   <li><strong>Balanced, separated selection.</strong> Candidates are ranked by
 *       {@code (quality desc, id asc)}. For each anchor in that ranking (highest
 *       quality first) we form the fairness <em>band</em> = candidates whose quality
 *       lies in {@code [anchor x (1 - tolerance), anchor]}, then greedily pick up to
 *       {@code factionCount} homes from the band in a seeded order, accepting a
 *       candidate only when it is at least {@link HomePlacementConfig#minSeparationHops()}
 *       lane hops from every home already chosen. The first anchor whose band yields a
 *       full set wins. Because every chosen home's quality is inside the band, the
 *       spread is {@code <= tolerance x maxChosenQuality} (balanced within tolerance);
 *       because each was accepted only past the hop gate, all pairs are
 *       {@code >= minSeparationHops} apart (minimum separation).</li>
 *   <li><strong>Deterministic failure.</strong> If no anchor's band can produce a full
 *       separated set, we throw {@link HomePlacementException} rather than cram
 *       factions together or relax fairness (the card's "don't silently cram").</li>
 * </ol>
 *
 * <p><strong>Faction-index assignment.</strong> The winning home set is emitted in a
 * canonical order (ascending star id) so faction {@code i} -> {@code homeStarIds[i]} is
 * itself reproducible and independent of selection order.
 *
 * <p><strong>Scale (principle 3).</strong> The generator works over the bounded set of
 * graph nodes the caller already materialised; it never iterates the whole catalog. The
 * BFS quality pass is O(candidates x neighbourhood) and the selection is O(candidates x
 * factionCount BFS probes) - fine for a playable region.
 */
public final class HomePlacementGenerator {

    private HomePlacementGenerator() {
    }

    /**
     * Chooses one home system per faction over a generated galaxy.
     *
     * @param gameSeed the per-match galaxy seed (also seeds the system rosters scored)
     * @param graph    the connected natural-lane graph of the playable region (E2-03)
     * @param config   the placement tunables (faction count, separation, balance band)
     * @return the deterministic {@link HomePlacement}; never {@code null}
     * @throws HomePlacementException if the galaxy cannot satisfy the request
     *                                deterministically (too few cradles, or no
     *                                separated+balanced set exists)
     */
    public static HomePlacement place(long gameSeed, LaneGraph graph, HomePlacementConfig config) {
        // 1. Candidates: stars whose own system carries at least one cradle (homeBiome).
        //    Walk the graph nodes in the graph's own deterministic order, then sort by
        //    id so the candidate set order cannot depend on adjacency insertion order.
        List<Long> candidateIds = new ArrayList<>();
        for (long starId : graph.starIds()) {
            if (hasHomeBiome(gameSeed, starId, config.homeBiome())) {
                candidateIds.add(starId);
            }
        }
        candidateIds.sort(Comparator.naturalOrder());

        if (candidateIds.size() < config.factionCount()) {
            throw new HomePlacementException(
                    "not enough " + config.homeBiome() + " home candidates: found "
                            + candidateIds.size() + " but need " + config.factionCount()
                            + " (one cradle per faction). Generate a larger region or "
                            + "lower the faction count.");
        }

        // 2. Neighbourhood quality per candidate (BFS to K hops).
        Map<Long, Double> quality = new HashMap<>(candidateIds.size() * 2);
        for (long id : candidateIds) {
            quality.put(id, neighbourhoodQuality(gameSeed, graph, id, config.neighbourhoodHops()));
        }

        // 3. Rank candidates by (quality desc, id asc) - a total order.
        List<Long> ranked = new ArrayList<>(candidateIds);
        ranked.sort(Comparator
                .comparingDouble((Long id) -> -quality.get(id))
                .thenComparingLong(id -> id));

        // 4. Slide the fairness band down from the highest-quality anchor; the first
        //    band that yields a full separated set wins (highest, fairest start band).
        for (int a = 0; a + config.factionCount() <= ranked.size(); a++) {
            double anchorQ = quality.get(ranked.get(a));
            double floor = anchorQ * (1.0 - config.qualityToleranceFraction());

            // Band = candidates with quality in [floor, anchorQ]. Anchors above this one
            // had strictly higher quality and were already tried as anchors, so the band
            // is exactly the ranked tail from a while quality >= floor.
            List<Long> band = new ArrayList<>();
            for (int j = a; j < ranked.size(); j++) {
                if (quality.get(ranked.get(j)) >= floor) {
                    band.add(ranked.get(j));
                } else {
                    break; // ranked is quality-desc; the rest are below the floor too
                }
            }

            List<Long> chosen = greedySeparatedPick(gameSeed, graph, band, config);
            if (chosen != null) {
                // Emit in canonical (ascending id) order so faction index assignment is
                // reproducible and independent of selection order.
                chosen.sort(Comparator.naturalOrder());
                List<Double> qs = new ArrayList<>(chosen.size());
                for (long id : chosen) {
                    qs.add(quality.get(id));
                }
                return new HomePlacement(chosen, qs);
            }
        }

        throw new HomePlacementException(
                "cannot place " + config.factionCount() + " homes at least "
                        + config.minSeparationHops() + " lane hops apart within a "
                        + config.qualityToleranceFraction() + " quality tolerance band "
                        + "(" + candidateIds.size() + " cradle candidates). Increase region "
                        + "size, relax separation/tolerance, or lower the faction count.");
    }

    // --- selection -----------------------------------------------------------

    /**
     * Greedily pick up to {@code factionCount} homes from {@code band}, in a seeded
     * order, accepting a candidate only when it is at least {@code minSeparationHops}
     * lane hops from every home already chosen.
     *
     * @return a full set of {@code factionCount} ids (mutable list the caller may sort),
     *         or {@code null} if the band cannot yield a full separated set
     */
    private static List<Long> greedySeparatedPick(long gameSeed, LaneGraph graph,
                                                  List<Long> band, HomePlacementConfig config) {
        // Seeded order over the band: a stable, seed-stable shuffle so two seeds can pick
        // different (equally fair) separated sets from the same band, while id is the
        // final discriminator (ids are unique, so the order is a strict total order).
        List<Long> order = new ArrayList<>(band);
        order.sort(Comparator
                .comparingLong((Long id) -> SeedHash.combine(gameSeed, id, Salt.HOME_TIEBREAK.value()))
                .thenComparingLong(id -> id));

        List<Long> chosen = new ArrayList<>(config.factionCount());
        for (long cand : order) {
            if (chosen.size() == config.factionCount()) {
                break;
            }
            if (separatedFromAll(graph, cand, chosen, config.minSeparationHops())) {
                chosen.add(cand);
            }
        }
        return chosen.size() == config.factionCount() ? chosen : null;
    }

    /** True iff {@code cand} is >= minHops lane hops from every already-chosen home. */
    private static boolean separatedFromAll(LaneGraph graph, long cand,
                                            List<Long> chosen, int minHops) {
        for (long home : chosen) {
            int hops = hopsWithinLimit(graph, cand, home, minHops);
            if (hops >= 0 && hops < minHops) {
                return false; // within the forbidden radius
            }
        }
        return true;
    }

    // --- quality -------------------------------------------------------------

    /**
     * Neighbourhood quality of a home candidate: the summed {@link #systemQuality} of
     * every system reachable within {@code maxHops} lane hops (inclusive of the home
     * itself at hop 0). A bounded BFS over the graph's deterministic adjacency order.
     */
    static double neighbourhoodQuality(long gameSeed, LaneGraph graph, long home, int maxHops) {
        double total = 0.0;
        Map<Long, Integer> hop = new HashMap<>();
        Deque<Long> queue = new ArrayDeque<>();
        hop.put(home, 0);
        queue.add(home);
        while (!queue.isEmpty()) {
            long node = queue.poll();
            int d = hop.get(node);
            total += systemQuality(gameSeed, node);
            if (d == maxHops) {
                continue;
            }
            for (long nb : graph.neighbours(node)) {
                if (!hop.containsKey(nb)) {
                    hop.put(nb, d + 1);
                    queue.add(nb);
                }
            }
        }
        return total;
    }

    /**
     * A single system's contribution to neighbourhood quality: its colonisable build
     * capacity plus a habitable-cradle bonus and resource accessibility, all derived
     * deterministically from the seed-regenerated roster. Designed so a system rich in
     * colonisable bodies and varied yields scores higher, which is what "comparable
     * starting neighbourhood (habitable/colonisable planets within K hops, resource
     * accessibility)" means in game-design 01 section 6.
     */
    static double systemQuality(long gameSeed, long systemId) {
        StarSystem sys = SystemGenerator.generate(gameSeed, systemId);
        double score = 0.0;
        // Track which physical resources the system can supply, for an accessibility
        // bonus (a neighbourhood that can reach all four resources is more self-sufficient).
        boolean[] hasResource = new boolean[Resource.values().length];
        for (Planet p : sys.planets()) {
            // Colonisable build capacity: every slot is a future building. Toxic worlds
            // count for less since they need terraforming before they yield.
            score += p.totalSlots();
            if (p.biome() == Biome.OCEANIC || p.biome() == Biome.TERRAN) {
                score += CRADLE_BONUS; // a habitable cradle nearby is worth extra
            }
            ResourceYield y = p.baseYields();
            for (Resource r : Resource.values()) {
                if (y.get(r) > 0) {
                    hasResource[r.ordinal()] = true;
                }
            }
        }
        int distinctResources = 0;
        for (boolean h : hasResource) {
            if (h) {
                distinctResources++;
            }
        }
        score += distinctResources * RESOURCE_ACCESS_BONUS;
        return score;
    }

    /** Extra quality for a habitable cradle (Oceanic/Terran) body in the neighbourhood. */
    private static final double CRADLE_BONUS = 3.0;

    /** Extra quality per distinct physical resource reachable in the neighbourhood. */
    private static final double RESOURCE_ACCESS_BONUS = 2.0;

    // --- hop distance --------------------------------------------------------

    /** True iff the candidate system carries at least one cradle (homeBiome) planet. */
    private static boolean hasHomeBiome(long gameSeed, long systemId, Biome homeBiome) {
        StarSystem sys = SystemGenerator.generate(gameSeed, systemId);
        for (Planet p : sys.planets()) {
            if (p.biome() == homeBiome) {
                return true;
            }
        }
        return false;
    }

    /**
     * Lane-hop distance between {@code from} and {@code to}, computed by a BFS that stops
     * as soon as it can decide the {@code >= limit} question: it explores only out to
     * {@code limit} hops, so it returns the true hop count when {@code <= limit}, or
     * {@code -1} when the target is strictly farther than {@code limit} hops (or
     * unreachable). That is all the separation gate needs and keeps the probe cheap.
     */
    private static int hopsWithinLimit(LaneGraph graph, long from, long to, int limit) {
        if (from == to) {
            return 0;
        }
        Map<Long, Integer> hop = new HashMap<>();
        Deque<Long> queue = new ArrayDeque<>();
        hop.put(from, 0);
        queue.add(from);
        while (!queue.isEmpty()) {
            long node = queue.poll();
            int d = hop.get(node);
            if (d >= limit) {
                continue; // do not expand past the limit; anything beyond is ">= limit"
            }
            for (long nb : graph.neighbours(node)) {
                if (nb == to) {
                    return d + 1;
                }
                if (!hop.containsKey(nb)) {
                    hop.put(nb, d + 1);
                    queue.add(nb);
                }
            }
        }
        return -1; // farther than `limit` hops (or unreachable)
    }
}
