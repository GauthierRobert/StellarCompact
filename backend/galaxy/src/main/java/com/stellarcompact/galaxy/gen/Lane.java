package com.stellarcompact.galaxy.gen;

/**
 * A natural lane: one undirected edge of the travel skeleton (game-design 01
 * section 3). A fleet or colony ship moves along lanes; you cannot teleport
 * across open space.
 *
 * <p>The two endpoints are star ids ({@link Star#id()}). {@code lengthTicks} is
 * the travel cost in ticks, derived deterministically from the Euclidean
 * distance between the two stars' coords via
 * {@link GalaxyConstants#LANE_TICKS_PER_UNIT} and floored at
 * {@link GalaxyConstants#LANE_MIN_TICKS} (so length is monotonic with distance
 * and never zero). This is the weight E1-09 pathfinding sums.
 *
 * <p>Undirected: {@code (a,b)} and {@code (b,a)} denote the same physical lane.
 * The graph stores each lane once in canonical {@code a < b} orientation
 * (see {@link LaneGraph}); the per-star adjacency exposes it from either end.
 *
 * @param a          one endpoint star id
 * @param b          the other endpoint star id
 * @param lengthTicks travel cost in ticks (>= {@link GalaxyConstants#LANE_MIN_TICKS})
 */
public record Lane(long a, long b, int lengthTicks) {

    public Lane {
        if (a == b) {
            throw new IllegalArgumentException("a lane cannot connect a star to itself: " + a);
        }
        if (lengthTicks < GalaxyConstants.LANE_MIN_TICKS) {
            throw new IllegalArgumentException(
                    "lane lengthTicks must be >= " + GalaxyConstants.LANE_MIN_TICKS
                            + " but was " + lengthTicks);
        }
    }

    /**
     * @param star one endpoint of this lane
     * @return the star id at the other end
     * @throws IllegalArgumentException if {@code star} is not an endpoint
     */
    public long other(long star) {
        if (star == a) {
            return b;
        }
        if (star == b) {
            return a;
        }
        throw new IllegalArgumentException(
                "star " + star + " is not an endpoint of lane " + this);
    }

    /** @return true if {@code star} is one of this lane's endpoints. */
    public boolean touches(long star) {
        return star == a || star == b;
    }
}
