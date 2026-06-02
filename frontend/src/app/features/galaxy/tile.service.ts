import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { firstValueFrom } from 'rxjs';
import type { WorldBbox } from '../../stores';
import {
  normalizeBrightness,
  normalizeSize,
  spectralIndex,
  type RenderAggregate,
  type RenderStar,
} from './render-model';

/**
 * Galaxy tile service — fetches LOD tiles from the E6-03 REST endpoint and maps
 * their payloads into the renderer decoupled RenderStar / RenderAggregate
 * inputs.
 *
 *   GET /api/galaxy/{seed}/tile/{level}/{x}/{y}
 *     to AggregateTile (levels 0..2)  |  StarListTile (levels 3..6)
 *
 * Caching: the endpoint sends Cache-Control immutable + ETag, so the browser
 * HTTP cache de-dupes repeat fetches; we add a small in-memory promise cache so
 * one tile is in flight at most once per address while panning. We do NOT set
 * conditional headers ourselves — the browser owns revalidation.
 *
 * The tile-grid maths mirror the backend TileGrid exactly so client tile
 * addresses are byte-identical to what the server serves.
 */

// Tile-grid constants — MUST match backend TileGrid.java
export const TILE_MAX_LEVEL = 6;
export const TILE_STAR_LIST_MIN_LEVEL = 3;
export const MAX_TILES_PER_VIEW = 64;

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
  const lvl = Math.round(levelF + 3);
  return lvl < 0 ? 0 : lvl > TILE_MAX_LEVEL ? TILE_MAX_LEVEL : lvl;
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

  private readonly cache = new Map<string, Promise<TilePayloadDto>>();

  private key(seed: string, a: TileAddress): string {
    return `${seed}/${a.level}/${a.x}/${a.y}`;
  }

  fetchTile(seed: string, addr: TileAddress): Promise<TilePayloadDto> {
    const k = this.key(seed, addr);
    const hit = this.cache.get(k);
    if (hit) {
      return hit;
    }
    const url = tileUrl(seed, addr);
    const p = firstValueFrom(this.http.get<TilePayloadDto>(url)).catch(
      (err) => {
        this.cache.delete(k);
        throw err;
      },
    );
    this.cache.set(k, p);
    return p;
  }

  async fetchVisible(
    seed: string,
    bbox: WorldBbox,
    levelF: number,
    rMax: number,
  ): Promise<TilePayloadDto[]> {
    const level = levelForZoom(levelF);
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

  clear(): void {
    this.cache.clear();
  }
}
