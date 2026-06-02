package com.stellarcompact.galaxy.gen;

import java.util.ArrayList;
import java.util.List;

/**
 * The procedural star-placement generator: the pure, deterministic function
 * {@code (gameSeed, cell) -> List<Star>} that is the source of truth both the
 * renderer and the simulation regenerate on demand without ever storing the
 * catalog (game-design 01 section 1; procedural-galaxy skill).
 *
 * <p><strong>Determinism contract (principle 1).</strong> Generation reads only
 * its arguments. There is no I/O, no Spring, no wall-clock, no
 * {@code Math.random}, no shared mutable RNG and no mutable statics. All
 * randomness is closed-form {@link SeedHash} mixing of
 * {@code (gameSeed, cellX, cellY, localSalt)}. Therefore:
 * <ul>
 *   <li>{@code generate(seed, cell)} returns a byte-identical list on every
 *       call;</li>
 *   <li>two independently constructed generators agree (the class is stateless;
 *       all methods are {@code static});</li>
 *   <li>results are stable across JVMs.</li>
 * </ul>
 *
 * <p><strong>Shape (matches PoC density character).</strong> Each cell hashes to
 * a bounded set of candidate positions; each candidate is kept with probability
 * proportional to {@link SpiralDensityField#densityAt} normalised by
 * {@link #DENSITY_PEAK}. The aggregate therefore reads as the PoC galaxy: a
 * dense central bulge, four wound log-spiral arms (denser than inter-arm space
 * at equal radius), and a sparse halo, all inside {@link GalaxyConstants#R_MAX}.
 *
 * <p><strong>Scale (this card only).</strong> Placement is per-cell and
 * O(cells requested); no quadtree/Hilbert tiling and no storage - that is E8.
 * Spectral class / brightness / planet rosters are E2-02 and are intentionally
 * not produced here (see {@link Star}).
 */
public final class StarFieldGenerator {

    /**
     * Density normaliser: an upper bound on {@link SpiralDensityField#densityAt}
     * so acceptance probabilities land in {@code [0,1]}. The field peaks at the
     * bulge core: {@code HALO_FLOOR + envelope(1)*ARM_WEIGHT*1 + BULGE_WEIGHT}.
     * A small safety margin keeps the ratio strictly below 1.
     */
    static final double DENSITY_PEAK =
            GalaxyConstants.HALO_FLOOR
                    + GalaxyConstants.ARM_WEIGHT
                    + GalaxyConstants.BULGE_WEIGHT
                    + 0.05;

    private StarFieldGenerator() {
    }

    /**
     * Generates the stars in one cell for a given game seed.
     *
     * @param gameSeed the per-match galaxy seed
     * @param cell     the integer cell address to materialise
     * @return an immutable, deterministically ordered list of stars in the cell
     *         (possibly empty); never {@code null}
     */
    public static List<Star> generate(long gameSeed, Cell cell) {
        long cellHash = SeedHash.combine(gameSeed, cell.x(), cell.y());

        int candidates = candidateCount(cellHash);
        List<Star> out = new ArrayList<>(candidates);

        double ox = cell.originX();
        double oy = cell.originY();

        for (int i = 0; i < candidates; i++) {
            // Each candidate gets its own decorrelated sub-stream off the cell
            // hash, keyed by index, so candidates within a cell are independent.
            long candHash = SeedHash.combine(cellHash, i);

            double fx = SeedHash.unit(candHash, Salt.STAR_X.value());
            double fy = SeedHash.unit(candHash, Salt.STAR_Y.value());
            double x = ox + fx * GalaxyConstants.CELL_SIZE;
            double y = oy + fy * GalaxyConstants.CELL_SIZE;

            double accept = SpiralDensityField.densityAt(x, y) / DENSITY_PEAK;
            double roll = SeedHash.unit(candHash, Salt.DENSITY_ACCEPT.value());
            if (roll >= accept) {
                continue; // rejected by the density field
            }

            long id = SeedHash.combine(candHash, Salt.STAR_ID.value());
            out.add(new Star(id, new StarCoords(x, y)));
        }

        return List.copyOf(out);
    }

    /**
     * Number of candidate positions a cell offers before density rejection.
     * Uniform in {@code [0, MAX_CANDIDATES_PER_CELL]} from the cell hash; the
     * spiral/bulge/halo field then thins them. Keeping the candidate count
     * uniform (shape comes entirely from rejection) makes the density purely a
     * property of {@link SpiralDensityField}, easy to reason about and test.
     */
    private static int candidateCount(long cellHash) {
        double u = SeedHash.unit(cellHash, Salt.CELL_COUNT.value());
        return (int) (u * (GalaxyConstants.MAX_CANDIDATES_PER_CELL + 1));
    }
}
