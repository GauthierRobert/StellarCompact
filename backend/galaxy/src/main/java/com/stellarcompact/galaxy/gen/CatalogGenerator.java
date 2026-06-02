package com.stellarcompact.galaxy.gen;

import java.util.ArrayList;
import java.util.List;

/**
 * The O(visible) procedural catalog facade (E8-03): the pure function
 * {@code (gameSeed, cell) -> List<CatalogStar>} that BOTH the server and the
 * TypeScript client implement identically, so they derive the same star from the
 * same seed+coords without shipping data
 * ({@code docs/specs/procedural-catalog-algorithm.md}; architecture
 * {@code 02-galaxy-scale.md} section 2; {@code procedural-galaxy} skill).
 *
 * <p><strong>O(visible), not O(catalog).</strong> Generation is strictly
 * per-cell: {@link #generateCell(long, Cell)} reads only its {@code (seed, cell)}
 * arguments and the fixed constants. It never iterates neighbouring cells, never
 * touches storage, and is bounded by {@link GalaxyConstants#MAX_CANDIDATES_PER_CELL}
 * candidates regardless of how large the galaxy is. A viewport therefore costs
 * {@code O(cells overlapping the viewport)}; a galaxy of billions of potential
 * stars costs nothing until a cell is asked for (principle 3: scale discipline).
 *
 * <p><strong>Parity.</strong> This composes the existing pure generators -
 * {@link StarFieldGenerator} for placement and {@link SystemGenerator} for the
 * visual slice (spectral / brightness / size) - into the single
 * {@link CatalogStar} the client mirrors. It introduces no new randomness: every
 * value is the same {@link SeedHash} mix the client reproduces in BigInt. The
 * cross-language proof lives in {@code CatalogParityFixtureTest} (Java) and
 * {@code catalog-generator.spec.ts} (TS), both asserting the shared fixture
 * {@code docs/specs/fixtures/catalog-parity.json}.
 *
 * <p><strong>Determinism (principle 1).</strong> No I/O, no Spring, no
 * wall-clock, no {@code Math.random}, no mutable statics; all methods static and
 * stateless, so the same inputs yield byte-identical output on every call and JVM.
 */
public final class CatalogGenerator {

    private CatalogGenerator() {
    }

    /**
     * Generates the fully-derived catalog stars in one cell.
     *
     * @param gameSeed the per-match galaxy seed
     * @param cell     the integer cell address to materialise
     * @return an immutable list of {@link CatalogStar} (possibly empty), in
     *         placement (candidate-index) order; never {@code null}
     */
    public static List<CatalogStar> generateCell(long gameSeed, Cell cell) {
        List<Star> placed = StarFieldGenerator.generate(gameSeed, cell);
        List<CatalogStar> out = new ArrayList<>(placed.size());
        for (Star s : placed) {
            out.add(deriveStar(gameSeed, s));
        }
        return List.copyOf(out);
    }

    /**
     * Derives the visual attributes for one placed star and joins them with its
     * position into a {@link CatalogStar}. Exposed so callers that already hold a
     * placed {@link Star} (e.g. the tile generator) reuse the exact same join.
     *
     * @param gameSeed the per-match galaxy seed
     * @param star     a placed star (its {@link Star#id()} seeds the attributes)
     * @return the fully-derived catalog star
     */
    public static CatalogStar deriveStar(long gameSeed, Star star) {
        StarSystem sys = SystemGenerator.generate(gameSeed, star.id());
        return new CatalogStar(
                star.id(),
                star.pos().x(),
                star.pos().y(),
                sys.spectral(),
                sys.brightness(),
                sys.size());
    }
}
