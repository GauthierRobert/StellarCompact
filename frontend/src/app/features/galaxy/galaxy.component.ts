import {
  AfterViewInit,
  ChangeDetectionStrategy,
  Component,
  ElementRef,
  NgZone,
  OnDestroy,
  inject,
  input,
  signal,
  viewChild,
} from '@angular/core';
import {
  CameraStore,
  FactionStore,
  OverlayStore,
  type ZoomAnchor,
} from '../../stores';
import { lodTransition } from './tile.service';
import { TileManager, type LayeredScene } from './tile-manager';
import { CanvasDrawLayer } from './canvas-draw-layer';
import { WebglDrawLayer } from './webgl-draw-layer';
import {
  buildOverlayMarks,
  buildOverlayRoutes,
  indexStarsBySystemId,
} from './overlay-layer';
import {
  type GalaxyDrawLayer,
  type RenderAggregate,
  type RenderOverlayMark,
  type RenderRoute,
  type RenderScene,
  type RenderStar,
  type ViewTransform,
} from './render-model';

/**
 * Galaxy view — WebGL2 instanced renderer (E8-01), Canvas2D fallback (E7-02).
 *
 * Responsibilities (all the framework-touching glue; NO drawing maths live here):
 *   - own the <canvas>, drive a requestAnimationFrame loop OUTSIDE Angular's
 *     change detection (zoneless: NgZone.runOutsideAngular) reading signals
 *     directly each frame;
 *   - translate pointer drag -> camera.applyPanDelta, wheel -> camera.zoomTo,
 *     dblclick -> camera.jumpTo, resize/dpr -> camera.setViewport;
 *   - each frame call camera.stepFrame(dt, anchor) then build a ViewTransform
 *     from the store's w2s + scale;
 *   - fetch LOD tiles via TileService keyed by camera.visibleBbox + levelF, map
 *     them into the decoupled RenderScene, and hand scene+transform to a
 *     GalaxyDrawLayer.
 *
 * Draw backend selection: prefer the WebGL2 instanced point-sprite layer (one
 * draw call for the whole visible star field, 10^5-10^6 instances); fall back to
 * the Canvas2D layer when a WebGL2 context cannot be created. Both implement the
 * SAME GalaxyDrawLayer contract, so the component code below is identical for
 * either backend. If neither can render, a fallback message is shown.
 *
 * The camera store is the single source of truth for pan/zoom; this component
 * only feeds it input events and reads its computed transforms.
 */
@Component({
  selector: 'app-galaxy',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `<canvas
      #canvas
      class="galaxy-canvas"
      (pointerdown)="onPointerDown($event)"
      (pointermove)="onPointerMove($event)"
      (pointerup)="onPointerUp($event)"
      (pointercancel)="onPointerUp($event)"
      (wheel)="onWheel($event)"
      (dblclick)="onDblClick($event)"
    ></canvas>
    @if (unsupported()) {
      <div class="galaxy-fallback" role="alert">
        Galaxy view unavailable: this browser cannot create a WebGL2 or 2D
        canvas context.
      </div>
    }`,
  styles: [
    `
      :host {
        display: block;
        position: absolute;
        inset: 0;
        background: #000308;
      }
      .galaxy-canvas {
        display: block;
        width: 100%;
        height: 100%;
        touch-action: none;
        cursor: grab;
      }
      .galaxy-canvas:active {
        cursor: grabbing;
      }
      .galaxy-fallback {
        position: absolute;
        inset: 0;
        display: flex;
        align-items: center;
        justify-content: center;
        padding: 2rem;
        text-align: center;
        color: #aab4d6;
        font: 14px/1.5 system-ui, sans-serif;
        pointer-events: none;
      }
    `,
  ],
})
export class GalaxyComponent implements AfterViewInit, OnDestroy {
  /** Galaxy seed for the tile endpoint. Defaults to the PoC seed. */
  readonly seed = input<string>('1');
  /** Galaxy radius in world units (matches backend GalaxyConstants.R_MAX). */
  readonly rMax = input<number>(1000);

  private readonly canvasRef =
    viewChild.required<ElementRef<HTMLCanvasElement>>('canvas');

  private readonly camera = inject(CameraStore);
  private readonly overlay = inject(OverlayStore);
  private readonly factions = inject(FactionStore);
  private readonly tileManager = inject(TileManager);
  private readonly zone = inject(NgZone);

  private draw: GalaxyDrawLayer | null = null;
  /** True when no draw backend (WebGL2 nor Canvas2D) could be created. */
  readonly unsupported = signal(false);
  /** Which backend is active — exposed for diagnostics/tests. */
  readonly backend = signal<'webgl2' | 'canvas2d' | 'none'>('none');
  private rafId = 0;
  private lastFrame = 0;
  private running = false;

  // pointer drag state
  private dragging = false;
  private lastX = 0;
  private lastY = 0;
  private lastT = 0;

  // pending zoom anchor (consumed by stepFrame while easing)
  private anchor: ZoomAnchor | null = null;

  // resolved render-input from the most recent tile fetch
  private sceneStars: readonly RenderStar[] = [];
  private sceneAggregates: readonly RenderAggregate[] = [];

  // tile-fetch throttling: refetch only when the visible address set changes
  private lastFetchKey = '';
  private fetchInFlight = false;

  private resizeObserver: ResizeObserver | null = null;

  ngAfterViewInit(): void {
    const canvas = this.canvasRef().nativeElement;
    // Camera wiring is independent of the draw backend; set it up first so the
    // store is correct even when no renderer is available.
    this.camera.setRMax(this.rMax());

    this.draw = this.createDrawLayer(canvas);
    if (!this.draw) {
      this.unsupported.set(true);
      this.backend.set('none');
      return;
    }
    this.syncViewport();

    // Observe element size for responsive resize (zoneless-friendly).
    if (typeof ResizeObserver !== 'undefined') {
      this.resizeObserver = new ResizeObserver(() => this.syncViewport());
      this.resizeObserver.observe(canvas);
    }

    // RAF loop runs outside Angular so it never triggers change detection.
    this.zone.runOutsideAngular(() => {
      this.running = true;
      this.lastFrame =
        typeof performance !== 'undefined' ? performance.now() : 0;
      this.scheduleFrame();
    });
  }

  /**
   * Select a draw backend: prefer the WebGL2 instanced layer, fall back to
   * Canvas2D, and return null if neither can be created (caller shows the
   * fallback message). Both honour the same GalaxyDrawLayer contract.
   */
  private createDrawLayer(canvas: HTMLCanvasElement): GalaxyDrawLayer | null {
    try {
      const layer = new WebglDrawLayer(canvas);
      this.backend.set('webgl2');
      return layer;
    } catch {
      // WebGL2 context creation/compile failed — degrade gracefully.
    }
    const ctx = canvas.getContext('2d');
    if (ctx) {
      this.backend.set('canvas2d');
      return new CanvasDrawLayer(canvas);
    }
    return null;
  }

  ngOnDestroy(): void {
    this.running = false;
    if (this.rafId && typeof cancelAnimationFrame !== 'undefined') {
      cancelAnimationFrame(this.rafId);
    }
    this.resizeObserver?.disconnect();
    this.draw?.dispose();
    this.draw = null;
  }

  // --- viewport / dpr ---

  private syncViewport(): void {
    const canvas = this.canvasRef().nativeElement;
    const dpr = Math.min(
      typeof devicePixelRatio !== 'undefined' ? devicePixelRatio : 1,
      2,
    );
    const cssWidth = canvas.clientWidth || canvas.width || 800;
    const cssHeight = canvas.clientHeight || canvas.height || 600;
    this.camera.setViewport({ cssWidth, cssHeight, dpr });
    const widthPx = Math.round(cssWidth * dpr);
    const heightPx = Math.round(cssHeight * dpr);
    canvas.width = widthPx;
    canvas.height = heightPx;
    this.draw?.resize(widthPx, heightPx, dpr);
  }

  // --- pointer / wheel input -> camera store mutators ---

  onPointerDown(e: PointerEvent): void {
    this.dragging = true;
    this.lastX = e.clientX;
    this.lastY = e.clientY;
    this.lastT = e.timeStamp;
    this.camera.resetMomentum();
    (e.target as HTMLElement).setPointerCapture?.(e.pointerId);
  }

  onPointerMove(e: PointerEvent): void {
    if (!this.dragging) {
      return;
    }
    const dt = Math.max(1, e.timeStamp - this.lastT) / 1000;
    const dx = e.clientX - this.lastX;
    const dy = e.clientY - this.lastY;
    this.camera.applyPanDelta(dx, dy, dt);
    this.lastX = e.clientX;
    this.lastY = e.clientY;
    this.lastT = e.timeStamp;
  }

  onPointerUp(e: PointerEvent): void {
    this.dragging = false;
    (e.target as HTMLElement).releasePointerCapture?.(e.pointerId);
  }

  onWheel(e: WheelEvent): void {
    e.preventDefault();
    const m = e.deltaMode === 1 ? 16 : 1;
    const rect = this.canvasRef().nativeElement.getBoundingClientRect();
    const sx = e.clientX - rect.left;
    const sy = e.clientY - rect.top;
    this.anchor = this.camera.zoomTo(sx, sy, -e.deltaY * 0.0016 * m);
  }

  onDblClick(e: WheelEvent | MouseEvent): void {
    const rect = this.canvasRef().nativeElement.getBoundingClientRect();
    const s2w = this.camera.s2w();
    const wx = s2w.x(e.clientX - rect.left);
    const wy = s2w.y(e.clientY - rect.top);
    // dive toward the nearest known star (PoC dblclick behaviour)
    let best: RenderStar | null = null;
    let bd = Infinity;
    for (const st of this.sceneStars) {
      const d = (st.x - wx) ** 2 + (st.y - wy) ** 2;
      if (d < bd) {
        bd = d;
        best = st;
      }
    }
    const tz = Math.min(this.camera.state().tz + 2.2, 9);
    if (best) {
      this.camera.jumpTo(best.x, best.y, Math.max(tz, 9));
    } else {
      this.camera.jumpTo(wx, wy, tz);
    }
    this.anchor = null;
  }

  // --- RAF loop ---

  private scheduleFrame(): void {
    if (!this.running || typeof requestAnimationFrame === 'undefined') {
      return;
    }
    this.rafId = requestAnimationFrame((t) => this.tick(t));
  }

  private tick(now: number): void {
    const dt = Math.min(0.05, (now - this.lastFrame) / 1000);
    this.lastFrame = now;

    // 1) advance the camera (reads/writes the store; clears anchor once settled)
    this.camera.stepFrame(dt, this.anchor);
    const st = this.camera.state();
    if (Math.abs(st.tz - st.z) <= 1e-5) {
      this.anchor = null;
    }

    // 2) refetch tiles if the visible address set changed (bounded per-frame)
    this.maybeFetchTiles();

    // 3) assemble the decoupled render scene and draw
    const scene = this.buildScene();
    const view = this.buildTransform();
    this.draw?.draw(scene, view, now / 1000);

    this.scheduleFrame();
  }

  // --- tile fetching (LOD by camera levelF + visibleBbox) ---

  private maybeFetchTiles(): void {
    const bbox = this.camera.visibleBbox();
    const levelF = this.camera.levelF();
    const rMax = this.rMax();
    // Refetch/reassemble only when the visible tile set or the cross-fade blend
    // meaningfully changes — never every frame. The key folds in:
    //   - the active level pair + quantised fade weight (so the blend updates as
    //     z eases through a zoom boundary, driving the cross-fade), and
    //   - the tile-grid cell of the bbox centre (so panning across a tile edge
    //     pulls the newly-visible tiles).
    const t = lodTransition(levelF);
    const fadeBucket = Math.round(t.secondaryWeight * 8); // quantised blend step
    const cellInv = 16 / Math.max(1, rMax); // ~16 cells across the galaxy half-span
    const key =
      t.primary +
      ':' +
      (t.secondary ?? -1) +
      ':' +
      fadeBucket +
      ':' +
      Math.round(((bbox.minX + bbox.maxX) / 2) * cellInv) +
      ':' +
      Math.round(((bbox.minY + bbox.maxY) / 2) * cellInv);
    if (key === this.lastFetchKey || this.fetchInFlight) {
      return;
    }
    this.lastFetchKey = key;
    this.fetchInFlight = true;
    this.tileManager
      .assemble(this.seed(), bbox, levelF, rMax)
      .then((layered) => this.ingestLayered(layered))
      .catch(() => {
        // swallow: a fetch failure leaves the previous scene in place
      })
      .finally(() => {
        this.fetchInFlight = false;
      });
  }

  /** Adopt the cross-faded render input assembled by the TileManager. */
  private ingestLayered(layered: LayeredScene): void {
    this.sceneStars = layered.stars;
    this.sceneAggregates = layered.aggregates;
  }

  // --- render-input assembly (decoupled from the draw backend) ---

  /**
   * Assemble the per-frame scene. The star/aggregate field comes from the
   * cacheable tiles (resolved in ingestLayered, refetched only on a tile-set
   * change). The active overlay (ownership tint / fleet+battle / blockade marks
   * + live routes) is rebuilt EVERY frame from the OverlayStore signals joined
   * to the current visible stars by system id — so it tracks live STOMP/REST
   * updates WITHOUT touching the tile cache or triggering any tile refetch (the
   * heavy star tiles stay CDN-cacheable). Fog-correct: only systems present in
   * the server-fed overlay store produce marks; nothing hidden is inferred.
   */
  private buildScene(): RenderScene {
    const overlay = this.buildOverlay();
    return {
      stars: this.sceneStars,
      aggregates: this.sceneAggregates,
      routes: overlay.routes,
      overlayMarks: overlay.marks,
      rMax: this.rMax(),
    };
  }

  /**
   * Join the active overlay to the visible star field by system id (E8-06).
   * Indexes the visible promoted stars once, then asks the pure overlay-layer
   * helpers to produce ownership/fleet/blockade marks and route segments. The
   * faction colour for ownership tint is resolved from the FactionStore.
   */
  private buildOverlay(): {
    marks: readonly RenderOverlayMark[];
    routes: readonly RenderRoute[];
  } {
    const systems = this.overlay.allSystems();
    const routes = this.overlay.activeRoutes();
    if (systems.length === 0 && routes.length === 0) {
      return { marks: [], routes: [] };
    }
    const byId = indexStarsBySystemId(this.sceneStars);
    return {
      marks: buildOverlayMarks(systems, byId, (id) =>
        this.factions.getById(id)?.colour,
      ),
      routes: buildOverlayRoutes(routes, byId),
    };
  }

  private buildTransform(): ViewTransform {
    const scale = this.camera.scale();
    const { dpr, cssWidth, cssHeight } = this.camera.viewport();
    const w2s = this.camera.w2s();
    const origin = this.camera.origin();
    const widthPx = Math.round(cssWidth * dpr);
    const heightPx = Math.round(cssHeight * dpr);
    return {
      scale,
      dpr,
      widthPx,
      heightPx,
      // store's w2s returns CSS px; the draw layer works in device px.
      w2s: (wx: number, wy: number) => ({
        x: w2s.x(wx) * dpr,
        y: w2s.y(wy) * dpr,
      }),
      // Floating-origin anchor (E8-07): the WebGL layer subtracts this from
      // world coords (in float64) before the float32 upload to hold precision
      // at deep zoom. The w2s above is computed on the CPU in float64 and is
      // unaffected — it stays byte-identical across a re-base, so the re-base is
      // visually transparent.
      originX: origin.x,
      originY: origin.y,
    };
  }
}
