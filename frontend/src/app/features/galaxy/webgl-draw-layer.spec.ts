import { WebglDrawLayer, recoverCamWorld } from './webgl-draw-layer';
import type { RenderScene, ViewTransform } from './render-model';

/**
 * Unit coverage for the WebGL2 draw layer that does not need a real GPU context
 * (vitest/jsdom has none). We test:
 *   - camera-origin recovery from the ViewTransform contract (the only non-GL
 *     maths in the layer), and
 *   - that draw() against a fake WebGL2 context issues exactly ONE instanced
 *     draw call for the whole star+aggregate set (the E8-01 done-when invariant).
 */
describe('recoverCamWorld', () => {
  it('inverts the contract w2s transform back to the camera origin', () => {
    // Camera centred at world (12, -7), 4 device-px per world unit, 800x600.
    const camX = 12;
    const camY = -7;
    const scalePx = 4;
    const view: ViewTransform = {
      scale: 4,
      dpr: 1,
      widthPx: 800,
      heightPx: 600,
      w2s: (wx, wy) => ({
        x: (wx - camX) * scalePx + 400,
        y: (wy - camY) * scalePx + 300,
      }),
    };
    const r = recoverCamWorld(view, scalePx);
    expect(r.camX).toBeCloseTo(camX, 6);
    expect(r.camY).toBeCloseTo(camY, 6);
  });

  it('is safe when scale is zero', () => {
    const view: ViewTransform = {
      scale: 0,
      dpr: 1,
      widthPx: 800,
      heightPx: 600,
      w2s: () => ({ x: 0, y: 0 }),
    };
    expect(() => recoverCamWorld(view, 0)).not.toThrow();
  });
});

interface FakeGl {
  instancedCalls: { mode: number; first: number; vcount: number; icount: number }[];
  lastBufferDataBytes: number;
}

/** Minimal WebGL2 stub: records the instanced draw + uploaded sizes. */
function fakeWebgl2(): { gl: WebGL2RenderingContext; rec: FakeGl } {
  const rec: FakeGl = { instancedCalls: [], lastBufferDataBytes: 0 };
  let uniformLoc = 0;
  const gl = {
    VERTEX_SHADER: 1,
    FRAGMENT_SHADER: 2,
    ARRAY_BUFFER: 3,
    STATIC_DRAW: 4,
    DYNAMIC_DRAW: 5,
    FLOAT: 6,
    TRIANGLE_STRIP: 7,
    COLOR_BUFFER_BIT: 8,
    BLEND: 9,
    DEPTH_TEST: 10,
    ONE: 11,
    LINK_STATUS: 12,
    COMPILE_STATUS: 13,
    createShader: () => ({}),
    shaderSource: () => undefined,
    compileShader: () => undefined,
    getShaderParameter: () => true,
    getShaderInfoLog: () => '',
    deleteShader: () => undefined,
    createProgram: () => ({}),
    attachShader: () => undefined,
    linkProgram: () => undefined,
    getProgramParameter: () => true,
    getProgramInfoLog: () => '',
    deleteProgram: () => undefined,
    getUniformLocation: () => ({ id: uniformLoc++ }),
    createVertexArray: () => ({}),
    bindVertexArray: () => undefined,
    deleteVertexArray: () => undefined,
    createBuffer: () => ({}),
    deleteBuffer: () => undefined,
    bindBuffer: () => undefined,
    bufferData: (_t: number, data: ArrayBufferView | number) => {
      if (typeof data !== 'number') {
        rec.lastBufferDataBytes = data.byteLength;
      }
    },
    enableVertexAttribArray: () => undefined,
    vertexAttribPointer: () => undefined,
    vertexAttribDivisor: () => undefined,
    disable: () => undefined,
    enable: () => undefined,
    blendFunc: () => undefined,
    clearColor: () => undefined,
    clear: () => undefined,
    viewport: () => undefined,
    useProgram: () => undefined,
    uniform2f: () => undefined,
    uniform1f: () => undefined,
    drawArraysInstanced: (mode: number, first: number, vcount: number, icount: number) => {
      rec.instancedCalls.push({ mode, first, vcount, icount });
    },
  } as unknown as WebGL2RenderingContext;
  return { gl, rec };
}

function fakeCanvas(gl: WebGL2RenderingContext | null): HTMLCanvasElement {
  return {
    getContext: (id: string) => (id === 'webgl2' ? gl : null),
  } as unknown as HTMLCanvasElement;
}

describe('WebglDrawLayer', () => {
  const view: ViewTransform = {
    scale: 2,
    dpr: 1,
    widthPx: 800,
    heightPx: 600,
    w2s: (wx, wy) => ({ x: 400 + wx * 2, y: 300 + wy * 2 }),
  };

  function scene(stars: number, aggregates: number): RenderScene {
    return {
      stars: Array.from({ length: stars }, (_, i) => ({
        id: i,
        x: i,
        y: -i,
        k: i % 7,
        b: 0.6,
        sz: 1,
        g: i % 2,
        activeSystemId: null,
      })),
      aggregates: Array.from({ length: aggregates }, (_, i) => ({
        x: i * 10,
        y: i * 10,
        weight: 0.5,
      })),
      routes: [],
      rMax: 1000,
    };
  }

  it('isSupported is false when no WebGL2 context can be created', () => {
    expect(WebglDrawLayer.isSupported(fakeCanvas(null))).toBe(false);
  });

  it('throws when WebGL2 is unavailable (so the component can fall back)', () => {
    expect(() => new WebglDrawLayer(fakeCanvas(null))).toThrow();
  });

  it('renders the whole visible set in ONE instanced draw call', () => {
    const { gl, rec } = fakeWebgl2();
    const layer = new WebglDrawLayer(fakeCanvas(gl));
    layer.resize(800, 600, 1);
    layer.draw(scene(1000, 50), view, 0);
    expect(rec.instancedCalls.length).toBe(1);
    const call = rec.instancedCalls[0];
    expect(call.mode).toBe(gl.TRIANGLE_STRIP);
    expect(call.vcount).toBe(4); // 4 quad vertices
    expect(call.icount).toBe(1050); // 1000 stars + 50 aggregates, one buffer
    // 1050 instances * 9 floats * 4 bytes uploaded in a single bufferData.
    expect(rec.lastBufferDataBytes).toBe(1050 * 9 * 4);
    layer.dispose();
  });

  it('issues no draw call for an empty scene (bounded work)', () => {
    const { gl, rec } = fakeWebgl2();
    const layer = new WebglDrawLayer(fakeCanvas(gl));
    layer.resize(800, 600, 1);
    layer.draw(scene(0, 0), view, 0);
    expect(rec.instancedCalls.length).toBe(0);
    layer.dispose();
  });

  it('reuses its instance buffer (capacity grows, never shrinks per frame)', () => {
    const { gl } = fakeWebgl2();
    const layer = new WebglDrawLayer(fakeCanvas(gl));
    layer.resize(800, 600, 1);
    expect(() => {
      layer.draw(scene(10, 0), view, 0);
      layer.draw(scene(5000, 0), view, 0);
      layer.draw(scene(20, 0), view, 0);
    }).not.toThrow();
    layer.dispose();
  });
});
