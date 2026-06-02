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
  /** Last full float payload uploaded via bufferData (a copy). */
  lastInstanceFloats: number[] | null;
  /** Every uniform2f(loc,a,b) call as [a,b] pairs, in order. */
  uniform2fCalls: [number, number][];
}

/** Minimal WebGL2 stub: records the instanced draw + uploaded sizes. */
function fakeWebgl2(): { gl: WebGL2RenderingContext; rec: FakeGl } {
  const rec: FakeGl = {
    instancedCalls: [],
    lastBufferDataBytes: 0,
    lastInstanceFloats: null,
    uniform2fCalls: [],
  };
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
        if (data instanceof Float32Array) {
          rec.lastInstanceFloats = Array.from(data);
        }
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
    uniform2f: (_loc: unknown, a: number, b: number) => {
      rec.uniform2fCalls.push([a, b]);
    },
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

  it('subtracts the floating origin from star + camera world (E8-07)', () => {
    const { gl, rec } = fakeWebgl2();
    const layer = new WebglDrawLayer(fakeCanvas(gl));
    layer.resize(800, 600, 1);
    // origin (0,0): the recovered camera world for this view is (0,0), and the
    // first star sits at world (1,-1).
    layer.draw(scene(3, 0), view, 0);
    const baselineStar0 = [
      rec.lastInstanceFloats![0],
      rec.lastInstanceFloats![1],
    ];
    // uCamWorld is the first uniform2f issued in the star pass.
    const baselineCam = rec.uniform2fCalls[0];

    // Re-render the SAME scene with a non-zero floating origin.
    rec.uniform2fCalls = [];
    const originX = 1_000_000;
    const originY = -500_000;
    layer.draw(scene(3, 0), { ...view, originX, originY }, 0);
    const shiftedStar0 = [
      rec.lastInstanceFloats![0],
      rec.lastInstanceFloats![1],
    ];
    const shiftedCam = rec.uniform2fCalls[0];

    // Both the buffered star position and the camera uniform are shifted by
    // exactly -origin, so the shader's `(aWorld - uCamWorld)` is unchanged ->
    // the re-base is transparent on screen.
    expect(shiftedStar0[0]).toBeCloseTo(baselineStar0[0] - originX, 1);
    expect(shiftedStar0[1]).toBeCloseTo(baselineStar0[1] - originY, 1);
    expect(shiftedCam[0]).toBeCloseTo(baselineCam[0] - originX, 1);
    expect(shiftedCam[1]).toBeCloseTo(baselineCam[1] - originY, 1);
    // The difference (what the shader actually uses) is preserved.
    expect(shiftedStar0[0] - shiftedCam[0]).toBeCloseTo(
      baselineStar0[0] - baselineCam[0],
      4,
    );
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

/**
 * Coverage for the E8-02 post-processing pipeline. When the GL implementation
 * exposes the framebuffer API, draw() must run a FIXED number of fullscreen
 * passes (nebula + bright-pass + 2 blurs + composite = 5 drawArrays calls) plus
 * the single instanced star pass — bounded per-frame work regardless of star
 * count. We assert the pass counts so the frame budget stays structurally
 * bounded, and that an active-system scene also issues a planet instanced pass.
 */
describe('WebglDrawLayer post-processing (FBO-capable stub)', () => {
  interface PostRec {
    instanced: number;
    arrays: { first: number; count: number }[];
    framebuffers: number;
    textures: number;
  }

  function fbWebgl2(): { gl: WebGL2RenderingContext; rec: PostRec } {
    const rec: PostRec = {
      instanced: 0,
      arrays: [],
      framebuffers: 0,
      textures: 0,
    };
    let id = 0;
    const gl = {
      VERTEX_SHADER: 1,
      FRAGMENT_SHADER: 2,
      ARRAY_BUFFER: 3,
      STATIC_DRAW: 4,
      DYNAMIC_DRAW: 5,
      FLOAT: 6,
      TRIANGLE_STRIP: 7,
      TRIANGLES: 70,
      COLOR_BUFFER_BIT: 8,
      BLEND: 9,
      DEPTH_TEST: 10,
      ONE: 11,
      LINK_STATUS: 12,
      COMPILE_STATUS: 13,
      FRAMEBUFFER: 20,
      COLOR_ATTACHMENT0: 21,
      TEXTURE_2D: 22,
      RGBA: 23,
      UNSIGNED_BYTE: 24,
      TEXTURE_MIN_FILTER: 25,
      TEXTURE_MAG_FILTER: 26,
      TEXTURE_WRAP_S: 27,
      TEXTURE_WRAP_T: 28,
      LINEAR: 29,
      CLAMP_TO_EDGE: 30,
      TEXTURE0: 33984,
      SRC_ALPHA: 40,
      ONE_MINUS_SRC_ALPHA: 41,
      createShader: () => ({}),
      shaderSource: () => undefined,
      compileShader: () => undefined,
      getShaderParameter: () => true,
      getShaderInfoLog: () => '',
      deleteShader: () => undefined,
      createProgram: () => ({ id: id++ }),
      attachShader: () => undefined,
      linkProgram: () => undefined,
      getProgramParameter: () => true,
      getProgramInfoLog: () => '',
      deleteProgram: () => undefined,
      getUniformLocation: () => ({ id: id++ }),
      createVertexArray: () => ({}),
      bindVertexArray: () => undefined,
      deleteVertexArray: () => undefined,
      createBuffer: () => ({}),
      deleteBuffer: () => undefined,
      bindBuffer: () => undefined,
      bufferData: () => undefined,
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
      uniform1i: () => undefined,
      uniform1f: () => undefined,
      uniform2f: () => undefined,
      activeTexture: () => undefined,
      createFramebuffer: () => {
        rec.framebuffers++;
        return {};
      },
      deleteFramebuffer: () => undefined,
      bindFramebuffer: () => undefined,
      framebufferTexture2D: () => undefined,
      createTexture: () => {
        rec.textures++;
        return {};
      },
      deleteTexture: () => undefined,
      bindTexture: () => undefined,
      texImage2D: () => undefined,
      texParameteri: () => undefined,
      drawArrays: (_m: number, first: number, count: number) => {
        rec.arrays.push({ first, count });
      },
      drawArraysInstanced: () => {
        rec.instanced++;
      },
    } as unknown as WebGL2RenderingContext;
    return { gl, rec };
  }

  const view: ViewTransform = {
    scale: 8, // > 2.2 so planet detail is active
    dpr: 1,
    widthPx: 800,
    heightPx: 600,
    w2s: (wx, wy) => ({ x: 400 + wx * 8, y: 300 + wy * 8 }),
  };

  function activeScene(stars: number): RenderScene {
    return {
      stars: Array.from({ length: stars }, (_, i) => ({
        id: i + 1,
        x: 0,
        y: 0,
        k: i % 7,
        b: 0.9,
        sz: 1.2,
        g: 1,
        activeSystemId: i + 1,
      })),
      aggregates: [],
      routes: [],
      rMax: 1000,
    };
  }

  it('allocates scene + 2 bloom FBOs and runs a bounded set of passes', () => {
    const { gl, rec } = fbWebgl2();
    const layer = new WebglDrawLayer(fakeCanvas(gl));
    layer.resize(800, 600, 1);
    layer.draw(activeScene(4), view, 1.0);
    // 3 offscreen targets: scene (full res) + bloomA + bloomB (1/4 res).
    expect(rec.framebuffers).toBe(3);
    expect(rec.textures).toBe(3);
    // Fullscreen passes: nebula + bright + blur-h + blur-v + composite = 5.
    expect(rec.arrays.length).toBe(5);
    for (const a of rec.arrays) {
      expect(a.count).toBe(3); // each is the single fullscreen triangle
    }
    // Instanced passes: the star pass + the planet pass = 2.
    expect(rec.instanced).toBe(2);
    layer.dispose();
  });

  it('keeps the pass count fixed as star count grows (bounded frame budget)', () => {
    const { gl, rec } = fbWebgl2();
    const layer = new WebglDrawLayer(fakeCanvas(gl));
    layer.resize(800, 600, 1);
    layer.draw(activeScene(50_000), view, 1.0);
    // Same fixed 5 fullscreen passes regardless of how many stars are visible.
    expect(rec.arrays.length).toBe(5);
    expect(rec.instanced).toBe(2); // stars + planets, still two instanced calls
    layer.dispose();
  });
});
