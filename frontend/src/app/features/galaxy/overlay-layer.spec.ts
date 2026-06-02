import {
  buildOverlayMarks,
  buildOverlayRoutes,
  hexToRgb01,
  indexStarsBySystemId,
} from './overlay-layer';
import type { RenderStar } from './render-model';
import type { RouteOverlay, SystemOverlay } from '../../stores';

function star(id: number, x: number, y: number, activeSystemId: number | null): RenderStar {
  return { id, x, y, k: 4, b: 0.5, sz: 1, g: 0, activeSystemId };
}

function sys(systemId: string, owner: string | null, extra: Partial<SystemOverlay> = {}): SystemOverlay {
  return {
    systemId,
    ownerFactionId: owner,
    activityLevel: 0,
    blockaded: false,
    battle: false,
    asOfTick: 1,
    ...extra,
  };
}

describe('hexToRgb01', () => {
  it('parses #rrggbb to 0..1 triplet', () => {
    expect(hexToRgb01('#ffffff')).toEqual([1, 1, 1]);
    expect(hexToRgb01('#000000')).toEqual([0, 0, 0]);
    const c = hexToRgb01('#4ad6a0')!;
    expect(c[0]).toBeCloseTo(0x4a / 255, 5);
    expect(c[1]).toBeCloseTo(0xd6 / 255, 5);
    expect(c[2]).toBeCloseTo(0xa0 / 255, 5);
  });

  it('parses #rgb shorthand and tolerates missing hash', () => {
    expect(hexToRgb01('#fff')).toEqual([1, 1, 1]);
    expect(hexToRgb01('ff0000')).toEqual([1, 0, 0]);
  });

  it('returns null for blank or malformed input', () => {
    expect(hexToRgb01(undefined)).toBeNull();
    expect(hexToRgb01(null)).toBeNull();
    expect(hexToRgb01('')).toBeNull();
    expect(hexToRgb01('#xyz123')).toBeNull();
    expect(hexToRgb01('#12345')).toBeNull();
  });
});

describe('indexStarsBySystemId', () => {
  it('indexes only promoted (active) stars by stringified system id', () => {
    const byId = indexStarsBySystemId([
      star(1, 10, 20, 7),
      star(2, 30, 40, null), // pure scenery -> skipped
      star(3, 50, 60, 9),
    ]);
    expect(byId.size).toBe(2);
    expect(byId.get('7')!.x).toBe(10);
    expect(byId.get('9')!.y).toBe(60);
    expect(byId.has('2')).toBe(false);
  });
});

describe('buildOverlayMarks — join by system id', () => {
  it('tints the star at the matching system id at that star world position', () => {
    const byId = indexStarsBySystemId([star(1, 123, -45, 7)]);
    const marks = buildOverlayMarks(
      [sys('7', 'f1', { activityLevel: 2 })],
      byId,
      (id) => (id === 'f1' ? '#4ad6a0' : undefined),
    );
    expect(marks.length).toBe(1);
    const m = marks[0];
    expect(m.systemId).toBe('7');
    // anchored at the JOINED star's world position (join-by-system-id correctness)
    expect(m.x).toBe(123);
    expect(m.y).toBe(-45);
    expect(m.tint).not.toBeNull();
    expect(m.tint![0]).toBeCloseTo(0x4a / 255, 5);
    expect(m.activity).toBe(2);
  });

  it('FOG-CORRECT: emits no mark for a system whose star is not in the visible set', () => {
    const byId = indexStarsBySystemId([star(1, 0, 0, 7)]);
    // overlay mentions system 99 which is NOT loaded/visible -> no invented mark
    const marks = buildOverlayMarks([sys('99', 'f1')], byId, () => '#ffffff');
    expect(marks).toEqual([]);
  });

  it('unclaimed system (owner null) yields a null tint (no invented ownership)', () => {
    const byId = indexStarsBySystemId([star(1, 1, 2, 7)]);
    const marks = buildOverlayMarks([sys('7', null)], byId, () => '#ffffff');
    expect(marks[0].tint).toBeNull();
  });

  it('carries battle and blockade flags through the join', () => {
    const byId = indexStarsBySystemId([star(1, 1, 2, 7)]);
    const marks = buildOverlayMarks(
      [sys('7', 'f1', { battle: true, blockaded: true })],
      byId,
      () => '#102030',
    );
    expect(marks[0].battle).toBe(true);
    expect(marks[0].blockaded).toBe(true);
  });

  it('returns [] when there are no systems or no stars', () => {
    expect(buildOverlayMarks([], new Map(), () => '#fff')).toEqual([]);
    expect(
      buildOverlayMarks([sys('7', 'f1')], new Map(), () => '#fff'),
    ).toEqual([]);
  });
});

describe('buildOverlayRoutes — join endpoints by system id', () => {
  function route(id: string, from: string, to: string): RouteOverlay {
    return {
      routeId: id,
      fromSystemId: from,
      toSystemId: to,
      ownerFactionId: 'f1',
      kind: 'trade',
      active: true,
      asOfTick: 1,
    };
  }

  it('resolves both endpoints to their star world positions', () => {
    const byId = indexStarsBySystemId([star(1, 0, 0, 7), star(2, 100, 50, 8)]);
    const routes = buildOverlayRoutes([route('r1', '7', '8')], byId);
    expect(routes.length).toBe(1);
    expect(routes[0].ax).toBe(0);
    expect(routes[0].bx).toBe(100);
    expect(routes[0].len).toBeCloseTo(Math.hypot(100, 50), 5);
  });

  it('skips a route whose endpoint is not in view (fog-correct, bounded)', () => {
    const byId = indexStarsBySystemId([star(1, 0, 0, 7)]);
    const routes = buildOverlayRoutes([route('r1', '7', '8')], byId);
    expect(routes).toEqual([]);
  });
});
