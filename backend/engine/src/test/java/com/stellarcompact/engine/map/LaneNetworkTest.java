package com.stellarcompact.engine.map;

import com.stellarcompact.engine.state.SystemId;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the engine-side {@link LaneNetwork}: the immutable, tick-weighted
 * lane graph the E1-09 movement, interception and validator adjacency checks use.
 * Hand-authored, deterministic, no I/O.
 */
class LaneNetworkTest {

    private static final SystemId A = new SystemId("A");
    private static final SystemId B = new SystemId("B");
    private static final SystemId C = new SystemId("C");
    private static final SystemId D = new SystemId("D");

    /** A-2-B-3-C linear chain; D is a separate, unconnected node only reachable via its own lane. */
    private static LaneNetwork chain() {
        return LaneNetwork.builder()
                .addLane(A, B, 2)
                .addLane(B, C, 3)
                .build();
    }

    @Test
    void emptyNetworkHasNoNodesAndProvesNothing() {
        assertTrue(LaneNetwork.EMPTY.isEmpty());
        assertEquals(0, LaneNetwork.EMPTY.systemCount());
        assertFalse(LaneNetwork.EMPTY.contains(A));
        assertFalse(LaneNetwork.EMPTY.adjacent(A, B));
        assertTrue(LaneNetwork.EMPTY.pathTicks(A, List.of(B)).isEmpty());
    }

    @Test
    void adjacencyIsUndirectedAndTickWeighted() {
        LaneNetwork net = chain();
        assertTrue(net.adjacent(A, B));
        assertTrue(net.adjacent(B, A), "lanes are undirected");
        assertTrue(net.adjacent(B, C));
        assertFalse(net.adjacent(A, C), "A and C are two hops apart, not adjacent");
        assertEquals(Optional.of(2), net.lengthTicks(A, B));
        assertEquals(Optional.of(2), net.lengthTicks(B, A));
        assertEquals(Optional.of(3), net.lengthTicks(B, C));
        assertTrue(net.lengthTicks(A, C).isEmpty());
    }

    @Test
    void neighboursAreDeterministicAndContainmentTracksNodes() {
        LaneNetwork net = chain();
        assertEquals(List.of(B), net.neighbours(A));
        assertEquals(List.of(A, C), net.neighbours(B));
        assertTrue(net.contains(A) && net.contains(B) && net.contains(C));
        assertFalse(net.contains(D));
        assertEquals(List.of(), net.neighbours(D), "unknown node has no neighbours, never null");
    }

    @Test
    void pathTicksSumsRealLaneWalkAndRejectsBrokenWalk() {
        LaneNetwork net = chain();
        // A -> B -> C is a real walk: 2 + 3 = 5 ticks.
        assertEquals(Optional.of(5L), net.pathTicks(A, List.of(B, C)));
        // A -> C directly is not a lane.
        assertTrue(net.pathTicks(A, List.of(C)).isEmpty());
        // A -> B -> D: B->D is not a lane.
        assertTrue(net.pathTicks(A, List.of(B, D)).isEmpty());
        // empty path is not a walk.
        assertTrue(net.pathTicks(A, List.of()).isEmpty());
    }

    @Test
    void builderRejectsSelfLoopAndNonPositiveLength() {
        assertThrows(IllegalArgumentException.class, () -> LaneNetwork.builder().addLane(A, A, 1));
        assertThrows(IllegalArgumentException.class, () -> LaneNetwork.builder().addLane(A, B, 0));
        assertThrows(IllegalArgumentException.class, () -> LaneNetwork.builder().addLane(A, B, -1));
    }

    @Test
    void buildIsDeterministicAcrossBuilds() {
        // Same lanes inserted in the same order -> identical adjacency/neighbour order.
        assertEquals(chain().neighbours(B), chain().neighbours(B));
        assertEquals(chain().pathTicks(A, List.of(B, C)), chain().pathTicks(A, List.of(B, C)));
    }
}
