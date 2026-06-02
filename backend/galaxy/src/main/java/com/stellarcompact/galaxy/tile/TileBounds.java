package com.stellarcompact.galaxy.tile;

/**
 * An axis-aligned bounding box in galaxy units - the world-space footprint of a
 * quadtree tile (the pure, framework-free version that lives in the galaxy module
 * alongside the tiling math). The HTTP/api layer mirrors this shape in its tile
 * DTOs; the math that produces it lives here so it stays unit-testable without
 * Spring.
 *
 * @param minX inclusive minimum x
 * @param minY inclusive minimum y
 * @param maxX exclusive maximum x
 * @param maxY exclusive maximum y
 */
public record TileBounds(double minX, double minY, double maxX, double maxY) {

    /**
     * Inclusive-min, exclusive-max containment so adjacent tiles tile the plane
     * cleanly (a point on a shared edge belongs to exactly one tile).
     */
    public boolean contains(double px, double py) {
        return px >= minX && px < maxX && py >= minY && py < maxY;
    }
}
