package com.stellarcompact.galaxy.gen;

/**
 * The pure log-spiral + central-bulge + sparse-halo density field that gives the
 * starfield its galactic shape, mirroring the PoC's {@code spiralDensity}
 * character.
 *
 * <p>{@code densityAt(x, y)} returns a relative probability weight in roughly
 * {@code [0, ~2.4]} for a star existing at that position. It is the product of:
 * <ul>
 *   <li>a radial disc envelope - dense inner disc fading exponentially outward,
 *       hard-zero beyond {@link GalaxyConstants#R_MAX};</li>
 *   <li>a four-arm log-spiral term - peaks on the arms, troughs between them,
 *       using the same arm phase as the PoC:
 *       {@code th = WIND*log(r/R_MAX*ARM_LOG_SCALE + 1)};</li>
 *   <li>a central Gaussian-ish bulge bump;</li>
 *   <li>a flat halo floor so inter-arm space is sparse, not empty.</li>
 * </ul>
 *
 * <p>Pure and stateless: same coordinates -> same weight, on every call and JVM.
 * No randomness lives here; the generator combines this weight with seeded
 * hashes to accept/reject candidate stars.
 */
public final class SpiralDensityField {

    private SpiralDensityField() {
    }

    /**
     * @param x galaxy-space x (origin = galactic centre)
     * @param y galaxy-space y
     * @return a non-negative relative density weight; {@code 0} outside the disc
     */
    public static double densityAt(double x, double y) {
        double r = Math.hypot(x, y);
        if (r > GalaxyConstants.R_MAX) {
            return 0.0;
        }

        double radialEnvelope = Math.exp(-r / GalaxyConstants.DISC_SCALE_LENGTH);

        double arm = armStrength(x, y, r);
        double bulge = bulgeStrength(r);

        // Arms ride on top of the radial envelope; bulge and halo are additive
        // floors so the centre is always dense and inter-arm space never empty.
        double weight = GalaxyConstants.HALO_FLOOR
                + radialEnvelope * GalaxyConstants.ARM_WEIGHT * arm
                + bulge;
        return weight;
    }

    /**
     * Arm membership in {@code [0,1]}: 1 on an arm crest, falling off with the
     * squared angular distance to the nearest of the {@link GalaxyConstants#ARMS}
     * arms. Uses the PoC log-spiral phase so the arms wind identically.
     */
    private static double armStrength(double x, double y, double r) {
        if (r < 1e-6) {
            return 1.0; // centre belongs to every arm; bulge dominates anyway
        }
        // Expected spiral phase at this radius (the PoC arm draw formula).
        double phase = GalaxyConstants.WIND
                * Math.log(Math.max(r, 1.0) / GalaxyConstants.R_MAX
                        * GalaxyConstants.ARM_LOG_SCALE + GalaxyConstants.ARM_LOG_BIAS);

        double theta = Math.atan2(y, x);
        double armSpacing = 2.0 * Math.PI / GalaxyConstants.ARMS;

        // Angular offset from the arm centre, wrapped to the nearest arm.
        double delta = theta - phase;
        // Wrap into [-armSpacing/2, armSpacing/2].
        delta = delta - armSpacing * Math.floor(delta / armSpacing + 0.5);

        double hw = GalaxyConstants.ARM_HALF_WIDTH;
        return Math.exp(-(delta * delta) / (2.0 * hw * hw));
    }

    /** Central bulge bump: peaks at the core, ~0 beyond the bulge radius. */
    private static double bulgeStrength(double r) {
        double br = GalaxyConstants.BULGE_RADIUS;
        double t = r / br;
        return GalaxyConstants.BULGE_WEIGHT * Math.exp(-t * t);
    }
}
