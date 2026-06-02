import {
  SPECTRAL_PALETTE,
  type GalaxyDrawLayer,
  type RenderScene,
  type ViewTransform,
} from './render-model';
import { clamp01, hashId, proceduralPlanets } from './canvas-draw-layer';

/**
 * WebGL2 instanced point-sprite galaxy draw layer with an astrophotography
 * post-processing pipeline (E8-01 base + E8-02 look).
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
 * E8-02 render-pass pipeline (FBO chain), all GPU-side and a FIXED number of
 * passes per frame (never per-star CPU work), so the frame budget is bounded:
 *
 *   1. NEBULA   -> sceneFbo (full res)  fullscreen fbm dust/cloud, additive,
 *                                       tinted by spiral-arm density.
 *   2. STARS    -> sceneFbo (full res)  the single instanced point-sprite pass;
 *                                       bright/giant stars also emit 4/6-point
 *                                       diffraction spikes inside the fragment
 *                                       shader (no extra draw call).
 *   3. PLANETS  -> sceneFbo (full res)  lit-sphere instanced pass for active
 *                                       systems once zoomed in (zoom-gated;
 *                                       empty at galaxy scale -> zero work).
 *   4. BRIGHT   -> bloomA (1/4 res)     bright-pass threshold of the scene.
 *   5. BLUR-H   -> bloomB (1/4 res)     separable Gaussian, horizontal.
 *   6. BLUR-V   -> bloomA (1/4 res)     separable Gaussian, vertical.
 *   7. COMPOSITE-> screen               scene + bloom (additive), tone curve.
 *
 * The bloom is computed at quarter resolution so the two blur taps stay cheap
 * regardless of window size. If framebuffer/float-texture support is missing
 * (e.g. the headless unit-test stub), the layer transparently falls back to
 * drawing the stars directly to the screen with additive blending — identical
 * to the E8-01 behaviour and the single-instanced-draw-call invariant.
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

// 8 floats per planet instance: cx, cy (screen px), radius (px), r,g,b, alpha, lightAngle.
const FLOATS_PER_PLANET = 8;

// Bloom is computed at 1/BLOOM_DOWNSAMPLE resolution to bound blur cost.
const BLOOM_DOWNSAMPLE = 4;

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
uniform float uTime;        // seconds, drives gentle twinkle

out vec3 vColor;
out float vBright;
out float vGiant;
out float vAggregate;
out float vSpike;           // diffraction-spike strength 0..1 (bright stars only)
out float vTwinkle;
out vec2 vQuad;

void main() {
  // Point radius in device px. Stars stay point-like: size grows only gently
  // with zoom (matches the PoC r ramp, clamped).
  float zoomGain = 0.85 + min(1.6, uScalePx * 0.02);
  float baseR = max(0.55, (0.45 + aSize * 0.55) * zoomGain);

  // Diffraction spikes only on the brightest stars, and only once they are a
  // few px across (matches the PoC spikeT gate). Drives a larger quad so the
  // cross has room to extend, and is rendered in the fragment shader.
  float spikeT = clamp((aBright - 0.6) / 0.3, 0.0, 1.0)
               * clamp((baseR - 1.6) / 2.0, 0.0, 1.0);

  // Per-star twinkle (deterministic from instance id is unavailable here, so
  // derive a phase from position; cheap and stable).
  float phase = (aWorld.x * 0.7 + aWorld.y * 1.3);
  float twinkle = 0.88 + 0.12 * sin(uTime * 1.6 + phase);

  // The drawn quad is the glow radius (~4.2x the core) so additive falloff fits.
  // When spikes are active the quad grows so the cross arms fit inside it.
  float glowR = baseR * 4.2;
  float spikeLen = glowR * (2.0 + aGiant * 1.6) * spikeT;
  float quadR = max(glowR, spikeLen);

  // Aggregates are big soft blobs sized to the viewport, not point-like.
  if (aAggregate > 0.5) {
    quadR = max(8.0, 0.04 * min(uViewportPx.x, uViewportPx.y));
    spikeT = 0.0;
  }

  // World -> device-pixel screen (same maths as ViewTransform.w2s).
  vec2 screenPx = (aWorld - uCamWorld) * uScalePx + uViewportPx * 0.5;
  vec2 cornerPx = screenPx + aCorner * quadR;

  // Device px -> clip space. Y is flipped (screen y grows downward).
  vec2 clip = (cornerPx / uViewportPx) * 2.0 - 1.0;
  clip.y = -clip.y;

  gl_Position = vec4(clip, 0.0, 1.0);
  vColor = aColor;
  vBright = aBright;
  vGiant = aGiant;
  vAggregate = aAggregate;
  vSpike = spikeT;
  vTwinkle = twinkle;
  // Remap quad coords so the glow core stays at the same relative radius even
  // when the quad was enlarged for spikes: vQuad is in units of glowR.
  vQuad = aCorner * (quadR / max(glowR, 1e-3));
}
`;

const FRAG_SRC = `#version 300 es
precision highp float;

in vec3 vColor;
in float vBright;
in float vGiant;
in float vAggregate;
in float vSpike;
in float vTwinkle;
in vec2 vQuad;

out vec4 outColor;

void main() {
  float d = length(vQuad);          // 0 at centre, 1 at glow edge, >1 in spike margin

  if (vAggregate > 0.5) {
    if (d > 1.0) { discard; }
    // Soft density blob: dim, blue-white, broad falloff.
    float a = (1.0 - d);
    a = a * a * (0.08 + vBright * 0.42);
    outColor = vec4(vec3(0.78, 0.80, 1.0) * a, a);
    return;
  }

  // Star: bright core + additive glow halo. Core occupies the inner ~24%.
  float core = smoothstep(0.26, 0.0, d);
  float halo = pow(max(0.0, 1.0 - d), 2.2);
  float coreA = core * min(1.0, 0.55 + vBright) * vTwinkle;
  float haloA = halo * (0.2 + 0.5 * vBright) * vTwinkle;

  // Diffraction spikes: thin bright lines along the axes (and 45-deg for a
  // 6-pointed feel on giants). Computed in the fragment shader for bright
  // instances; zero cost on dim stars because vSpike is 0.
  float spike = 0.0;
  if (vSpike > 0.03) {
    vec2 q = vQuad;
    float along = max(abs(q.x), abs(q.y));        // axis distance
    float across = min(abs(q.x), abs(q.y));       // perpendicular tightness
    // Sharp falloff perpendicular to the arm, smooth taper along its length.
    float arm = exp(-across * 90.0) * pow(max(0.0, 1.0 - along), 1.3);
    float diag = 0.0;
    if (vGiant > 0.5) {
      // Secondary 45-degree pair -> 6/8-point look on the biggest stars.
      vec2 r = vec2(q.x + q.y, q.x - q.y) * 0.70710678;
      float dAlong = max(abs(r.x), abs(r.y));
      float dAcross = min(abs(r.x), abs(r.y));
      diag = exp(-dAcross * 110.0) * pow(max(0.0, 1.0 - dAlong), 1.5) * 0.6;
    }
    spike = (arm + diag) * vSpike * 0.5 * vTwinkle;
  }

  if (d > 1.0 && spike <= 0.0) { discard; }

  // White-hot core blended toward spectral colour outward.
  vec3 rgb = mix(vColor, vec3(1.0), core * 0.9);
  float a = clamp(coreA + haloA, 0.0, 1.0);
  vec3 outRgb = rgb * a + vColor * spike;
  // Premultiplied additive output (blend func is ONE, ONE).
  outColor = vec4(outRgb, a + spike);
}
`;

// --- Fullscreen post-processing shaders -----------------------------------

// A single oversized triangle covers the screen with one draw (no VBO needed,
// gl_VertexID drives it). Used for nebula, bright-pass, blur and composite.
const FULLSCREEN_VERT = `#version 300 es
precision highp float;
out vec2 vUv;
void main() {
  vec2 p = vec2((gl_VertexID == 2) ? 3.0 : -1.0,
                (gl_VertexID == 1) ? 3.0 : -1.0);
  vUv = p * 0.5 + 0.5;
  gl_Position = vec4(p, 0.0, 1.0);
}
`;

// Nebulosity / dust: value-noise fbm, tinted to spiral-arm-style cloud colours,
// brightest toward the galaxy centre and fading out as you zoom in. Additive.
const NEBULA_FRAG = `#version 300 es
precision highp float;
in vec2 vUv;
out vec4 outColor;

uniform vec2 uViewportPx;
uniform vec2 uCamWorld;   // camera world origin
uniform float uScalePx;   // device px per world unit
uniform float uRmax;      // galaxy radius (world units)
uniform float uTime;

float hash(vec2 p) {
  p = fract(p * vec2(123.34, 345.45));
  p += dot(p, p + 34.345);
  return fract(p.x * p.y);
}
float valueNoise(vec2 p) {
  vec2 i = floor(p);
  vec2 f = fract(p);
  vec2 u = f * f * (3.0 - 2.0 * f);
  float a = hash(i);
  float b = hash(i + vec2(1.0, 0.0));
  float c = hash(i + vec2(0.0, 1.0));
  float d = hash(i + vec2(1.0, 1.0));
  return mix(mix(a, b, u.x), mix(c, d, u.x), u.y);
}
float fbm(vec2 p) {
  float v = 0.0;
  float amp = 0.5;
  for (int i = 0; i < 5; i++) {
    v += amp * valueNoise(p);
    p = p * 2.02 + vec2(11.3, 7.1);
    amp *= 0.5;
  }
  return v;
}

void main() {
  // Reconstruct this pixel's world position from the same projection the stars
  // use, so the dust is locked to the galaxy and pans/zooms with it.
  vec2 fragPx = vec2(vUv.x, 1.0 - vUv.y) * uViewportPx;
  vec2 world = (fragPx - uViewportPx * 0.5) / max(uScalePx, 1e-4) + uCamWorld;

  float rr = length(world) / max(uRmax, 1.0);
  // Dust mostly reads when zoomed out; gently fades as the disc fills the view.
  float zoomFade = clamp((0.06 - uScalePx) / 0.06, 0.0, 1.0);
  float discFall = exp(-rr * rr * 2.2);

  // Two noise octaves at galaxy scale; drift slowly so it feels alive.
  vec2 nUv = world / max(uRmax, 1.0) * 3.5;
  float n = fbm(nUv + vec2(uTime * 0.005, -uTime * 0.003));
  float n2 = fbm(nUv * 2.3 - vec2(5.0, 2.0));
  float cloud = pow(clamp(n * 0.7 + n2 * 0.35, 0.0, 1.0), 1.6);

  // Spiral-arm-ish modulation: brighten where the angular noise aligns.
  float ang = atan(world.y, world.x);
  float arm = 0.6 + 0.4 * sin(ang * 2.0 + log(rr + 0.05) * 6.0);

  float intensity = cloud * discFall * arm * (0.18 + 0.5 * zoomFade);
  intensity = clamp(intensity, 0.0, 1.0);

  // Cool blue-violet dust with a warm core tint near the centre.
  vec3 cool = vec3(0.16, 0.20, 0.42);
  vec3 warm = vec3(0.42, 0.30, 0.34);
  vec3 col = mix(cool, warm, discFall * 0.7) * intensity;

  // Add a soft central bulge glow (mirrors the PoC nebula core).
  float bulge = exp(-rr * rr * 26.0);
  col += vec3(0.55, 0.46, 0.34) * bulge * (0.30 + 0.2 * zoomFade);

  outColor = vec4(col, 1.0); // additive (ONE, ONE); rgb is the contribution
}
`;

// Bright-pass: keep only luminance above a soft knee (the bloom source).
const BRIGHT_FRAG = `#version 300 es
precision highp float;
in vec2 vUv;
out vec4 outColor;
uniform sampler2D uScene;
uniform float uThreshold;
void main() {
  vec3 c = texture(uScene, vUv).rgb;
  float l = max(max(c.r, c.g), c.b);
  float k = max(0.0, l - uThreshold) / max(l, 1e-4);
  outColor = vec4(c * k, 1.0);
}
`;

// Separable Gaussian (9-tap) along uDir (in texel units).
const BLUR_FRAG = `#version 300 es
precision highp float;
in vec2 vUv;
out vec4 outColor;
uniform sampler2D uSrc;
uniform vec2 uTexel;   // 1/size of the source
uniform vec2 uDir;     // (1,0) horizontal or (0,1) vertical
void main() {
  float w[5];
  w[0] = 0.227027; w[1] = 0.194595; w[2] = 0.121622;
  w[3] = 0.054054; w[4] = 0.016216;
  vec2 step = uTexel * uDir;
  vec3 acc = texture(uSrc, vUv).rgb * w[0];
  for (int i = 1; i < 5; i++) {
    vec2 off = step * float(i) * 1.6;
    acc += texture(uSrc, vUv + off).rgb * w[i];
    acc += texture(uSrc, vUv - off).rgb * w[i];
  }
  outColor = vec4(acc, 1.0);
}
`;

// Composite: scene + bloom, with a gentle filmic tone curve to keep the
// brightest cores from clipping flat while preserving black space.
const COMPOSITE_FRAG = `#version 300 es
precision highp float;
in vec2 vUv;
out vec4 outColor;
uniform sampler2D uScene;
uniform sampler2D uBloom;
uniform float uBloomStrength;
void main() {
  vec3 scene = texture(uScene, vUv).rgb;
  vec3 bloom = texture(uBloom, vUv).rgb;
  vec3 hdr = scene + bloom * uBloomStrength;
  // Reinhard-ish tone map; keeps near-black void black, rolls off highlights.
  vec3 mapped = hdr / (hdr + vec3(0.85));
  mapped = pow(mapped, vec3(0.9));
  outColor = vec4(mapped, 1.0);
}
`;

// --- Planet shading (system scale) ----------------------------------------

// Instanced lit-sphere pass. Each planet is a quad; the fragment shader shades
// a sphere with a simple Lambert term + rim, so planets look like lit bodies
// instead of flat dots once a system is large on screen (zoom-gated on the CPU
// side, so this pass is empty at galaxy scale).
const PLANET_VERT = `#version 300 es
precision highp float;
layout(location = 0) in vec2 aCorner;        // unit quad [-1,1]
layout(location = 1) in vec2 aCenter;        // screen px
layout(location = 2) in float aRadius;       // screen px
layout(location = 3) in vec3 aColor;
layout(location = 4) in float aAlpha;
layout(location = 5) in float aLight;        // light direction angle (rad)
uniform vec2 uViewportPx;
out vec2 vLocal;     // [-1,1] across the sphere quad
out vec3 vColor;
out float vAlpha;
out vec2 vLightDir;
void main() {
  vec2 px = aCenter + aCorner * aRadius;
  vec2 clip = (px / uViewportPx) * 2.0 - 1.0;
  clip.y = -clip.y;
  gl_Position = vec4(clip, 0.0, 1.0);
  vLocal = aCorner;
  vColor = aColor;
  vAlpha = aAlpha;
  vLightDir = vec2(cos(aLight), sin(aLight));
}
`;

const PLANET_FRAG = `#version 300 es
precision highp float;
in vec2 vLocal;
in vec3 vColor;
in float vAlpha;
in vec2 vLightDir;
out vec4 outColor;
void main() {
  float r2 = dot(vLocal, vLocal);
  if (r2 > 1.0) { discard; }
  // Reconstruct the sphere normal for this point on the disc.
  float z = sqrt(max(0.0, 1.0 - r2));
  vec3 n = normalize(vec3(vLocal, z));
  vec3 lightDir = normalize(vec3(vLightDir, 0.55));
  float lambert = max(0.0, dot(n, lightDir));
  // Soft terminator + small ambient so the night side is not pure black.
  float lit = 0.12 + 0.88 * smoothstep(0.0, 0.35, lambert);
  // Rim/atmosphere glow at the limb.
  float rim = pow(1.0 - z, 2.5) * 0.5;
  vec3 col = vColor * lit + vColor * rim;
  // Antialias the disc edge.
  float edge = smoothstep(1.0, 0.93, r2);
  outColor = vec4(col, vAlpha * edge);
}
`;

/** One offscreen colour target (texture + framebuffer) at a given size. */
interface Fbo {
  fb: WebGLFramebuffer;
  tex: WebGLTexture;
  w: number;
  h: number;
}

export class WebglDrawLayer implements GalaxyDrawLayer {
  private gl: WebGL2RenderingContext | null = null;
  private program: WebGLProgram | null = null;
  private vao: WebGLVertexArrayObject | null = null;
  private quadBuf: WebGLBuffer | null = null;
  private instanceBuf: WebGLBuffer | null = null;

  private uCamWorld: WebGLUniformLocation | null = null;
  private uScalePx: WebGLUniformLocation | null = null;
  private uViewportPx: WebGLUniformLocation | null = null;
  private uTime: WebGLUniformLocation | null = null;

  // --- E8-02 post-processing programs (null when FBO support is missing) ---
  private postOk = false;
  private nebulaProg: WebGLProgram | null = null;
  private brightProg: WebGLProgram | null = null;
  private blurProg: WebGLProgram | null = null;
  private compositeProg: WebGLProgram | null = null;
  private planetProg: WebGLProgram | null = null;

  private postVao: WebGLVertexArrayObject | null = null;
  private planetVao: WebGLVertexArrayObject | null = null;
  private planetQuadBuf: WebGLBuffer | null = null;
  private planetInstanceBuf: WebGLBuffer | null = null;

  private sceneFbo: Fbo | null = null;
  private bloomA: Fbo | null = null;
  private bloomB: Fbo | null = null;

  /** Reused instance staging buffer; grown as the visible count rises. */
  private data = new Float32Array(0);
  private capacity = 0;
  /** Reused planet staging buffer. */
  private planetData = new Float32Array(0);
  private planetCapacity = 0;

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
    this.uTime = gl.getUniformLocation(program, 'uTime');

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

    // Optional E8-02 post-processing. If anything here is unsupported (e.g. the
    // headless unit-test GL stub has no createFramebuffer), we degrade to the
    // direct-to-screen star pass without affecting correctness.
    this.initPost(gl);
  }

  /**
   * Build the post-processing programs + VAOs. Sets this.postOk only when the
   * GL implementation exposes the framebuffer/texture entry points we need.
   * Per-frame FBOs are (re)allocated lazily in ensureFbos().
   */
  private initPost(gl: WebGL2RenderingContext): void {
    const hasFboApi =
      typeof gl.createFramebuffer === 'function' &&
      typeof gl.createTexture === 'function' &&
      typeof gl.framebufferTexture2D === 'function' &&
      typeof gl.texImage2D === 'function' &&
      typeof gl.bindFramebuffer === 'function' &&
      typeof gl.drawArrays === 'function';
    if (!hasFboApi) {
      this.postOk = false;
      return;
    }
    try {
      this.nebulaProg = linkProgram(gl, FULLSCREEN_VERT, NEBULA_FRAG);
      this.brightProg = linkProgram(gl, FULLSCREEN_VERT, BRIGHT_FRAG);
      this.blurProg = linkProgram(gl, FULLSCREEN_VERT, BLUR_FRAG);
      this.compositeProg = linkProgram(gl, FULLSCREEN_VERT, COMPOSITE_FRAG);
      this.planetProg = linkProgram(gl, PLANET_VERT, PLANET_FRAG);

      // Empty VAO drives the gl_VertexID fullscreen triangle.
      this.postVao = gl.createVertexArray();

      // Planet instancing VAO: shared unit quad + per-planet buffer.
      this.planetVao = gl.createVertexArray();
      gl.bindVertexArray(this.planetVao);
      const quad = new Float32Array([-1, -1, 1, -1, -1, 1, 1, 1]);
      this.planetQuadBuf = gl.createBuffer();
      gl.bindBuffer(gl.ARRAY_BUFFER, this.planetQuadBuf);
      gl.bufferData(gl.ARRAY_BUFFER, quad, gl.STATIC_DRAW);
      gl.enableVertexAttribArray(0);
      gl.vertexAttribPointer(0, 2, gl.FLOAT, false, 0, 0);
      this.planetInstanceBuf = gl.createBuffer();
      gl.bindBuffer(gl.ARRAY_BUFFER, this.planetInstanceBuf);
      const pstride = FLOATS_PER_PLANET * 4;
      // loc1 center(2), loc2 radius(1), loc3 color(3), loc4 alpha(1), loc5 light(1)
      const pdesc: readonly [number, number, number][] = [
        [1, 2, 0],
        [2, 1, 2],
        [3, 3, 3],
        [4, 1, 6],
        [5, 1, 7],
      ];
      for (const [loc, size, off] of pdesc) {
        gl.enableVertexAttribArray(loc);
        gl.vertexAttribPointer(loc, size, gl.FLOAT, false, pstride, off * 4);
        gl.vertexAttribDivisor(loc, 1);
      }
      gl.bindVertexArray(null);

      this.postOk = true;
    } catch {
      // Shader/program failure -> degrade to the direct pass.
      this.postOk = false;
    }
  }

  resize(widthPx: number, heightPx: number, dpr: number): void {
    this.widthPx = widthPx;
    this.heightPx = heightPx;
    this.dpr = dpr;
    this.gl?.viewport(0, 0, widthPx, heightPx);
  }

  draw(scene: RenderScene, view: ViewTransform, timeSeconds: number): void {
    const gl = this.gl;
    if (!gl || !this.program) {
      return;
    }
    if (this.widthPx !== view.widthPx || this.heightPx !== view.heightPx) {
      this.resize(view.widthPx, view.heightPx, view.dpr);
    }

    const count = scene.stars.length + scene.aggregates.length;
    const scalePx = view.scale * view.dpr;
    const { camX, camY } = recoverCamWorld(view, scalePx);

    // Decide whether to run the full astrophotography pipeline this frame. We
    // need post support AND non-zero FBOs; otherwise fall back to direct draw.
    const usePost =
      this.postOk &&
      view.widthPx > 0 &&
      view.heightPx > 0 &&
      this.ensureFbos(view.widthPx, view.heightPx);

    if (!usePost) {
      // Direct-to-screen path (E8-01 behaviour + spikes/twinkle in-shader).
      gl.viewport(0, 0, view.widthPx, view.heightPx);
      gl.clear(gl.COLOR_BUFFER_BIT);
      if (count > 0) {
        this.drawStarPass(gl, scene, view, camX, camY, scalePx, timeSeconds);
      }
      return;
    }

    // --- Pass 1+2+3: build the HDR scene in the full-res sceneFbo. ----------
    const scene_ = this.sceneFbo as Fbo;
    gl.bindFramebuffer(gl.FRAMEBUFFER, scene_.fb);
    gl.viewport(0, 0, scene_.w, scene_.h);
    gl.clearColor(0.0, 0.012, 0.031, 1.0);
    gl.clear(gl.COLOR_BUFFER_BIT);

    // Pass 1: nebulosity / dust (additive).
    gl.blendFunc(gl.ONE, gl.ONE);
    this.drawNebula(gl, view, camX, camY, scalePx, scene.rMax, timeSeconds);

    // Pass 2: the single instanced star/aggregate pass (+ in-shader spikes).
    if (count > 0) {
      this.drawStarPass(gl, scene, view, camX, camY, scalePx, timeSeconds);
    }

    // Pass 3: lit planets for active systems (zoom-gated; empty -> no work).
    this.drawPlanets(gl, scene, view, scalePx, timeSeconds);

    // --- Pass 4: bright-pass into the 1/4-res bloomA. -----------------------
    const a = this.bloomA as Fbo;
    const b = this.bloomB as Fbo;
    gl.disable(gl.BLEND);
    gl.bindVertexArray(this.postVao);

    gl.bindFramebuffer(gl.FRAMEBUFFER, a.fb);
    gl.viewport(0, 0, a.w, a.h);
    gl.useProgram(this.brightProg);
    this.bindTex(gl, this.brightProg as WebGLProgram, 'uScene', scene_.tex, 0);
    gl.uniform1f(loc(gl, this.brightProg, 'uThreshold'), 0.5);
    gl.drawArrays(gl.TRIANGLES, 0, 3);

    // --- Pass 5: horizontal blur (bloomA -> bloomB). ------------------------
    gl.bindFramebuffer(gl.FRAMEBUFFER, b.fb);
    gl.viewport(0, 0, b.w, b.h);
    gl.useProgram(this.blurProg);
    this.bindTex(gl, this.blurProg as WebGLProgram, 'uSrc', a.tex, 0);
    gl.uniform2f(loc(gl, this.blurProg, 'uTexel'), 1 / a.w, 1 / a.h);
    gl.uniform2f(loc(gl, this.blurProg, 'uDir'), 1, 0);
    gl.drawArrays(gl.TRIANGLES, 0, 3);

    // --- Pass 6: vertical blur (bloomB -> bloomA). --------------------------
    gl.bindFramebuffer(gl.FRAMEBUFFER, a.fb);
    gl.viewport(0, 0, a.w, a.h);
    this.bindTex(gl, this.blurProg as WebGLProgram, 'uSrc', b.tex, 0);
    gl.uniform2f(loc(gl, this.blurProg, 'uTexel'), 1 / b.w, 1 / b.h);
    gl.uniform2f(loc(gl, this.blurProg, 'uDir'), 0, 1);
    gl.drawArrays(gl.TRIANGLES, 0, 3);

    // --- Pass 7: composite scene + bloom to the screen. ---------------------
    gl.bindFramebuffer(gl.FRAMEBUFFER, null);
    gl.viewport(0, 0, view.widthPx, view.heightPx);
    gl.useProgram(this.compositeProg);
    this.bindTex(gl, this.compositeProg as WebGLProgram, 'uScene', scene_.tex, 0);
    this.bindTex(gl, this.compositeProg as WebGLProgram, 'uBloom', a.tex, 1);
    gl.uniform1f(loc(gl, this.compositeProg, 'uBloomStrength'), 1.25);
    gl.drawArrays(gl.TRIANGLES, 0, 3);

    gl.bindVertexArray(null);
    // Restore additive blending for the next frame's offscreen passes.
    gl.enable(gl.BLEND);
    gl.blendFunc(gl.ONE, gl.ONE);
  }

  /** The instanced star/aggregate pass (one drawArraysInstanced call). */
  private drawStarPass(
    gl: WebGL2RenderingContext,
    scene: RenderScene,
    view: ViewTransform,
    camX: number,
    camY: number,
    scalePx: number,
    timeSeconds: number,
  ): void {
    const count = scene.stars.length + scene.aggregates.length;
    if (count === 0) {
      return;
    }
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
    for (const ag of scene.aggregates) {
      buf[o] = ag.x;
      buf[o + 1] = ag.y;
      buf[o + 2] = 0.78;
      buf[o + 3] = 0.8;
      buf[o + 4] = 1.0;
      buf[o + 5] = 1.0;
      buf[o + 6] = ag.weight;
      buf[o + 7] = 0;
      buf[o + 8] = 1;
      o += FLOATS_PER_INSTANCE;
    }

    gl.blendFunc(gl.ONE, gl.ONE);
    gl.bindVertexArray(this.vao);
    gl.bindBuffer(gl.ARRAY_BUFFER, this.instanceBuf);
    gl.bufferData(
      gl.ARRAY_BUFFER,
      buf.subarray(0, count * FLOATS_PER_INSTANCE),
      gl.DYNAMIC_DRAW,
    );

    gl.useProgram(this.program);
    gl.uniform2f(this.uCamWorld, camX, camY);
    gl.uniform1f(this.uScalePx, scalePx);
    gl.uniform2f(this.uViewportPx, view.widthPx, view.heightPx);
    if (this.uTime) {
      gl.uniform1f(this.uTime, timeSeconds);
    }

    // THE single instanced draw call: 4 quad vertices x `count` instances.
    gl.drawArraysInstanced(gl.TRIANGLE_STRIP, 0, 4, count);
    gl.bindVertexArray(null);
  }

  /** Fullscreen procedural nebula/dust pass (additive into the bound target). */
  private drawNebula(
    gl: WebGL2RenderingContext,
    view: ViewTransform,
    camX: number,
    camY: number,
    scalePx: number,
    rMax: number,
    timeSeconds: number,
  ): void {
    if (!this.nebulaProg || rMax <= 0) {
      return;
    }
    gl.bindVertexArray(this.postVao);
    gl.useProgram(this.nebulaProg);
    gl.uniform2f(loc(gl, this.nebulaProg, 'uViewportPx'), view.widthPx, view.heightPx);
    gl.uniform2f(loc(gl, this.nebulaProg, 'uCamWorld'), camX, camY);
    gl.uniform1f(loc(gl, this.nebulaProg, 'uScalePx'), scalePx);
    gl.uniform1f(loc(gl, this.nebulaProg, 'uRmax'), rMax);
    gl.uniform1f(loc(gl, this.nebulaProg, 'uTime'), timeSeconds);
    gl.drawArrays(gl.TRIANGLES, 0, 3);
    gl.bindVertexArray(null);
  }

  /**
   * Lit-sphere planet pass for active systems, zoom-gated exactly like the
   * Canvas2D layer (planets fade in via detailA above scale 2.2). The planet
   * count per visible active system is small and bounded, and the whole set is
   * drawn in ONE instanced call — bounded per-frame work, no per-planet JS draw.
   */
  private drawPlanets(
    gl: WebGL2RenderingContext,
    scene: RenderScene,
    view: ViewTransform,
    scalePx: number,
    timeSeconds: number,
  ): void {
    if (!this.planetProg || scene.stars.length === 0) {
      return;
    }
    const detailA = clamp01((scalePx - 2.2) / 3.5);
    if (detailA <= 0.01) {
      return;
    }
    const W = view.widthPx;
    const H = view.heightPx;
    const DPR = view.dpr;
    const planetSize = Math.min(1.6, scalePx * 0.05);

    // Build the per-planet instance buffer (CPU work bounded by *visible active
    // systems*, never the catalog; only fires when zoomed into a few systems).
    let n = 0;
    // First count to size the buffer (cheap; same procedural data reused below).
    for (const st of scene.stars) {
      if (st.activeSystemId === null) {
        continue;
      }
      const p = view.w2s(st.x, st.y);
      if (p.x < -300 || p.x > W + 300 || p.y < -300 || p.y > H + 300) {
        continue;
      }
      n += proceduralPlanets(st).length;
    }
    if (n === 0) {
      return;
    }
    this.ensurePlanetCapacity(n);
    const buf = this.planetData;
    let o = 0;
    let written = 0;
    for (const st of scene.stars) {
      if (st.activeSystemId === null) {
        continue;
      }
      const p = view.w2s(st.x, st.y);
      if (p.x < -300 || p.x > W + 300 || p.y < -300 || p.y > H + 300) {
        continue;
      }
      const planets = proceduralPlanets(st);
      const bi = Math.floor(hashId(st.id * 19) * 1000) % BIOME_COLORS.length;
      for (let i = 0; i < planets.length; i++) {
        const pl = planets[i];
        const or = pl.o * 18 * scalePx;
        if (or < 14) {
          continue;
        }
        const ang = pl.ph + timeSeconds * pl.sp;
        const Px = p.x + Math.cos(ang) * or;
        const Py = p.y + Math.sin(ang) * or;
        const pr =
          Math.max(2 * DPR, pl.r * 2.4 * DPR * planetSize) * detailA;
        if (pr < 1.2) {
          continue;
        }
        const col = BIOME_COLORS[(bi + i) % BIOME_COLORS.length];
        // Light points from the star at the system centre.
        const lightAngle = Math.atan2(p.y - Py, p.x - Px);
        buf[o] = Px;
        buf[o + 1] = Py;
        buf[o + 2] = pr;
        buf[o + 3] = col[0];
        buf[o + 4] = col[1];
        buf[o + 5] = col[2];
        buf[o + 6] = detailA;
        buf[o + 7] = lightAngle;
        o += FLOATS_PER_PLANET;
        written++;
      }
    }
    if (written === 0) {
      return;
    }

    // Planets are solid lit bodies -> standard alpha blending, not additive.
    gl.blendFunc(gl.SRC_ALPHA, gl.ONE_MINUS_SRC_ALPHA);
    gl.bindVertexArray(this.planetVao);
    gl.bindBuffer(gl.ARRAY_BUFFER, this.planetInstanceBuf);
    gl.bufferData(
      gl.ARRAY_BUFFER,
      buf.subarray(0, written * FLOATS_PER_PLANET),
      gl.DYNAMIC_DRAW,
    );
    gl.useProgram(this.planetProg);
    gl.uniform2f(loc(gl, this.planetProg, 'uViewportPx'), W, H);
    gl.drawArraysInstanced(gl.TRIANGLE_STRIP, 0, 4, written);
    gl.bindVertexArray(null);
    // Restore additive for subsequent passes.
    gl.blendFunc(gl.ONE, gl.ONE);
  }

  /** Bind a texture to a sampler uniform on `unit`. */
  private bindTex(
    gl: WebGL2RenderingContext,
    prog: WebGLProgram,
    name: string,
    tex: WebGLTexture,
    unit: number,
  ): void {
    gl.activeTexture(gl.TEXTURE0 + unit);
    gl.bindTexture(gl.TEXTURE_2D, tex);
    gl.uniform1i(loc(gl, prog, name), unit);
  }

  /**
   * (Re)allocate the scene + half/quarter-res bloom FBOs to match the viewport.
   * Returns false (disabling post) if allocation fails on this GL impl.
   */
  private ensureFbos(w: number, h: number): boolean {
    const gl = this.gl as WebGL2RenderingContext;
    if (this.sceneFbo && this.sceneFbo.w === w && this.sceneFbo.h === h) {
      return true;
    }
    this.freeFbos();
    const bw = Math.max(1, Math.floor(w / BLOOM_DOWNSAMPLE));
    const bh = Math.max(1, Math.floor(h / BLOOM_DOWNSAMPLE));
    try {
      this.sceneFbo = this.makeFbo(gl, w, h);
      this.bloomA = this.makeFbo(gl, bw, bh);
      this.bloomB = this.makeFbo(gl, bw, bh);
      return this.sceneFbo !== null && this.bloomA !== null && this.bloomB !== null;
    } catch {
      this.freeFbos();
      this.postOk = false;
      return false;
    }
  }

  private makeFbo(gl: WebGL2RenderingContext, w: number, h: number): Fbo {
    const tex = gl.createTexture();
    gl.bindTexture(gl.TEXTURE_2D, tex);
    // 8-bit RGBA target: universally renderable, no float-FBO extension needed.
    gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA, w, h, 0, gl.RGBA, gl.UNSIGNED_BYTE, null);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, gl.LINEAR);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MAG_FILTER, gl.LINEAR);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_S, gl.CLAMP_TO_EDGE);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_T, gl.CLAMP_TO_EDGE);
    const fb = gl.createFramebuffer();
    gl.bindFramebuffer(gl.FRAMEBUFFER, fb);
    gl.framebufferTexture2D(gl.FRAMEBUFFER, gl.COLOR_ATTACHMENT0, gl.TEXTURE_2D, tex, 0);
    gl.bindFramebuffer(gl.FRAMEBUFFER, null);
    gl.bindTexture(gl.TEXTURE_2D, null);
    return { fb: fb as WebGLFramebuffer, tex: tex as WebGLTexture, w, h };
  }

  private freeFbos(): void {
    const gl = this.gl;
    if (!gl) {
      return;
    }
    for (const f of [this.sceneFbo, this.bloomA, this.bloomB]) {
      if (f) {
        gl.deleteFramebuffer(f.fb);
        gl.deleteTexture(f.tex);
      }
    }
    this.sceneFbo = null;
    this.bloomA = null;
    this.bloomB = null;
  }

  dispose(): void {
    const gl = this.gl;
    if (!gl) {
      return;
    }
    this.freeFbos();
    for (const p of [
      this.program,
      this.nebulaProg,
      this.brightProg,
      this.blurProg,
      this.compositeProg,
      this.planetProg,
    ]) {
      if (p) {
        gl.deleteProgram(p);
      }
    }
    for (const v of [this.vao, this.postVao, this.planetVao]) {
      if (v) {
        gl.deleteVertexArray(v);
      }
    }
    for (const b of [
      this.quadBuf,
      this.instanceBuf,
      this.planetQuadBuf,
      this.planetInstanceBuf,
    ]) {
      if (b) {
        gl.deleteBuffer(b);
      }
    }
    this.program = null;
    this.nebulaProg = null;
    this.brightProg = null;
    this.blurProg = null;
    this.compositeProg = null;
    this.planetProg = null;
    this.vao = null;
    this.postVao = null;
    this.planetVao = null;
    this.quadBuf = null;
    this.instanceBuf = null;
    this.planetQuadBuf = null;
    this.planetInstanceBuf = null;
    this.postOk = false;
    this.gl = null;
    this.data = new Float32Array(0);
    this.capacity = 0;
    this.planetData = new Float32Array(0);
    this.planetCapacity = 0;
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

  private ensurePlanetCapacity(instances: number): void {
    if (instances <= this.planetCapacity) {
      return;
    }
    let cap = Math.max(this.planetCapacity, 256);
    while (cap < instances) {
      cap *= 2;
    }
    this.planetCapacity = cap;
    this.planetData = new Float32Array(cap * FLOATS_PER_PLANET);
  }
}

/**
 * Biome-ish planet colours (0..1), mirroring the warm/cool variety of the PoC
 * planet palette so lit spheres read as varied worlds rather than identical
 * dots. Index is derived deterministically from the star id.
 */
const BIOME_COLORS: readonly (readonly [number, number, number])[] = [
  [0.62, 0.71, 0.86], // ice / pale
  [0.32, 0.55, 0.78], // ocean blue
  [0.55, 0.72, 0.45], // verdant
  [0.78, 0.62, 0.42], // arid tan
  [0.74, 0.45, 0.33], // rust / mars
  [0.7, 0.66, 0.58], // rocky grey
  [0.84, 0.78, 0.6], // desert gold
];

/** Resolve and return a uniform location (small helper for the post passes). */
function loc(
  gl: WebGL2RenderingContext,
  prog: WebGLProgram | null,
  name: string,
): WebGLUniformLocation | null {
  if (!prog) {
    return null;
  }
  return gl.getUniformLocation(prog, name);
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
