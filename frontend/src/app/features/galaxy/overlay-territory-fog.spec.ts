import { describe, it, expect } from 'vitest';
import {
  buildTerritories,
  buildFogReveals,
  DEFAULT_VISION_RADIUS,
} from './overlay-layer';
import type { RenderStar } from './render-model';
import type { SystemOverlay } from '../../stores/overlay.store';

function star(systemId: number, x: number, y: number): RenderStar {
  return { id: systemId, x, y, k: 4, b: 0.6, sz: 1, g: 0, activeSystemId: systemId };
}
function sys(
  id: number,
  owner: string | null,
  extra: Partial<SystemOverlay> = {},
): SystemOverlay {
  return {
    systemId: String(id),
    ownerFactionId: owner,
    activityLevel: 0,
    blockaded: false,
    battle: false,
    asOfTick: 1,
    ...extra,
  };
}

const colours: Record<string, string> = { A: '#ff0000', B: '#00ff00' };
const colourOf = (id: string) => colours[id];

describe('buildTerritories', () => {
  it('groups owned systems by faction with tint + node positions', () => {
    const stars = new Map([
      ['1', star(1, 0, 0)],
      ['2', star(2, 10, 0)],
      ['3', star(3, 0, 10)],
    ]);
    const systems = [sys(1, 'A'), sys(2, 'A'), sys(3, 'B')];
    const terr = buildTerritories(systems, stars, colourOf);
    const a = terr.find((t) => t.factionId === 'A')!;
    const b = terr.find((t) => t.factionId === 'B')!;
    expect(a.nodes.length).toBe(2);
    expect(a.tint).toEqual([1, 0, 0]);
    expect(b.nodes.length).toBe(1);
    expect(b.tint).toEqual([0, 1, 0]);
  });

  it('skips unowned systems, off-screen systems, and unknown colours', () => {
    const stars = new Map([['1', star(1, 0, 0)]]);
    const systems = [
      sys(1, 'A'),
      sys(2, 'A'), // no star in view → skipped
      sys(3, null), // unowned → skipped
      sys(4, 'Z'), // colour unknown → faction dropped if no nodes
    ];
    const terr = buildTerritories(systems, stars, colourOf);
    expect(terr.length).toBe(1);
    expect(terr[0].nodes.length).toBe(1);
  });

  it('returns empty when there are no systems or no visible stars', () => {
    expect(buildTerritories([], new Map(), colourOf)).toEqual([]);
    expect(buildTerritories([sys(1, 'A')], new Map(), colourOf)).toEqual([]);
  });
});

describe('buildFogReveals', () => {
  it('reveals a disk around every visible known system', () => {
    const stars = new Map([
      ['1', star(1, 0, 0)],
      ['2', star(2, 100, 0)],
    ]);
    const systems = [sys(1, 'A'), sys(2, null)];
    const reveals = buildFogReveals(systems, stars, { x: 0, y: 0 }, 0);
    expect(reveals.length).toBe(2);
    // owned systems get a wider reveal than merely-sighted ones
    const owned = reveals[0];
    const seen = reveals[1];
    expect(owned.r).toBeGreaterThan(seen.r);
    expect(owned.r).toBeCloseTo(DEFAULT_VISION_RADIUS * 1.25, 5);
  });

  it('adds a camera reveal disk when camRevealRadius > 0', () => {
    const reveals = buildFogReveals([], new Map(), { x: 5, y: 6 }, 200);
    expect(reveals.length).toBe(1);
    expect(reveals[0]).toEqual({ x: 5, y: 6, r: 200 });
  });

  it('widens the reveal for busy (high-activity) systems', () => {
    const stars = new Map([['1', star(1, 0, 0)]]);
    const calm = buildFogReveals([sys(1, 'A')], stars, { x: 0, y: 0 }, 0);
    const busy = buildFogReveals(
      [sys(1, 'A', { activityLevel: 2 })],
      stars,
      { x: 0, y: 0 },
      0,
    );
    expect(busy[0].r).toBeGreaterThan(calm[0].r);
  });
});
