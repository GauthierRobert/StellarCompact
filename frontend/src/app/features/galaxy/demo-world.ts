/**
 * Demo world (offline mode) — a self-consistent, deterministic galaxy slice the
 * frontend can run WITHOUT a backend, so the immersive galaxy + dashboard are
 * fully demonstrable standalone.
 *
 * It reuses the byte-parity {@link generateCell} catalog generator (the same
 * procedural field the server serves) to:
 *   1. pick a spread-out set of "active systems" near the galactic core, each
 *      with a stable small integer id, and
 *   2. synthesise LOD star-list tiles on demand, flagging those active systems'
 *      stars with their `activeSystemId` so the live-overlay join (ownership
 *      tint, territories, routes, fog) lights up exactly the systems the demo
 *      simulation drives.
 *
 * Pure + dependency-free (no Angular, no I/O). The simulation service and the
 * local tile provider both read from one DemoWorld instance, which is what keeps
 * the map and the dashboard telling the same story.
 */

import {
  CATALOG_CONSTANTS,
  generateCell,
  type CatalogStar,
  type CellAddress,
} from './catalog-generator';
import {
  normalizeBrightness,
  normalizeSize,
  spectralIndex,
  type RenderStar,
} from './render-model';
import type {
  AggregateTileDto,
  StarDto,
  StarListTileDto,
  TileAddress,
  TilePayloadDto,
} from './tile.service';

const R_MAX = CATALOG_CONSTANTS.R_MAX; // 1000
const CELL = CATALOG_CONSTANTS.CELL_SIZE; // 50

/** A chosen, simulatable system in the demo galaxy. */
export interface DemoSystem {
  /** Small integer id, also the overlay `systemId` (as a string). */
  readonly id: number;
  /** Catalog star id (bigint decimal string) this system sits on. */
  readonly starId: string;
  readonly x: number;
  readonly y: number;
  /** Spectral palette index + perceptual draw attrs (for labels/panels). */
  readonly k: number;
  readonly b: number;
  readonly sz: number;
  readonly name: string;
}

export interface DemoWorld {
  readonly seedNum: number;
  readonly rMax: number;
  readonly systems: readonly DemoSystem[];
  /** Catalog star-id string → active system id, for tile flagging. */
  readonly activeByStarId: ReadonlyMap<string, number>;
  /** Generate (and cache) an LOD tile payload for the given address. */
  tile(seed: string, addr: TileAddress): TilePayloadDto;
}

// ---------------------------------------------------------------------------
// Stable small-int hash for a catalog star id string (renderer point id).
// ---------------------------------------------------------------------------

export function hashStarId(idStr: string): number {
  let h = 2166136261 >>> 0;
  for (let i = 0; i < idStr.length; i++) {
    h ^= idStr.charCodeAt(i);
    h = Math.imul(h, 16777619) >>> 0;
  }
  return h % 2147483647;
}

// ---------------------------------------------------------------------------
// System naming — evocative, deterministic by index.
// ---------------------------------------------------------------------------

const NAME_STEMS = [
  'Sol', 'Vega', 'Rigel', 'Antares', 'Lyra', 'Orion', 'Draco', 'Cygnus',
  'Tauri', 'Ceti', 'Eridani', 'Hydrae', 'Centauri', 'Pavonis', 'Indi',
  'Maia', 'Atlas', 'Electra', 'Mizar', 'Alcor', 'Deneb', 'Altair', 'Vela',
  'Carina', 'Crux', 'Ara', 'Lupus', 'Corvus', 'Serpens', 'Aquila', 'Phoenix',
  'Hydra', 'Pyxis', 'Norma', 'Mensa', 'Fornax', 'Caelum', 'Dorado', 'Volans',
  'Tucana', 'Grus', 'Sculptor', 'Reticulum', 'Horologium', 'Octans', 'Apus',
  'Musca', 'Chamaeleon',
];
const NAME_SUFFIX = ['Prime', 'Major', 'Minor', 'II', 'III', 'IV', 'Gate', 'Reach', 'Verge', 'Hub'];

function systemName(index: number): string {
  const stem = NAME_STEMS[index % NAME_STEMS.length];
  const suffix = NAME_SUFFIX[Math.floor(index / NAME_STEMS.length) % NAME_SUFFIX.length];
  return Math.floor(index / NAME_STEMS.length) === 0 ? stem : `${stem} ${suffix}`;
}

// ---------------------------------------------------------------------------
// Builder
// ---------------------------------------------------------------------------

export interface DemoWorldOptions {
  /** Number of active systems to elevate from the scenery field. */
  readonly systemCount?: number;
  /** Half-extent (world units) of the dense core we pick systems from. */
  readonly coreRadius?: number;
  /** Minimum spacing between picked systems (world units). */
  readonly minSpacing?: number;
}

/**
 * Build the demo world for a numeric seed: generate the core scenery, elevate a
 * spread-out set of bright stars to active systems, and return a world handle
 * with an on-demand tile generator (cells cached internally).
 */
export function buildDemoWorld(
  seedNum: number,
  opts: DemoWorldOptions = {},
): DemoWorld {
  const systemCount = opts.systemCount ?? 46;
  const coreRadius = opts.coreRadius ?? 620;
  const minSpacing = opts.minSpacing ?? 64;
  const gameSeed = BigInt(seedNum);

  // Per-world cell cache so tile generation and system selection share work and
  // stay deterministic. Bounded by the cells we ever touch (the visible set).
  const cellCache = new Map<string, CatalogStar[]>();
  const cell = (cx: number, cy: number): CatalogStar[] => {
    const key = cx + ',' + cy;
    let stars = cellCache.get(key);
    if (!stars) {
      stars = generateCell(gameSeed, { x: cx, y: cy } as CellAddress);
      cellCache.set(key, stars);
    }
    return stars;
  };

  // --- elevate active systems from the core scenery ---
  const cellRange = Math.ceil(coreRadius / CELL);
  const candidates: CatalogStar[] = [];
  for (let cy = -cellRange; cy <= cellRange; cy++) {
    for (let cx = -cellRange; cx <= cellRange; cx++) {
      for (const s of cell(cx, cy)) {
        if (Math.hypot(s.x, s.y) <= coreRadius && s.brightness >= 0.9) {
          candidates.push(s);
        }
      }
    }
  }
  // Brightest first, then greedy spatial thinning for a well-spread set.
  candidates.sort((a, b) => b.brightness - a.brightness);
  const picked: CatalogStar[] = [];
  for (const c of candidates) {
    if (picked.length >= systemCount) {
      break;
    }
    let ok = true;
    for (const p of picked) {
      if (Math.hypot(p.x - c.x, p.y - c.y) < minSpacing) {
        ok = false;
        break;
      }
    }
    if (ok) {
      picked.push(c);
    }
  }

  const systems: DemoSystem[] = picked.map((s, i) => ({
    id: i + 1,
    starId: s.id,
    x: s.x,
    y: s.y,
    k: spectralIndex(s.spectral),
    b: normalizeBrightness(s.brightness),
    sz: normalizeSize(s.size),
    name: systemName(i),
  }));

  const activeByStarId = new Map<string, number>();
  for (const sys of systems) {
    activeByStarId.set(sys.starId, sys.id);
  }

  // --- on-demand tile generator (star-list + coarse aggregate) ---
  const tileCache = new Map<string, TilePayloadDto>();
  const tile = (seed: string, addr: TileAddress): TilePayloadDto => {
    const key = addr.level + '/' + addr.x + '/' + addr.y;
    const hit = tileCache.get(key);
    if (hit) {
      return hit;
    }
    const payload =
      addr.level >= 3
        ? starListTile(addr, cell, activeByStarId)
        : aggregateTile(addr, cell);
    tileCache.set(key, payload);
    return payload;
  };

  return { seedNum, rMax: R_MAX, systems, activeByStarId, tile };
}

// ---------------------------------------------------------------------------
// Tile synthesis
// ---------------------------------------------------------------------------

/** World-space bbox covered by a tile address (mirrors TileGrid.java maths). */
function tileBbox(addr: TileAddress): {
  minX: number;
  minY: number;
  maxX: number;
  maxY: number;
} {
  const n = 1 << addr.level;
  const side = (2 * R_MAX) / n;
  const minX = -R_MAX + addr.x * side;
  const minY = -R_MAX + addr.y * side;
  return { minX, minY, maxX: minX + side, maxY: minY + side };
}

function starListTile(
  addr: TileAddress,
  cell: (cx: number, cy: number) => CatalogStar[],
  activeByStarId: ReadonlyMap<string, number>,
): StarListTileDto {
  const bb = tileBbox(addr);
  // Cell index range covering this tile (cells are indexed from world/-R_MAX).
  const cx0 = Math.floor(bb.minX / CELL);
  const cx1 = Math.floor((bb.maxX - 1e-6) / CELL);
  const cy0 = Math.floor(bb.minY / CELL);
  const cy1 = Math.floor((bb.maxY - 1e-6) / CELL);
  const stars: StarDto[] = [];
  for (let cy = cy0; cy <= cy1; cy++) {
    for (let cx = cx0; cx <= cx1; cx++) {
      for (const s of cell(cx, cy)) {
        if (s.x < bb.minX || s.x >= bb.maxX || s.y < bb.minY || s.y >= bb.maxY) {
          continue;
        }
        const activeId = activeByStarId.get(s.id);
        stars.push({
          localId: hashStarId(s.id),
          x: s.x,
          y: s.y,
          spectral: s.spectral,
          brightness: s.brightness,
          size: s.size,
          activeSystemId: activeId ?? null,
        });
      }
    }
  }
  return {
    kind: 'starlist',
    level: addr.level,
    x: addr.x,
    y: addr.y,
    bbox: bb,
    stars,
    schemaVersion: 1,
  };
}

/** Coarse aggregate impostors for galaxy-scale levels (sampled scenery). */
function aggregateTile(
  addr: TileAddress,
  cell: (cx: number, cy: number) => CatalogStar[],
): AggregateTileDto {
  const bb = tileBbox(addr);
  // Sample a sparse sub-grid of cells inside the tile to estimate density,
  // emitting a handful of impostor blobs. Bounded work per tile.
  const cx0 = Math.floor(bb.minX / CELL);
  const cx1 = Math.floor((bb.maxX - 1e-6) / CELL);
  const cy0 = Math.floor(bb.minY / CELL);
  const cy1 = Math.floor((bb.maxY - 1e-6) / CELL);
  const stepX = Math.max(1, Math.floor((cx1 - cx0 + 1) / 4));
  const stepY = Math.max(1, Math.floor((cy1 - cy0 + 1) / 4));
  const impostors: { x: number; y: number; weight: number }[] = [];
  let total = 0;
  let peak = 0;
  let samples = 0;
  for (let cy = cy0; cy <= cy1; cy += stepY) {
    for (let cx = cx0; cx <= cx1; cx += stepX) {
      const cs = cell(cx, cy);
      let wx = 0;
      let wy = 0;
      let w = 0;
      for (const s of cs) {
        wx += s.x;
        wy += s.y;
        w += 1;
      }
      samples++;
      if (w > 0) {
        const weight = Math.min(1, w / 8);
        total += weight;
        peak = Math.max(peak, weight);
        impostors.push({ x: wx / w, y: wy / w, weight });
      }
    }
  }
  return {
    kind: 'aggregate',
    level: addr.level,
    x: addr.x,
    y: addr.y,
    bbox: bb,
    impostors,
    colorStats: {
      avgDensity: samples > 0 ? total / samples : 0,
      peakDensity: peak,
      sampleCount: samples,
    },
    schemaVersion: 1,
  };
}

// ---------------------------------------------------------------------------
// Convenience: a RenderStar for a demo system (used for HUD lookups/labels).
// ---------------------------------------------------------------------------

export function demoSystemRenderStar(sys: DemoSystem): RenderStar {
  return {
    id: hashStarId(sys.starId),
    x: sys.x,
    y: sys.y,
    k: sys.k,
    b: sys.b,
    sz: sys.sz,
    g: sys.k <= 1 ? 1 : 0,
    activeSystemId: sys.id,
  };
}
