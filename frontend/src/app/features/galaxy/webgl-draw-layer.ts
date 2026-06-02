import {
  SPECTRAL_PALETTE,
  type GalaxyDrawLayer,
  type RenderScene,
  type ViewTransform,
} from './render-model';

/**
 * WebGL2 instanced point-sprite galaxy draw layer (E8-01).
 *
 * Drop-in replacement for CanvasDrawLayer: implements the same GalaxyDrawLayer
 * contract, consuming the same RenderScene + ViewTransform produced by the
 * GalaxyComponent. The component, camera wiring and tile fetching are untouched.
 *
 * The whole visible star field is drawn in a SINGLE instanced draw call
 * (gl.drawArraysInstanced): one 4-vertex quad is reused for every star, and a
 * compact per-instance buffer carries (worldX, worldY, paletteRGB, size,
 * brightness, giant). Cost scales with the number of *visible* instances, never
 * with the catalog — and there is no per-star JS draw loop. This is the
 * architecture that holds 10^5-10^6 visible stars at interactive rates.
 *
 * Principles carried from the galaxy-rendering skill / PoC:
 *   - black space; additive blending of glows (ONE, ONE);
 *   - stars stay point-like, size grows only gently with zoom;
 *   - bounded per-frame work (only the uploaded screenful is drawn);
 *   - a soft radial sprite gives the luminous core+glow look; the heavier
 *     bloom/spike/nebula post-pass is E8-02's job (kept additive-ready here).
 *
 * Aggregates (coarse-zoom impostors) are drawn through the SAME instanced
 * pipeline as fat, dim, blue-white points so the zoomed-out galaxy glow keeps
 * working during the canvas->WebGL swap; the textured density-quad path is
 * E8-04/E8-05 territory.
 *
 * Camera params (world origin + pixels-per-world) are recovered from the
 * ViewTransform w2s/scale/dpr so the data contract stays byte-identical to
 * E7-02 — the layer never reads the CameraStore directly.
 */

// 9 floats per instance: x, y, r, g, b, size, brightness, giant, isAggregate.
const FLOATS_PER_INSTANCE = 9;

const VERT_SRC = `#version 300 es
precision highp float;

// Per-vertex: unit quad corner in [-1,1].
layout(location = 0) in vec2 aCorner;

// Per-instance attributes (compact star buffer).
layout(location = 1) in vec2 aWorld;       // world position
layout(location = 2) in vec3 aColor;       // spectral rgb 0..1
layout(location = 3) in float aSize;       // perceptual size ~0.3..1.4
layout(location = 4) in float aBright;     // perceptual brightness 0..1
layout(location = 5) in float aGiant;      // 1 = luminous giant
layout(location = 6) in float aAggregate;  // 1 = coarse density impostor

uniform vec2 uCamWorld;     // camera world origin (rendered x,y)
uniform float uScalePx;     // device px per world unit (scale * dpr)
uniform vec2 uViewportPx;   // backing-store size in device px

out vec3 vColor;
out float vBright;
out float vGiant;
out float vAggregate;
out vec2 vQuad;

void main() {
  // Point radius in device px. Stars stay point-like: size grows only gently
  // with zoom (matches the PoC r ramp, clamped).
  float zoomGain = 0.85 + min(1.6, uScalePx * 0.02);
  float baseR = max(0.55, (0.45 + aSize * 0.55) * zoomGain);
  // The drawn quad is the glow radius (~4.2x the core) so additive falloff fits.
  float glowR = baseR * 4.2;
  // Aggregates are big soft blobs sized to the viewport, not point-like.
  if (aAggregate > 0.5) {
    glowR = max(8.0, 0.04 * min(uViewportPx.x, uViewportPx.y));
  }

  // World -> device-pixel screen (same maths as ViewTransform.w2s).
  vec2 screenPx = (aWorld - uCamWorld) * uScalePx + uViewportPx * 0.5;
  vec2 cornerPx = screenPx + aCorner * glowR;

  // Device px -> clip space. Y is flipped (screen y grows downward).
  vec2 clip = (cornerPx / uViewportPx) * 2.0 - 1.0;
  clip.y = -clip.y;

  gl_Position = vec4(clip, 0.0, 1.0);
  vColor = aColor;
  vBright = aBright;
  vGiant = aGiant;
  vAggregate = aAggregate;
  vQuad = aCorner;
}
`;

const FRAG_SRC = `#version 300 es
precision highp float;

in vec3 vColor;
in float vBright;
in float vGiant;
in float vAggregate;
in vec2 vQuad;

out vec4 outColor;

void main() {
  float d = length(vQuad);          // 0 at centre, ~1.41 at quad corner
  if (d > 1.0) { discard; }

  if (vAggregate > 0.5) {
    // Soft density blob: dim, blue-white, broad falloff.
    float a = (1.0 - d);
    a = a * a * (0.08 + vBright * 0.42);
    outColor = vec4(vec3(0.78, 0.80, 1.0) * a, a);
    return;
  }

  // Star: bright core + additive glow halo. Core occupies the inner ~24%.
  float core = smoothstep(0.26, 0.0, d);
  float halo = pow(max(0.0, 1.0 - d), 2.2);
  float coreA = core * min(1.0, 0.55 + vBright);
  float haloA = halo * (0.2 + 0.5 * vBright);

  // White-hot core blended toward spectral colour outward.
  vec3 rgb = mix(vColor, vec3(1.0), core * 0.9);
  float a = clamp(coreA + haloA, 0.0, 1.0);
  // Premultiplied additive output (blend func is ONE, ONE).
  outColor = vec4(rgb * a, a);
}
`;

export class WebglDrawLayer implements GalaxyDrawLayer {
  private gl: WebGL2RenderingContext | null = null;
  private program: WebGLProgram | null = null;
  private vao: WebGLVertexArrayObject | null = null;
  private quadBuf: WebGLBuffer | null = null;
  private instanceBuf: WebGLBuffer | null = null;

  private uCamWorld: WebGLUniformLocation | null = null;
  private uScalePx: WebGLUniformLocation | null = null;
  private uViewportPx: WebGLUniformLocation | null = null;

  /** Reused instance staging buffer; grown as the visible count rises. */
  private data = new Float32Array(0);
  private capacity = 0;

  private widthPx = 0;
  private heightPx = 0;
  private dpr = 1;

  /**
   * Probe whether a usable WebGL2 context can be created on a canvas. Used by
   * the component to decide between this layer and the Canvas2D fallback.
   */
  static isSupported(canvas: HTMLCanvasElement): boolean {
    try {
      const gl = canvas.getContext('webgl2');
      return gl !== null;
    } catch {
      return false;
    }
  }

  constructor(canvas: HTMLCanvasElement) {
    const gl = canvas.getContext('webgl2', {
      alpha: false,
      antialias: true,
      premultipliedAlpha: true,
      powerPreference: 'high-performance',
    });
    if (!gl) {
      throw new Error('WebGL2 unavailable');
    }
    this.gl = gl;
    this.init();
  }

  private init(): void {
    const gl = this.gl as WebGL2RenderingContext;
    const program = linkProgram(gl, VERT_SRC, FRAG_SRC);
    this.program = program;
    this.uCamWorld = gl.getUniformLocation(program, 'uCamWorld');
    this.uScalePx = gl.getUniformLocation(program, 'uScalePx');
    this.uViewportPx = gl.getUniformLocation(program, 'uViewportPx');

    this.vao = gl.createVertexArray();
    gl.bindVertexArray(this.vao);

    // Unit quad (TRIANGLE_STRIP), corners in [-1,1].
    const quad = new Float32Array([-1, -1, 1, -1, -1, 1, 1, 1]);
    this.quadBuf = gl.createBuffer();
    gl.bindBuffer(gl.ARRAY_BUFFER, this.quadBuf);
    gl.bufferData(gl.ARRAY_BUFFER, quad, gl.STATIC_DRAW);
    gl.enableVertexAttribArray(0);
    gl.vertexAttribPointer(0, 2, gl.FLOAT, false, 0, 0);

    // Per-instance buffer; layout described once, data uploaded each frame.
    this.instanceBuf = gl.createBuffer();
    gl.bindBuffer(gl.ARRAY_BUFFER, this.instanceBuf);
    const stride = FLOATS_PER_INSTANCE * 4;
    // loc1 world(2), loc2 color(3), loc3 size(1), loc4 bright(1), loc5 giant(1), loc6 aggregate(1)
    const desc: readonly [number, number, number][] = [
      [1, 2, 0],
      [2, 3, 2],
      [3, 1, 5],
      [4, 1, 6],
      [5, 1, 7],
      [6, 1, 8],
    ];
    for (const [loc, size, offsetFloats] of desc) {
      gl.enableVertexAttribArray(loc);
      gl.vertexAttribPointer(
        loc,
        size,
        gl.FLOAT,
        false,
        stride,
        offsetFloats * 4,
      );
      gl.vertexAttribDivisor(loc, 1);
    }

    gl.bindVertexArray(null);

    // Additive blending on black space (premultiplied output -> ONE, ONE).
    gl.disable(gl.DEPTH_TEST);
    gl.enable(gl.BLEND);
    gl.blendFunc(gl.ONE, gl.ONE);
    gl.clearColor(0.0, 0.012, 0.031, 1.0); // ~#000308
  }

  resize(widthPx: number, heightPx: number, dpr: number): void {
    this.widthPx = widthPx;
    this.heightPx = heightPx;
    this.dpr = dpr;
    this.gl?.viewport(0, 0, widthPx, heightPx);
  }

  draw(scene: RenderScene, view: ViewTransform, timeSeconds: number): void {
    // timeSeconds drives twinkle/animation in the E8-02 post-pass; the base
    // instanced pass is time-independent. Referenced to satisfy the contract.
    void timeSeconds;
    const gl = this.gl;
    if (!gl || !this.program) {
      return;
    }
    if (this.widthPx !== view.widthPx || this.heightPx !== view.heightPx) {
      this.resize(view.widthPx, view.heightPx, view.dpr);
    }

    gl.viewport(0, 0, view.widthPx, view.heightPx);
    gl.clear(gl.COLOR_BUFFER_BIT);

    const count = scene.stars.length + scene.aggregates.length;
    if (count === 0) {
      return;
    }

    // Recover camera world origin + device-px scale from the contract w2s, so
    // this layer needs nothing the Canvas2D layer did not already receive.
    const scalePx = view.scale * view.dpr;
    const { camX, camY } = recoverCamWorld(view, scalePx);

    this.ensureCapacity(count);
    const buf = this.data;
    let o = 0;
    for (const st of scene.stars) {
      const c = SPECTRAL_PALETTE[st.k] ?? SPECTRAL_PALETTE[4];
      buf[o] = st.x;
      buf[o + 1] = st.y;
      buf[o + 2] = c[0] / 255;
      buf[o + 3] = c[1] / 255;
      buf[o + 4] = c[2] / 255;
      buf[o + 5] = st.sz;
      buf[o + 6] = st.b;
      buf[o + 7] = st.g;
      buf[o + 8] = 0;
      o += FLOATS_PER_INSTANCE;
    }
    for (const a of scene.aggregates) {
      buf[o] = a.x;
      buf[o + 1] = a.y;
      buf[o + 2] = 0.78;
      buf[o + 3] = 0.8;
      buf[o + 4] = 1.0;
      buf[o + 5] = 1.0;
      buf[o + 6] = a.weight;
      buf[o + 7] = 0;
      buf[o + 8] = 1;
      o += FLOATS_PER_INSTANCE;
    }

    gl.bindVertexArray(this.vao);
    gl.bindBuffer(gl.ARRAY_BUFFER, this.instanceBuf);
    // Upload only the used sub-range (subarray view, no copy).
    gl.bufferData(
      gl.ARRAY_BUFFER,
      buf.subarray(0, count * FLOATS_PER_INSTANCE),
      gl.DYNAMIC_DRAW,
    );

    gl.useProgram(this.program);
    gl.uniform2f(this.uCamWorld, camX, camY);
    gl.uniform1f(this.uScalePx, scalePx);
    gl.uniform2f(this.uViewportPx, view.widthPx, view.heightPx);

    // THE single instanced draw call: 4 quad vertices x `count` instances.
    gl.drawArraysInstanced(gl.TRIANGLE_STRIP, 0, 4, count);

    gl.bindVertexArray(null);
  }

  dispose(): void {
    const gl = this.gl;
    if (!gl) {
      return;
    }
    if (this.program) {
      gl.deleteProgram(this.program);
    }
    if (this.vao) {
      gl.deleteVertexArray(this.vao);
    }
    if (this.quadBuf) {
      gl.deleteBuffer(this.quadBuf);
    }
    if (this.instanceBuf) {
      gl.deleteBuffer(this.instanceBuf);
    }
    this.program = null;
    this.vao = null;
    this.quadBuf = null;
    this.instanceBuf = null;
    this.gl = null;
    this.data = new Float32Array(0);
    this.capacity = 0;
  }

  private ensureCapacity(instances: number): void {
    if (instances <= this.capacity) {
      return;
    }
    // Grow geometrically to amortise reallocation while panning.
    let cap = Math.max(this.capacity, 4096);
    while (cap < instances) {
      cap *= 2;
    }
    this.capacity = cap;
    this.data = new Float32Array(cap * FLOATS_PER_INSTANCE);
  }
}

/**
 * Recover the camera world origin from the contract w2s transform.
 * w2s(wx,wy) = (w - cam) * scalePx + viewport/2  (device px), so
 *   cam = w - (w2s(w) - viewport/2) / scalePx.
 * Evaluated at world (0,0) for stability.
 */
export function recoverCamWorld(
  view: ViewTransform,
  scalePx: number,
): { camX: number; camY: number } {
  const origin = view.w2s(0, 0);
  const hw = view.widthPx / 2;
  const hh = view.heightPx / 2;
  if (scalePx === 0) {
    return { camX: 0, camY: 0 };
  }
  return {
    camX: -(origin.x - hw) / scalePx,
    camY: -(origin.y - hh) / scalePx,
  };
}

function linkProgram(
  gl: WebGL2RenderingContext,
  vertSrc: string,
  fragSrc: string,
): WebGLProgram {
  const vs = compileShader(gl, gl.VERTEX_SHADER, vertSrc);
  const fs = compileShader(gl, gl.FRAGMENT_SHADER, fragSrc);
  const program = gl.createProgram();
  if (!program) {
    throw new Error('WebGL2: failed to create program');
  }
  gl.attachShader(program, vs);
  gl.attachShader(program, fs);
  gl.linkProgram(program);
  gl.deleteShader(vs);
  gl.deleteShader(fs);
  if (!gl.getProgramParameter(program, gl.LINK_STATUS)) {
    const log = gl.getProgramInfoLog(program);
    gl.deleteProgram(program);
    throw new Error('WebGL2: program link failed: ' + log);
  }
  return program;
}

function compileShader(
  gl: WebGL2RenderingContext,
  type: number,
  src: string,
): WebGLShader {
  const shader = gl.createShader(type);
  if (!shader) {
    throw new Error('WebGL2: failed to create shader');
  }
  gl.shaderSource(shader, src);
  gl.compileShader(shader);
  if (!gl.getShaderParameter(shader, gl.COMPILE_STATUS)) {
    const log = gl.getShaderInfoLog(shader);
    gl.deleteShader(shader);
    throw new Error('WebGL2: shader compile failed: ' + log);
  }
  return shader;
}
