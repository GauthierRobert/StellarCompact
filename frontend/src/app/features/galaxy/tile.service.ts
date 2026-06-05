import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { firstValueFrom } from 'rxjs';
import { DemoModeService } from '../../services/demo-mode.service';
import type { WorldBbox } from '../../stores';
import {
  normalizeBrightness,
  normalizeSize,
  spectralIndex,
  type RenderAggregate,
  type RenderStar,
} from './render-model';

/**
 * Galaxy tile service — fetches LOD tiles from the E8-04 REST endpoint and maps
 * their payloads into the renderer decoupled RenderStar / RenderAggregate
 * inputs.
 *
 *   GET /api/galaxy/{seed}/tile/{level}/{x}/{y}
 *     to AggregateTile (levels 0..2)  |  StarListTile (levels 3..MAX_LEVEL)
 *
 * Caching (E8-05): the endpoint sends Cache-Control immutable + ETag, so the
 * browser HTTP cache de-dupes repeat fetches across reloads. On top of that we
 * keep a bounded in-memory **LRU keyed by quadkey** (seed/level/x/y) of the
 * resolved payload promises, so panning back over a region is an in-process
 * cache HIT (no HTTP at all) and one tile is in flight at most once per address.
 * The LRU is capacity-bounded → memory O(capacity), never O(catalog) — the
 * scale-discipline invariant. We do NOT set conditional headers ourselves — the
 * browser owns revalidation; the immutable scenery tiles never actually change.
 *
 * The tile-grid maths mirror the backend QuadTileScheme/TileGrid exactly so
 * client tile addresses are byte-identical to what the server serves.
 */

// Tile-grid constants — MUST match backend QuadTileScheme.java / TileGrid.java.
// E8-04 widened the served depth from the E6-03 6-level small tier to the full
// deep-zoom descent (level 20: a tile spans 2000/2^20 ≈ 0.0019 units, far below
// CELL_SIZE = 50, i.e. individual-star scale). The client mirrors that so it can
// keep descending instead of stalling at level 6.
export const TILE_MAX_LEVEL = 20;
export const TILE_STAR_LIST_MIN_LEVEL = 3;
export const MAX_TILES_PER_VIEW = 64;
/**
 * Client-side payload-LRU capacity (bounded data-in-flight; scale discipline).
 * A viewport touches at most MAX_TILES_PER_VIEW tiles at one level plus the
 * adjacent cross-fade level; a few hundred entries is ample working-set headroom
 * while panning, and the cap guarantees memory is O(capacity), never O(catalog).
 */
export const TILE_CACHE_CAPACITY = 256;
/**
 * Continuous-LOD bias: the discrete tile level at camera exponent z is
 * round(z + LOD_LEVEL_BIAS). At fit (z = 0) this picks level 3 — the first
 * star-list level (the galaxy resolves into stars exactly as the PoC zoom does).
 */
export const LOD_LEVEL_BIAS = 3;
/**
 * Half-width (in z units) of the cross-fade window around each integer
 * level-boundary. Within ±this of a boundary both the outgoing and incoming
 * levels are rendered, blended by alpha, so the LOD swap never pops. 0.5 means
 * the fade spans the whole interval between two levels (always blending toward
 * the nearer neighbour); a smaller value gives a sharper but still pop-free swap.
 */
export const LOD_FADE_HALF_WIDTH = 0.35;

// Wire DTOs — mirror backend TilePayload.java (kind discriminator)
export interface TileBbox {
  readonly minX: number;
  readonly minY: number;
  readonly maxX: number;
  readonly maxY: number;
}

export interface ImpostorDto {
  readonly x: number;
  readonly y: number;
  readonly weight: number;
}

export interface ColorStatsDto {
  readonly avgDensity: number;
  readonly peakDensity: number;
  readonly sampleCount: number;
}

export interface AggregateTileDto {
  readonly kind: 'aggregate';
  readonly level: number;
  readonly x: number;
  readonly y: number;
  readonly bbox: TileBbox;
  readonly impostors: readonly ImpostorDto[];
  readonly colorStats: ColorStatsDto;
  readonly schemaVersion: number;
}

export interface StarDto {
  readonly localId: number;
  readonly x: number;
  readonly y: number;
  readonly spectral: string;
  readonly brightness: number;
  readonly size: number;
  readonly activeSystemId: number | null;
}

export interface StarListTileDto {
  readonly kind: 'starlist';
  readonly level: number;
  readonly x: number;
  readonly y: number;
  readonly bbox: TileBbox;
  readonly stars: readonly StarDto[];
  readonly schemaVersion: number;
}

export type TilePayloadDto = AggregateTileDto | StarListTileDto;

export interface TileAddress {
  readonly level: number;
  readonly x: number;
  readonly y: number;
}

// Pure tile-grid maths (exported for unit tests) — mirror TileGrid.java
export function tilesPerAxis(level: number): number {
  return 1 << level;
}

export function tileSide(level: number, rMax: number): number {
  return (2 * rMax) / tilesPerAxis(level);
}

export function isStarListLevel(level: number): boolean {
  return level >= TILE_STAR_LIST_MIN_LEVEL;
}

/**
 * Map the camera continuous LOD exponent (camera.levelF, the eased zoom
 * exponent z) to a discrete tile level in [0, MAX_LEVEL].
 *
 * The camera scale is fit * BASE^z, so each unit of z doubles the on-screen
 * size, matching one extra quadtree level (each level halves the tile span).
 * We bias by +3 so that at fit (z=0, whole galaxy on screen) we pull a coarse
 * aggregate level, crossing into the star-list tiers (level 3) as the user zooms
 * in — exactly the PoC "stars resolve as you zoom" behaviour.
 */
export function levelForZoom(levelF: number): number {
  const lvl = Math.round(levelF + LOD_LEVEL_BIAS);
  return lvl < 0 ? 0 : lvl > TILE_MAX_LEVEL ? TILE_MAX_LEVEL : lvl;
}

/**
 * The continuous (un-rounded, clamped) tile level for camera exponent z.
 * Its fractional part drives the cross-fade; `Math.round` of it is levelForZoom.
 */
export function levelFracForZoom(levelF: number): number {
  const lf = levelF + LOD_LEVEL_BIAS;
  return lf < 0 ? 0 : lf > TILE_MAX_LEVEL ? TILE_MAX_LEVEL : lf;
}

/** A cross-fade plan: which two tile levels to draw this frame and at what alpha. */
export interface LodTransition {
  /** The dominant (nearest) discrete level — the one we are settling toward. */
  readonly primary: number;
  /** Its blend weight in (0,1]. 1 when no fade is in progress. */
  readonly primaryWeight: number;
  /**
   * The adjacent level being faded in/out, or null when we are far enough from a
   * boundary that only `primary` is needed (no second fetch, no blending).
   */
  readonly secondary: number | null;
  /** Secondary blend weight in [0,1). 0 when `secondary` is null. */
  readonly secondaryWeight: number;
}

/**
 * Compute the LOD cross-fade plan for a continuous camera level (E8-05).
 *
 * Detail is a function of zoom, but a HARD swap at each integer boundary pops.
 * Instead, within ±LOD_FADE_HALF_WIDTH of a boundary we render BOTH the
 * outgoing and incoming levels and blend them by alpha: the level we are leaving
 * fades out as the level we are entering fades in, summing to ~1 so total
 * brightness is preserved (additive renderer). Outside the window only the
 * nearest level is drawn (secondary = null → no extra tiles fetched), keeping
 * per-frame work bounded.
 *
 * The schedule is symmetric and continuous in `levelF`: at a boundary the two
 * neighbours are each at 0.5; one frame either side they shift smoothly, so the
 * transition is seamless in both zoom directions.
 */
export function lodTransition(levelF: number): LodTransition {
  const lf = levelFracForZoom(levelF);
  const nearest = Math.round(lf);
  const frac = lf - nearest; // in [-0.5, 0.5]
  const dist = Math.abs(frac);
  // Far from a boundary → single level, full weight, no second fetch.
  if (dist <= 0.5 - LOD_FADE_HALF_WIDTH) {
    return {
      primary: nearest,
      primaryWeight: 1,
      secondary: null,
      secondaryWeight: 0,
    };
  }
  // Inside the fade window: blend toward the neighbour on the far side of the
  // boundary. t = 0 at the window edge (neighbour just appearing) → 0.5 at the
  // boundary itself (50/50).
  const neighbour = nearest + (frac >= 0 ? 1 : -1);
  const clampedNeighbour =
    neighbour < 0 ? 0 : neighbour > TILE_MAX_LEVEL ? TILE_MAX_LEVEL : neighbour;
  if (clampedNeighbour === nearest) {
    // At the grid edge there is no neighbour to fade to.
    return {
      primary: nearest,
      primaryWeight: 1,
      secondary: null,
      secondaryWeight: 0,
    };
  }
  // Linear ramp: secondary weight rises from 0 at the window edge to 0.5 at the
  // boundary; primary = 1 - secondary so the pair never exceeds full brightness.
  const into = (dist - (0.5 - LOD_FADE_HALF_WIDTH)) / LOD_FADE_HALF_WIDTH; // 0..1
  const secondaryWeight = 0.5 * into;
  return {
    primary: nearest,
    primaryWeight: 1 - secondaryWeight,
    secondary: clampedNeighbour,
    secondaryWeight,
  };
}

/**
 * Enumerate the tile addresses covering a world bbox at a given level.
 * Inclusive-min / clamped to the grid, capped at MAX_TILES_PER_VIEW so a single
 * frame never fans out unbounded work (scale-discipline invariant).
 */
export function tilesForBbox(
  bbox: WorldBbox,
  level: number,
  rMax: number,
): TileAddress[] {
  const n = tilesPerAxis(level);
  const side = tileSide(level, rMax);
  const toIdx = (w: number): number => Math.floor((w + rMax) / side);
  const clamp = (i: number): number => (i < 0 ? 0 : i >= n ? n - 1 : i);
  const x0 = clamp(toIdx(bbox.minX));
  const x1 = clamp(toIdx(bbox.maxX));
  const y0 = clamp(toIdx(bbox.minY));
  const y1 = clamp(toIdx(bbox.maxY));
  const out: TileAddress[] = [];
  for (let y = y0; y <= y1; y++) {
    for (let x = x0; x <= x1; x++) {
      out.push({ level, x, y });
      if (out.length >= MAX_TILES_PER_VIEW) {
        return out;
      }
    }
  }
  return out;
}

export function tileUrl(seed: string, addr: TileAddress): string {
  return `/api/galaxy/${seed}/tile/${addr.level}/${addr.x}/${addr.y}`;
}

// Render-input mapping (pure, exported for tests)
export function toRenderStar(s: StarDto): RenderStar {
  const k = spectralIndex(s.spectral);
  return {
    id: s.localId,
    x: s.x,
    y: s.y,
    k,
    b: normalizeBrightness(s.brightness),
    sz: normalizeSize(s.size),
    g: k <= 1 && s.size >= 4 ? 1 : 0,
    activeSystemId: s.activeSystemId,
  };
}

export function toRenderAggregates(t: AggregateTileDto): RenderAggregate[] {
  return t.impostors.map((im) => ({ x: im.x, y: im.y, weight: im.weight }));
}

@Injectable({ providedIn: 'root' })
export class TileService {
  private readonly http = inject(HttpClient);
  private readonly demo = inject(DemoModeService);

  /**
   * Bounded LRU keyed by quadkey (seed/level/x/y) of resolved-payload promises.
   * A Map preserves insertion order, so the oldest key is `keys().next()`; we
   * re-insert on access to mark it most-recently-used and evict from the front
   * once we exceed TILE_CACHE_CAPACITY. Holding the PROMISE (not the value)
   * coalesces concurrent fetches and survives in-flight panning.
   */
  private readonly cache = new Map<string, Promise<TilePayloadDto>>();
  private readonly capacity = TILE_CACHE_CAPACITY;

  private key(seed: string, a: TileAddress): string {
    return `${seed}/${a.level}/${a.x}/${a.y}`;
  }

  /** Mark a key most-recently-used (move to the end of the Map order). */
  private touch(k: string, p: Promise<TilePayloadDto>): void {
    this.cache.delete(k);
    this.cache.set(k, p);
    this.evictIfNeeded();
  }

  /** Evict the least-recently-used entries until within capacity. */
  private evictIfNeeded(): void {
    while (this.cache.size > this.capacity) {
      const oldest = this.cache.keys().next().value;
      if (oldest === undefined) {
        return;
      }
      this.cache.delete(oldest);
    }
  }

  /** Whether an address is currently cached (in-flight or resolved). For tests. */
  hasCached(seed: string, addr: TileAddress): boolean {
    return this.cache.has(this.key(seed, addr));
  }

  /** Current LRU occupancy — bounded by TILE_CACHE_CAPACITY. For tests/diagnostics. */
  get cacheSize(): number {
    return this.cache.size;
  }

  fetchTile(seed: string, addr: TileAddress): Promise<TilePayloadDto> {
    // Offline/demo galaxy: synthesise the tile from the in-process demo world
    // instead of hitting the backend, so the renderer has stars (and active
    // systems) with zero network. No-op when demo mode is inactive.
    if (this.demo.isActive()) {
      const local = this.demo.localTile(seed, addr);
      if (local) {
        return Promise.resolve(local);
      }
    }
    const k = this.key(seed, addr);
    const hit = this.cache.get(k);
    if (hit) {
      // Cache HIT: refresh recency, return the same promise (no HTTP).
      this.touch(k, hit);
      return hit;
    }
    const url = tileUrl(seed, addr);
    const p = firstValueFrom(this.http.get<TilePayloadDto>(url)).catch(
      (err) => {
        // A failed fetch must not stick in the LRU (so a retry can re-request).
        this.cache.delete(k);
        throw err;
      },
    );
    this.touch(k, p);
    return p;
  }

  /**
   * Fetch every tile covering `bbox` at an explicit discrete `level`. The tile
   * set is exactly tilesForBbox(bbox, level) — the covering set, never the whole
   * catalog. Failed tiles are dropped so one 4xx/5xx never blanks the frame.
   */
  async fetchVisibleAtLevel(
    seed: string,
    bbox: WorldBbox,
    level: number,
    rMax: number,
  ): Promise<TilePayloadDto[]> {
    const addrs = tilesForBbox(bbox, level, rMax);
    const settled = await Promise.allSettled(
      addrs.map((a) => this.fetchTile(seed, a)),
    );
    return settled
      .filter(
        (r): r is PromiseFulfilledResult<TilePayloadDto> =>
          r.status === 'fulfilled',
      )
      .map((r) => r.value);
  }

  /**
   * Fetch the tiles covering `bbox` at the discrete LOD level chosen from the
   * camera exponent. Retained for callers that do not cross-fade; the
   * TileManager (E8-05) uses fetchVisibleAtLevel directly for the two blended
   * levels.
   */
  fetchVisible(
    seed: string,
    bbox: WorldBbox,
    levelF: number,
    rMax: number,
  ): Promise<TilePayloadDto[]> {
    return this.fetchVisibleAtLevel(seed, bbox, levelForZoom(levelF), rMax);
  }

  clear(): void {
    this.cache.clear();
  }
}
