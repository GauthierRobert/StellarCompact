package com.stellarcompact.galaxy.gen;

/**
 * Morgan-Keenan spectral classes, hottest to coolest (game-design 01 section 1
 * "spectral classes"; procedural-galaxy skill: "weighted toward cool dwarfs
 * (realistic IMF-ish); rare luminous giants").
 *
 * <p><strong>Distribution (the {@link #weight}).</strong> Each class carries a
 * relative selection weight, used by {@link SystemGenerator} to pick a class from
 * a single seeded {@code [0,1)} roll. The weights are a deliberately stylised but
 * order-correct stand-in for the real stellar mix on the main sequence, where M
 * dwarfs hugely dominate and O stars are vanishingly rare:
 *
 * <pre>
 *   Class   weight   ~share   real-ish main-sequence frequency
 *   O          1      0.1%    ~0.00003% (we keep it merely "very rare", not absent)
 *   B         12      1.2%    ~0.1%
 *   A         30      3.0%    ~0.6%
 *   F         60      6.0%    ~3%
 *   G        100     10.0%    ~7.6%
 *   K        220     22.0%    ~12%
 *   M        577     57.7%    ~76%
 *   total   1000
 * </pre>
 *
 * <p>The real IMF makes O stars so rare they would essentially never appear in a
 * test sample, which is useless for a statistical assert and unsatisfying for the
 * map; the weights compress the tail so hot stars are <em>rare but observable</em>
 * (O &lt; B &lt; A &lt; F &lt; G &lt; K &lt; M monotonic) while M dwarfs still
 * dominate the galaxy. Tuning these weights changes generated output (they are
 * part of the determinism contract), so they live as fixed constants here.
 *
 * <p><strong>Brightness &amp; size correlation.</strong> Hotter classes are
 * physically larger and more luminous on the main sequence. Each class declares a
 * {@code [min,max]} band for relative brightness (luminosity) and relative size
 * (radius); {@link SystemGenerator} samples within the band so two stars of the
 * same class still differ, but an O star is always brighter and bigger than an M
 * dwarf. Bands are relative units (Sol ~ class G ~ 1.0), not physical SI values.
 */
public enum SpectralClass {
    /** Blue, hottest, largest, brightest; vanishingly rare. */
    O(1, 12.0, 30.0, 6.0, 10.0),
    /** Blue-white; rare. */
    B(12, 5.0, 12.0, 3.0, 6.0),
    /** White. */
    A(30, 2.5, 5.0, 1.6, 3.0),
    /** Yellow-white. */
    F(60, 1.3, 2.5, 1.1, 1.6),
    /** Yellow, Sol-like. */
    G(100, 0.7, 1.3, 0.9, 1.1),
    /** Orange dwarf; common. */
    K(220, 0.3, 0.7, 0.6, 0.9),
    /** Red dwarf; coolest, smallest, dimmest; by far the most common. */
    M(577, 0.05, 0.3, 0.3, 0.6);

    private final int weight;
    private final double minBrightness;
    private final double maxBrightness;
    private final double minSize;
    private final double maxSize;

    SpectralClass(int weight,
                  double minBrightness, double maxBrightness,
                  double minSize, double maxSize) {
        this.weight = weight;
        this.minBrightness = minBrightness;
        this.maxBrightness = maxBrightness;
        this.minSize = minSize;
        this.maxSize = maxSize;
    }

    /** Relative selection weight (see class Javadoc table). */
    public int weight() {
        return weight;
    }

    /** Sum of all class weights; the denominator for the weighted pick. */
    public static int totalWeight() {
        int t = 0;
        for (SpectralClass c : values()) {
            t += c.weight;
        }
        return t;
    }

    /**
     * Picks a class from a uniform {@code [0,1)} roll using the weighted CDF in
     * declaration order (O..M). Pure; deterministic for a given roll.
     */
    public static SpectralClass fromRoll(double roll) {
        double target = roll * totalWeight();
        double acc = 0;
        for (SpectralClass c : values()) {
            acc += c.weight;
            if (target < acc) {
                return c;
            }
        }
        return M; // floating-point guard: roll==~1.0 falls through to the last bin
    }

    /** Lower bound of this class's relative brightness band (Sol G ~ 1.0). */
    public double minBrightness() {
        return minBrightness;
    }

    /** Upper bound of this class's relative brightness band. */
    public double maxBrightness() {
        return maxBrightness;
    }

    /** Lower bound of this class's relative size (radius) band. */
    public double minSize() {
        return minSize;
    }

    /** Upper bound of this class's relative size (radius) band. */
    public double maxSize() {
        return maxSize;
    }
}
