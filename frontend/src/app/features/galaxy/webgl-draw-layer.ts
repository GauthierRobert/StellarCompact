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

// 8 floats per overlay mark: cx, cy (screen px), radius (px), r,g,b, flags, activity.
// flags packs battle (bit0) + blockade (bit1) + hasTint (bit2) for the marker.
const FLOATS_PER_OVERLAY = 8;

// 6 floats per territory node: cx, cy (screen px), radius (px), r, g, b.
const FLOATS_PER_TERRITORY = 6;

// 8 floats per route instance: ax, ay (screen px), bx, by (screen px),
// halfWidth (px), r, g, b. Route kind colour is baked into rgb on the CPU; a
// per-instance flag (contested flicker) is folded into the brightness uniform.
const FLOATS_PER_ROUTE = 8;

// 13 floats per interstellar object instance:
//   cx, cy (screen px), radius (px), kind, phase, selected, fade,
//   tintR, tintG, tintB, tint2R, tint2G, tint2B.
const FLOATS_PER_OBJECT = 13;

// 11 floats per sector cell instance:
//   cx, cy (screen px centre), half (px), tintR, tintG, tintB, hasTint,
//   lineAlpha, fillAlpha, selected, hovered.
const FLOATS_PER_SECTOR = 11;

// Numeric kind codes for the object fragment shader switch (mirrors the order in
// InterstellarKind; the CPU maps the string kind to this code once per frame).
const OBJECT_KIND_CODE: Record<string, number> = {
  nebula: 0,
  blackhole: 1,
  pulsar: 2,
  wormhole: 3,
  asteroidField: 4,
  roguePlanet: 5,
  supernovaRemnant: 6,
};

// Fog reveal-disk uniform-array cap. If more than this many reveal disks are in
// view, the CPU keeps the N nearest the screen centre (bounded shader work).
const FOG_MAX_REVEALS = 64;

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
uniform float uDpr;         // device-pixel ratio (so px sizes scale with DPR)

out vec3 vColor;
out float vBright;
out float vGiant;
out float vAggregate;
out float vSpike;           // diffraction-spike strength 0..1 (bright stars only)
out float vTwinkle;
out vec2 vQuad;

void main() {
  // Point radius in device px. Stars stay point-like at galaxy scale but grow
  // as you zoom in so a region/system view reads as a populated star field
  // rather than sparse sub-pixel dots (the ramp is negligible at galaxy fit and
  // ramps up with zoom). Render-only — the catalog itself is unchanged.
  // DPR-aware so the on-screen star size is perceptually identical at 1x and 2x
  // (the Canvas2D fallback multiplies its radii by DPR; match that here so the
  // backends look the same and stars are not half-size on hi-DPI displays).
  float dpr = max(uDpr, 1.0);
  float zoomGain = 0.9 + min(3.5, uScalePx * 0.35);
  float baseR = max(0.7 * dpr, (0.5 + aSize * 0.6) * zoomGain * dpr);

  // Diffraction spikes only on the brightest stars, and only once they are a
  // few px across (matches the PoC spikeT gate). Drives a larger quad so the
  // cross has room to extend, and is rendered in the fragment shader. The size
  // gate is in DPR-scaled px so spikes appear at the same apparent size.
  float spikeT = clamp((aBright - 0.6) / 0.3, 0.0, 1.0)
               * clamp((baseR - 1.6 * dpr) / (2.0 * dpr), 0.0, 1.0);

  // Per-star twinkle (deterministic from instance id is unavailable here, so
  // derive a phase from position; cheap and stable).
  float phase = (aWorld.x * 0.7 + aWorld.y * 1.3);
  float twinkle = 0.88 + 0.12 * sin(uTime * 1.6 + phase);

  // The drawn quad is the glow radius (~4.2x the core) so additive falloff fits.
  // When spikes are active the quad grows so the cross arms fit inside it.
  float glowR = baseR * 4.2;
  float spikeLen = glowR * (2.0 + aGiant * 1.6) * spikeT;
  float quadR = max(glowR, spikeLen);

  // Aggregates are soft density blobs that add clumping on top of the smooth
  // nebulosity glow — kept modest so they read as texture, not overlapping
  // circles (the smooth galaxy haze is the dominant zoomed-out structure).
  if (aAggregate > 0.5) {
    quadR = max(6.0, 0.026 * min(uViewportPx.x, uViewportPx.y));
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
    // Soft density blob with a very broad, coreless falloff so many overlap
    // into smooth galaxy haze (never a discrete circle). Tinted by the radial
    // blackbody gradient carried in vColor (warm core -> blue arms).
    float a = (1.0 - d);
    a = a * a * a * (0.05 + vBright * 0.30);
    outColor = vec4(vColor * a, a);
    return;
  }

  // Star: bright core + additive glow halo. Core occupies the inner ~24%.
  float core = smoothstep(0.26, 0.0, d);
  float halo = pow(max(0.0, 1.0 - d), 2.2);
  float coreA = core * min(1.0, 0.62 + vBright) * vTwinkle;
  float haloA = halo * (0.28 + 0.5 * vBright) * vTwinkle;

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

  // White-hot core blended toward the star's spectral colour outward. Brighter
  // (hotter) stars saturate their core toward white more strongly, while cool
  // dim dwarfs keep their warm spectral tint right into the core — a truer
  // blackbody read than washing every core to pure white.
  float whiteHot = core * (0.55 + 0.4 * vBright);
  vec3 rgb = mix(vColor, vec3(1.0), whiteHot);
  float a = clamp(coreA + haloA, 0.0, 1.0);
  // Spikes carry the spectral colour (refraction tints the cross), kept additive.
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

// 4-arm log-spiral arm strength — a GLSL mirror of the catalog density field
// (catalog-generator.ts armStrength / GalaxyConstants.java) so the diffuse
// glow lands exactly where the procedural stars are. Same WIND/ARMS/HALF_WIDTH.
// (Render-only: the spiral *phase* matches the catalog so the haze tracks the
// stars; the half-width is slightly tightened so arms read as crisp ribbons.)
float armStrength(vec2 world, float r) {
  const float WIND = 2.7;
  const float ARMS = 4.0;
  const float ARM_LOG_SCALE = 4.0;
  const float ARM_LOG_BIAS = 1.0;
  const float HALF_WIDTH = 0.30;
  float rr = max(r, 1.0) / max(uRmax, 1.0);
  float phase = WIND * log(rr * ARM_LOG_SCALE + ARM_LOG_BIAS);
  float theta = atan(world.y, world.x);
  float armSpacing = 6.28318530718 / ARMS;
  float delta = theta - phase;
  delta -= armSpacing * floor(delta / armSpacing + 0.5);
  return exp(-(delta * delta) / (2.0 * HALF_WIDTH * HALF_WIDTH));
}

// Signed angular distance to the nearest arm ridge (for the leading-edge dust).
float armDelta(vec2 world, float r) {
  const float WIND = 2.7;
  const float ARMS = 4.0;
  float rr = max(r, 1.0) / max(uRmax, 1.0);
  float phase = WIND * log(rr * 4.0 + 1.0);
  float theta = atan(world.y, world.x);
  float armSpacing = 6.28318530718 / ARMS;
  float delta = theta - phase;
  delta -= armSpacing * floor(delta / armSpacing + 0.5);
  return delta;
}

// Central stellar bar (the defining feature of a *barred* spiral). RENDER-ONLY:
// it brightens/tints the diffuse glow along an oriented elongated core — it does
// NOT move any catalog star. Returns a 0..1 strength inside a soft capsule
// aligned to BAR_ANGLE, fading near the bar ends so the spiral arms take over.
float barStrength(vec2 world, float r, out float along01) {
  const float BAR_ANGLE = 0.55;          // radians; bar position angle
  const float BAR_HALF_LEN = 0.30;       // bar half-length as a fraction of Rmax
  const float BAR_HALF_WID = 0.075;      // bar half-width as a fraction of Rmax
  float c = cos(-BAR_ANGLE), s = sin(-BAR_ANGLE);
  vec2 b = vec2(world.x * c - world.y * s, world.x * s + world.y * c);
  float L = BAR_HALF_LEN * max(uRmax, 1.0);
  float Wd = BAR_HALF_WID * max(uRmax, 1.0);
  float u = b.x / L;                      // -1..1 along the bar
  float v = b.y / Wd;                      // -1..1 across the bar
  along01 = clamp(abs(u), 0.0, 1.0);
  // Boxy (peanut) profile along, gaussian across; tapered ends.
  float across = exp(-v * v * 1.3);
  float lenFall = pow(clamp(1.0 - abs(u) * abs(u), 0.0, 1.0), 0.85);
  return across * lenFall;
}

// Always-present background star field — the unresolvable far galaxy. Drawn in
// screen space (constant on-screen density at ANY zoom) and parallax-panned by
// the camera, so deep zoom into the void between catalog stars is never empty
// black, and it adds a faint foreground-star dusting at galaxy scale. Kept very
// faint so it never competes with the bright galaxy or the resolved stars.
vec3 backgroundStars(vec2 fragPx) {
  vec3 acc = vec3(0.0);
  for (int L = 0; L < 3; L++) {
    float cellPx = 30.0 + float(L) * 22.0;        // coarser, dimmer far layers
    float parallax = 0.05 + float(L) * 0.04;      // deeper layers drift slower
    vec2 uv = fragPx / cellPx + uCamWorld * parallax;
    vec2 cell = floor(uv);
    vec2 f = fract(uv);
    vec2 rnd = vec2(hash(cell), hash(cell + 19.7));
    float d = length(f - rnd);
    float pt = smoothstep(0.10, 0.0, d);
    float b = pow(hash(cell + 4.3), 7.0);         // a few bright, many faint
    float tw = 0.75 + 0.25 * sin(uTime * 1.7 + hash(cell) * 40.0);
    acc += pt * b * tw / (1.0 + float(L) * 0.8);
  }
  return acc * vec3(0.72, 0.80, 1.0); // cool blue-white
}

void main() {
  // Reconstruct this pixel's world position from the same projection the stars
  // use, so the galaxy is locked to the field and pans/zooms with it.
  vec2 fragPx = vec2(vUv.x, 1.0 - vUv.y) * uViewportPx;
  vec2 world = (fragPx - uViewportPx * 0.5) / max(uScalePx, 1e-4) + uCamWorld;

  float r = length(world);
  float rr = r / max(uRmax, 1.0);

  // The diffuse galaxy haze is full strength at galaxy/region scale and
  // dissolves into resolved stars as you zoom into systems. Drive it off the
  // zoom factor (1 = whole galaxy on screen), reconstructed robustly from the
  // viewport + galaxy radius so it is screen-size independent and mirrors the
  // interstellar-object gate (objects fade in 3..8 as the haze fades out).
  float fitScalePx = min(uViewportPx.x, uViewportPx.y) / (uRmax * 2.4);
  float zoomFactor = uScalePx / max(fitScalePx, 1e-6);
  // Full at galaxy scale, handed off to resolved stars by ~6x zoom so zooming
  // in reveals the star field rather than magnifying a smooth bloom blob.
  float fade = clamp(1.0 - (zoomFactor - 1.5) / 4.5, 0.0, 1.0);
  // A faint disc haze (unresolved-star glow + interstellar medium) lingers at
  // region/system zoom so the disk never reads as empty black, then fades out
  // on a deep single-system dive so the close-up stays clean.
  float floorHaze = 0.14 * clamp(1.0 - (zoomFactor - 6.0) / 60.0, 0.0, 1.0);
  float vis = max(fade, floorHaze);

  // Exponential stellar disc (catalog DISC_SCALE_LENGTH = 420) + faint halo
  // extending to ~2x the visible radius (no hard rim — fades to black).
  float disc = exp(-r / 420.0);
  float halo = exp(-rr * rr * 1.1);

  // fbm cloud break-up so arms are knotty, not smooth ribbons.
  vec2 nUv = world / max(uRmax, 1.0) * 4.0;
  float cloud = fbm(nUv + vec2(uTime * 0.006, -uTime * 0.004));
  float cloudHi = fbm(nUv * 2.7 + 7.0);

  // 4-arm log spiral aligned to the star field, modulated by the clouds.
  float arm = armStrength(world, r);
  float arms = arm * (0.45 + 0.85 * cloud);

  // Central stellar bar (barred-spiral feature; render-only brightening).
  float barAlong;
  float bar = barStrength(world, r, barAlong);
  // The bar fades in just outside the nucleus and the arms emerge from its ends.
  float barEnv = exp(-rr * rr * 9.0);      // confine the bar to the inner galaxy
  bar *= barEnv;

  // de Vaucouleurs-style bulge: a bright, *concentrated* nucleus plus a modest
  // surrounding glow. Slightly softened/dimmed so the nucleus does not clip flat
  // to white after bloom (telescope-photo read: bright, but not a blown disc).
  float bCore = exp(-pow(r / 66.0, 1.5));
  float bHalo = exp(-(r * r) / (2.0 * 160.0 * 160.0));

  // Radial colour gradient (blackbody ramp): warm yellow-orange old-star core →
  // near-white inner disc → blue young-star arms/outskirts.
  vec3 cCore = vec3(1.00, 0.82, 0.54);
  vec3 cInner = vec3(0.98, 0.94, 0.92);
  vec3 cArm = vec3(0.55, 0.71, 1.00);
  vec3 cBar = vec3(1.00, 0.86, 0.62);      // warm old-star bar
  vec3 discCol = mix(cInner, cArm, smoothstep(0.12, 0.62, rr));

  // HII star-forming knots: pink Hα emission strung along the arm ridges, and a
  // brighter cluster of them at the bar ends where star formation peaks. The
  // ONLY saturated accent — bloom desaturates the hottest cores toward white.
  float hiiArm = pow(arm, 2.2) * smoothstep(0.62, 0.95, cloudHi) * disc;
  float hiiBarEnds = pow(bar, 1.6) * smoothstep(0.55, 0.95, barAlong)
                   * smoothstep(0.6, 0.95, cloudHi);
  float hii = hiiArm + hiiBarEnds * 1.4;
  vec3 cHII = vec3(1.0, 0.42, 0.66);

  // Dust lanes: dark filaments tracing the leading inner edge of each arm
  // (offset to one side of the ridge) PLUS a lane running along the bar — the
  // signature dark dust lane silhouetting a barred spiral's core. Darkens glow.
  float dd = armDelta(world, r) + 0.20;
  float dustBand = exp(-(dd * dd) / (2.0 * 0.085 * 0.085));
  float armDust = dustBand * disc * smoothstep(0.22, 0.85, fbm(nUv * 3.1 + 2.0));
  // Bar dust: filaments threading the warm bar, broken up by the clouds.
  float barDust = bar * smoothstep(0.4, 0.95, fbm(nUv * 4.0 + 9.0)) * 0.6;
  float dust = clamp(armDust + barDust, 0.0, 1.0);

  vec3 col = vec3(0.0);
  col += discCol * disc * 0.065;           // faint inter-arm disc haze
  col += discCol * arms * disc * 0.78;     // luminous spiral arms
  col += cBar * bar * 0.55;                // warm central bar
  col += cHII * hii * 0.80;                // pink star-forming knots
  col += cCore * (bCore * 1.05 + bHalo * 0.30); // bright concentrated nucleus
  col += cArm * halo * 0.022;              // faint outer halo into black

  col *= (1.0 - dust * 0.82);              // carve the dust lanes
  col *= vis;

  // Always-on faint background star field (NOT faded by vis) so the void is
  // never empty black at any zoom, and the galaxy sits in a dusting of stars.
  col += backgroundStars(fragPx) * 0.5;

  outColor = vec4(max(col, 0.0), 1.0); // additive (ONE, ONE); rgb is contribution
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
  // Slightly lower exposure than before so the brightest cores roll into the
  // tone curve's shoulder (retaining warm colour) instead of clipping flat to
  // white. The bloom carries the "glow", the scene keeps the highlight detail.
  vec3 hdr = (scene + bloom * uBloomStrength) * 1.08;
  // ACES filmic tone map (Narkowicz approximation): rolls bright highlights
  // toward white like a real sensor overexposing, giving the photographic
  // white-hot core / HII-knot look, while keeping the void black.
  const float a = 2.51, b = 0.03, c = 2.43, d = 0.59, e = 0.14;
  vec3 mapped = clamp((hdr * (a * hdr + b)) / (hdr * (c * hdr + d) + e), 0.0, 1.0);
  // Gentle desaturation only at the very top of the range — a real sensor's
  // hottest cores read near-white, but mid-bright arms/knots keep their colour.
  float lum = dot(mapped, vec3(0.2126, 0.7152, 0.0722));
  float hot = smoothstep(0.85, 1.0, lum);
  mapped = mix(mapped, vec3(lum), hot * 0.5);
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

// --- Active overlay compositing (E8-06) -----------------------------------

// Ownership tint / fleet+battle / blockade marks, composited ON TOP of the star
// field in a dedicated instanced pass. Each mark is a quad centred on the joined
// star's screen position; the fragment shader draws a soft ownership glow plus a
// battle pulse and/or a blockade ring. Additive (premultiplied) so it reads as a
// luminous accent on the black void without occluding the underlying star.
const OVERLAY_VERT = `#version 300 es
precision highp float;
layout(location = 0) in vec2 aCorner;     // unit quad [-1,1]
layout(location = 1) in vec2 aCenter;     // screen px (joined star position)
layout(location = 2) in float aRadius;    // marker radius px
layout(location = 3) in vec3 aColor;      // ownership tint rgb 0..1
layout(location = 4) in float aFlags;     // bit0 battle, bit1 blockade, bit2 hasTint
layout(location = 5) in float aActivity;  // 0..2 activity level
uniform vec2 uViewportPx;
out vec2 vLocal;
out vec3 vColor;
out float vFlags;
out float vActivity;
void main() {
  vec2 px = aCenter + aCorner * aRadius;
  vec2 clip = (px / uViewportPx) * 2.0 - 1.0;
  clip.y = -clip.y;
  gl_Position = vec4(clip, 0.0, 1.0);
  vLocal = aCorner;
  vColor = aColor;
  vFlags = aFlags;
  vActivity = aActivity;
}
`;

const OVERLAY_FRAG = `#version 300 es
precision highp float;
in vec2 vLocal;
in vec3 vColor;
in float vFlags;
in float vActivity;
out vec4 outColor;
uniform float uTime;
void main() {
  float d = length(vLocal);              // 0 centre .. 1 quad edge
  if (d > 1.0) { discard; }
  bool battle = mod(floor(vFlags + 0.5), 2.0) >= 1.0;
  bool blockade = mod(floor(vFlags * 0.5 + 0.5), 2.0) >= 1.0;
  bool hasTint = mod(floor(vFlags * 0.25 + 0.5), 2.0) >= 1.0;

  vec3 acc = vec3(0.0);

  // Ownership tint: a soft halo ring around the star (does not wash the core),
  // intensity gently scaled by activity so busy systems read brighter.
  if (hasTint) {
    float ring = smoothstep(0.55, 0.30, d) - smoothstep(0.30, 0.0, d);
    float glow = (1.0 - d) * (1.0 - d) * 0.35;
    float amp = 0.45 + 0.18 * clamp(vActivity, 0.0, 2.0);
    acc += vColor * (ring * 0.9 + glow) * amp;
  }

  // Battle: red pulsing core PLUS an expanding shock ring so a contested system
  // visibly throbs and draws the eye (gamified "something is happening here").
  if (battle) {
    float pulse = 0.55 + 0.45 * sin(uTime * 6.0);
    float core = smoothstep(0.4, 0.0, d);
    acc += vec3(1.0, 0.25, 0.18) * core * pulse * 1.0;
    // Outward ping: a ring sweeps from the core to the edge on a ~1.4s cycle.
    float ping = fract(uTime * 0.7);
    float ringR = ping * 0.85;
    float ring = exp(-pow((d - ringR) * 9.0, 2.0)) * (1.0 - ping);
    acc += vec3(1.0, 0.30, 0.22) * ring * 0.9;
  }

  // Blockade: a pulsing amber ring near the quad edge (dashed-feel via angle).
  if (blockade) {
    float pulse = 0.65 + 0.35 * sin(uTime * 3.5);
    float ring = smoothstep(0.05, 0.0, abs(d - 0.78));
    // Angular gating gives a segmented "cordon" read rather than a solid ring.
    float ang = atan(vLocal.y, vLocal.x);
    float seg = 0.55 + 0.45 * sin(ang * 8.0 - uTime * 1.5);
    acc += vec3(1.0, 0.72, 0.25) * ring * pulse * (0.4 + 0.6 * seg);
  }

  float a = clamp(max(max(acc.r, acc.g), acc.b), 0.0, 1.0);
  // Premultiplied additive output (blend func ONE, ONE).
  outColor = vec4(acc, a);
}
`;

// --- Empire territory influence fields (E-immersive) ----------------------

// One soft additive gaussian per controlled system. Overlapping nodes of one
// faction sum into a coherent coloured region (a "border glow") that sits UNDER
// the star field. Drawn into the sceneFbo after the nebula so it blooms subtly.
const TERRITORY_VERT = `#version 300 es
precision highp float;
layout(location = 0) in vec2 aCorner;     // unit quad [-1,1]
layout(location = 1) in vec2 aCenter;     // screen px
layout(location = 2) in float aRadius;    // screen px
layout(location = 3) in vec3 aTint;       // ownership rgb 0..1
uniform vec2 uViewportPx;
out vec2 vLocal;
out vec3 vTint;
void main() {
  vec2 px = aCenter + aCorner * aRadius;
  vec2 clip = (px / uViewportPx) * 2.0 - 1.0;
  clip.y = -clip.y;
  gl_Position = vec4(clip, 0.0, 1.0);
  vLocal = aCorner;
  vTint = aTint;
}
`;

const TERRITORY_FRAG = `#version 300 es
precision highp float;
in vec2 vLocal;
in vec3 vTint;
out vec4 outColor;
void main() {
  float d = length(vLocal);          // 0 centre .. 1 quad edge
  if (d > 1.0) { discard; }
  // Broad, soft gaussian-ish falloff; gentle so overlapping nodes accumulate
  // into a faint regional aura that tints the galaxy without shattering it into
  // bright discs or washing out the stars drawn on top.
  float fill = pow(max(0.0, 1.0 - d), 2.0);
  // A faint mid-radius rim so a faction reads as territory with a soft *edge*
  // (a border falloff), not just a centre-weighted blob. The rim peaks where a
  // single node's field is mid-strength; where many nodes overlap the interior
  // the fills dominate, so the rim only shows at a region's outer boundary.
  float rim = exp(-pow((d - 0.62) * 4.2, 2.0));
  float a = fill * 0.085 + rim * 0.05;
  // Premultiplied additive output (blend func ONE, ONE).
  outColor = vec4(vTint * a, a);
}
`;

// --- Trade-route lanes (E-immersive) ---------------------------------------

// One oriented rectangle per route, stretched between its two screen-px
// endpoints. A bright travelling pulse runs along the lane so routes feel
// alive; contested lanes flicker. Additive on top of the star field.
const ROUTE_VERT = `#version 300 es
precision highp float;
layout(location = 0) in vec2 aCorner;     // unit quad [-1,1]: x along lane 0..1 (remapped), y across
layout(location = 1) in vec2 aA;          // screen px endpoint A
layout(location = 2) in vec2 aB;          // screen px endpoint B
layout(location = 3) in float aHalfW;     // half lane width px
layout(location = 4) in vec3 aColor;      // lane rgb 0..1
uniform vec2 uViewportPx;
out vec2 vLane;     // x: 0..1 along lane, y: -1..1 across
out vec3 vColor;
void main() {
  // Remap corner.x from [-1,1] to t in [0,1] along the lane.
  float t = aCorner.x * 0.5 + 0.5;
  vec2 dir = aB - aA;
  float len = max(length(dir), 1e-3);
  vec2 ndir = dir / len;
  vec2 perp = vec2(-ndir.y, ndir.x);
  vec2 px = mix(aA, aB, t) + perp * aCorner.y * aHalfW;
  vec2 clip = (px / uViewportPx) * 2.0 - 1.0;
  clip.y = -clip.y;
  gl_Position = vec4(clip, 0.0, 1.0);
  vLane = vec2(t, aCorner.y);
  vColor = aColor;
}
`;

const ROUTE_FRAG = `#version 300 es
precision highp float;
in vec2 vLane;
in vec3 vColor;
out vec4 outColor;
uniform float uTime;
uniform float uLaneAlpha;   // zoom-gated base lane alpha
void main() {
  // Across-lane falloff: bright at centre line, fades to the edges.
  float across = 1.0 - abs(vLane.y);
  float lane = smoothstep(0.0, 1.0, across);
  float base = lane * uLaneAlpha;

  // Travelling pulse: a moving bright comet along the lane.
  float head = fract(uTime * 0.25);
  float dist = abs(vLane.x - head);
  dist = min(dist, 1.0 - dist);          // wrap-around distance
  float pulse = exp(-dist * dist * 90.0) * lane;

  float a = clamp(base + pulse * 0.9, 0.0, 1.0);
  vec3 rgb = vColor * (base + pulse);
  // Premultiplied additive output (blend func ONE, ONE).
  outColor = vec4(rgb, a);
}
`;

// --- Fog-of-war veil (E-immersive) -----------------------------------------

// A fullscreen veil over unexplored space. The union of reveal disks (passed as
// screen-px uniforms) is punched clear; the veil ramps in over a soft falloff
// band so faint scenery still shimmers through unexplored space (not opaque).
// Standard alpha blend (SRC_ALPHA, ONE_MINUS_SRC_ALPHA).
const FOG_FRAG = `#version 300 es
precision highp float;
in vec2 vUv;
out vec4 outColor;
uniform vec2 uViewportPx;
uniform int uRevealCount;
uniform vec3 uReveals[${FOG_MAX_REVEALS}];   // xy = screen px centre, z = radius px
uniform float uVeilStrength;                 // 0 at galaxy scale -> 1 at region/system
void main() {
  vec2 fragPx = vec2(vUv.x, 1.0 - vUv.y) * uViewportPx;
  // Find how "revealed" this pixel is: 1 well inside any disk, 0 far outside.
  float reveal = 0.0;
  for (int i = 0; i < ${FOG_MAX_REVEALS}; i++) {
    if (i >= uRevealCount) { break; }
    vec3 rv = uReveals[i];
    float r = max(rv.z, 1.0);
    float d = distance(fragPx, rv.xy);
    // Soft edge: clear within (r - band), ramp to veiled by r.
    float band = r * 0.35;
    float inside = 1.0 - smoothstep(r - band, r, d);
    reveal = max(reveal, inside);
  }
  // The galaxy's luminous structure is distant scenery, not secret intel — the
  // veil fades out at galaxy scale (uVeilStrength->0) so the whole disc is
  // visible when zoomed out, and only hides unexplored DETAIL once you zoom into
  // a region/system. (Fog-correctness is enforced by the overlay store, which is
  // already server-side fog-filtered; the veil is purely a visual dimming.)
  float MAX_VEIL = 0.82;
  float a = (1.0 - reveal) * MAX_VEIL * uVeilStrength;
  vec3 veil = vec3(0.0, 0.01, 0.03);
  outColor = vec4(veil, a);
}
`;

// --- Interstellar objects (deep-space landmarks) ---------------------------

// One quad per object; the fragment shader procedurally draws each kind's look
// in local quad space [-1,1]. Drawn into the sceneFbo (so it blooms + tone-maps
// with everything else) with standard alpha blending so the blackhole/wormhole
// can punch a dark core while bright rings/cores still exceed the bloom knee.
const OBJECT_VERT = `#version 300 es
precision highp float;
layout(location = 0) in vec2 aCorner;     // unit quad [-1,1]
layout(location = 1) in vec2 aCenter;     // screen px
layout(location = 2) in float aRadius;    // drawn radius px (quad half-size)
layout(location = 3) in float aKind;      // OBJECT_KIND_CODE
layout(location = 4) in float aPhase;     // stable phase + accumulated time
layout(location = 5) in float aSelected;  // 1 = draw focus ring
layout(location = 6) in float aFade;      // LOD cross-fade 0..1
layout(location = 7) in vec3 aTint;       // primary rgb 0..1
layout(location = 8) in vec3 aTint2;      // secondary rgb 0..1
uniform vec2 uViewportPx;
out vec2 vLocal;       // [-1,1] across the object quad
out float vKind;
out float vPhase;
out float vSelected;
out float vFade;
out vec3 vTint;
out vec3 vTint2;
void main() {
  // Quad is oversized vs the object radius so beams/spikes/focus ring fit.
  vec2 px = aCenter + aCorner * aRadius * 1.6;
  vec2 clip = (px / uViewportPx) * 2.0 - 1.0;
  clip.y = -clip.y;
  gl_Position = vec4(clip, 0.0, 1.0);
  vLocal = aCorner * 1.6;   // so |vLocal|==1 sits at the object radius
  vKind = aKind;
  vPhase = aPhase;
  vSelected = aSelected;
  vFade = aFade;
  vTint = aTint;
  vTint2 = aTint2;
}
`;

const OBJECT_FRAG = `#version 300 es
precision highp float;
in vec2 vLocal;
in float vKind;
in float vPhase;
in float vSelected;
in float vFade;
in vec3 vTint;
in vec3 vTint2;
out vec4 outColor;

const float PI = 3.14159265;

float hash11(float p) {
  p = fract(p * 0.1031);
  p *= p + 33.33;
  p *= p + p;
  return fract(p);
}
float hash21(vec2 p) {
  vec3 p3 = fract(vec3(p.xyx) * 0.1031);
  p3 += dot(p3, p3.yzx + 33.33);
  return fract((p3.x + p3.y) * p3.z);
}
float vnoise(vec2 p) {
  vec2 i = floor(p); vec2 f = fract(p);
  vec2 u = f * f * (3.0 - 2.0 * f);
  float a = hash21(i), b = hash21(i + vec2(1.0, 0.0));
  float c = hash21(i + vec2(0.0, 1.0)), d = hash21(i + vec2(1.0, 1.0));
  return mix(mix(a, b, u.x), mix(c, d, u.x), u.y);
}
float fbm(vec2 p) {
  float v = 0.0, a = 0.5;
  for (int i = 0; i < 4; i++) { v += a * vnoise(p); p = p * 2.03 + 7.1; a *= 0.5; }
  return v;
}

void main() {
  vec2 q = vLocal;            // -1.6..1.6; object edge at |q|==1
  float r = length(q);
  float ph = vPhase;
  int kind = int(vKind + 0.5);
  vec3 col = vec3(0.0);
  float alpha = 0.0;

  if (kind == 0) {
    // NEBULA: layered fbm clouds, two tints, embedded young stars. Additive feel
    // via high colour + moderate alpha. Soft, billowing, irregular.
    float cl = 0.0;
    for (int i = 0; i < 3; i++) {
      vec2 off = vec2(cos(ph + float(i) * 2.1), sin(ph * 1.3 + float(i)));
      float n = fbm(q * (1.6 + float(i) * 0.7) + off + vec2(ph * 0.05, 0.0));
      cl += n * (0.6 - float(i) * 0.12);
    }
    float fall = smoothstep(1.0, 0.05, r);
    cl = pow(clamp(cl * fall, 0.0, 1.0), 1.4);
    float mixc = fbm(q * 2.2 + 3.0);
    col = mix(vTint, vTint2, mixc) * cl * 1.6;
    // Embedded young stars: a few sharp bright points.
    float stars = 0.0;
    for (int i = 0; i < 5; i++) {
      vec2 sp = (vec2(hash11(ph + float(i) * 7.0), hash11(ph + float(i) * 13.0)) - 0.5) * 1.3;
      float d = length(q - sp);
      stars += exp(-d * d * 600.0) * (0.6 + 0.4 * sin(ph * 3.0 + float(i)));
    }
    col += vec3(1.0) * stars;
    alpha = clamp(cl * 0.8 + stars, 0.0, 1.0);
  } else if (kind == 1) {
    // BLACKHOLE: dark core, bright accretion disk ring, sharp photon ring.
    float core = 0.34;
    float disk = smoothstep(0.0, 0.32, r) * smoothstep(1.0, 0.34, r);
    float doppler = 0.6 + 0.4 * cos(atan(q.y, q.x) - ph);
    col = mix(vTint, vTint2, smoothstep(core, 1.0, r)) * disk * 1.7 * doppler;
    float photon = exp(-pow((r - core * 1.12) * 26.0, 2.0));
    col += mix(vTint2, vec3(1.0), 0.4) * photon * 1.4;
    float diskA = clamp(disk * doppler + photon, 0.0, 1.0);
    // Dark core punched on top (low rgb, high alpha) — alpha blending darkens.
    float dark = smoothstep(core, core * 0.7, r);
    col = mix(col, vec3(0.0, 0.004, 0.012), dark);
    alpha = max(diskA, dark);
  } else if (kind == 2) {
    // PULSAR: brilliant core + twin sweeping beams + faint synchrotron halo.
    float ang = ph;
    float pulse = 0.45 + 0.55 * abs(sin(ph * 2.7));
    vec2 dir = vec2(cos(ang), sin(ang));
    float along = dot(q, dir);
    float across = abs(dot(q, vec2(-dir.y, dir.x)));
    float beam = exp(-across * across * 60.0) * smoothstep(1.6, 0.0, abs(along)) * pulse;
    float halo = exp(-r * r * 4.0) * 0.5;
    float core = exp(-r * r * 220.0);
    col = mix(vTint, vec3(1.0), core) * (beam + halo + core * 2.0);
    alpha = clamp(beam + halo + core, 0.0, 1.0);
  } else if (kind == 3) {
    // WORMHOLE: luminous ring + swirling interior + dark centre aperture.
    float ang = atan(q.y, q.x);
    float swirl = 0.5 + 0.5 * sin(ang * 4.0 + r * 14.0 - ph * 2.0);
    float interior = smoothstep(0.82, 0.1, r) * swirl;
    col = mix(vTint, vTint2, swirl) * interior * 1.3;
    float ring = exp(-pow((r - 0.82) * 12.0, 2.0));
    col += vTint2 * ring * 1.6;
    float dark = smoothstep(0.45, 0.1, r);
    col = mix(col, vec3(0.004, 0.006, 0.02), dark * 0.92);
    alpha = clamp(interior + ring + dark * 0.92, 0.0, 1.0);
  } else if (kind == 4) {
    // ASTEROID FIELD: scattered dim rocky dots in a loose annulus. No glow.
    float rock = 0.0;
    vec3 rc = vTint;
    for (int i = 0; i < 80; i++) {
      float fi = float(i);
      float a = hash11(ph + fi * 1.7) * 6.2831853;
      float rr = 0.35 + 0.6 * sqrt(hash11(ph + fi * 2.3 + 5.0));
      vec2 sp = vec2(cos(a), sin(a)) * rr;
      float d = length(q - sp);
      float dot_ = exp(-d * d * 2400.0);
      rock += dot_;
      rc = mix(rc, ((int(i) & 1) == 0) ? vTint : vTint2, dot_);
    }
    col = rc * clamp(rock, 0.0, 1.0);
    alpha = clamp(rock, 0.0, 1.0) * 0.9;
  } else if (kind == 5) {
    // ROGUE PLANET: small dim lit sphere, no star glow.
    float pr = 0.7;
    if (r > pr) { discard; }
    float z = sqrt(max(0.0, pr * pr - dot(q, q)));
    vec3 n = normalize(vec3(q, z));
    vec3 lightDir = normalize(vec3(cos(ph * 0.1), sin(ph * 0.1), 0.5));
    float lit = 0.1 + 0.9 * smoothstep(0.0, 0.4, dot(n, lightDir));
    col = vTint * lit;
    alpha = 1.0;
  } else {
    // SUPERNOVA REMNANT: bright irregular shock shell + wispy interior.
    float ang = atan(q.y, q.x);
    float fil = fbm(vec2(ang * 3.0, r * 5.0) + ph * 0.2);
    float shell = exp(-pow((r - (0.78 + 0.12 * fil)) * 9.0, 2.0));
    float shimmer = 0.8 + 0.2 * sin(ph + ang * 6.0);
    float interior = smoothstep(0.92, 0.1, r) * fbm(q * 3.0 + ph) * 0.4;
    col = vTint * shell * 1.6 * shimmer + vTint2 * interior * 1.4;
    alpha = clamp(shell * shimmer + interior, 0.0, 1.0);
  }

  // Selection focus ring just outside the object radius.
  if (vSelected > 0.5) {
    float ring = exp(-pow((r - 1.25) * 22.0, 2.0));
    col += vec3(0.6, 0.78, 1.0) * ring * 1.2;
    alpha = max(alpha, ring);
  }

  alpha *= vFade;
  if (alpha <= 0.003) { discard; }
  outColor = vec4(col * vFade, alpha);
}
`;

// --- Named sector grid (navigational overlay) ------------------------------

// One quad per in-view cell, drawn to the DEFAULT framebuffer AFTER the fog veil
// (so the grid stays readable everywhere). Thin boundary + subtle tinted fill;
// hover/selection escalate the look; selected cells get corner ticks. Standard
// alpha blending. Empty/disabled -> the pass is skipped entirely.
const SECTOR_VERT = `#version 300 es
precision highp float;
layout(location = 0) in vec2 aCorner;     // unit quad [-1,1]
layout(location = 1) in vec2 aCenter;     // screen px centre
layout(location = 2) in float aHalf;      // half-extent px
layout(location = 3) in vec3 aTint;       // owner rgb 0..1 (or neutral)
layout(location = 4) in float aHasTint;   // 1 = tinted owner
layout(location = 5) in float aLineAlpha;
layout(location = 6) in float aFillAlpha;
layout(location = 7) in float aSelected;
layout(location = 8) in float aHovered;
uniform vec2 uViewportPx;
out vec2 vLocal;       // [-1,1] across the cell
out float vHalfPx;     // half-extent in px (for px-accurate borders)
out vec3 vTint;
out float vLineAlpha;
out float vFillAlpha;
out float vSelected;
out float vHovered;
void main() {
  vec2 px = aCenter + aCorner * aHalf;
  vec2 clip = (px / uViewportPx) * 2.0 - 1.0;
  clip.y = -clip.y;
  gl_Position = vec4(clip, 0.0, 1.0);
  vLocal = aCorner;
  vHalfPx = aHalf;
  vTint = aTint;
  vLineAlpha = aLineAlpha;
  vFillAlpha = aFillAlpha;
  vSelected = aSelected;
  vHovered = aHovered;
}
`;

const SECTOR_FRAG = `#version 300 es
precision highp float;
in vec2 vLocal;
in float vHalfPx;
in vec3 vTint;
in float vLineAlpha;
in float vFillAlpha;
in float vSelected;
in float vHovered;
out vec4 outColor;
void main() {
  // Distance (in px) to the nearest cell edge.
  vec2 edgePx = (1.0 - abs(vLocal)) * vHalfPx;
  float edge = min(edgePx.x, edgePx.y);
  float lineW = vSelected > 0.5 ? 2.0 : 1.0;
  float line = 1.0 - smoothstep(lineW, lineW + 1.0, edge);

  // Subtle fill across the whole cell.
  float a = vFillAlpha;
  vec3 col = vTint * vFillAlpha;

  // Boundary line.
  col = mix(col, vTint, line * vLineAlpha);
  a = max(a, line * vLineAlpha);

  // Selected: corner ticks (short bright marks near each corner).
  if (vSelected > 0.5) {
    float tickLen = min(vHalfPx * 0.36, 18.0);
    bool nearCornerX = edgePx.x < tickLen;
    bool nearCornerY = edgePx.y < tickLen;
    float onX = (1.0 - smoothstep(2.0, 3.0, edgePx.y)) * (nearCornerX ? 1.0 : 0.0);
    float onY = (1.0 - smoothstep(2.0, 3.0, edgePx.x)) * (nearCornerY ? 1.0 : 0.0);
    float tick = max(onX, onY);
    col = mix(col, vTint, tick);
    a = max(a, tick * 0.9);
  }

  if (a <= 0.003) { discard; }
  outColor = vec4(col, a);
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
  private uDpr: WebGLUniformLocation | null = null;

  // --- E8-02 post-processing programs (null when FBO support is missing) ---
  private postOk = false;
  private nebulaProg: WebGLProgram | null = null;
  private brightProg: WebGLProgram | null = null;
  private blurProg: WebGLProgram | null = null;
  private compositeProg: WebGLProgram | null = null;
  private planetProg: WebGLProgram | null = null;
  private overlayProg: WebGLProgram | null = null;
  private territoryProg: WebGLProgram | null = null;
  private routeProg: WebGLProgram | null = null;
  private fogProg: WebGLProgram | null = null;
  private objectProg: WebGLProgram | null = null;
  private sectorProg: WebGLProgram | null = null;

  private postVao: WebGLVertexArrayObject | null = null;
  private planetVao: WebGLVertexArrayObject | null = null;
  private planetQuadBuf: WebGLBuffer | null = null;
  private planetInstanceBuf: WebGLBuffer | null = null;
  private overlayVao: WebGLVertexArrayObject | null = null;
  private overlayQuadBuf: WebGLBuffer | null = null;
  private overlayInstanceBuf: WebGLBuffer | null = null;
  private territoryVao: WebGLVertexArrayObject | null = null;
  private territoryQuadBuf: WebGLBuffer | null = null;
  private territoryInstanceBuf: WebGLBuffer | null = null;
  private routeVao: WebGLVertexArrayObject | null = null;
  private routeQuadBuf: WebGLBuffer | null = null;
  private routeInstanceBuf: WebGLBuffer | null = null;
  private objectVao: WebGLVertexArrayObject | null = null;
  private objectQuadBuf: WebGLBuffer | null = null;
  private objectInstanceBuf: WebGLBuffer | null = null;
  private sectorVao: WebGLVertexArrayObject | null = null;
  private sectorQuadBuf: WebGLBuffer | null = null;
  private sectorInstanceBuf: WebGLBuffer | null = null;

  private sceneFbo: Fbo | null = null;
  private bloomA: Fbo | null = null;
  private bloomB: Fbo | null = null;

  /** Reused instance staging buffer; grown as the visible count rises. */
  private data = new Float32Array(0);
  private capacity = 0;
  /** Reused planet staging buffer. */
  private planetData = new Float32Array(0);
  private planetCapacity = 0;
  /** Reused overlay-mark staging buffer. */
  private overlayData = new Float32Array(0);
  private overlayCapacity = 0;
  /** Reused territory-node staging buffer. */
  private territoryData = new Float32Array(0);
  private territoryCapacity = 0;
  /** Reused route staging buffer. */
  private routeData = new Float32Array(0);
  private routeCapacity = 0;
  /** Reused interstellar-object staging buffer. */
  private objectData = new Float32Array(0);
  private objectCapacity = 0;
  /** Reused sector-cell staging buffer. */
  private sectorData = new Float32Array(0);
  private sectorCapacity = 0;
  /** Reused fog reveal-disk uniform staging (FOG_MAX_REVEALS * vec3). */
  private fogReveals = new Float32Array(FOG_MAX_REVEALS * 3);

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
    this.uDpr = gl.getUniformLocation(program, 'uDpr');

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
      this.overlayProg = linkProgram(gl, OVERLAY_VERT, OVERLAY_FRAG);

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

      // Overlay-mark instancing VAO: shared unit quad + per-mark buffer.
      this.overlayVao = gl.createVertexArray();
      gl.bindVertexArray(this.overlayVao);
      this.overlayQuadBuf = gl.createBuffer();
      gl.bindBuffer(gl.ARRAY_BUFFER, this.overlayQuadBuf);
      gl.bufferData(gl.ARRAY_BUFFER, quad, gl.STATIC_DRAW);
      gl.enableVertexAttribArray(0);
      gl.vertexAttribPointer(0, 2, gl.FLOAT, false, 0, 0);
      this.overlayInstanceBuf = gl.createBuffer();
      gl.bindBuffer(gl.ARRAY_BUFFER, this.overlayInstanceBuf);
      const ostride = FLOATS_PER_OVERLAY * 4;
      // loc1 center(2), loc2 radius(1), loc3 color(3), loc4 flags(1), loc5 activity(1)
      const odesc: readonly [number, number, number][] = [
        [1, 2, 0],
        [2, 1, 2],
        [3, 3, 3],
        [4, 1, 6],
        [5, 1, 7],
      ];
      for (const [loc, size, off] of odesc) {
        gl.enableVertexAttribArray(loc);
        gl.vertexAttribPointer(loc, size, gl.FLOAT, false, ostride, off * 4);
        gl.vertexAttribDivisor(loc, 1);
      }
      gl.bindVertexArray(null);

      // Territory influence-field programs + VAO (instanced soft gaussians).
      this.territoryProg = linkProgram(gl, TERRITORY_VERT, TERRITORY_FRAG);
      this.territoryVao = gl.createVertexArray();
      gl.bindVertexArray(this.territoryVao);
      this.territoryQuadBuf = gl.createBuffer();
      gl.bindBuffer(gl.ARRAY_BUFFER, this.territoryQuadBuf);
      gl.bufferData(gl.ARRAY_BUFFER, quad, gl.STATIC_DRAW);
      gl.enableVertexAttribArray(0);
      gl.vertexAttribPointer(0, 2, gl.FLOAT, false, 0, 0);
      this.territoryInstanceBuf = gl.createBuffer();
      gl.bindBuffer(gl.ARRAY_BUFFER, this.territoryInstanceBuf);
      const tstride = FLOATS_PER_TERRITORY * 4;
      // loc1 center(2), loc2 radius(1), loc3 tint(3)
      const tdesc: readonly [number, number, number][] = [
        [1, 2, 0],
        [2, 1, 2],
        [3, 3, 3],
      ];
      for (const [loc, size, off] of tdesc) {
        gl.enableVertexAttribArray(loc);
        gl.vertexAttribPointer(loc, size, gl.FLOAT, false, tstride, off * 4);
        gl.vertexAttribDivisor(loc, 1);
      }
      gl.bindVertexArray(null);

      // Route-lane programs + VAO (instanced oriented rectangles).
      this.routeProg = linkProgram(gl, ROUTE_VERT, ROUTE_FRAG);
      this.routeVao = gl.createVertexArray();
      gl.bindVertexArray(this.routeVao);
      this.routeQuadBuf = gl.createBuffer();
      gl.bindBuffer(gl.ARRAY_BUFFER, this.routeQuadBuf);
      gl.bufferData(gl.ARRAY_BUFFER, quad, gl.STATIC_DRAW);
      gl.enableVertexAttribArray(0);
      gl.vertexAttribPointer(0, 2, gl.FLOAT, false, 0, 0);
      this.routeInstanceBuf = gl.createBuffer();
      gl.bindBuffer(gl.ARRAY_BUFFER, this.routeInstanceBuf);
      const rstride = FLOATS_PER_ROUTE * 4;
      // loc1 A(2), loc2 B(2), loc3 halfW(1), loc4 color(3)
      const rdesc: readonly [number, number, number][] = [
        [1, 2, 0],
        [2, 2, 2],
        [3, 1, 4],
        [4, 3, 5],
      ];
      for (const [loc, size, off] of rdesc) {
        gl.enableVertexAttribArray(loc);
        gl.vertexAttribPointer(loc, size, gl.FLOAT, false, rstride, off * 4);
        gl.vertexAttribDivisor(loc, 1);
      }
      gl.bindVertexArray(null);

      // Interstellar-object programs + VAO (instanced procedural quads).
      this.objectProg = linkProgram(gl, OBJECT_VERT, OBJECT_FRAG);
      this.objectVao = gl.createVertexArray();
      gl.bindVertexArray(this.objectVao);
      this.objectQuadBuf = gl.createBuffer();
      gl.bindBuffer(gl.ARRAY_BUFFER, this.objectQuadBuf);
      gl.bufferData(gl.ARRAY_BUFFER, quad, gl.STATIC_DRAW);
      gl.enableVertexAttribArray(0);
      gl.vertexAttribPointer(0, 2, gl.FLOAT, false, 0, 0);
      this.objectInstanceBuf = gl.createBuffer();
      gl.bindBuffer(gl.ARRAY_BUFFER, this.objectInstanceBuf);
      const obstride = FLOATS_PER_OBJECT * 4;
      // loc1 center(2), loc2 radius(1), loc3 kind(1), loc4 phase(1),
      // loc5 selected(1), loc6 fade(1), loc7 tint(3), loc8 tint2(3)
      const obdesc: readonly [number, number, number][] = [
        [1, 2, 0],
        [2, 1, 2],
        [3, 1, 3],
        [4, 1, 4],
        [5, 1, 5],
        [6, 1, 6],
        [7, 3, 7],
        [8, 3, 10],
      ];
      for (const [loc, size, off] of obdesc) {
        gl.enableVertexAttribArray(loc);
        gl.vertexAttribPointer(loc, size, gl.FLOAT, false, obstride, off * 4);
        gl.vertexAttribDivisor(loc, 1);
      }
      gl.bindVertexArray(null);

      // Sector-grid programs + VAO (instanced cell quads).
      this.sectorProg = linkProgram(gl, SECTOR_VERT, SECTOR_FRAG);
      this.sectorVao = gl.createVertexArray();
      gl.bindVertexArray(this.sectorVao);
      this.sectorQuadBuf = gl.createBuffer();
      gl.bindBuffer(gl.ARRAY_BUFFER, this.sectorQuadBuf);
      gl.bufferData(gl.ARRAY_BUFFER, quad, gl.STATIC_DRAW);
      gl.enableVertexAttribArray(0);
      gl.vertexAttribPointer(0, 2, gl.FLOAT, false, 0, 0);
      this.sectorInstanceBuf = gl.createBuffer();
      gl.bindBuffer(gl.ARRAY_BUFFER, this.sectorInstanceBuf);
      const scstride = FLOATS_PER_SECTOR * 4;
      // loc1 center(2), loc2 half(1), loc3 tint(3), loc4 hasTint(1),
      // loc5 lineAlpha(1), loc6 fillAlpha(1), loc7 selected(1), loc8 hovered(1)
      const scdesc: readonly [number, number, number][] = [
        [1, 2, 0],
        [2, 1, 2],
        [3, 3, 3],
        [4, 1, 6],
        [5, 1, 7],
        [6, 1, 8],
        [7, 1, 9],
        [8, 1, 10],
      ];
      for (const [loc, size, off] of scdesc) {
        gl.enableVertexAttribArray(loc);
        gl.vertexAttribPointer(loc, size, gl.FLOAT, false, scstride, off * 4);
        gl.vertexAttribDivisor(loc, 1);
      }
      gl.bindVertexArray(null);

      // Fog veil program (fullscreen; reuses the gl_VertexID triangle / postVao).
      this.fogProg = linkProgram(gl, FULLSCREEN_VERT, FOG_FRAG);

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
      // Territory glows under the stars (additive instanced draw to screen).
      this.drawTerritories(gl, scene, view, scalePx);
      // Interstellar objects sit with/under the stars but BEFORE the fog veil.
      this.drawObjects(gl, scene, view, scalePx, timeSeconds);
      // Trade-route lanes UNDER the star field (matches the PoC + Canvas2D
      // z-order: lanes are scenery the stars sit on top of; the active-overlay
      // marks/battle pulses are what composite ON TOP).
      this.drawRoutes(gl, scene, view, scalePx, timeSeconds);
      if (count > 0) {
        this.drawStarPass(gl, scene, view, camX, camY, scalePx, timeSeconds);
      }
      // Overlay marks still composite in the no-bloom fallback path (E8-06).
      this.drawOverlay(gl, scene, view, scalePx, timeSeconds);
      // Fog veil is the last thing drawn (fullscreen alpha-blended draw).
      this.drawFog(gl, scene, view);
      // Sector grid is the final navigational overlay, over the fog veil.
      this.drawSectors(gl, scene, view, scalePx, timeSeconds);
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

    // Pass 1b: empire influence fields UNDER the stars (additive). Soft region
    // glows so factions read as coloured space, not just per-star dots.
    this.drawTerritories(gl, scene, view, scalePx);

    // Pass 1c: interstellar objects into the sceneFbo (NOT after composite) so
    // they bloom + tone-map with everything else. Drawn with/under the stars so
    // the star field stays crisp on top; veiled by the fog later (it draws to
    // the default framebuffer, after composite).
    this.drawObjects(gl, scene, view, scalePx, timeSeconds);

    // Pass 1d: trade-route lanes UNDER the stars (matches the PoC + Canvas2D
    // z-order). Drawn into the sceneFbo so the lane glow + pulse still bloom.
    this.drawRoutes(gl, scene, view, scalePx, timeSeconds);

    // Pass 2: the single instanced star/aggregate pass (+ in-shader spikes).
    if (count > 0) {
      this.drawStarPass(gl, scene, view, camX, camY, scalePx, timeSeconds);
    }

    // Pass 3: lit planets for active systems (zoom-gated; empty -> no work).
    this.drawPlanets(gl, scene, view, scalePx, timeSeconds);

    // Pass 3b: active-overlay marks (ownership tint / fleet+battle / blockade),
    // composited on top of the star field; additive so they bloom too (E8-06).
    this.drawOverlay(gl, scene, view, scalePx, timeSeconds);

    // --- Pass 4: bright-pass into the 1/4-res bloomA. -----------------------
    const a = this.bloomA as Fbo;
    const b = this.bloomB as Fbo;
    gl.disable(gl.BLEND);
    gl.bindVertexArray(this.postVao);

    gl.bindFramebuffer(gl.FRAMEBUFFER, a.fb);
    gl.viewport(0, 0, a.w, a.h);
    gl.useProgram(this.brightProg);
    this.bindTex(gl, this.brightProg as WebGLProgram, 'uScene', scene_.tex, 0);
    // Higher knee: bloom only from genuine highlights (bright cores, HII knots,
    // spikes), so mid-bright arms/nebulosity stay crisp and cores don't blow out.
    gl.uniform1f(loc(gl, this.brightProg, 'uThreshold'), 0.62);
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

    // --- Fog-of-war veil: drawn to the default framebuffer over the scene. ---
    this.drawFog(gl, scene, view);

    // --- Sector grid: the FINAL navigational overlay, over the fog veil, to
    // the default framebuffer (so the grid stays readable everywhere). --------
    this.drawSectors(gl, scene, view, scalePx, timeSeconds);

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
    // Floating origin (E8-07): subtract the camera-tracked world anchor from
    // BOTH the star positions and the camera world HERE, in float64 on the CPU,
    // before the values are narrowed to float32 in the instance buffer / camera
    // uniform. The shader then computes `(aWorld - uCamWorld) * scalePx` on small
    // origin-relative magnitudes, so deep-zoom precision holds. Subtracting the
    // SAME origin from both operands leaves the on-screen result unchanged, so a
    // re-base (origin jumping to track the camera) is visually transparent.
    const originX = view.originX ?? 0;
    const originY = view.originY ?? 0;
    this.ensureCapacity(count);
    const buf = this.data;
    let o = 0;
    for (const st of scene.stars) {
      const c = SPECTRAL_PALETTE[st.k] ?? SPECTRAL_PALETTE[4];
      // LOD cross-fade weight (default 1): scales perceptual brightness so an
      // outgoing/incoming tile level fades by alpha and never pops (E8-05).
      const fade = st.a === undefined ? 1 : st.a;
      buf[o] = st.x - originX;
      buf[o + 1] = st.y - originY;
      buf[o + 2] = c[0] / 255;
      buf[o + 3] = c[1] / 255;
      buf[o + 4] = c[2] / 255;
      buf[o + 5] = st.sz;
      buf[o + 6] = st.b * fade;
      buf[o + 7] = st.g;
      buf[o + 8] = 0;
      o += FLOATS_PER_INSTANCE;
    }
    const aggRmax = scene.rMax || 1000;
    for (const ag of scene.aggregates) {
      const fade = ag.a === undefined ? 1 : ag.a;
      // Radial blackbody tint so the zoomed-out glow carries the galaxy's
      // colour gradient (warm old-star core → blue young-star arms), matching
      // the nebulosity pass — not a flat blue-white wash.
      const rr = Math.hypot(ag.x, ag.y) / aggRmax;
      const tw = Math.min(1, Math.max(0, (rr - 0.1) / 0.5));
      buf[o] = ag.x - originX;
      buf[o + 1] = ag.y - originY;
      buf[o + 2] = 1.0 + (0.62 - 1.0) * tw;
      buf[o + 3] = 0.84 + (0.74 - 0.84) * tw;
      buf[o + 4] = 0.62 + (1.0 - 0.62) * tw;
      buf[o + 5] = 1.0;
      // Subtle: impostors add clumping over the smooth nebulosity, they are not
      // the structure themselves — keep them from reading as discrete circles.
      buf[o + 6] = ag.weight * fade * 0.5;
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
    // Camera world in the SAME origin-relative frame as the star buffer above.
    gl.uniform2f(this.uCamWorld, camX - originX, camY - originY);
    gl.uniform1f(this.uScalePx, scalePx);
    gl.uniform2f(this.uViewportPx, view.widthPx, view.heightPx);
    if (this.uTime) {
      gl.uniform1f(this.uTime, timeSeconds);
    }
    if (this.uDpr) {
      gl.uniform1f(this.uDpr, view.dpr || 1);
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

  /**
   * Active-overlay compositing pass (E8-06). Draws the join-by-system-id marks
   * (ownership tint / fleet+battle / blockade) on top of the star field as ONE
   * instanced draw call. Bounded by the visible active systems present in the
   * overlay (never the catalog). Empty overlay -> zero work. Fog-correctness is
   * upstream: the marks only exist for systems the server-fed overlay disclosed.
   */
  private drawOverlay(
    gl: WebGL2RenderingContext,
    scene: RenderScene,
    view: ViewTransform,
    scalePx: number,
    timeSeconds: number,
  ): void {
    const marks = scene.overlayMarks;
    if (!this.overlayProg || !marks || marks.length === 0) {
      return;
    }
    const W = view.widthPx;
    const H = view.heightPx;
    // Marker radius grows gently with zoom so it stays a readable accent without
    // ballooning; clamped at both ends (point-like discipline).
    const radius = Math.max(6 * view.dpr, Math.min(42 * view.dpr, scalePx * 4));

    this.ensureOverlayCapacity(marks.length);
    const buf = this.overlayData;
    let o = 0;
    let written = 0;
    for (const m of marks) {
      const p = view.w2s(m.x, m.y);
      if (p.x < -radius || p.x > W + radius || p.y < -radius || p.y > H + radius) {
        continue;
      }
      const tint = m.tint;
      const hasTint = tint !== null;
      const flags =
        (m.battle ? 1 : 0) + (m.blockaded ? 2 : 0) + (hasTint ? 4 : 0);
      if (flags === 0) {
        continue; // nothing to draw for this system
      }
      buf[o] = p.x;
      buf[o + 1] = p.y;
      buf[o + 2] = radius;
      buf[o + 3] = hasTint ? (tint as readonly number[])[0] : 0;
      buf[o + 4] = hasTint ? (tint as readonly number[])[1] : 0;
      buf[o + 5] = hasTint ? (tint as readonly number[])[2] : 0;
      buf[o + 6] = flags;
      buf[o + 7] = m.activity;
      o += FLOATS_PER_OVERLAY;
      written++;
    }
    if (written === 0) {
      return;
    }

    gl.blendFunc(gl.ONE, gl.ONE); // additive accent on the void
    gl.bindVertexArray(this.overlayVao);
    gl.bindBuffer(gl.ARRAY_BUFFER, this.overlayInstanceBuf);
    gl.bufferData(
      gl.ARRAY_BUFFER,
      buf.subarray(0, written * FLOATS_PER_OVERLAY),
      gl.DYNAMIC_DRAW,
    );
    gl.useProgram(this.overlayProg);
    gl.uniform2f(loc(gl, this.overlayProg, 'uViewportPx'), W, H);
    gl.uniform1f(loc(gl, this.overlayProg, 'uTime'), timeSeconds);
    gl.drawArraysInstanced(gl.TRIANGLE_STRIP, 0, 4, written);
    gl.bindVertexArray(null);
  }

  /**
   * Empire influence-field pass. One soft additive gaussian per controlled,
   * in-view system; overlapping nodes of one faction sum into a coherent
   * coloured region. Drawn UNDER the stars. Bounded by the visible owned
   * systems (never the catalog). Empty/undefined territories -> zero work.
   */
  private drawTerritories(
    gl: WebGL2RenderingContext,
    scene: RenderScene,
    view: ViewTransform,
    scalePx: number,
  ): void {
    const territories = scene.territories;
    if (!this.territoryProg || !territories || territories.length === 0) {
      return;
    }
    const W = view.widthPx;
    const H = view.heightPx;
    // Node glow radius scales gently with zoom; clamped so a region reads as a
    // soft halo at galaxy scale and does not balloon when zoomed in.
    const radius = Math.max(64 * view.dpr, Math.min(260 * view.dpr, scalePx * 72));

    // Count visible nodes to size the buffer (bounded by visible owned systems).
    let n = 0;
    for (const t of territories) {
      n += t.nodes.length;
    }
    if (n === 0) {
      return;
    }
    this.ensureTerritoryCapacity(n);
    const buf = this.territoryData;
    let o = 0;
    let written = 0;
    for (const t of territories) {
      const [tr, tg, tb] = t.tint;
      for (const node of t.nodes) {
        const p = view.w2s(node.x, node.y);
        if (
          p.x < -radius ||
          p.x > W + radius ||
          p.y < -radius ||
          p.y > H + radius
        ) {
          continue;
        }
        buf[o] = p.x;
        buf[o + 1] = p.y;
        buf[o + 2] = radius;
        buf[o + 3] = tr;
        buf[o + 4] = tg;
        buf[o + 5] = tb;
        o += FLOATS_PER_TERRITORY;
        written++;
      }
    }
    if (written === 0) {
      return;
    }

    gl.blendFunc(gl.ONE, gl.ONE); // additive region glow
    gl.bindVertexArray(this.territoryVao);
    gl.bindBuffer(gl.ARRAY_BUFFER, this.territoryInstanceBuf);
    gl.bufferData(
      gl.ARRAY_BUFFER,
      buf.subarray(0, written * FLOATS_PER_TERRITORY),
      gl.DYNAMIC_DRAW,
    );
    gl.useProgram(this.territoryProg);
    gl.uniform2f(loc(gl, this.territoryProg, 'uViewportPx'), W, H);
    gl.drawArraysInstanced(gl.TRIANGLE_STRIP, 0, 4, written);
    gl.bindVertexArray(null);
  }

  /**
   * Trade-route lane pass. One oriented rectangle per route between its two
   * screen-px endpoints, with an animated travelling pulse. Zoom-gated exactly
   * like the Canvas2D layer (fade in at mid zoom, cull lanes longer than a
   * zoom-dependent max). Bounded by the visible routes. Empty -> zero work.
   */
  private drawRoutes(
    gl: WebGL2RenderingContext,
    scene: RenderScene,
    view: ViewTransform,
    scalePx: number,
    timeSeconds: number,
  ): void {
    const routes = scene.routes;
    if (!this.routeProg || !routes || routes.length === 0) {
      return;
    }
    // Zoom gate (matches the Canvas2D drawRoutes ramp): fade in mid-zoom, fade
    // out again very zoomed in; cull lanes longer than a zoom-dependent max.
    const laneAlpha =
      clamp01((scalePx - 0.9) / 1.0) * (1 - clamp01((scalePx - 30) / 24)) * 0.18;
    if (laneAlpha <= 0.004) {
      return;
    }
    const maxLen = 60 + 400 * clamp01((scalePx - 1.0) / 6);
    const W = view.widthPx;
    const H = view.heightPx;
    const halfW = Math.max(0.75 * view.dpr, Math.min(1.5 * view.dpr, scalePx * 0.5));

    this.ensureRouteCapacity(routes.length);
    const buf = this.routeData;
    let o = 0;
    let written = 0;
    for (const rt of routes) {
      // rt.len is in WORLD units; convert the cull threshold accordingly.
      if (rt.len * scalePx > maxLen * view.dpr) {
        continue;
      }
      const A = view.w2s(rt.ax, rt.ay);
      const B = view.w2s(rt.bx, rt.by);
      // Cull lanes entirely outside the viewport (bounding-box reject).
      const minX = Math.min(A.x, B.x);
      const maxX = Math.max(A.x, B.x);
      const minY = Math.min(A.y, B.y);
      const maxY = Math.max(A.y, B.y);
      if (maxX < 0 || minX > W || maxY < 0 || minY > H) {
        continue;
      }
      const col = ROUTE_COLORS[rt.kind] ?? ROUTE_COLORS['trade'];
      // Contested lanes flicker (deterministic from time + endpoint position).
      let flicker = 1;
      if (rt.kind === 'contested') {
        flicker = 0.55 + 0.45 * Math.sin(timeSeconds * 9 + rt.ax * 0.5 + rt.ay);
      }
      buf[o] = A.x;
      buf[o + 1] = A.y;
      buf[o + 2] = B.x;
      buf[o + 3] = B.y;
      buf[o + 4] = halfW;
      buf[o + 5] = col[0] * flicker;
      buf[o + 6] = col[1] * flicker;
      buf[o + 7] = col[2] * flicker;
      o += FLOATS_PER_ROUTE;
      written++;
    }
    if (written === 0) {
      return;
    }

    gl.blendFunc(gl.ONE, gl.ONE); // additive lanes on the void
    gl.bindVertexArray(this.routeVao);
    gl.bindBuffer(gl.ARRAY_BUFFER, this.routeInstanceBuf);
    gl.bufferData(
      gl.ARRAY_BUFFER,
      buf.subarray(0, written * FLOATS_PER_ROUTE),
      gl.DYNAMIC_DRAW,
    );
    gl.useProgram(this.routeProg);
    gl.uniform2f(loc(gl, this.routeProg, 'uViewportPx'), W, H);
    gl.uniform1f(loc(gl, this.routeProg, 'uTime'), timeSeconds);
    gl.uniform1f(loc(gl, this.routeProg, 'uLaneAlpha'), laneAlpha);
    gl.drawArraysInstanced(gl.TRIANGLE_STRIP, 0, 4, written);
    gl.bindVertexArray(null);
  }

  /**
   * Interstellar-object pass (deep-space landmarks). One instanced draw of all
   * visible objects; the fragment shader procedurally renders each kind's look.
   * Drawn into whatever target is currently bound (the sceneFbo in the post
   * path, so objects bloom + tone-map with the scene; the default framebuffer in
   * the no-FBO fallback). Standard alpha blending so the blackhole/wormhole can
   * punch a dark core; additive is restored afterwards. Bounded by the visible
   * (already bbox-culled) objects; each is screen-culled here too. Empty -> 0.
   */
  private drawObjects(
    gl: WebGL2RenderingContext,
    scene: RenderScene,
    view: ViewTransform,
    scalePx: number,
    timeSeconds: number,
  ): void {
    const objects = scene.objects;
    if (!this.objectProg || !objects || objects.length === 0) {
      return;
    }
    const W = view.widthPx;
    const H = view.heightPx;

    this.ensureObjectCapacity(objects.length);
    const buf = this.objectData;
    let o = 0;
    let written = 0;
    for (const ob of objects) {
      const p = view.w2s(ob.x, ob.y);
      const R = ob.r * scalePx; // drawn radius in device px
      // Quad is oversized 1.6x in the shader, so cull with that margin.
      const cull = R * 1.6;
      if (R <= 0 || p.x < -cull || p.x > W + cull || p.y < -cull || p.y > H + cull) {
        continue;
      }
      const fade = ob.a === undefined ? 1 : ob.a;
      const tint = ob.tint;
      const tint2 = ob.tint2 ?? ob.tint;
      buf[o] = p.x;
      buf[o + 1] = p.y;
      buf[o + 2] = R;
      buf[o + 3] = OBJECT_KIND_CODE[ob.kind] ?? 0;
      buf[o + 4] = ob.phase + timeSeconds;
      buf[o + 5] = ob.selected ? 1 : 0;
      buf[o + 6] = fade;
      buf[o + 7] = tint[0];
      buf[o + 8] = tint[1];
      buf[o + 9] = tint[2];
      buf[o + 10] = tint2[0];
      buf[o + 11] = tint2[1];
      buf[o + 12] = tint2[2];
      o += FLOATS_PER_OBJECT;
      written++;
    }
    if (written === 0) {
      return;
    }

    // Standard alpha blending so dark cores read; restore additive afterwards.
    gl.blendFunc(gl.SRC_ALPHA, gl.ONE_MINUS_SRC_ALPHA);
    gl.bindVertexArray(this.objectVao);
    gl.bindBuffer(gl.ARRAY_BUFFER, this.objectInstanceBuf);
    gl.bufferData(
      gl.ARRAY_BUFFER,
      buf.subarray(0, written * FLOATS_PER_OBJECT),
      gl.DYNAMIC_DRAW,
    );
    gl.useProgram(this.objectProg);
    gl.uniform2f(loc(gl, this.objectProg, 'uViewportPx'), W, H);
    gl.drawArraysInstanced(gl.TRIANGLE_STRIP, 0, 4, written);
    gl.bindVertexArray(null);
    gl.blendFunc(gl.ONE, gl.ONE);
  }

  /**
   * Named sector-grid pass — the final navigational overlay, drawn to the
   * default framebuffer AFTER the fog veil so it stays readable everywhere. One
   * instanced draw of the in-view cells; the fragment shader draws the boundary +
   * subtle tinted fill, escalating for hover/selection (selected cells get corner
   * ticks). Gated by apparent on-screen cell size so the grid only shows when
   * cells are a reasonable size. Bounded by the passed (in-view) cells. No text
   * (the HUD draws sector names). Disabled/empty -> the pass is skipped.
   */
  private drawSectors(
    gl: WebGL2RenderingContext,
    scene: RenderScene,
    view: ViewTransform,
    scalePx: number,
    _timeSeconds: number,
  ): void {
    const sectors = scene.sectors;
    if (
      !this.sectorProg ||
      !sectors ||
      !sectors.enabled ||
      sectors.cells.length === 0
    ) {
      return;
    }
    const W = view.widthPx;
    const H = view.heightPx;
    const DPR = view.dpr;

    this.ensureSectorCapacity(sectors.cells.length);
    const buf = this.sectorData;
    let o = 0;
    let written = 0;
    for (const cell of sectors.cells) {
      const sidePx = cell.half * 2 * scalePx;
      // Apparent-size gate: skip cells that are tiny or fill the whole screen.
      if (sidePx < 48 * DPR || sidePx > 2.4 * Math.max(W, H)) {
        continue;
      }
      const c = view.w2s(cell.cx, cell.cy);
      const half = cell.half * scalePx;
      // Off-screen reject.
      if (
        c.x - half > W ||
        c.x + half < 0 ||
        c.y - half > H ||
        c.y + half < 0
      ) {
        continue;
      }
      const tint = cell.tint;
      const hasTint = tint !== null;
      let lineA = 0.12;
      let fillA = hasTint ? 0.04 : 0;
      if (cell.hovered) {
        lineA = 0.4;
        fillA = hasTint ? 0.07 : 0.04;
      }
      let tr = hasTint ? (tint as readonly number[])[0] : 0.47;
      let tg = hasTint ? (tint as readonly number[])[1] : 0.59;
      let tb = hasTint ? (tint as readonly number[])[2] : 0.75;
      if (cell.selected) {
        lineA = 0.7;
        fillA = 0.1;
        if (!hasTint) {
          tr = 0.35;
          tg = 0.67;
          tb = 1.0;
        }
      }
      buf[o] = c.x;
      buf[o + 1] = c.y;
      buf[o + 2] = half;
      buf[o + 3] = tr;
      buf[o + 4] = tg;
      buf[o + 5] = tb;
      buf[o + 6] = hasTint ? 1 : 0;
      buf[o + 7] = lineA;
      buf[o + 8] = fillA;
      buf[o + 9] = cell.selected ? 1 : 0;
      buf[o + 10] = cell.hovered ? 1 : 0;
      o += FLOATS_PER_SECTOR;
      written++;
    }
    if (written === 0) {
      return;
    }

    gl.bindFramebuffer(gl.FRAMEBUFFER, null);
    gl.viewport(0, 0, W, H);
    gl.enable(gl.BLEND);
    gl.blendFunc(gl.SRC_ALPHA, gl.ONE_MINUS_SRC_ALPHA);
    gl.bindVertexArray(this.sectorVao);
    gl.bindBuffer(gl.ARRAY_BUFFER, this.sectorInstanceBuf);
    gl.bufferData(
      gl.ARRAY_BUFFER,
      buf.subarray(0, written * FLOATS_PER_SECTOR),
      gl.DYNAMIC_DRAW,
    );
    gl.useProgram(this.sectorProg);
    gl.uniform2f(loc(gl, this.sectorProg, 'uViewportPx'), W, H);
    gl.drawArraysInstanced(gl.TRIANGLE_STRIP, 0, 4, written);
    gl.bindVertexArray(null);
    gl.blendFunc(gl.ONE, gl.ONE);
  }

  /**
   * Fog-of-war veil pass — the LAST thing drawn each frame, to the default
   * framebuffer, with standard alpha blending. A fullscreen draw that darkens
   * space outside the union of the reveal disks. The reveal set is passed as a
   * capped uniform array (FOG_MAX_REVEALS); if more reveals are in view we keep
   * the nearest to screen centre on the CPU (bounded shader work). Undefined or
   * disabled fog -> the pass is skipped entirely (full reveal).
   */
  private drawFog(
    gl: WebGL2RenderingContext,
    scene: RenderScene,
    view: ViewTransform,
  ): void {
    const fog = scene.fog;
    if (!this.fogProg || !fog || !fog.enabled) {
      return;
    }
    const W = view.widthPx;
    const H = view.heightPx;
    const cx = W * 0.5;
    const cy = H * 0.5;

    // Zoom factor (1 = whole galaxy on screen), reconstructed from the viewport
    // + galaxy radius. The veil fades out at galaxy scale so the disc is fully
    // visible when zoomed out, and fades in across ~1.5x..4x as you zoom into a
    // region/system where unexplored detail should be hidden.
    const rMax = scene.rMax || 1000;
    const fitScaleCss = Math.min(W, H) / Math.max(view.dpr, 1e-6) / (rMax * 2.4);
    const zoomFactor = view.scale / Math.max(fitScaleCss, 1e-6);
    const veilStrength = Math.min(1, Math.max(0, (zoomFactor - 1.5) / 2.5));
    if (veilStrength <= 0.001) {
      return; // galaxy scale: no veil, the whole galaxy is visible
    }

    // Project reveal disks to screen px; keep the FOG_MAX_REVEALS nearest the
    // screen centre when the visible set exceeds the cap (bounded uniform work).
    const projected: { x: number; y: number; r: number; d2: number }[] = [];
    for (const rv of fog.reveals) {
      const p = view.w2s(rv.x, rv.y);
      const r = rv.r * view.scale * view.dpr; // world radius -> device px
      const dx = p.x - cx;
      const dy = p.y - cy;
      projected.push({ x: p.x, y: p.y, r, d2: dx * dx + dy * dy });
    }
    if (projected.length > FOG_MAX_REVEALS) {
      projected.sort((a, b) => a.d2 - b.d2);
      projected.length = FOG_MAX_REVEALS;
    }
    const count = projected.length;
    const u = this.fogReveals;
    for (let i = 0; i < count; i++) {
      u[i * 3] = projected[i].x;
      u[i * 3 + 1] = projected[i].y;
      u[i * 3 + 2] = projected[i].r;
    }

    gl.bindFramebuffer(gl.FRAMEBUFFER, null);
    gl.viewport(0, 0, W, H);
    gl.enable(gl.BLEND);
    gl.blendFunc(gl.SRC_ALPHA, gl.ONE_MINUS_SRC_ALPHA);
    gl.useProgram(this.fogProg);
    gl.uniform2f(loc(gl, this.fogProg, 'uViewportPx'), W, H);
    gl.uniform1f(loc(gl, this.fogProg, 'uVeilStrength'), veilStrength);
    gl.uniform1i(loc(gl, this.fogProg, 'uRevealCount'), count);
    const uloc = loc(gl, this.fogProg, 'uReveals');
    // Only upload when there is at least one reveal — WebGL rejects an empty
    // array for a uniform-array setter (INVALID_VALUE). count 0 leaves the
    // whole screen veiled, which is the correct "all unexplored" result.
    if (uloc && count > 0 && typeof gl.uniform3fv === 'function') {
      gl.uniform3fv(uloc, u.subarray(0, count * 3));
    }
    gl.bindVertexArray(this.postVao);
    gl.drawArrays(gl.TRIANGLES, 0, 3);
    gl.bindVertexArray(null);
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
      this.overlayProg,
      this.territoryProg,
      this.routeProg,
      this.fogProg,
      this.objectProg,
      this.sectorProg,
    ]) {
      if (p) {
        gl.deleteProgram(p);
      }
    }
    for (const v of [
      this.vao,
      this.postVao,
      this.planetVao,
      this.overlayVao,
      this.territoryVao,
      this.routeVao,
      this.objectVao,
      this.sectorVao,
    ]) {
      if (v) {
        gl.deleteVertexArray(v);
      }
    }
    for (const b of [
      this.quadBuf,
      this.instanceBuf,
      this.planetQuadBuf,
      this.planetInstanceBuf,
      this.overlayQuadBuf,
      this.overlayInstanceBuf,
      this.territoryQuadBuf,
      this.territoryInstanceBuf,
      this.routeQuadBuf,
      this.routeInstanceBuf,
      this.objectQuadBuf,
      this.objectInstanceBuf,
      this.sectorQuadBuf,
      this.sectorInstanceBuf,
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
    this.overlayProg = null;
    this.territoryProg = null;
    this.routeProg = null;
    this.fogProg = null;
    this.objectProg = null;
    this.sectorProg = null;
    this.vao = null;
    this.postVao = null;
    this.planetVao = null;
    this.overlayVao = null;
    this.territoryVao = null;
    this.routeVao = null;
    this.objectVao = null;
    this.sectorVao = null;
    this.quadBuf = null;
    this.instanceBuf = null;
    this.planetQuadBuf = null;
    this.planetInstanceBuf = null;
    this.overlayQuadBuf = null;
    this.overlayInstanceBuf = null;
    this.territoryQuadBuf = null;
    this.territoryInstanceBuf = null;
    this.routeQuadBuf = null;
    this.routeInstanceBuf = null;
    this.objectQuadBuf = null;
    this.objectInstanceBuf = null;
    this.sectorQuadBuf = null;
    this.sectorInstanceBuf = null;
    this.postOk = false;
    this.gl = null;
    this.data = new Float32Array(0);
    this.capacity = 0;
    this.planetData = new Float32Array(0);
    this.planetCapacity = 0;
    this.overlayData = new Float32Array(0);
    this.overlayCapacity = 0;
    this.territoryData = new Float32Array(0);
    this.territoryCapacity = 0;
    this.routeData = new Float32Array(0);
    this.routeCapacity = 0;
    this.objectData = new Float32Array(0);
    this.objectCapacity = 0;
    this.sectorData = new Float32Array(0);
    this.sectorCapacity = 0;
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

  private ensureOverlayCapacity(instances: number): void {
    if (instances <= this.overlayCapacity) {
      return;
    }
    let cap = Math.max(this.overlayCapacity, 256);
    while (cap < instances) {
      cap *= 2;
    }
    this.overlayCapacity = cap;
    this.overlayData = new Float32Array(cap * FLOATS_PER_OVERLAY);
  }

  private ensureTerritoryCapacity(instances: number): void {
    if (instances <= this.territoryCapacity) {
      return;
    }
    let cap = Math.max(this.territoryCapacity, 256);
    while (cap < instances) {
      cap *= 2;
    }
    this.territoryCapacity = cap;
    this.territoryData = new Float32Array(cap * FLOATS_PER_TERRITORY);
  }

  private ensureRouteCapacity(instances: number): void {
    if (instances <= this.routeCapacity) {
      return;
    }
    let cap = Math.max(this.routeCapacity, 256);
    while (cap < instances) {
      cap *= 2;
    }
    this.routeCapacity = cap;
    this.routeData = new Float32Array(cap * FLOATS_PER_ROUTE);
  }

  private ensureObjectCapacity(instances: number): void {
    if (instances <= this.objectCapacity) {
      return;
    }
    let cap = Math.max(this.objectCapacity, 64);
    while (cap < instances) {
      cap *= 2;
    }
    this.objectCapacity = cap;
    this.objectData = new Float32Array(cap * FLOATS_PER_OBJECT);
  }

  private ensureSectorCapacity(instances: number): void {
    if (instances <= this.sectorCapacity) {
      return;
    }
    let cap = Math.max(this.sectorCapacity, 256);
    while (cap < instances) {
      cap *= 2;
    }
    this.sectorCapacity = cap;
    this.sectorData = new Float32Array(cap * FLOATS_PER_SECTOR);
  }
}

/**
 * Biome-ish planet colours (0..1), mirroring the warm/cool variety of the PoC
 * planet palette so lit spheres read as varied worlds rather than identical
 * dots. Index is derived deterministically from the star id.
 */
/**
 * Trade-lane colours (0..1) by route kind, matching the Canvas2D palette:
 * allied #4ad6a0, trade #ffcf73, contested #ff6b6b.
 */
const ROUTE_COLORS: Record<string, readonly [number, number, number]> = {
  allied: [0x4a / 255, 0xd6 / 255, 0xa0 / 255],
  trade: [0xff / 255, 0xcf / 255, 0x73 / 255],
  contested: [0xff / 255, 0x6b / 255, 0x6b / 255],
};

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
