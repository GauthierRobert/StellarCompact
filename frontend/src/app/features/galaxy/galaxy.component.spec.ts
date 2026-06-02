import { TestBed } from '@angular/core/testing';
import { provideZonelessChangeDetection } from '@angular/core';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { GalaxyComponent } from './galaxy.component';
import { CameraStore, FactionStore, OverlayStore } from '../../stores';
import { TileManager } from './tile-manager';
import type { RenderScene } from './render-model';

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

  it('overlay-only change updates overlay render data WITHOUT a new tile fetch (E8-06)', () => {
    const overlay = TestBed.inject(OverlayStore);
    const factions = TestBed.inject(FactionStore);
    const manager = TestBed.inject(TileManager);
    const assembleSpy = vi
      .spyOn(manager, 'assemble')
      .mockResolvedValue({
        stars: [],
        aggregates: [],
        transition: {
          primary: 3,
          secondary: null,
          primaryWeight: 1,
          secondaryWeight: 0,
        },
      });

    const fixture = create();
    const c = fixture.componentInstance as unknown as {
      sceneStars: unknown[];
      buildScene: () => RenderScene;
    };
    // a visible promoted star at system id 7
    c.sceneStars = [
      { id: 1, x: 5, y: -5, k: 4, b: 0.5, sz: 1, g: 0, activeSystemId: 7 },
    ];
    factions.applyTick({
      tick: 1,
      factions: [
        {
          factionId: 'f1',
          name: 'F1',
          colour: '#4ad6a0',
          resources: { credits: 0, minerals: 0, influence: 0 },
          reputation: 0,
          systemCount: 1,
          eliminated: false,
        },
      ],
    });

    const fetchesAfterCreate = assembleSpy.mock.calls.length;

    // an overlay-only change (no tile-set change)
    overlay.applyOverlayDelta({
      changedSystems: [
        {
          systemId: '7',
          ownerFactionId: 'f1',
          activityLevel: 2,
          blockaded: false,
          battle: true,
          asOfTick: 2,
        },
      ],
      changedRoutes: [],
      asOfTick: 2,
    });

    // building the scene reflects the live overlay...
    const scene = c.buildScene();
    expect(scene.overlayMarks!.length).toBe(1);
    const mark = scene.overlayMarks![0];
    // join-by-system-id: mark sits at system 7's star world position
    expect(mark.x).toBe(5);
    expect(mark.y).toBe(-5);
    expect(mark.battle).toBe(true);
    expect(mark.tint).not.toBeNull();

    // ...and NO new tile fetch was triggered by the overlay change.
    expect(assembleSpy.mock.calls.length).toBe(fetchesAfterCreate);
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
