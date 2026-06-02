import { Injectable, inject } from '@angular/core';
import type { WorldBbox } from '../../stores';
import {
  TileService,
  lodTransition,
  toRenderAggregates,
  toRenderStar,
  type LodTransition,
  type TileAddress,
  type TilePayloadDto,
} from './tile.service';
import type { RenderAggregate, RenderStar } from './render-model';

/**
 * Tile manager / scene assembler (E8-05) — the layer that sits BETWEEN the tile
 * source (TileService + the procedural generator) and the WebGL2 renderer.
 *
 * Responsibilities:
 *   1. Viewport tile selection: from the camera bbox + continuous zoom it picks
 *      the quadtree level(s) and the (x,y) tile range covering ONLY the screen
 *      — never the whole catalog (scale discipline). It delegates the addressing
 *      to TileService.tilesForBbox so the math stays byte-identical to the
 *      server's QuadTileScheme.
 *   2. Caching: a bounded render-mapped LRU keyed by quadkey holds the already-
 *      mapped RenderStar[]/RenderAggregate[] for each tile, so panning back over
 *      a region reuses the mapped arrays without re-decoding the payload (the raw
 *      payload promise LRU lives in TileService and de-dupes the HTTP fetch).
 *   3. LOD cross-fade: across a zoom boundary it fetches BOTH the outgoing and
 *      incoming levels and emits each level's stars/aggregates tagged with a
 *      blend weight (RenderStar.a / RenderAggregate.a). The renderer multiplies
 *      brightness by that weight, so the level we leave fades out as the level we
 *      enter fades in — no popping. Outside the fade window only one level is
 *      fetched (bounded data-in-flight).
 *
 * The manager owns NO drawing and NO camera logic; it produces the decoupled
 * RenderStar/RenderAggregate arrays the component folds into a RenderScene.
 */

/** Render-mapped LRU capacity (tiles). Bounded → memory O(capacity). */
export const MANAGER_CACHE_CAPACITY = 384;

/** The mapped render-input for a single tile (stars OR aggregates, never both). */
interface MappedTile {
  readonly stars: readonly RenderStar[];
  readonly aggregates: readonly RenderAggregate[];
}

/**
 * The assembled, cross-faded render input for a frame. `stars`/`aggregates` are
 * the concatenation of every active level's tiles, each entry already carrying
 * its level's blend weight in `.a`. Empty arrays are valid (nothing loaded yet).
 */
export interface LayeredScene {
  readonly stars: readonly RenderStar[];
  readonly aggregates: readonly RenderAggregate[];
  /** The transition plan used (exposed for diagnostics/tests). */
  readonly transition: LodTransition;
}

/** Apply a blend weight to a mapped tile's stars (immutable copy). */
function withStarWeight(
  stars: readonly RenderStar[],
  weight: number,
): RenderStar[] {
  if (weight >= 1) {
    // Avoid the alloc when fully opaque (the common no-fade path).
    return stars as RenderStar[];
  }
  return stars.map((s) => ({ ...s, a: weight }));
}

function withAggWeight(
  aggs: readonly RenderAggregate[],
  weight: number,
): RenderAggregate[] {
  if (weight >= 1) {
    return aggs as RenderAggregate[];
  }
  return aggs.map((g) => ({ ...g, a: weight }));
}

@Injectable({ providedIn: 'root' })
export class TileManager {
  private readonly tiles = inject(TileService);

  /** Render-mapped LRU keyed by quadkey (insertion-ordered Map → LRU). */
  private readonly mapped = new Map<string, MappedTile>();
  private readonly capacity = MANAGER_CACHE_CAPACITY;

  private key(seed: string, level: number, x: number, y: number): string {
    return `${seed}/${level}/${x}/${y}`;
  }

  /** Current mapped-LRU occupancy (bounded). For tests/diagnostics. */
  get cacheSize(): number {
    return this.mapped.size;
  }

  /** Map a payload to render input, caching the result keyed by its quadkey. */
  private ingest(seed: string, payload: TilePayloadDto): MappedTile {
    const k = this.key(seed, payload.level, payload.x, payload.y);
    const hit = this.mapped.get(k);
    if (hit) {
      // Cache HIT: refresh recency.
      this.mapped.delete(k);
      this.mapped.set(k, hit);
      return hit;
    }
    const m: MappedTile =
      payload.kind === 'starlist'
        ? { stars: payload.stars.map(toRenderStar), aggregates: [] }
        : { stars: [], aggregates: toRenderAggregates(payload) };
    this.mapped.set(k, m);
    while (this.mapped.size > this.capacity) {
      const oldest = this.mapped.keys().next().value;
      if (oldest === undefined) {
        break;
      }
      this.mapped.delete(oldest);
    }
    return m;
  }

  /** Whether a tile's MAPPED render input is cached (re-entry → hit). For tests. */
  hasMapped(seed: string, addr: TileAddress): boolean {
    return this.mapped.has(this.key(seed, addr.level, addr.x, addr.y));
  }

  /**
   * Fetch + assemble the cross-faded render input covering `bbox` at the camera
   * exponent `levelF`. Fetches only the viewport-covering tiles of the active
   * level(s); during a transition both levels are fetched and blended.
   */
  async assemble(
    seed: string,
    bbox: WorldBbox,
    levelF: number,
    rMax: number,
  ): Promise<LayeredScene> {
    const transition = lodTransition(levelF);

    const primaryPayloads = await this.tiles.fetchVisibleAtLevel(
      seed,
      bbox,
      transition.primary,
      rMax,
    );
    const secondaryPayloads =
      transition.secondary === null
        ? []
        : await this.tiles.fetchVisibleAtLevel(
            seed,
            bbox,
            transition.secondary,
            rMax,
          );

    const stars: RenderStar[] = [];
    const aggregates: RenderAggregate[] = [];

    for (const p of primaryPayloads) {
      const m = this.ingest(seed, p);
      stars.push(...withStarWeight(m.stars, transition.primaryWeight));
      aggregates.push(...withAggWeight(m.aggregates, transition.primaryWeight));
    }
    for (const p of secondaryPayloads) {
      const m = this.ingest(seed, p);
      stars.push(...withStarWeight(m.stars, transition.secondaryWeight));
      aggregates.push(
        ...withAggWeight(m.aggregates, transition.secondaryWeight),
      );
    }

    return { stars, aggregates, transition };
  }

  clear(): void {
    this.mapped.clear();
    this.tiles.clear();
  }
}
