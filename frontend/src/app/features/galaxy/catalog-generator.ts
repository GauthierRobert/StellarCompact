/**
 * Procedural catalog generator — the CLIENT half of E8-03.
 *
 * A pure, dependency-free TypeScript port of the server's
 * `(gameSeed, cell) -> CatalogStar[]` function. It is BYTE-FOR-BYTE identical to
 * the Java `CatalogGenerator`/`StarFieldGenerator`/`SystemGenerator`/`SeedHash`
 * stack, so the client can render scenery stars it was never sent — the whole
 * point of the billion-star scale trick (architecture `02-galaxy-scale.md`
 * section 2). The shared algorithm + constants are specified authoritatively in
 * `docs/specs/procedural-catalog-algorithm.md`; this file MUST match it and the
 * Java side exactly. Parity is proven by the golden fixture
 * `docs/specs/fixtures/catalog-parity.json` (see catalog-generator.spec.ts).
 *
 * No Angular, no signals, no I/O — a plain function library, trivially testable
 * and zoneless-compatible. It is O(visible): `generateCell` reads only its
 * (seed, cell) and touches no other cell, so a viewport costs O(cells on screen),
 * never O(catalog).
 *
 * **Bit-exactness.** Java `long` is signed 64-bit with wrapping multiply; JS
 * `number` only has 53 safe integer bits. So all hashing here is done in
 * `BigInt`, masked to 64 bits, reproducing Java's two's-complement wrap-around
 * exactly. Only the final unit-double conversion leaves BigInt for `number`.
 */

// ---------------------------------------------------------------------------
// Shared constants — MUST equal GalaxyConstants.java / SpectralClass.java and
// docs/specs/procedural-catalog-algorithm.md section 7.
// ---------------------------------------------------------------------------

export const CATALOG_CONSTANTS = {
  R_MAX: 1000.0,
  ARMS: 4,
  WIND: 2.7,
  ARM_LOG_SCALE: 4.0,
  ARM_LOG_BIAS: 1.0,
  ARM_HALF_WIDTH: 0.32,
  ARM_WEIGHT: 1.0,
  BULGE_RADIUS: 180.0,
  BULGE_WEIGHT: 1.4,
  HALO_FLOOR: 0.06,
  DISC_SCALE_LENGTH: 420.0,
  CELL_SIZE: 50.0,
  MAX_CANDIDATES_PER_CELL: 12,
} as const;

/** Density normaliser (StarFieldGenerator.DENSITY_PEAK) so accept is in [0,1). */
export const DENSITY_PEAK =
  CATALOG_CONSTANTS.HALO_FLOOR +
  CATALOG_CONSTANTS.ARM_WEIGHT +
  CATALOG_CONSTANTS.BULGE_WEIGHT +
  0.05;

/**
 * Spectral classes O..M in declaration order, weight + relative bands.
 * Mirrors SpectralClass.java verbatim (total weight 1000).
 */
export const SPECTRAL_CLASSES = [
  { name: 'O', weight: 1, minB: 12.0, maxB: 30.0, minSize: 6.0, maxSize: 10.0 },
  { name: 'B', weight: 12, minB: 5.0, maxB: 12.0, minSize: 3.0, maxSize: 6.0 },
  { name: 'A', weight: 30, minB: 2.5, maxB: 5.0, minSize: 1.6, maxSize: 3.0 },
  { name: 'F', weight: 60, minB: 1.3, maxB: 2.5, minSize: 1.1, maxSize: 1.6 },
  { name: 'G', weight: 100, minB: 0.7, maxB: 1.3, minSize: 0.9, maxSize: 1.1 },
  { name: 'K', weight: 220, minB: 0.3, maxB: 0.7, minSize: 0.6, maxSize: 0.9 },
  { name: 'M', weight: 577, minB: 0.05, maxB: 0.3, minSize: 0.3, maxSize: 0.6 },
] as const;

export type SpectralClassName = (typeof SPECTRAL_CLASSES)[number]['name'];

const SPECTRAL_TOTAL_WEIGHT = SPECTRAL_CLASSES.reduce(
  (acc, c) => acc + c.weight,
  0,
);

/** Local-salt ordinals — MUST match Salt.java (append-only). Value = 0x51A1 + ordinal. */
export const Salt = {
  CELL_COUNT: 0,
  STAR_X: 1,
  STAR_Y: 2,
  DENSITY_ACCEPT: 3,
  STAR_ID: 4,
  SPECTRAL_CLASS: 5,
  BRIGHTNESS: 6,
  STAR_SIZE: 7,
} as const;

const SALT_BASE = 0x51a1;

function salt(ordinal: number): bigint {
  return BigInt(SALT_BASE + ordinal);
}

// ---------------------------------------------------------------------------
// SplitMix64 hashing in BigInt — bit-exact with SeedHash.java
// ---------------------------------------------------------------------------

const U64 = 0xffffffffffffffffn;
const GOLDEN = 0x9e3779b97f4a7c15n;
const MIX_A = 0xbf58476d1ce4e5b9n;
const MIX_B = 0x94d049bb133111ebn;

/** Truncate a BigInt to unsigned 64 bits (matches Java long wrap-around). */
function mask64(z: bigint): bigint {
  return z & U64;
}

/** SplitMix64 finaliser (SeedHash.mix). All ops masked to 64 bits. */
export function mix(z: bigint): bigint {
  z = mask64(z);
  z = mask64((z ^ (z >> 30n)) * MIX_A);
  z = mask64((z ^ (z >> 27n)) * MIX_B);
  return mask64(z ^ (z >> 31n));
}

/**
 * Fold an ordered list of 64-bit values into one hash (SeedHash.combine).
 * Inputs are coerced to unsigned 64-bit two's-complement first, so a negative
 * cell coord hashes the same as Java's sign-extended long.
 */
export function combine(...values: bigint[]): bigint {
  let h = GOLDEN;
  for (const v of values) {
    h = mask64(h + GOLDEN);
    h = mix(h ^ mix(BigInt.asUintN(64, v)));
  }
  return mix(h);
}

/** Top 53 bits of a 64-bit hash mapped to [0,1) (SeedHash.toUnitDouble). */
export function toUnitDouble(hash: bigint): number {
  // (hash >>> 11) * 2^-53. The shifted value fits in 53 bits → safe integer.
  return Number(mask64(hash) >> 11n) * 2 ** -53;
}

/** unit(baseHash, salt) — an independent [0,1) stream for a salt. */
export function unit(baseHash: bigint, saltOrdinal: number): number {
  return toUnitDouble(combine(baseHash, salt(saltOrdinal)));
}

// ---------------------------------------------------------------------------
// Density field — bit-exact with SpiralDensityField.java
// ---------------------------------------------------------------------------

export function densityAt(x: number, y: number): number {
  const C = CATALOG_CONSTANTS;
  const r = Math.hypot(x, y);
  if (r > C.R_MAX) {
    return 0.0;
  }
  const radialEnvelope = Math.exp(-r / C.DISC_SCALE_LENGTH);
  const arm = armStrength(x, y, r);
  const bulge = bulgeStrength(r);
  return C.HALO_FLOOR + radialEnvelope * C.ARM_WEIGHT * arm + bulge;
}

function armStrength(x: number, y: number, r: number): number {
  const C = CATALOG_CONSTANTS;
  if (r < 1e-6) {
    return 1.0;
  }
  const phase =
    C.WIND *
    Math.log((Math.max(r, 1.0) / C.R_MAX) * C.ARM_LOG_SCALE + C.ARM_LOG_BIAS);
  const theta = Math.atan2(y, x);
  const armSpacing = (2.0 * Math.PI) / C.ARMS;
  let delta = theta - phase;
  delta = delta - armSpacing * Math.floor(delta / armSpacing + 0.5);
  const hw = C.ARM_HALF_WIDTH;
  return Math.exp(-(delta * delta) / (2.0 * hw * hw));
}

function bulgeStrength(r: number): number {
  const C = CATALOG_CONSTANTS;
  const t = r / C.BULGE_RADIUS;
  return C.BULGE_WEIGHT * Math.exp(-t * t);
}

// ---------------------------------------------------------------------------
// Catalog star + per-cell placement — bit-exact with the Java stack
// ---------------------------------------------------------------------------

/**
 * A fully-derived catalog star. `id` is a 64-bit value kept as a decimal string
 * (beyond JS safe-integer range) so it round-trips and compares against the
 * server exactly; callers that need a small numeric id for the renderer can hash
 * the string locally.
 */
export interface CatalogStar {
  readonly id: string;
  readonly x: number;
  readonly y: number;
  readonly spectral: SpectralClassName;
  readonly brightness: number;
  readonly size: number;
}

export interface CellAddress {
  readonly x: number;
  readonly y: number;
}

/** Candidate count for a cell (StarFieldGenerator.candidateCount). */
function candidateCount(cellHash: bigint): number {
  const u = unit(cellHash, Salt.CELL_COUNT);
  return Math.trunc(u * (CATALOG_CONSTANTS.MAX_CANDIDATES_PER_CELL + 1));
}

/**
 * The O(visible) entry point: generate every catalog star in one cell. Reads only
 * (gameSeed, cell); touches no other cell; bounded by MAX_CANDIDATES_PER_CELL.
 *
 * @param gameSeed per-match galaxy seed (bigint; pass via BigInt(seedNumber))
 * @param cell     integer cell address
 */
export function generateCell(gameSeed: bigint, cell: CellAddress): CatalogStar[] {
  const C = CATALOG_CONSTANTS;
  const cellHash = combine(gameSeed, BigInt(cell.x), BigInt(cell.y));
  const candidates = candidateCount(cellHash);

  const ox = cell.x * C.CELL_SIZE;
  const oy = cell.y * C.CELL_SIZE;
  const out: CatalogStar[] = [];

  for (let i = 0; i < candidates; i++) {
    const candHash = combine(cellHash, BigInt(i));
    const fx = unit(candHash, Salt.STAR_X);
    const fy = unit(candHash, Salt.STAR_Y);
    const x = ox + fx * C.CELL_SIZE;
    const y = oy + fy * C.CELL_SIZE;

    const accept = densityAt(x, y) / DENSITY_PEAK;
    const roll = unit(candHash, Salt.DENSITY_ACCEPT);
    if (roll >= accept) {
      continue; // rejected by density field
    }

    const id = combine(candHash, salt(Salt.STAR_ID));
    out.push(deriveStar(gameSeed, id, x, y));
  }
  return out;
}

/**
 * Derive the visual attributes for a star id and join with its position
 * (mirrors SystemGenerator's spectral/brightness/size slice + CatalogGenerator).
 */
export function deriveStar(
  gameSeed: bigint,
  id: bigint,
  x: number,
  y: number,
): CatalogStar {
  const base = combine(gameSeed, id);
  const spectral = spectralFromRoll(unit(base, Salt.SPECTRAL_CLASS));
  const brightness = lerp(spectral.minB, spectral.maxB, unit(base, Salt.BRIGHTNESS));
  const size = lerp(spectral.minSize, spectral.maxSize, unit(base, Salt.STAR_SIZE));
  return {
    id: bigintToUnsignedString(id),
    x,
    y,
    spectral: spectral.name,
    brightness,
    size,
  };
}

/** Weighted CDF spectral pick (SpectralClass.fromRoll). */
function spectralFromRoll(roll: number): (typeof SPECTRAL_CLASSES)[number] {
  const target = roll * SPECTRAL_TOTAL_WEIGHT;
  let acc = 0;
  for (const c of SPECTRAL_CLASSES) {
    acc += c.weight;
    if (target < acc) {
      return c;
    }
  }
  return SPECTRAL_CLASSES[SPECTRAL_CLASSES.length - 1]; // float guard → M
}

function lerp(lo: number, hi: number, t: number): number {
  return lo + (hi - lo) * t;
}

/** Java Long.toUnsignedString equivalent for a 64-bit BigInt. */
export function bigintToUnsignedString(v: bigint): string {
  return BigInt.asUintN(64, v).toString();
}
