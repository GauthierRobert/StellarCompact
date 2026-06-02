import { computed, Injectable, signal } from '@angular/core';

/**
 * Camera store — signal-based service mirroring the PoC camera model exactly.
 *
 * PoC model (poc/galaxy-navigator.html) recap:
 *   fit()  = min(cssW, cssH) / (R_MAX * 2.4)
 *   scale  = fit() * BASE^z   where BASE = 2
 *   z in [ZMIN = -1.5 , ZMAX = 15]
 *
 * Two layers:
 *   target  (tx, ty, tz) — where the user intends the camera to be
 *   rendered (x,  y,  z) — exponentially-eased position used by the renderer
 *
 * The galaxy renderer's RAF loop calls stepFrame(dt, anchor) each frame.
 * Signals expose the target state; computed signals derive scale / bbox / transforms.
 * Everything is zoneless-friendly — no Zone.js wrapping needed.
 */

// ---------------------------------------------------------------------------
// Types
// ---------------------------------------------------------------------------

/** World-space 2-D position (galaxy coordinate units). */
export interface Vec2 {
  readonly x: number;
  readonly y: number;
}

/** Full camera state — both target and rendered layers plus momentum. */
export interface CameraState {
  /** Rendered pan (world units) — eased towards target. */
  readonly x: number;
  readonly y: number;
  /** Rendered zoom exponent — eased towards tz. */
  readonly z: number;
  /** Target pan (world units) — set directly by pointer events. */
  readonly tx: number;
  readonly ty: number;
  /** Target zoom exponent — set by wheel / button. */
  readonly tz: number;
  /** Momentum velocity (world units / second). Decayed by stepFrame. */
  readonly vx: number;
  readonly vy: number;
}

/** Axis-aligned bounding box in world space. */
export interface WorldBbox {
  readonly minX: number;
  readonly minY: number;
  readonly maxX: number;
  readonly maxY: number;
}

/** Viewport dimensions — supplied by the renderer component on resize. */
export interface ViewportSize {
  readonly cssWidth: number;
  readonly cssHeight: number;
  /** Device-pixel ratio, capped at 2 (matching the PoC). */
  readonly dpr: number;
}

/** Zoom anchor used during stepFrame to keep world point under the cursor. */
export interface ZoomAnchor {
  readonly anchorWorld: Vec2;
  readonly anchorScreen: Vec2;
}

// ---------------------------------------------------------------------------
// Constants
// ---------------------------------------------------------------------------

export const CAMERA_BASE = 2;
export const CAMERA_ZMIN = -1.5;
export const CAMERA_ZMAX = 15;
/** Default galaxy radius — matches PoC seed R_MAX; updated from tile metadata when E7-03 lands. */
export const GALAXY_R_MAX_DEFAULT = 8000;

/**
 * Floating-origin re-base threshold (E8-07), expressed in *screen pixels*.
 *
 * The GPU renders `(world - origin) * scalePx`. Both `world` and `origin` reach
 * the shader as float32 (~24-bit mantissa, ~7 significant decimal digits). When
 * the camera sits far from the origin AND we are zoomed deep, the float32
 * product loses precision (large value, tiny visible delta) and the star field
 * shimmers/quantises. We keep the *pixel* magnitude of the camera offset from
 * the origin bounded: re-base whenever `|camWorld - origin| * scalePx` exceeds
 * this many pixels. Because the threshold is in pixels, it adapts to zoom — at
 * deep zoom (huge scalePx) a tiny world drift triggers a re-base; zoomed out it
 * essentially never fires (origin stays 0, matching pre-E8-07 behaviour).
 *
 * 16384 px ≈ a handful of 4K screens of drift — small enough that the
 * float32-relative precision of any on-screen coordinate stays well under a
 * sub-pixel, large enough that re-bases are infrequent (a few per long pan).
 */
export const ORIGIN_REBASE_PX = 16384;

// ---------------------------------------------------------------------------
// Store
// ---------------------------------------------------------------------------

@Injectable({ providedIn: 'root' })
export class CameraStore {
  // ---- writable private signals ----

  private readonly _rMax = signal<number>(GALAXY_R_MAX_DEFAULT);

  private readonly _viewport = signal<ViewportSize>({
    cssWidth: 800,
    cssHeight: 600,
    dpr: 1,
  });

  /** Bundled camera state — one signal avoids excessive fan-out. */
  private readonly _cam = signal<CameraState>({
    x: 0,
    y: 0,
    z: 0,
    tx: 0,
    ty: 0,
    tz: 0,
    vx: 0,
    vy: 0,
  });

  /**
   * Floating-origin world anchor (E8-07). World coordinates are expressed
   * relative to this point before being uploaded to the float32 GPU buffers, so
   * rendered magnitudes stay small at deep zoom. Re-based in stepFrame whenever
   * the camera drifts beyond ORIGIN_REBASE_PX (see rebaseOrigin). Held in a
   * dedicated signal so a re-base does NOT churn the camera-state signal (the
   * pan/zoom values are byte-identical across a re-base — it is transparent).
   */
  private readonly _origin = signal<Vec2>({ x: 0, y: 0 });

  // ---- public read-only signals ----

  readonly state = this._cam.asReadonly();
  readonly viewport = this._viewport.asReadonly();

  /**
   * Current floating-origin world anchor (E8-07). The renderer subtracts this
   * from world coords (and from the recovered camera world) before the float32
   * GPU upload. Consumers that work in float64 on the CPU (the w2s/s2w pixel
   * transforms, tile-fetch bbox) ignore it — it is purely a GPU-precision aid.
   */
  readonly origin = this._origin.asReadonly();

  // ---- computed signals ----

  /**
   * fit() — base scale that maps the galaxy to the viewport at z = 0.
   * PoC: `Math.min(innerWidth, innerHeight) / (R_MAX * 2.4)`.
   */
  readonly fitScale = computed<number>(() => {
    const { cssWidth, cssHeight } = this._viewport();
    return Math.min(cssWidth, cssHeight) / (this._rMax() * 2.4);
  });

  /** Rendered scale in CSS pixels per world unit. PoC: `fit() * BASE^z`. */
  readonly scale = computed<number>(
    () => this.fitScale() * Math.pow(CAMERA_BASE, this._cam().z),
  );

  /** Target scale used during drag (PoC scaleT). */
  readonly scaleT = computed<number>(
    () => this.fitScale() * Math.pow(CAMERA_BASE, this._cam().tz),
  );

  /** Zoom factor relative to fit (1.0 = galaxy fills screen). PoC readout: `s / fit()`. */
  readonly zoomFactor = computed<number>(() => this.scale() / this.fitScale());

  /** Continuous LOD exponent (eased) — consumers derive tile zoom from this. */
  readonly levelF = computed<number>(() => this._cam().z);

  /**
   * World-to-CSS-pixel transform functions derived from the rendered camera position.
   * PoC w2s: `(wx - x) * scale * dpr + W/2` then /dpr → CSS.
   */
  readonly w2s = computed(() => {
    const { x, y } = this._cam();
    const s = this.scale();
    const { cssWidth, cssHeight } = this._viewport();
    return {
      x: (wx: number): number => (wx - x) * s + cssWidth / 2,
      y: (wy: number): number => (wy - y) * s + cssHeight / 2,
    };
  });

  /**
   * Screen-to-world functions using the rendered camera position.
   * PoC s2w (T=false): `x + (sx*DPR - W/2) / (scale * DPR)` = `x + (sx - W/(2)) / scale`.
   */
  readonly s2w = computed(() => {
    const { x, y } = this._cam();
    const s = this.scale();
    const { cssWidth, cssHeight } = this._viewport();
    return {
      x: (sx: number): number => x + (sx - cssWidth / 2) / s,
      y: (sy: number): number => y + (sy - cssHeight / 2) / s,
    };
  });

  /**
   * Visible world bounding box derived from the rendered camera position.
   * Used by the tile service (E7-05) and the optional SEND spectate message.
   */
  readonly visibleBbox = computed<WorldBbox>(() => {
    const { x, y } = this._cam();
    const s = this.scale();
    const { cssWidth, cssHeight } = this._viewport();
    const hw = cssWidth / 2 / s;
    const hh = cssHeight / 2 / s;
    return { minX: x - hw, minY: y - hh, maxX: x + hw, maxY: y + hh };
  });

  // ---- mutators ----

  /** Update viewport dimensions — call from renderer on resize. */
  setViewport(viewport: ViewportSize): void {
    this._viewport.set(viewport);
  }

  /** Update galaxy radius from tile metadata. */
  setRMax(rMax: number): void {
    this._rMax.set(rMax);
  }

  /**
   * Apply a pointer-drag delta in CSS pixels.
   * Matches PoC pointermove: converts to world-space delta using scaleT (target scale),
   * updates both rendered and target positions immediately (no easing lag during drag),
   * records velocity for post-drag momentum fling.
   */
  applyPanDelta(dxCss: number, dyCss: number, dt: number): void {
    const safeDt = Math.max(0.001, dt);
    const s = this.scaleT();
    const dxw = dxCss / s;
    const dyw = dyCss / s;
    this._cam.update((c) => ({
      ...c,
      tx: c.tx - dxw,
      ty: c.ty - dyw,
      x: c.x - dxw,
      y: c.y - dyw,
      vx: -dxw / safeDt,
      vy: -dyw / safeDt,
    }));
  }

  /**
   * Reset momentum (call on pointerdown — PoC: `Cam.vx = Cam.vy = 0`).
   */
  resetMomentum(): void {
    this._cam.update((c) => ({ ...c, vx: 0, vy: 0 }));
  }

  /**
   * Zoom towards a screen-space anchor point.
   * Matches PoC zoomTo: clamps tz; returns the anchor so stepFrame can correct pan.
   */
  zoomTo(screenX: number, screenY: number, dz: number): ZoomAnchor {
    const s2w = this.s2w();
    const anchorWorld: Vec2 = { x: s2w.x(screenX), y: s2w.y(screenY) };
    const anchorScreen: Vec2 = { x: screenX, y: screenY };
    this._cam.update((c) => ({
      ...c,
      tz: Math.max(CAMERA_ZMIN, Math.min(CAMERA_ZMAX, c.tz + dz)),
    }));
    return { anchorWorld, anchorScreen };
  }

  /**
   * Advance the camera one animation frame.
   * Call from the galaxy renderer RAF loop each frame.
   *
   * Mirrors PoC frame logic (lines 103-105):
   *   1. Exponential ease z → tz  (rate 15)
   *   2. Anchor correction (keep world point fixed under cursor while zooming)
   *   3. Momentum decay: vx/vy *= exp(-4.5*dt); advance tx/ty
   *   4. Exponential ease x → tx, y → ty  (rate 18)
   *
   * @param dt     Frame delta time in seconds
   * @param anchor Zoom anchor; null when not actively zooming
   */
  stepFrame(dt: number, anchor: ZoomAnchor | null): void {
    const fitScale = this.fitScale();
    const { cssWidth, cssHeight } = this._viewport();
    this._cam.update((c) => {
      let { x, y, z, tx, ty, vx, vy } = c;
      const { tz } = c;

      // 1 & 2: zoom easing + anchor correction
      if (Math.abs(tz - z) > 1e-5) {
        z = expEase(z, tz, 15, dt);
        if (anchor !== null) {
          const s = fitScale * Math.pow(CAMERA_BASE, z);
          const newWorldX = x + (anchor.anchorScreen.x - cssWidth / 2) / s;
          const newWorldY = y + (anchor.anchorScreen.y - cssHeight / 2) / s;
          x += anchor.anchorWorld.x - newWorldX;
          y += anchor.anchorWorld.y - newWorldY;
          tx = x;
          ty = y;
        }
      }

      // 3: momentum
      if (Math.abs(vx) > 1e-4 || Math.abs(vy) > 1e-4) {
        tx += vx * dt;
        ty += vy * dt;
        const decay = Math.exp(-4.5 * dt);
        vx *= decay;
        vy *= decay;
        if (Math.hypot(vx, vy) < 0.002) {
          vx = 0;
          vy = 0;
        }
      }

      // 4: pan easing
      x = expEase(x, tx, 18, dt);
      y = expEase(y, ty, 18, dt);

      return { x, y, z, tx, ty, tz, vx, vy };
    });

    // 5: floating-origin re-base (E8-07). Done AFTER the camera settles so we
    // re-base around the rendered position. Transparent: changes only the GPU
    // reference frame, never the on-screen result (see rebaseOrigin).
    this.maybeRebaseOrigin();
  }

  /**
   * Floating-origin re-base (E8-07). Re-centre the GPU world origin on the
   * rendered camera position when the camera has drifted more than
   * ORIGIN_REBASE_PX pixels from the current origin, so the float32 magnitudes
   * the GPU sees (`(world - origin) * scalePx`) stay bounded at deep zoom.
   *
   * This is purely a change of reference frame: the camera pan/zoom signals are
   * untouched, the w2s/s2w CPU transforms (float64) are untouched, so the
   * on-screen projection is byte-identical before and after. Only the `origin`
   * signal moves; the renderer subtracts it equally from star and camera world.
   */
  private maybeRebaseOrigin(): void {
    const { x, y } = this._cam();
    const scalePx = this.scale() * this._viewport().dpr;
    const o = this._origin();
    const driftPx = Math.hypot(x - o.x, y - o.y) * scalePx;
    if (driftPx > ORIGIN_REBASE_PX) {
      // Re-base exactly onto the rendered camera so the offset resets to 0.
      this._origin.set({ x, y });
    }
  }

  /**
   * Force the floating origin onto the current rendered camera position
   * (E8-07). Exposed for tests / explicit resets; stepFrame calls the throttled
   * `maybeRebaseOrigin` automatically every frame.
   */
  rebaseOrigin(): void {
    const { x, y } = this._cam();
    this._origin.set({ x, y });
  }

  /**
   * Jump to a target world position and zoom (used for double-click dive — PoC line 86).
   * Sets only the target; the easing in stepFrame smoothly moves the camera there.
   */
  jumpTo(worldX: number, worldY: number, tz: number): void {
    this._cam.update((c) => ({
      ...c,
      tx: worldX,
      ty: worldY,
      tz: Math.max(CAMERA_ZMIN, Math.min(CAMERA_ZMAX, tz)),
    }));
  }
}

// ---------------------------------------------------------------------------
// Pure math helpers — no Angular dependency, straightforward to unit-test.
// ---------------------------------------------------------------------------

/**
 * Exponential ease — identical to PoC expEase.
 * Equivalent to `target + (current - target) * exp(-rate * dt)`.
 */
export function expEase(
  current: number,
  target: number,
  rate: number,
  dt: number,
): number {
  return target + (current - target) * Math.exp(-rate * dt);
}
