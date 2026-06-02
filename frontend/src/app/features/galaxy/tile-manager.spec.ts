import { TestBed } from '@angular/core/testing';
import { provideZonelessChangeDetection } from '@angular/core';
import { provideHttpClient } from '@angular/common/http';
import {
  HttpTestingController,
  provideHttpClientTesting,
} from '@angular/common/http/testing';
import { TileManager } from './tile-manager';
import {
  TileService,
  type StarListTileDto,
  type AggregateTileDto,
} from './tile.service';

/**
 * E8-05 done-when coverage for the scene assembler:
 *   - viewport tile fetch covers the screen set (not the whole catalog),
 *   - re-entering a region is a cache hit (no re-decode / no re-fetch),
 *   - the LOD cross-fade renders BOTH levels with blend weights mid-transition
 *     (the structural no-popping guarantee), summing to full brightness.
 */
describe('TileManager (viewport fetch + LRU + LOD cross-fade)', () => {
  let mgr: TileManager;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        provideZonelessChangeDetection(),
        provideHttpClient(),
        provideHttpClientTesting(),
        TileManager,
        TileService,
      ],
    });
    mgr = TestBed.inject(TileManager);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  function flushStarTiles(level: number, starsPerTile: number): number {
    const reqs = http.match((r) =>
      r.url.startsWith(`/api/galaxy/s1/tile/${level}/`),
    );
    for (const r of reqs) {
      const parts = r.request.url.split('/');
      const x = Number(parts[parts.length - 2]);
      const y = Number(parts[parts.length - 1]);
      r.flush({
        kind: 'starlist',
        level,
        x,
        y,
        bbox: { minX: 0, minY: 0, maxX: 1, maxY: 1 },
        stars: Array.from({ length: starsPerTile }, (_, i) => ({
          localId: i,
          x: 0,
          y: 0,
          spectral: 'G',
          brightness: 1,
          size: 1,
          activeSystemId: null,
        })),
        schemaVersion: 1,
      } satisfies StarListTileDto);
    }
    return reqs.length;
  }

  /**
   * Flush every pending star tile at any of `levels`, yielding to microtasks
   * between passes. assemble() awaits the primary fetch before issuing the
   * secondary, so secondary requests appear only after the primary promises
   * resolve — this drains across those turns deterministically. Returns the
   * per-level flushed counts. Stops once a quiet pass (no requests at all)
   * follows at least one flush.
   */
  async function drainStarTiles(
    levels: readonly number[],
    starsPerTile: number,
  ): Promise<Record<number, number>> {
    const totals: Record<number, number> = {};
    for (const l of levels) {
      totals[l] = 0;
    }
    // Fixed generous pass count: each pass yields enough microtask turns for the
    // firstValueFrom + allSettled chain (and assemble's secondary fetch) to
    // register the next batch. No early break — late-registering requests (the
    // secondary level) are picked up on a later pass.
    for (let pass = 0; pass < 40; pass++) {
      for (const l of levels) {
        totals[l] += flushStarTiles(l, starsPerTile);
      }
      await Promise.resolve();
      await Promise.resolve();
      await Promise.resolve();
    }
    return totals;
  }

  it('fetches only the viewport-covering tiles at the active level', async () => {
    // z=0 (mid-interval) -> single level 3, a small bbox covers one tile.
    const p = mgr.assemble('s1', { minX: 0, minY: 0, maxX: 10, maxY: 10 }, 0, 1000);
    const n = flushStarTiles(3, 2);
    const scene = await p;
    // Only the covering set was requested (not 8x8 = 64 level-3 tiles).
    expect(n).toBeGreaterThan(0);
    expect(n).toBeLessThan(64);
    expect(scene.stars.length).toBe(n * 2);
    // Mid-interval: one level, full opacity, no fade.
    expect(scene.transition.secondary).toBeNull();
    expect(scene.stars.every((s) => s.a === undefined || s.a >= 1)).toBe(true);
  });

  it('re-entering the same region re-uses cached tiles (no second HTTP call)', async () => {
    const bbox = { minX: 0, minY: 0, maxX: 10, maxY: 10 };
    const p1 = mgr.assemble('s1', bbox, 0, 1000);
    const n1 = flushStarTiles(3, 1);
    const first = await p1;
    expect(first.stars.length).toBe(n1);

    // Second assemble of the same viewport: every covering tile is cached, so
    // no HTTP request should be issued at all.
    const p2 = mgr.assemble('s1', bbox, 0, 1000);
    http.expectNone((r) => r.url.startsWith('/api/galaxy/s1/tile/3/'));
    const second = await p2;
    expect(second.stars.length).toBe(n1);
    expect(mgr.hasMapped('s1', { level: 3, x: 4, y: 4 })).toBe(true);
  });

  it('cross-fade renders BOTH levels with blend weights mid-transition', async () => {
    // z=0.5 -> levels 3 and 4 are blended 50/50 (the no-popping window).
    const p = mgr.assemble(
      's1',
      { minX: 0, minY: 0, maxX: 10, maxY: 10 },
      0.5,
      1000,
    );
    // Primary (level 4 at z=0.5) is fetched first; its resolution then triggers
    // the secondary (level 3) fetch — drain both across microtask turns.
    const counts = await drainStarTiles([3, 4], 1);
    const n3 = counts[3];
    const n4 = counts[4];
    const scene = await p;
    expect(scene.transition.secondary).not.toBeNull();
    // Both levels contributed stars to the same frame.
    expect(n3).toBeGreaterThan(0);
    expect(n4).toBeGreaterThan(0);
    expect(scene.stars.length).toBe(n3 + n4);
    // Each star carries a partial blend weight in (0,1) — the cross-fade alpha.
    const weights = new Set(scene.stars.map((s) => s.a));
    for (const w of weights) {
      expect(w).toBeGreaterThan(0);
      expect(w).toBeLessThan(1);
    }
    // The two level weights sum to ~1 (brightness preserved across the swap).
    expect(
      scene.transition.primaryWeight + scene.transition.secondaryWeight,
    ).toBeCloseTo(1, 6);
  });

  it('maps aggregate tiles to weighted impostors at coarse zoom', async () => {
    // z=-1.0 -> levelFrac=2.0 exactly -> level 2 (aggregate), mid-interval.
    const p = mgr.assemble(
      's1',
      { minX: -1000, minY: -1000, maxX: 1000, maxY: 1000 },
      -1.0,
      1000,
    );
    const reqs = http.match((r) => r.url.startsWith('/api/galaxy/s1/tile/2/'));
    for (const r of reqs) {
      const parts = r.request.url.split('/');
      const x = Number(parts[parts.length - 2]);
      const y = Number(parts[parts.length - 1]);
      r.flush({
        kind: 'aggregate',
        level: 2,
        x,
        y,
        bbox: { minX: 0, minY: 0, maxX: 1, maxY: 1 },
        impostors: [{ x: 1, y: 2, weight: 0.5 }],
        colorStats: { avgDensity: 0.2, peakDensity: 0.9, sampleCount: 16 },
        schemaVersion: 1,
      } satisfies AggregateTileDto);
    }
    const scene = await p;
    expect(scene.stars.length).toBe(0);
    expect(scene.aggregates.length).toBe(reqs.length);
  });
});
