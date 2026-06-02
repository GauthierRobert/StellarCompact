package com.stellarcompact.galaxy.gen;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Test-only builder that assembles a {@link LaneGraph} from explicit nodes and
 * lanes. Lives in the production package so it can reach the package-private
 * {@link LaneGraph} constructor; used to author known-topology graphs for
 * {@link LanePathfinder} tests without going through the proximity generator.
 */
final class TestGraphs {

    private TestGraphs() {
    }

    /** Build a graph over the given stars with exactly the given lanes. */
    static LaneGraph of(List<Star> stars, Lane... lanes) {
        Map<Long, List<Lane>> adjacency = new LinkedHashMap<>();
        for (Star s : stars) {
            adjacency.put(s.id(), new ArrayList<>());
        }
        List<Lane> flat = new ArrayList<>();
        for (Lane lane : lanes) {
            flat.add(lane);
            adjacency.get(lane.a()).add(lane);
            adjacency.get(lane.b()).add(lane);
        }
        return new LaneGraph(adjacency, flat);
    }
}
