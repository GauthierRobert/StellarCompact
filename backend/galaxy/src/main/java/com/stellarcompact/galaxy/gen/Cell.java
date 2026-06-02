package com.stellarcompact.galaxy.gen;

/**
 * An integer cell address into the procedural placement grid.
 *
 * <p>The galaxy is diced into a fixed grid of square cells of side
 * {@link GalaxyConstants#CELL_SIZE} (galaxy units). Star placement is a pure
 * function of {@code (gameSeed, cell)}: a cell is hashed to decide how many
 * candidate stars it holds and where they sit, then the spiral/bulge/halo
 * density field rejects or keeps each candidate. Cells make generation local and
 * O(visible) - a caller materialises only the cells it needs and never iterates
 * the whole catalog (principle 3: scale discipline).
 *
 * <p>This card is "fixed/small scale matching the PoC"; the billion-star
 * quadtree/Hilbert tiling that sits above these cells is E8, not here.
 */
public record Cell(int x, int y) {

    /** @return the world-space minimum corner x of this cell. */
    double originX() {
        return (double) x * GalaxyConstants.CELL_SIZE;
    }

    /** @return the world-space minimum corner y of this cell. */
    double originY() {
        return (double) y * GalaxyConstants.CELL_SIZE;
    }
}
