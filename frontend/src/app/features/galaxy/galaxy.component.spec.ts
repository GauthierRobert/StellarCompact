import { TestBed } from '@angular/core/testing';
import { provideZonelessChangeDetection } from '@angular/core';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { GalaxyComponent } from './galaxy.component';
import { CameraStore } from '../../stores';

/**
 * Component-level wiring tests. We deliberately do NOT assert canvas pixels;
 * we verify the component constructs, the RAF loop is guarded, and pointer/wheel
 * events drive the camera store mutators (the source of truth for pan/zoom).
 */
describe('GalaxyComponent', () => {
  let camera: CameraStore;

  beforeEach(() => {
    // keep the RAF loop from running unbounded in jsdom
    let rafCalls = 0;
    globalThis.requestAnimationFrame = ((cb: FrameRequestCallback) => {
      // fire at most one frame so ngAfterViewInit's loop does not spin
      if (rafCalls++ < 1) {
        cb(16);
      }
      return rafCalls;
    }) as typeof requestAnimationFrame;
    globalThis.cancelAnimationFrame = (() => undefined) as typeof cancelAnimationFrame;

    TestBed.configureTestingModule({
      imports: [GalaxyComponent],
      providers: [
        provideZonelessChangeDetection(),
        provideHttpClient(),
        provideHttpClientTesting(),
      ],
    });
    camera = TestBed.inject(CameraStore);
  });

  function create() {
    const fixture = TestBed.createComponent(GalaxyComponent);
    fixture.detectChanges(); // runs ngAfterViewInit
    return fixture;
  }

  it('constructs and runs ngAfterViewInit without throwing', () => {
    const fixture = create();
    expect(fixture.componentInstance).toBeTruthy();
    fixture.destroy();
  });

  it('sets the galaxy radius on the camera store from the rMax input', () => {
    const fixture = create();
    // default rMax input is 1000; visibleBbox uses it via fitScale
    expect(camera.fitScale()).toBeGreaterThan(0);
    fixture.destroy();
  });

  it('pointer drag drives camera.applyPanDelta (pan moves the target)', () => {
    const fixture = create();
    const c = fixture.componentInstance;
    c.onPointerDown({
      clientX: 100,
      clientY: 100,
      timeStamp: 0,
      pointerId: 1,
      target: {
        setPointerCapture: () => undefined,
        releasePointerCapture: () => undefined,
      },
    } as unknown as PointerEvent);
    const before = camera.state();
    c.onPointerMove({
      clientX: 150,
      clientY: 100,
      timeStamp: 16,
      pointerId: 1,
    } as unknown as PointerEvent);
    const after = camera.state();
    // dragging right moves the camera target left (negative world x), per PoC
    expect(after.tx).toBeLessThan(before.tx);
    fixture.destroy();
  });

  it('wheel drives camera.zoomTo (target zoom changes)', () => {
    const fixture = create();
    const c = fixture.componentInstance;
    const tzBefore = camera.state().tz;
    c.onWheel({
      clientX: 10,
      clientY: 10,
      deltaY: -100,
      deltaMode: 0,
      preventDefault: () => undefined,
    } as unknown as WheelEvent);
    expect(camera.state().tz).toBeGreaterThan(tzBefore);
    fixture.destroy();
  });

  it('double-click on empty space drives camera.jumpTo (zooms in by ~2.2)', () => {
    const fixture = create();
    const c = fixture.componentInstance;
    const tzBefore = camera.state().tz;
    c.onDblClick({
      clientX: 10,
      clientY: 10,
    } as unknown as MouseEvent);
    // no stars loaded -> empty-space dive just bumps zoom toward the cursor
    expect(camera.state().tz).toBeGreaterThan(tzBefore);
    fixture.destroy();
  });

  it('double-click near a known star dives to it at tz >= 9 (PoC dive)', () => {
    const fixture = create();
    const c = fixture.componentInstance;
    // seed the resolved scene with a star so the dive path triggers
    (c as unknown as { sceneStars: unknown[] }).sceneStars = [
      { id: 1, x: 5, y: -5, k: 4, b: 0.5, sz: 1, g: 0, activeSystemId: 1 },
    ];
    c.onDblClick({ clientX: 10, clientY: 10 } as unknown as MouseEvent);
    const s = camera.state();
    expect(s.tz).toBeGreaterThanOrEqual(9);
    expect(s.tx).toBe(5);
    expect(s.ty).toBe(-5);
    fixture.destroy();
  });
});
