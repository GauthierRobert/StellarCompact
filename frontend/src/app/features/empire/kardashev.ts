/**
 * Kardashev model — the central progression spine of Stellar Compact.
 *
 * A civilization is measured by the energy it captures, in watts. The Kardashev
 * value compresses that onto a continuous scale:
 *
 *     K = (log10(W) - 6) / 10
 *
 * so Type I ≈ 10^16 W (K=1, all energy reaching the homeworld), Type II ≈ 10^26 W
 * (K=2, the full output of the star — a Dyson swarm/sphere) and Type III ≈ 10^36 W
 * (K=3, the luminosity of the whole galaxy). K is continuous and monotone in
 * captured watts, so progress is always legible.
 *
 * This module is the pure reference implementation (no Angular, no I/O); the
 * engine mirrors the same constants server-side (BOARD E12-06). See
 * docs/game-design/06-technology.md §6.
 */

// ---------------------------------------------------------------------------
// Tiers
// ---------------------------------------------------------------------------

export type KardashevTierId = 'K0' | 'K1' | 'K2' | 'K3';

export interface KardashevBand {
  readonly id: KardashevTierId;
  /** Display label, e.g. "Type II". */
  readonly label: string;
  /** Lower K bound (inclusive). */
  readonly kFrom: number;
  /** Upper K bound (exclusive); Infinity for the top band. */
  readonly kTo: number;
  /** Evocative one-line characterisation. */
  readonly blurb: string;
  /** Accent colour for gauges/badges. */
  readonly accent: string;
}

/** The four civilization bands, low to high. */
export const KARDASHEV_BANDS: readonly KardashevBand[] = [
  {
    id: 'K0',
    label: 'Type 0',
    kFrom: 0,
    kTo: 1,
    blurb: 'Planetary infancy — mines, farms and solar arrays on a young world.',
    accent: '#9fb2c8',
  },
  {
    id: 'K1',
    label: 'Type I',
    kFrom: 1,
    kTo: 2,
    blurb: 'Planetary mastery — fusion grids, weather control, arcologies, orbital collectors.',
    accent: '#7fd8ef',
  },
  {
    id: 'K2',
    label: 'Type II',
    kFrom: 2,
    kTo: 3,
    blurb: 'Stellar mastery — Dyson swarms, star-lifting, stellar engines, matrioshka minds.',
    accent: '#e8c061',
  },
  {
    id: 'K3',
    label: 'Type III',
    kFrom: 3,
    kTo: Infinity,
    blurb: 'Galactic ascendancy — black-hole taps, Birch planets, galaxy-spanning power.',
    accent: '#e89ac4',
  },
];

// ---------------------------------------------------------------------------
// Core conversions
// ---------------------------------------------------------------------------

/** Captured power (watts) → continuous Kardashev value, clamped at 0. */
export function kFromWatts(watts: number): number {
  if (watts <= 1) return 0;
  const k = (Math.log10(watts) - 6) / 10;
  return k < 0 ? 0 : k;
}

/** Inverse: a Kardashev value → the watts it represents. */
export function wattsFromK(k: number): number {
  return Math.pow(10, k * 10 + 6);
}

/** The band a K-value falls in (defaults to the top band above 3). */
export function bandForK(k: number): KardashevBand {
  for (const b of KARDASHEV_BANDS) {
    if (k >= b.kFrom && k < b.kTo) return b;
  }
  return KARDASHEV_BANDS[KARDASHEV_BANDS.length - 1];
}

/** Progress [0,1] from the current band's floor to the next whole tier. */
export function progressToNextTier(k: number): number {
  const floor = Math.floor(k);
  const p = k - floor;
  return p < 0 ? 0 : p > 1 ? 1 : p;
}

/** Watts still needed to reach the next whole Kardashev tier. */
export function wattsToNextTier(k: number): number {
  const next = Math.floor(k) + 1;
  return Math.max(0, wattsFromK(next) - wattsFromK(k));
}

// ---------------------------------------------------------------------------
// Energy-capture breakdown
// ---------------------------------------------------------------------------

/**
 * The named sources that contribute captured watts. The engine recomputes these
 * each tick from authoritative buildings/megastructures; the breakdown sums to
 * the faction's total captured power.
 */
export type EnergySourceKind =
  | 'planetaryGrid' // fusion + ground/atmospheric solar across owned worlds
  | 'orbitalCollectors' // solar-power satellites / orbital lattices
  | 'dysonSwarm' // partial-to-full Dyson swarm shells around a star
  | 'stellarEngine' // star-lifting / Shkadov-Caplan thrusters tapping the star
  | 'blackHoleTap'; // Penrose / Blandford–Znajek extraction near a singularity

export interface EnergySource {
  readonly kind: EnergySourceKind;
  /** Human label, e.g. "Dyson Swarm". */
  readonly label: string;
  /** Captured power from this source, in watts. */
  readonly watts: number;
}

export const ENERGY_SOURCE_LABELS: Record<EnergySourceKind, string> = {
  planetaryGrid: 'Planetary Grid',
  orbitalCollectors: 'Orbital Collectors',
  dysonSwarm: 'Dyson Swarm',
  stellarEngine: 'Stellar Engine',
  blackHoleTap: 'Black-Hole Tap',
};

/**
 * Build a normalised, descending-by-watts breakdown plus the derived K-value and
 * band. Sources contributing ~0 are dropped so the UI stays clean.
 */
export function summariseCapture(sources: readonly EnergySource[]): {
  totalWatts: number;
  k: number;
  band: KardashevBand;
  progress: number;
  sources: readonly EnergySource[];
} {
  const kept = sources.filter((s) => s.watts > 1).slice().sort((a, b) => b.watts - a.watts);
  const totalWatts = kept.reduce((sum, s) => sum + s.watts, 0);
  const k = kFromWatts(totalWatts);
  return { totalWatts, k, band: bandForK(k), progress: progressToNextTier(k), sources: kept };
}

// ---------------------------------------------------------------------------
// Formatting
// ---------------------------------------------------------------------------

const SI_PREFIXES = ['', 'k', 'M', 'G', 'T', 'P', 'E', 'Z', 'Y', 'R', 'Q'];

/** Format a wattage with an SI prefix, e.g. 4.2e25 → "42.0 YW". */
export function formatWatts(watts: number): string {
  if (watts <= 0) return '0 W';
  const exp = Math.floor(Math.log10(watts) / 3);
  const clamped = Math.min(exp, SI_PREFIXES.length - 1);
  const scaled = watts / Math.pow(10, clamped * 3);
  const prefix = SI_PREFIXES[clamped];
  if (clamped >= SI_PREFIXES.length - 1) {
    // Past the SI table (≳10^33 W) fall back to scientific notation.
    return watts.toExponential(2).replace('e+', '×10^') + ' W';
  }
  return `${scaled.toFixed(scaled >= 100 ? 0 : 1)} ${prefix}W`;
}

/** Format a K-value to two decimals, e.g. "K 1.43". */
export function formatK(k: number): string {
  return 'K ' + k.toFixed(2);
}
