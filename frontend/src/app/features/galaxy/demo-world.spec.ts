import { describe, it, expect } from 'vitest';
import {
  buildDemoWorld,
  demoSystemRenderStar,
  hashStarId,
} from './demo-world';

describe('hashStarId', () => {
  it('is deterministic and a non-negative 31-bit int', () => {
    const a = hashStarId('12345678901234567890');
    const b = hashStarId('12345678901234567890');
    expect(a).toBe(b);
    expect(a).toBeGreaterThanOrEqual(0);
    expect(a).toBeLessThan(2147483647);
    expect(Number.isInteger(a)).toBe(true);
  });

  it('separates distinct ids', () => {
    expect(hashStarId('1')).not.toBe(hashStarId('2'));
  });
});

describe('buildDemoWorld', () => {
  it('elevates a spread-out set of active systems from the catalog', () => {
    const w = buildDemoWorld(1, { systemCount: 30, minSpacing: 60 });
    expect(w.systems.length).toBeGreaterThan(10);
    expect(w.systems.length).toBeLessThanOrEqual(30);
    // ids are 1..N and unique
    const ids = new Set(w.systems.map((s) => s.id));
    expect(ids.size).toBe(w.systems.length);
    expect(Math.min(...w.systems.map((s) => s.id))).toBe(1);
    // minimum spacing respected
    for (let i = 0; i < w.systems.length; i++) {
      for (let j = i + 1; j < w.systems.length; j++) {
        const d = Math.hypot(
          w.systems[i].x - w.systems[j].x,
          w.systems[i].y - w.systems[j].y,
        );
        expect(d).toBeGreaterThanOrEqual(60 - 1e-6);
      }
    }
  });

  it('is deterministic for a given seed', () => {
    const a = buildDemoWorld(7);
    const b = buildDemoWorld(7);
    expect(a.systems.map((s) => s.starId)).toEqual(b.systems.map((s) => s.starId));
    expect(a.systems.map((s) => s.id)).toEqual(b.systems.map((s) => s.id));
  });

  it('differs across seeds', () => {
    const a = buildDemoWorld(1);
    const b = buildDemoWorld(2);
    expect(a.systems.map((s) => s.starId)).not.toEqual(
      b.systems.map((s) => s.starId),
    );
  });

  it('synthesises a star-list tile that flags active systems with activeSystemId', () => {
    const w = buildDemoWorld(1);
    // Cover the whole core at level 4 and collect every flagged star.
    const flagged = new Map<number, true>();
    const n = 1 << 4;
    for (let x = 0; x < n; x++) {
      for (let y = 0; y < n; y++) {
        const tile = w.tile('1', { level: 4, x, y });
        if (tile.kind !== 'starlist') continue;
        for (const s of tile.stars) {
          if (s.activeSystemId !== null) {
            flagged.set(s.activeSystemId, true);
          }
        }
      }
    }
    // Every demo system within the core should appear flagged in some tile.
    const inCore = w.systems.filter((s) => Math.hypot(s.x, s.y) < 1000);
    for (const sys of inCore) {
      expect(flagged.has(sys.id)).toBe(true);
    }
  });

  it('produces coarse aggregate tiles at galaxy scale', () => {
    const w = buildDemoWorld(1);
    const tile = w.tile('1', { level: 1, x: 1, y: 1 });
    expect(tile.kind).toBe('aggregate');
  });
});

describe('demoSystemRenderStar', () => {
  it('round-trips a demo system to a render star with its active id', () => {
    const w = buildDemoWorld(1);
    const sys = w.systems[0];
    const star = demoSystemRenderStar(sys);
    expect(star.activeSystemId).toBe(sys.id);
    expect(star.x).toBe(sys.x);
    expect(star.y).toBe(sys.y);
    expect(star.id).toBe(hashStarId(sys.starId));
  });
});
