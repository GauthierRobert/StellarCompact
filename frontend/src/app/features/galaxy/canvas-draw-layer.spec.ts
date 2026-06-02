import {
  CanvasDrawLayer,
  clamp01,
  hashId,
  proceduralPlanets,
} from './canvas-draw-layer';
import type { RenderScene, RenderStar, ViewTransform } from './render-model';

describe('canvas draw helpers', () => {
  it('clamp01 clamps to [0,1]', () => {
    expect(clamp01(-1)).toBe(0);
    expect(clamp01(0.5)).toBe(0.5);
    expect(clamp01(2)).toBe(1);
  });

  it('hashId is deterministic and in [0,1)', () => {
    const a = hashId(42);
    const b = hashId(42);
    expect(a).toBe(b);
    expect(a).toBeGreaterThanOrEqual(0);
    expect(a).toBeLessThan(1);
  });

  it('proceduralPlanets is stable for a given star id', () => {
    const st: RenderStar = {
      id: 7,
      x: 0,
      y: 0,
      k: 4,
      b: 0.5,
      sz: 1,
      g: 0,
      activeSystemId: 1,
    };
    const p1 = proceduralPlanets(st);
    const p2 = proceduralPlanets(st);
    expect(p1).toEqual(p2);
    expect(p1.length).toBeGreaterThanOrEqual(1);
  });
});

describe('CanvasDrawLayer (smoke, stub 2d context)', () => {
  function stubCtx(): CanvasRenderingContext2D {
    const noop = (): void => undefined;
    const grad = { addColorStop: noop };
    return {
      save: noop,
      restore: noop,
      fillRect: noop,
      beginPath: noop,
      arc: noop,
      fill: noop,
      stroke: noop,
      moveTo: noop,
      lineTo: noop,
      setLineDash: noop,
      createRadialGradient: () => grad,
      globalCompositeOperation: '',
      globalAlpha: 1,
      fillStyle: '',
      strokeStyle: '',
      lineWidth: 1,
    } as unknown as CanvasRenderingContext2D;
  }

  function layerWithStub(): CanvasDrawLayer {
    const layer = new CanvasDrawLayer();
    // inject a stub context without a real canvas
    (layer as unknown as { ctx: CanvasRenderingContext2D }).ctx = stubCtx();
    return layer;
  }

  const view: ViewTransform = {
    scale: 2,
    dpr: 1,
    widthPx: 800,
    heightPx: 600,
    w2s: (wx, wy) => ({ x: 400 + wx, y: 300 + wy }),
  };

  it('draws a scene with stars without throwing', () => {
    const layer = layerWithStub();
    const scene: RenderScene = {
      stars: [
        { id: 1, x: 0, y: 0, k: 4, b: 0.8, sz: 1, g: 0, activeSystemId: null },
        { id: 2, x: 10, y: 10, k: 0, b: 0.9, sz: 1.2, g: 1, activeSystemId: 5 },
      ],
      aggregates: [],
      routes: [],
      rMax: 1000,
    };
    expect(() => layer.draw(scene, view, 1.0)).not.toThrow();
  });

  it('draws an aggregate (coarse) scene without throwing', () => {
    const layer = layerWithStub();
    const scene: RenderScene = {
      stars: [],
      aggregates: [
        { x: 0, y: 0, weight: 0.5 },
        { x: 50, y: -20, weight: 0.9 },
      ],
      routes: [],
      rMax: 1000,
    };
    expect(() => layer.draw(scene, view, 0.5)).not.toThrow();
  });

  it('is a no-op when no context is bound', () => {
    const layer = new CanvasDrawLayer();
    const scene: RenderScene = {
      stars: [],
      aggregates: [],
      routes: [],
      rMax: 1000,
    };
    expect(() => layer.draw(scene, view, 0)).not.toThrow();
  });
});
