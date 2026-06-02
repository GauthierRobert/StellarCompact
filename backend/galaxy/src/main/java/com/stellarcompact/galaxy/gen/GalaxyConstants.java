package com.stellarcompact.galaxy.gen;

/**
 * Generation constants for the procedural galaxy, mirroring the PoC.
 *
 * <p>Principle 6 says gameplay numbers live in the balance config, never
 * hardcoded. These are not gameplay numbers - they are the fixed shape of the
 * starfield (galaxy geometry), and the procedural-galaxy skill is explicit that
 * "generation constants live in shared config; never fork them between client
 * and server". Centralising them here makes them the single shared source the
 * renderer and any client port must reuse to derive the same scenery
 * (consistency requirement of the skill). The values are taken verbatim from
 * {@code poc/galaxy-navigator.html}'s {@code GALAXY.meta} and arm formula:
 * <pre>
 *   "meta":{"n":5000,"r_max":1000.0,"arms":4,"wind":2.7}
 *   th = WIND*log(max(r,1)/R_MAX*4 + 1) + arm*(2*PI/ARMS) + a*0.1
 * </pre>
 *
 * <p>{@link #ARM_LOG_SCALE} (the {@code *4+1} inside the log) and
 * {@link #ARM_HALF_WIDTH} are read off that same formula / the PoC's visual arm
 * thickness; {@link #BULGE_RADIUS}, {@link #HALO_FLOOR} and the cell sizing are
 * tuned so the aggregate matches the PoC's density character (dense bulge, four
 * wound arms, sparse halo).
 */
public final class GalaxyConstants {

    private GalaxyConstants() {
    }

    // --- PoC GALAXY.meta (verbatim) ---

    /** Maximum galactic radius (galaxy units). PoC {@code r_max}. */
    public static final double R_MAX = 1000.0;

    /** Number of spiral arms. PoC {@code arms}. */
    public static final int ARMS = 4;

    /** Spiral winding tightness. PoC {@code wind}. */
    public static final double WIND = 2.7;

    /** Target visible star count across the disc (PoC {@code n}); informs density scale. */
    public static final int TARGET_STAR_COUNT = 5000;

    // --- Arm geometry derived from the PoC arm formula ---

    /**
     * The radial-to-angle scale inside the log-spiral: {@code r/R_MAX*4 + 1}.
     * From the PoC's {@code WIND*Math.log(max(r,1)/R_MAX*4+1)} arm draw.
     */
    public static final double ARM_LOG_SCALE = 4.0;
    public static final double ARM_LOG_BIAS = 1.0;

    /**
     * Angular half-width of an arm (radians) at which arm membership has fallen
     * to ~1/e. Sets how "thin" the bright arms read against inter-arm space;
     * chosen to match the PoC's arm thickness.
     */
    public static final double ARM_HALF_WIDTH = 0.32;

    /** Peak density contribution of being centred on an arm (relative to halo). */
    public static final double ARM_WEIGHT = 1.0;

    // --- Bulge / halo ---

    /** Radius of the dense central bulge (galaxy units). */
    public static final double BULGE_RADIUS = 180.0;

    /** Peak density contribution at the very centre of the bulge. */
    public static final double BULGE_WEIGHT = 1.4;

    /**
     * Constant background density everywhere inside {@code R_MAX} - the sparse
     * halo / field-star floor that keeps inter-arm space non-empty but thin.
     */
    public static final double HALO_FLOOR = 0.06;

    /**
     * How quickly overall density falls off with radius (exponential scale
     * length, galaxy units). Outer disc is sparser than the inner disc.
     */
    public static final double DISC_SCALE_LENGTH = 420.0;

    // --- Cell grid / sampling ---

    /** Side length of a placement cell (galaxy units). */
    public static final double CELL_SIZE = 50.0;

    /**
     * Maximum candidate stars considered per cell before density rejection.
     * Bounds per-cell work; the density field then keeps a subset.
     */
    public static final int MAX_CANDIDATES_PER_CELL = 12;

    // --- E2-02 system & planet roster sizing ---

    /**
     * Inclusive minimum number of planets a system carries. No empty systems:
     * every star a player can reach offers at least one body to consider, which
     * keeps systems meaningful as the unit of ownership (game-design 01 section
     * 2).
     */
    public static final int MIN_PLANETS = 1;

    /**
     * Inclusive maximum number of planets a system carries. Bounds per-system
     * work and keeps rosters readable; chosen to give a believable Solar-system
     * scale spread (1..8) without sprawling slot counts.
     */
    public static final int MAX_PLANETS = 8;

    /**
     * Per-biome generation weights (declaration order of {@link Biome}:
     * OCEANIC, TERRAN, ARID, DESERT, VOLCANIC, FROZEN, TOXIC, GAS_GIANT).
     *
     * <p>Game-design 01 section 2 says the galaxy is "mostly empty / mostly
     * inhospitable" and cradle worlds (Oceanic, Terran) are comfortable but
     * <em>rare</em>. So the weights make the hostile/edge biomes (Toxic, Frozen,
     * Volcanic, Gas giant, Arid, Desert) dominate and keep the two cradle biomes
     * a deliberate minority:
     * <pre>
     *   Oceanic   4   ( 4%)  cradle - rare
     *   Terran    6   ( 6%)  cradle - rare (10% cradle total)
     *   Arid     16   (16%)
     *   Desert   16   (16%)
     *   Volcanic 14   (14%)
     *   Frozen   16   (16%)
     *   Toxic    18   (18%)  most common - inhospitable dominates
     *   GasGiant 10   (10%)
     *   total   100
     * </pre>
     * The numbers are fixed generation constants (part of the determinism
     * contract); tuning them changes generated rosters.
     */
    public static final int[] BIOME_WEIGHTS = {4, 6, 16, 16, 14, 16, 18, 10};

    // --- E2-03 natural lane graph (travel skeleton, game-design 01 section 3) ---

    /**
     * Maximum natural lanes per star (k in the k-nearest-neighbour proximity
     * build). Bounds graph degree so the map reads as a sparse travel skeleton
     * (a handful of jump options per system, like the PoC route web) rather than
     * a dense mesh. A generation constant (part of the determinism contract,
     * like {@link #CELL_SIZE}); changing it changes the generated graph.
     */
    public static final int LANE_MAX_NEIGHBOURS = 4;

    /**
     * Maximum length of a proximity lane (galaxy units). Stars farther apart than
     * this are not linked by a natural lane; sparser frontier regions are bridged
     * deterministically by the connectivity (MST) pass instead. Chosen relative to
     * {@link #CELL_SIZE} so typical neighbours within a few cells connect while
     * far field stars do not spawn implausibly long lanes.
     */
    public static final double LANE_MAX_RADIUS = 140.0;

    /**
     * Travel cost factor: ticks per galaxy distance unit. A lane's length in ticks
     * is {@code round(distance * LANE_TICKS_PER_UNIT)}, floored to a minimum of
     * {@link #LANE_MIN_TICKS}. Lane length is monotonic with real distance
     * (game-design 01 section 3: "length (travel cost in ticks) derived from real
     * distance"). A generation/travel tunable kept here with the other map-skeleton
     * constants, not in the balance profile, because it is part of the reproducible
     * graph contract E1-09 pathfinding depends on.
     */
    public static final double LANE_TICKS_PER_UNIT = 0.05;

    /** Floor on a lane's travel cost: no lane is instantaneous. */
    public static final int LANE_MIN_TICKS = 1;
}
