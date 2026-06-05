/**
 * Interstellar objects (offline/demo) — a deterministic roster of discrete
 * deep-space phenomena scattered through the galaxy: emission nebulae, black
 * holes, pulsars, wormholes, asteroid belts, rogue planets and supernova
 * remnants. These are the "other interstellar objects" beyond the star field.
 *
 * Pure + dependency-free (no Angular, no I/O). Each object carries BOTH its
 * render attributes (position, extent, tints, animation phase → {@link toRenderObject})
 * and its HUD detail-panel content (name, classification, blurb, stat readouts
 * → {@link toSelectedObject}), so the map and the side panel always agree.
 *
 * The roster is seeded from the game seed, so the same seed always yields the
 * same objects in the same places — consistent with the determinism principle.
 * A real backend would stream these through the tile/overlay path; here they are
 * synthesised client-side for the standalone demo.
 */

import type {
  InterstellarKind,
  RenderObject,
} from './render-model';
import type { ObjectStat, SelectedObjectInfo } from '../../stores/selection.store';

/** A generated interstellar object with render + panel data. */
export interface DemoObject {
  readonly id: string;
  readonly kind: InterstellarKind;
  readonly x: number;
  readonly y: number;
  readonly r: number;
  readonly tint: readonly [number, number, number];
  readonly tint2: readonly [number, number, number];
  readonly phase: number;
  readonly name: string;
  readonly kindLabel: string;
  readonly description: string;
  readonly stats: readonly ObjectStat[];
  /** Header accent colour hex derived from the primary tint. */
  readonly accent: string;
}

// ---------------------------------------------------------------------------
// Deterministic RNG (mulberry32) — local so generation is side-effect free.
// ---------------------------------------------------------------------------

function makeRng(seed: number): () => number {
  let s = seed >>> 0 || 1;
  return () => {
    s = (s + 0x6d2b79f5) >>> 0;
    let t = s;
    t = Math.imul(t ^ (t >>> 15), t | 1);
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

// ---------------------------------------------------------------------------
// Per-kind visual + descriptive blueprints.
// ---------------------------------------------------------------------------

interface KindSpec {
  readonly kind: InterstellarKind;
  readonly label: string;
  /** Min/max visual radius in world units. */
  readonly rMin: number;
  readonly rMax: number;
  /** Candidate primary/secondary tint pairs (RGB 0..1). */
  readonly palettes: readonly {
    readonly a: readonly [number, number, number];
    readonly b: readonly [number, number, number];
  }[];
  readonly names: readonly string[];
  readonly blurb: string;
  /** Build the stat readouts (rng available for tasteful variation). */
  readonly stats: (rng: () => number) => readonly ObjectStat[];
}

const r1 = (rng: () => number, lo: number, hi: number) => lo + rng() * (hi - lo);
const ri = (rng: () => number, lo: number, hi: number) => Math.round(r1(rng, lo, hi));

const KIND_SPECS: readonly KindSpec[] = [
  {
    kind: 'nebula',
    label: 'Emission Nebula',
    rMin: 80,
    rMax: 150,
    palettes: [
      { a: [1.0, 0.34, 0.42], b: [0.36, 0.4, 1.0] }, // Orion red + blue
      { a: [0.36, 0.95, 0.8], b: [0.3, 0.5, 1.0] }, // teal + blue
      { a: [1.0, 0.55, 0.3], b: [0.8, 0.3, 0.7] }, // Carina gold + magenta
      { a: [0.7, 0.45, 1.0], b: [0.3, 0.7, 1.0] }, // violet + cyan
    ],
    names: [
      'Veil of Cygnus', 'Ember Shroud', 'Maiden’s Tears', 'Carmine Drift',
      'Halcyon Cloud', 'Seraph Nebula', 'Pillars of Vash', 'Auroral Mist',
    ],
    blurb:
      'A vast cloud of ionised hydrogen and stardust, lit from within by hot young stars. A stellar nursery — and rich in volatiles for any fleet that can brave the radiation.',
    stats: (rng) => [
      { label: 'Span', value: `${ri(rng, 12, 90)} ly` },
      { label: 'Composition', value: 'H II · He · dust' },
      { label: 'Young stars', value: String(ri(rng, 40, 900)) },
      { label: 'Volatiles', value: ['Rich', 'Abundant', 'Vast'][ri(rng, 0, 2)] },
      { label: 'Hazard', value: 'Ionising radiation' },
    ],
  },
  {
    kind: 'blackhole',
    label: 'Black Hole',
    rMin: 30,
    rMax: 48,
    palettes: [
      { a: [1.0, 0.62, 0.24], b: [1.0, 0.9, 0.7] },
      { a: [1.0, 0.5, 0.2], b: [1.0, 0.82, 0.55] },
    ],
    names: [
      'Cygnus Maw', 'The Devourer', 'Erebus', 'Stygian Eye',
      'Tartarus Well', 'Nyx Singularity',
    ],
    blurb:
      'A stellar-mass singularity wrapped in a blazing accretion disk, bending the light of the stars behind it into a perfect photon ring. Catastrophic up close; a gravitational anchor for whole trade lattices at range.',
    stats: (rng) => [
      { label: 'Mass', value: `${ri(rng, 6, 28)} M☉` },
      { label: 'Schwarzschild r', value: `${ri(rng, 18, 84)} km` },
      { label: 'Disk temp', value: `${ri(rng, 4, 12)}×10⁶ K` },
      { label: 'Jet', value: rng() > 0.5 ? 'Active relativistic' : 'Dormant' },
      { label: 'Hazard', value: 'Extreme tidal' },
    ],
  },
  {
    kind: 'pulsar',
    label: 'Pulsar',
    rMin: 20,
    rMax: 30,
    palettes: [
      { a: [0.7, 0.92, 1.0], b: [0.5, 0.8, 1.0] },
      { a: [0.85, 0.95, 1.0], b: [0.6, 0.7, 1.0] },
    ],
    names: [
      'Lighthouse PSR', 'Metronome', 'The Beacon', 'Drum of Vela',
      'Tolling Star', 'Hammerbeat',
    ],
    blurb:
      'A neutron star spinning hundreds of times a second, raking the void with twin beams of synchrotron light. Its pulse is a galaxy-wide clock — invaluable for navigation, lethal in the beam path.',
    stats: (rng) => [
      { label: 'Spin period', value: `${(r1(rng, 1.4, 89)).toFixed(1)} ms` },
      { label: 'Mass', value: `${(r1(rng, 1.2, 2.1)).toFixed(2)} M☉` },
      { label: 'Mag. field', value: `${ri(rng, 8, 40)}×10¹² G` },
      { label: 'Use', value: 'Pulsar navigation' },
      { label: 'Hazard', value: 'Beam sweep' },
    ],
  },
  {
    kind: 'wormhole',
    label: 'Wormhole',
    rMin: 26,
    rMax: 40,
    palettes: [
      { a: [0.72, 0.5, 1.0], b: [0.4, 0.92, 1.0] },
      { a: [0.55, 0.65, 1.0], b: [0.7, 1.0, 0.95] },
    ],
    names: [
      'The Threshold', 'Gate of Ys', 'Annwn Aperture', 'Mirror Rift',
      'Throat of Janus', 'The Fold',
    ],
    blurb:
      'A traversable fold in spacetime — a swirling aperture that links to a distant region of the galaxy. Whoever holds both mouths holds a shortcut armies would die for.',
    stats: (rng) => [
      { label: 'Aperture', value: `${ri(rng, 2, 18)} km` },
      { label: 'Stability', value: ['Flickering', 'Marginal', 'Stable'][ri(rng, 0, 2)] },
      { label: 'Transit', value: `~${ri(rng, 1, 6)} t` },
      { label: 'Far terminus', value: 'Uncharted' },
      { label: 'Value', value: 'Strategic' },
    ],
  },
  {
    kind: 'asteroidField',
    label: 'Asteroid Belt',
    rMin: 55,
    rMax: 100,
    palettes: [
      { a: [0.66, 0.58, 0.48], b: [0.5, 0.46, 0.42] },
      { a: [0.72, 0.6, 0.4], b: [0.45, 0.42, 0.38] },
    ],
    names: [
      'Shattered Reach', 'Tycho Belt', 'The Scatterlands', 'Hammerfall Field',
      'Iron Drift', 'Prospector’s Run',
    ],
    blurb:
      'A scattered belt of rock and metal — the bones of a planet that never formed. Slow to cross and easy to ambush in, but a mining concern’s dream of raw minerals and rare alloys.',
    stats: (rng) => [
      { label: 'Bodies', value: `${ri(rng, 4, 90)}k` },
      { label: 'Minerals', value: ['Good', 'Rich', 'Exceptional'][ri(rng, 0, 2)] },
      { label: 'Rare alloys', value: rng() > 0.4 ? 'Present' : 'Trace' },
      { label: 'Mining', value: 'Belt-rated' },
      { label: 'Hazard', value: 'Collision' },
    ],
  },
  {
    kind: 'roguePlanet',
    label: 'Rogue Planet',
    rMin: 16,
    rMax: 24,
    palettes: [
      { a: [0.42, 0.5, 0.66], b: [0.2, 0.26, 0.4] },
      { a: [0.5, 0.42, 0.5], b: [0.24, 0.2, 0.3] },
    ],
    names: [
      'Wanderer', 'Hespera', 'The Orphan', 'Nyctos',
      'Driftworld', 'Pale Exile',
    ],
    blurb:
      'A sunless world cast out of its system, drifting alone through the dark between stars. Frozen on the surface, but geothermally alive beneath — a hidden refuge for those who do not wish to be found.',
    stats: (rng) => [
      { label: 'Mass', value: `${(r1(rng, 0.3, 4)).toFixed(1)} M⊕` },
      { label: 'Surface', value: `${ri(rng, 30, 60)} K` },
      { label: 'Subsurface', value: rng() > 0.5 ? 'Liquid ocean' : 'Geothermal' },
      { label: 'Visibility', value: 'Very low' },
      { label: 'Use', value: 'Covert base' },
    ],
  },
  {
    kind: 'supernovaRemnant',
    label: 'Supernova Remnant',
    rMin: 90,
    rMax: 160,
    palettes: [
      { a: [0.4, 1.0, 0.82], b: [1.0, 0.4, 0.5] },
      { a: [0.5, 0.95, 1.0], b: [1.0, 0.55, 0.4] },
    ],
    names: [
      'Crab’s Wake', 'Cassandra’s Shell', 'The Cinder Ring', 'Phoenix Remnant',
      'Dirge of Lupus', 'Ashfall',
    ],
    blurb:
      'The expanding shock shell of a star that died in fire, enriching nearby space with heavy elements. Beautiful and bountiful — its filaments seed the alloys of empires, if you can survive the shockfront.',
    stats: (rng) => [
      { label: 'Age', value: `${ri(rng, 1, 30)}k yr` },
      { label: 'Shell radius', value: `${ri(rng, 8, 60)} ly` },
      { label: 'Heavy elements', value: 'Enriched' },
      { label: 'Compact core', value: rng() > 0.5 ? 'Neutron star' : 'None found' },
      { label: 'Hazard', value: 'Shockfront' },
    ],
  },
];

// The mix of objects to place (deterministic order; positions are seeded). The
// roster favours nebulae + belts (common) over black holes/wormholes (rare).
const ROSTER: readonly InterstellarKind[] = [
  'nebula', 'nebula', 'nebula', 'nebula',
  'asteroidField', 'asteroidField', 'asteroidField',
  'supernovaRemnant', 'supernovaRemnant',
  'pulsar', 'pulsar',
  'blackhole', 'blackhole',
  'wormhole',
  'roguePlanet', 'roguePlanet',
];

// ---------------------------------------------------------------------------
// Builder
// ---------------------------------------------------------------------------

/**
 * Generate the deterministic interstellar-object roster for a numeric seed,
 * spread across the galaxy disk with simple spacing rejection so objects do not
 * pile up. Names cycle deterministically per kind.
 */
export function buildInterstellarObjects(
  seedNum: number,
  rMax: number,
): DemoObject[] {
  const rng = makeRng((seedNum * 0x9e3779b1) >>> 0);
  const out: DemoObject[] = [];
  const nameCounters = new Map<InterstellarKind, number>();
  const placed: { x: number; y: number; r: number }[] = [];

  for (let i = 0; i < ROSTER.length; i++) {
    const kind = ROSTER[i];
    const spec = KIND_SPECS.find((s) => s.kind === kind)!;
    const radius = r1(rng, spec.rMin, spec.rMax);

    // Reject positions that overlap an already-placed object (bounded retries).
    let x = 0;
    let y = 0;
    let ok = false;
    for (let attempt = 0; attempt < 24 && !ok; attempt++) {
      // Place across the disk, biased away from the dense core so objects read
      // against darker space (ring 0.28..0.92 of the radius).
      const ang = rng() * Math.PI * 2;
      const rr = (0.28 + rng() * 0.64) * rMax;
      x = Math.cos(ang) * rr;
      y = Math.sin(ang) * rr;
      ok = true;
      for (const p of placed) {
        const minD = (p.r + radius) * 1.15;
        if ((p.x - x) ** 2 + (p.y - y) ** 2 < minD * minD) {
          ok = false;
          break;
        }
      }
    }
    placed.push({ x, y, r: radius });

    const pal = spec.palettes[ri(rng, 0, spec.palettes.length - 1)];
    const nIdx = nameCounters.get(kind) ?? 0;
    nameCounters.set(kind, nIdx + 1);
    const name = spec.names[nIdx % spec.names.length];
    const phase = rng() * Math.PI * 2;

    out.push({
      id: `obj-${i + 1}`,
      kind,
      x,
      y,
      r: radius,
      tint: pal.a,
      tint2: pal.b,
      phase,
      name,
      kindLabel: spec.label,
      description: spec.blurb,
      stats: spec.stats(rng),
      accent: rgbToHex(pal.a),
    });
  }
  return out;
}

// ---------------------------------------------------------------------------
// Mappers to the render + selection contracts.
// ---------------------------------------------------------------------------

/** Project a demo object to the renderer's {@link RenderObject} contract. */
export function toRenderObject(
  o: DemoObject,
  selectedId: string | null,
): RenderObject {
  return {
    id: o.id,
    kind: o.kind,
    x: o.x,
    y: o.y,
    r: o.r,
    tint: o.tint,
    tint2: o.tint2,
    phase: o.phase,
    selected: o.id === selectedId,
  };
}

/** Project a demo object to the HUD's {@link SelectedObjectInfo} contract. */
export function toSelectedObject(o: DemoObject): SelectedObjectInfo {
  return {
    objectId: o.id,
    kind: o.kind,
    name: o.name,
    kindLabel: o.kindLabel,
    description: o.description,
    stats: o.stats,
    accent: o.accent,
  };
}

function rgbToHex(c: readonly [number, number, number]): string {
  const h = (v: number) =>
    Math.max(0, Math.min(255, Math.round(v * 255)))
      .toString(16)
      .padStart(2, '0');
  return `#${h(c[0])}${h(c[1])}${h(c[2])}`;
}
