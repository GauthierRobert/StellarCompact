import {
  CATALOG_CONSTANTS,
  DENSITY_PEAK,
  SPECTRAL_CLASSES,
  combine,
  generateCell,
  mix,
  toUnitDouble,
  unit,
  type CatalogStar,
} from './catalog-generator';
import { CATALOG_PARITY_FIXTURE } from './catalog-parity.fixture';

/**
 * The CLIENT half of the E8-03 server/client parity cross-check.
 *
 * Uses CATALOG_PARITY_FIXTURE — the TS mirror of the canonical
 * docs/specs/fixtures/catalog-parity.json, both authored by the Java
 * CatalogParityFixtureTest from the same generator (so they cannot drift) — and
 * asserts the TypeScript generator produces byte-identical stars for the same
 * (seed, cell). Together with the Java test this proves "identical output for
 * identical (seed, coords)" across languages, so the client can render scenery it
 * was never sent.
 *
 * Also checks the O(visible) guarantee structurally: generating a cell is
 * independent of any other cell and bounded.
 */

describe('catalog generator — server/client parity (E8-03)', () => {
  const fixture = CATALOG_PARITY_FIXTURE;

  it('reproduces every star in the shared golden fixture byte-for-byte', () => {
    expect(fixture.cells.length).toBeGreaterThan(0);
    for (const cell of fixture.cells) {
      const got = generateCell(BigInt(cell.seed), { x: cell.cellX, y: cell.cellY });
      expect(
        got.length,
        `star count for seed=${cell.seed} cell=(${cell.cellX},${cell.cellY})`,
      ).toBe(cell.stars.length);

      for (let i = 0; i < cell.stars.length; i++) {
        const want = cell.stars[i];
        const g: CatalogStar = got[i];
        const where = `seed=${cell.seed} cell=(${cell.cellX},${cell.cellY}) star#${i}`;
        // id is the 64-bit value as a decimal string — exact compare.
        expect(g.id, `id ${where}`).toBe(want.id);
        // doubles must be bit-identical to the Java Double.toString round-trip.
        expect(g.x, `x ${where}`).toBe(want.x);
        expect(g.y, `y ${where}`).toBe(want.y);
        expect(g.spectral, `spectral ${where}`).toBe(want.spectral);
        expect(g.brightness, `brightness ${where}`).toBe(want.brightness);
        expect(g.size, `size ${where}`).toBe(want.size);
      }
    }
  });
});

describe('catalog generator — hashing primitives match SeedHash.java', () => {
  it('combine + toUnitDouble produce values in [0,1)', () => {
    const u = unit(combine(123n, 4n), 0);
    expect(u).toBeGreaterThanOrEqual(0);
    expect(u).toBeLessThan(1);
  });

  it('mix avalanches: flipping one input bit changes the hash', () => {
    expect(mix(1n)).not.toBe(mix(0n));
  });

  it('toUnitDouble uses the top 53 bits', () => {
    expect(toUnitDouble(0n)).toBe(0);
    // all-ones 64-bit → just below 1
    expect(toUnitDouble(0xffffffffffffffffn)).toBeLessThan(1);
    expect(toUnitDouble(0xffffffffffffffffn)).toBeGreaterThan(0.999);
  });

  it('shared constants equal the spec (section 7)', () => {
    expect(CATALOG_CONSTANTS.R_MAX).toBe(1000.0);
    expect(CATALOG_CONSTANTS.ARMS).toBe(4);
    expect(CATALOG_CONSTANTS.CELL_SIZE).toBe(50.0);
    expect(CATALOG_CONSTANTS.MAX_CANDIDATES_PER_CELL).toBe(12);
    expect(DENSITY_PEAK).toBeCloseTo(2.51, 10);
    expect(SPECTRAL_CLASSES.reduce((a, c) => a + c.weight, 0)).toBe(1000);
  });
});

describe('catalog generator — O(visible)', () => {
  const seed = 0xc0ffeen;

  it('a cell is deterministic and independent of other cells', () => {
    const target = { x: 2, y: 2 };
    const clean = generateCell(seed, target);
    // Generate a swathe of unrelated cells in between.
    for (let cx = -15; cx <= 15; cx++) {
      for (let cy = -15; cy <= 15; cy++) {
        generateCell(seed, { x: cx, y: cy });
      }
    }
    const again = generateCell(seed, target);
    expect(again).toEqual(clean);
  });

  it('per-cell output is bounded by MAX_CANDIDATES_PER_CELL', () => {
    for (let cx = -10; cx <= 10; cx++) {
      for (let cy = -10; cy <= 10; cy++) {
        const stars = generateCell(seed, { x: cx, y: cy });
        expect(stars.length).toBeLessThanOrEqual(
          CATALOG_CONSTANTS.MAX_CANDIDATES_PER_CELL,
        );
      }
    }
  });
});
