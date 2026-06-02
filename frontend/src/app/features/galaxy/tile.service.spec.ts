import { TestBed } from '@angular/core/testing';
import { provideZonelessChangeDetection } from '@angular/core';
import { provideHttpClient } from '@angular/common/http';
import {
  HttpTestingController,
  provideHttpClientTesting,
} from '@angular/common/http/testing';
import {
  TileService,
  TILE_MAX_LEVEL,
  TILE_STAR_LIST_MIN_LEVEL,
  MAX_TILES_PER_VIEW,
  isStarListLevel,
  levelForZoom,
  tileSide,
  tilesForBbox,
  tilesPerAxis,
  tileUrl,
  toRenderAggregates,
  toRenderStar,
  type AggregateTileDto,
  type StarDto,
  type StarListTileDto,
} from './tile.service';

describe('tile-grid maths (mirror backend TileGrid.java)', () => {
  it('tilesPerAxis is 2^level', () => {
    expect(tilesPerAxis(0)).toBe(1);
    expect(tilesPerAxis(3)).toBe(8);
    expect(tilesPerAxis(6)).toBe(64);
  });

  it('tileSide is 2*rMax / 2^level', () => {
    expect(tileSide(0, 1000)).toBeCloseTo(2000, 6);
    expect(tileSide(3, 1000)).toBeCloseTo(250, 6); // matches spec "level 3 spans 250"
    expect(tileSide(6, 1000)).toBeCloseTo(2000 / 64, 6);
  });

  it('isStarListLevel splits at STAR_LIST_MIN_LEVEL (3)', () => {
    expect(isStarListLevel(2)).toBe(false);
    expect(isStarListLevel(TILE_STAR_LIST_MIN_LEVEL)).toBe(true);
    expect(isStarListLevel(TILE_MAX_LEVEL)).toBe(true);
  });
});

describe('levelForZoom (LOD selection from camera levelF)', () => {
  it('picks an aggregate level at fit (z=0)', () => {
    const lvl = levelForZoom(0);
    expect(lvl).toBe(3); // z=0 -> level 3 (boundary: first star-list level)
    expect(isStarListLevel(lvl)).toBe(true);
  });

  it('zooming out crosses into aggregate tiers (< level 3)', () => {
    expect(levelForZoom(-1)).toBe(2);
    expect(isStarListLevel(levelForZoom(-1))).toBe(false);
    expect(levelForZoom(-1.5)).toBe(2);
  });

  it('zooming in selects deeper star-list levels, clamped at MAX_LEVEL', () => {
    expect(levelForZoom(1)).toBe(4);
    expect(levelForZoom(3)).toBe(6);
    expect(levelForZoom(99)).toBe(TILE_MAX_LEVEL);
  });

  it('clamps below 0', () => {
    expect(levelForZoom(-99)).toBe(0);
  });
});

describe('tilesForBbox', () => {
  it('returns the single tile at level 0 covering the galaxy', () => {
    const addrs = tilesForBbox(
      { minX: -1000, minY: -1000, maxX: 1000, maxY: 1000 },
      0,
      1000,
    );
    expect(addrs).toEqual([{ level: 0, x: 0, y: 0 }]);
  });

  it('maps the bbox centre to the correct tile index at level 3', () => {
    // centre tile near origin: index = floor((0+1000)/250) = 4
    const addrs = tilesForBbox(
      { minX: -10, minY: -10, maxX: 10, maxY: 10 },
      3,
      1000,
    );
    expect(addrs).toContainEqual({ level: 3, x: 4, y: 4 });
  });

  it('clamps indices to the grid edges', () => {
    const addrs = tilesForBbox(
      { minX: -5000, minY: -5000, maxX: -2000, maxY: -2000 },
      3,
      1000,
    );
    expect(addrs).toEqual([{ level: 3, x: 0, y: 0 }]);
  });

  it('caps the result at MAX_TILES_PER_VIEW (bounded per-frame work)', () => {
    const addrs = tilesForBbox(
      { minX: -1000, minY: -1000, maxX: 1000, maxY: 1000 },
      6,
      1000,
    );
    expect(addrs.length).toBeLessThanOrEqual(MAX_TILES_PER_VIEW);
  });
});

describe('tileUrl', () => {
  it('builds the E6-03 endpoint path', () => {
    expect(tileUrl('seed42', { level: 3, x: 4, y: 5 })).toBe(
      '/api/galaxy/seed42/tile/3/4/5',
    );
  });
});

describe('render-input mapping', () => {
  it('maps a StarDto to a RenderStar with palette + normalised attrs', () => {
    const dto: StarDto = {
      localId: 7,
      x: 12,
      y: -3,
      spectral: 'G',
      brightness: 1,
      size: 1,
      activeSystemId: null,
    };
    const r = toRenderStar(dto);
    expect(r.id).toBe(7);
    expect(r.x).toBe(12);
    expect(r.y).toBe(-3);
    expect(r.k).toBe(4); // G -> index 4
    expect(r.b).toBeGreaterThan(0);
    expect(r.b).toBeLessThanOrEqual(1);
    expect(r.sz).toBeGreaterThan(0.3);
    expect(r.g).toBe(0); // G is not a giant
    expect(r.activeSystemId).toBeNull();
  });

  it('flags bright early-class large stars as giants', () => {
    const o: StarDto = {
      localId: 1,
      x: 0,
      y: 0,
      spectral: 'O',
      brightness: 25,
      size: 8,
      activeSystemId: 99,
    };
    const r = toRenderStar(o);
    expect(r.k).toBe(0); // O -> index 0
    expect(r.g).toBe(1);
    expect(r.activeSystemId).toBe(99);
  });

  it('maps aggregate impostors to RenderAggregate points', () => {
    const t: AggregateTileDto = {
      kind: 'aggregate',
      level: 1,
      x: 0,
      y: 0,
      bbox: { minX: 0, minY: 0, maxX: 1, maxY: 1 },
      impostors: [
        { x: 1, y: 2, weight: 0.5 },
        { x: 3, y: 4, weight: 0.9 },
      ],
      colorStats: { avgDensity: 0.2, peakDensity: 0.9, sampleCount: 64 },
      schemaVersion: 1,
    };
    const aggs = toRenderAggregates(t);
    expect(aggs).toEqual([
      { x: 1, y: 2, weight: 0.5 },
      { x: 3, y: 4, weight: 0.9 },
    ]);
  });
});

describe('TileService (HTTP)', () => {
  let svc: TileService;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        provideZonelessChangeDetection(),
        provideHttpClient(),
        provideHttpClientTesting(),
        TileService,
      ],
    });
    svc = TestBed.inject(TileService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('fetches a tile from the correct URL', async () => {
    const tile: StarListTileDto = {
      kind: 'starlist',
      level: 3,
      x: 4,
      y: 4,
      bbox: { minX: 0, minY: 0, maxX: 250, maxY: 250 },
      stars: [],
      schemaVersion: 1,
    };
    const p = svc.fetchTile('s1', { level: 3, x: 4, y: 4 });
    const req = http.expectOne('/api/galaxy/s1/tile/3/4/4');
    expect(req.request.method).toBe('GET');
    req.flush(tile);
    await expect(p).resolves.toEqual(tile);
  });

  it('coalesces concurrent requests for the same address (one HTTP call)', async () => {
    const addr = { level: 4, x: 1, y: 2 };
    const p1 = svc.fetchTile('s1', addr);
    const p2 = svc.fetchTile('s1', addr);
    const req = http.expectOne('/api/galaxy/s1/tile/4/1/2');
    req.flush({
      kind: 'starlist',
      level: 4,
      x: 1,
      y: 2,
      bbox: { minX: 0, minY: 0, maxX: 1, maxY: 1 },
      stars: [],
      schemaVersion: 1,
    } satisfies StarListTileDto);
    await Promise.all([p1, p2]);
    // a second expectOne would fail in verify() if a duplicate request fired
  });

  it('fetchVisible requests every tile in the visible set at the LOD level', async () => {
    // z=0 -> level 3; a wide bbox spans several level-3 tiles
    const promise = svc.fetchVisible(
      's1',
      { minX: -300, minY: -10, maxX: 300, maxY: 10 },
      0,
      1000,
    );
    const reqs = http.match((r) => r.url.startsWith('/api/galaxy/s1/tile/3/'));
    expect(reqs.length).toBeGreaterThan(1);
    for (const r of reqs) {
      r.flush({
        kind: 'starlist',
        level: 3,
        x: 0,
        y: 0,
        bbox: { minX: 0, minY: 0, maxX: 1, maxY: 1 },
        stars: [],
        schemaVersion: 1,
      } satisfies StarListTileDto);
    }
    const out = await promise;
    expect(out.length).toBe(reqs.length);
  });
});
