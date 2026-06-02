import { TestBed } from '@angular/core/testing';
import { provideZonelessChangeDetection } from '@angular/core';
import {
  CameraStore,
  expEase,
  CAMERA_BASE,
  CAMERA_ZMIN,
  CAMERA_ZMAX,
  GALAXY_R_MAX_DEFAULT,
} from './camera.store';

// ---------------------------------------------------------------------------
// expEase pure helper
// ---------------------------------------------------------------------------

describe('expEase', () => {
  it('returns target when current === target', () => {
    expect(expEase(5, 5, 10, 0.016)).toBeCloseTo(5);
  });

  it('moves current towards target', () => {
    const result = expEase(0, 10, 15, 0.016);
    expect(result).toBeGreaterThan(0);
    expect(result).toBeLessThan(10);
  });

  it('larger rate yields faster approach', () => {
    const slow = expEase(0, 10, 1, 0.1);
    const fast = expEase(0, 10, 20, 0.1);
    expect(fast).toBeGreaterThan(slow);
  });

  it('converges to target when dt is very large', () => {
    expect(expEase(0, 100, 15, 1000)).toBeCloseTo(100, 3);
  });
});

// ---------------------------------------------------------------------------
// CameraStore
// ---------------------------------------------------------------------------

describe('CameraStore', () => {
  let store: CameraStore;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideZonelessChangeDetection(), CameraStore],
    });
    store = TestBed.inject(CameraStore);
  });

  // ---- initial state ----

  it('starts with zero pan and zoom', () => {
    const s = store.state();
    expect(s.x).toBe(0);
    expect(s.y).toBe(0);
    expect(s.z).toBe(0);
    expect(s.tx).toBe(0);
    expect(s.ty).toBe(0);
    expect(s.tz).toBe(0);
  });

  // ---- fitScale / scale ----

  it('computes fitScale from viewport and rMax', () => {
    store.setViewport({ cssWidth: 800, cssHeight: 600, dpr: 1 });
    // fit = min(800, 600) / (R_MAX * 2.4) = 600 / (8000 * 2.4)
    const expected = 600 / (GALAXY_R_MAX_DEFAULT * 2.4);
    expect(store.fitScale()).toBeCloseTo(expected, 10);
  });

  it('scale equals fitScale at z=0', () => {
    expect(store.scale()).toBeCloseTo(store.fitScale(), 10);
  });

  // ---- setViewport ----

  it('setViewport updates the viewport signal', () => {
    store.setViewport({ cssWidth: 1920, cssHeight: 1080, dpr: 2 });
    expect(store.viewport().cssWidth).toBe(1920);
    expect(store.viewport().cssHeight).toBe(1080);
    expect(store.viewport().dpr).toBe(2);
  });

  it('setViewport triggers fitScale recomputation', () => {
    store.setViewport({ cssWidth: 1000, cssHeight: 1000, dpr: 1 });
    const fit1 = store.fitScale();
    store.setViewport({ cssWidth: 500, cssHeight: 500, dpr: 1 });
    const fit2 = store.fitScale();
    expect(fit2).toBeCloseTo(fit1 / 2, 10);
  });

  // ---- pan ----

  it('applyPanDelta moves the camera target and rendered position', () => {
    store.setViewport({ cssWidth: 800, cssHeight: 600, dpr: 1 });
    // drag 100 CSS px right → world shift = 100 / scale (negative because camera follows drag)
    store.applyPanDelta(100, 0, 0.016);
    const s = store.state();
    expect(s.tx).toBeLessThan(0); // camera pan right moves world left (negative x)
    expect(s.x).toBeCloseTo(s.tx, 5); // rendered = target during drag
  });

  it('applyPanDelta records momentum velocity', () => {
    store.setViewport({ cssWidth: 800, cssHeight: 600, dpr: 1 });
    store.applyPanDelta(0, -50, 0.016);
    const s = store.state();
    expect(s.vy).not.toBe(0);
  });

  it('resetMomentum zeros velocity', () => {
    store.setViewport({ cssWidth: 800, cssHeight: 600, dpr: 1 });
    store.applyPanDelta(100, 50, 0.016);
    store.resetMomentum();
    const s = store.state();
    expect(s.vx).toBe(0);
    expect(s.vy).toBe(0);
  });

  // ---- zoom ----

  it('zoomTo clamps tz to ZMAX', () => {
    store.setViewport({ cssWidth: 800, cssHeight: 600, dpr: 1 });
    store.zoomTo(400, 300, 999);
    expect(store.state().tz).toBe(CAMERA_ZMAX);
  });

  it('zoomTo clamps tz to ZMIN', () => {
    store.setViewport({ cssWidth: 800, cssHeight: 600, dpr: 1 });
    store.zoomTo(400, 300, -999);
    expect(store.state().tz).toBe(CAMERA_ZMIN);
  });

  it('zoomTo returns the correct world anchor from the current camera position', () => {
    store.setViewport({ cssWidth: 800, cssHeight: 600, dpr: 1 });
    // Camera is centred at (0,0) with z=0; screen centre is (400, 300).
    // s2w(400, 300) = (0 + (400 - 400) / scale, 0 + (300 - 300) / scale) = (0, 0)
    const { anchorWorld } = store.zoomTo(400, 300, 1);
    expect(anchorWorld.x).toBeCloseTo(0, 5);
    expect(anchorWorld.y).toBeCloseTo(0, 5);
  });

  it('zoomTo from off-centre returns non-zero world anchor', () => {
    store.setViewport({ cssWidth: 800, cssHeight: 600, dpr: 1 });
    const { anchorWorld } = store.zoomTo(0, 0, 0);
    // Screen (0,0) is top-left; world anchor should be negative x,y
    expect(anchorWorld.x).toBeLessThan(0);
    expect(anchorWorld.y).toBeLessThan(0);
  });

  it('zoomFactor is 1 at z=0', () => {
    expect(store.zoomFactor()).toBeCloseTo(1, 5);
  });

  it('zoomFactor doubles when tz increases by 1 (after stepFrame converges)', () => {
    store.setViewport({ cssWidth: 800, cssHeight: 600, dpr: 1 });
    // Drive z to 1 by stepping many frames
    store.zoomTo(400, 300, 1);
    for (let i = 0; i < 200; i++) store.stepFrame(0.016, null);
    // BASE^1 = 2, so zoomFactor should be ~2
    expect(store.zoomFactor()).toBeCloseTo(2, 1);
  });

  // ---- zoom-to-point transform correctness ----

  it('zoom-to-point keeps world anchor fixed under cursor after stepFrame converges', () => {
    store.setViewport({ cssWidth: 800, cssHeight: 600, dpr: 1 });
    // Place anchor at screen (200, 150) — which maps to some world point
    const anchor = store.zoomTo(200, 150, 3);
    // Run until converged
    for (let i = 0; i < 300; i++) store.stepFrame(0.016, anchor);
    // After convergence the anchor screen point should still map to the same world point
    const s2w = store.s2w();
    const wx = s2w.x(anchor.anchorScreen.x);
    const wy = s2w.y(anchor.anchorScreen.y);
    expect(wx).toBeCloseTo(anchor.anchorWorld.x, 1);
    expect(wy).toBeCloseTo(anchor.anchorWorld.y, 1);
  });

  // ---- scale limit clamping ----

  it('scale does not exceed BASE^ZMAX * fitScale', () => {
    store.setViewport({ cssWidth: 800, cssHeight: 600, dpr: 1 });
    store.zoomTo(400, 300, 999);
    for (let i = 0; i < 300; i++) store.stepFrame(0.016, null);
    const maxScale =
      store.fitScale() * Math.pow(CAMERA_BASE, CAMERA_ZMAX);
    expect(store.scale()).toBeLessThanOrEqual(maxScale + 1e-6);
  });

  it('scale does not go below BASE^ZMIN * fitScale', () => {
    store.setViewport({ cssWidth: 800, cssHeight: 600, dpr: 1 });
    store.zoomTo(400, 300, -999);
    for (let i = 0; i < 300; i++) store.stepFrame(0.016, null);
    const minScale =
      store.fitScale() * Math.pow(CAMERA_BASE, CAMERA_ZMIN);
    expect(store.scale()).toBeGreaterThanOrEqual(minScale - 1e-6);
  });

  // ---- visibleBbox ----

  it('visibleBbox is centred at camera world position', () => {
    store.setViewport({ cssWidth: 800, cssHeight: 600, dpr: 1 });
    const bbox = store.visibleBbox();
    const midX = (bbox.minX + bbox.maxX) / 2;
    const midY = (bbox.minY + bbox.maxY) / 2;
    expect(midX).toBeCloseTo(0, 5);
    expect(midY).toBeCloseTo(0, 5);
  });

  it('visibleBbox width equals cssWidth / scale', () => {
    store.setViewport({ cssWidth: 800, cssHeight: 600, dpr: 1 });
    const bbox = store.visibleBbox();
    const width = bbox.maxX - bbox.minX;
    expect(width).toBeCloseTo(800 / store.scale(), 3);
  });

  // ---- jumpTo ----

  it('jumpTo sets target position and zoom', () => {
    store.jumpTo(1000, -500, 5);
    const s = store.state();
    expect(s.tx).toBe(1000);
    expect(s.ty).toBe(-500);
    expect(s.tz).toBe(5);
  });

  it('jumpTo clamps tz', () => {
    store.jumpTo(0, 0, 999);
    expect(store.state().tz).toBe(CAMERA_ZMAX);
  });

  // ---- stepFrame momentum decay ----

  it('stepFrame decays momentum over time', () => {
    store.setViewport({ cssWidth: 800, cssHeight: 600, dpr: 1 });
    store.applyPanDelta(100, 0, 0.016);
    const vx0 = store.state().vx;
    store.stepFrame(0.5, null);
    const vx1 = store.state().vx;
    // After 0.5s the velocity should be smaller in magnitude
    expect(Math.abs(vx1)).toBeLessThan(Math.abs(vx0));
  });

  // ---- levelF ----

  it('levelF equals z (rendered exponent)', () => {
    store.zoomTo(400, 300, 2);
    // Before any frame, z is still 0
    expect(store.levelF()).toBe(store.state().z);
    store.stepFrame(0.016, null);
    expect(store.levelF()).toBe(store.state().z);
  });

  // ---- floating origin (E8-07) ----

  it('starts with a zero floating origin', () => {
    expect(store.origin()).toEqual({ x: 0, y: 0 });
  });

  it('does not re-base near the galaxy centre when zoomed out', () => {
    store.setViewport({ cssWidth: 800, cssHeight: 600, dpr: 1 });
    // Zoomed out, small world coords: drift in pixels stays well under the
    // threshold, so the origin must remain 0 (matches pre-E8-07 behaviour).
    store.jumpTo(200, 150, 0);
    for (let i = 0; i < 200; i++) {
      store.stepFrame(0.05, null);
    }
    expect(store.origin()).toEqual({ x: 0, y: 0 });
  });

  it('re-bases the origin off zero and tracks the camera after deep-zoom drift', () => {
    store.setViewport({ cssWidth: 1920, cssHeight: 1080, dpr: 2 });
    // Deep zoom -> large scalePx, so even a modest world offset crosses the
    // pixel-magnitude re-base threshold while the camera eases to (5000,-3000).
    store.jumpTo(5000, -3000, CAMERA_ZMAX);
    for (let i = 0; i < 400; i++) {
      store.stepFrame(0.05, null);
    }
    const o = store.origin();
    const cam = store.state();
    // Origin left zero and now sits in the camera's neighbourhood (the re-base
    // point may trail the still-easing camera by a fraction of the threshold;
    // the bounded-offset invariant test below is the strict guarantee).
    expect(Math.hypot(o.x, o.y)).toBeGreaterThan(0);
    expect(Math.hypot(o.x - cam.x, o.y - cam.y)).toBeLessThan(50);
  });

  it('an explicit rebaseOrigin snaps the origin exactly onto the camera', () => {
    store.setViewport({ cssWidth: 1920, cssHeight: 1080, dpr: 2 });
    store.jumpTo(5000, -3000, CAMERA_ZMAX);
    for (let i = 0; i < 400; i++) {
      store.stepFrame(0.05, null);
    }
    store.rebaseOrigin();
    const o = store.origin();
    const cam = store.state();
    expect(o.x).toBe(cam.x);
    expect(o.y).toBe(cam.y);
  });

  it('re-base is transparent: w2s is byte-identical before and after', () => {
    store.setViewport({ cssWidth: 1920, cssHeight: 1080, dpr: 2 });
    store.jumpTo(5000, -3000, CAMERA_ZMAX);
    for (let i = 0; i < 400; i++) {
      store.stepFrame(0.05, null);
    }
    // Sample the CPU projection of a world point, then force another re-base
    // and re-sample: the on-screen pixel must be identical (origin only changes
    // the GPU reference frame, never the float64 CPU w2s transform).
    const before = store.w2s();
    const px0 = before.x(5001.25);
    const py0 = before.y(-2999.5);
    store.rebaseOrigin();
    const after = store.w2s();
    expect(after.x(5001.25)).toBe(px0);
    expect(after.y(-2999.5)).toBe(py0);
  });

  it('keeps the origin-relative camera offset bounded in pixels (precision)', () => {
    store.setViewport({ cssWidth: 1920, cssHeight: 1080, dpr: 2 });
    store.jumpTo(5000, -3000, CAMERA_ZMAX);
    for (let i = 0; i < 400; i++) {
      store.stepFrame(0.05, null);
    }
    const o = store.origin();
    const cam = store.state();
    const scalePx = store.scale() * store.viewport().dpr;
    const offsetPx = Math.hypot(cam.x - o.x, cam.y - o.y) * scalePx;
    // The whole point of re-basing: the float32 magnitude the GPU receives for
    // the camera stays small even at maximum zoom + large world coordinates.
    expect(offsetPx).toBeLessThanOrEqual(20000);
  });
});
