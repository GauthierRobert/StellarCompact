package com.stellarcompact.galaxy.tile;

/**
 * Hilbert space-filling curve over a {@code 2^level x 2^level} grid of tiles, used
 * to order tile addresses for spatial locality (lod-tiling skill: "Hilbert-curve
 * cell ordering for spatial locality - good cache/CDN behaviour; prefix-matching
 * range queries"; architecture 02 section 3).
 *
 * <p>Why Hilbert and not a Z/Morton order: the Hilbert curve keeps spatially
 * adjacent tiles adjacent (or near) in 1-D index space far better than Morton, so
 * a viewport - a contiguous 2-D rectangle - maps to a small number of short
 * contiguous index runs. That is what makes cache keys, CDN prefixes and range
 * scans behave (neighbouring tiles cluster together).
 *
 * <p>The mapping is per-level: at level {@code L} the curve fills the
 * {@code n = 2^L} square, so a Hilbert index is in {@code [0, n*n)} and uniquely
 * round-trips to a tile {@code (x, y)} in {@code [0, n)^2}. The classic iterative
 * algorithm (Wikipedia "Hilbert curve", {@code xy2d} / {@code d2xy}) is used so the
 * two directions are exact inverses by construction.
 *
 * <p>Pure and stateless (galaxy module is framework-free; determinism is sacred):
 * closed-form integer math, no I/O, no randomness, same inputs -> same output on
 * every JVM. {@code long} index so the space is safe up to {@code level = 31}
 * ({@code n*n} fits a signed 64-bit value well beyond any served level).
 */
public final class HilbertCurve {

    private HilbertCurve() {
    }

    /**
     * Maps a tile {@code (x, y)} to its Hilbert distance along the
     * order-{@code level} curve.
     *
     * @param level the quadtree level; the grid is {@code 2^level} per axis
     * @param x     tile x in {@code [0, 2^level)}
     * @param y     tile y in {@code [0, 2^level)}
     * @return the Hilbert index {@code d} in {@code [0, 4^level)}
     * @throws IllegalArgumentException if the level is out of range or
     *                                  {@code (x, y)} is out of range
     */
    public static long index(int level, long x, long y) {
        long n = sideOrThrow(level);
        if (x < 0 || y < 0 || x >= n || y >= n) {
            throw new IllegalArgumentException(
                    "tile out of range for level " + level + ": (" + x + "," + y + ")");
        }
        long rx;
        long ry;
        long d = 0;
        long lx = x;
        long ly = y;
        for (long s = n / 2; s > 0; s /= 2) {
            rx = (lx & s) > 0 ? 1 : 0;
            ry = (ly & s) > 0 ? 1 : 0;
            d += s * s * ((3 * rx) ^ ry);
            if (ry == 0) {
                if (rx == 1) {
                    lx = s - 1 - lx;
                    ly = s - 1 - ly;
                }
                long t = lx;
                lx = ly;
                ly = t;
            }
        }
        return d;
    }

    /**
     * Inverse of {@link #index(int, long, long)}: maps a Hilbert distance back to
     * its tile coordinate on the order-{@code level} curve.
     *
     * @param level the quadtree level; the grid is {@code 2^level} per axis
     * @param d     the Hilbert index in {@code [0, 4^level)}
     * @return the tile {@code [x, y]} (a two-element array)
     * @throws IllegalArgumentException if the level is out of range or
     *                                  {@code d} is out of range
     */
    public static long[] coords(int level, long d) {
        long n = sideOrThrow(level);
        long total = n * n;
        if (d < 0 || d >= total) {
            throw new IllegalArgumentException(
                    "hilbert index out of range for level " + level + ": " + d);
        }
        long rx;
        long ry;
        long t = d;
        long x = 0;
        long y = 0;
        for (long s = 1; s < n; s *= 2) {
            rx = 1 & (t / 2);
            ry = 1 & (t ^ rx);
            if (ry == 0) {
                if (rx == 1) {
                    x = s - 1 - x;
                    y = s - 1 - y;
                }
                long tmp = x;
                x = y;
                y = tmp;
            }
            x += s * rx;
            y += s * ry;
            t /= 4;
        }
        return new long[] {x, y};
    }

    /** @return {@code 2^level} as a long, validating the level is in {@code [0, 31]}. */
    private static long sideOrThrow(int level) {
        if (level < 0 || level > 31) {
            throw new IllegalArgumentException("level out of range [0,31]: " + level);
        }
        return 1L << level;
    }
}
