import {
  AfterViewInit,
  ChangeDetectionStrategy,
  Component,
  ElementRef,
  NgZone,
  OnDestroy,
  inject,
  input,
  viewChild,
} from '@angular/core';
import { CameraStore, OverlayStore, type ZoomAnchor } from '../../stores';
import {
  TileService,
  toRenderAggregates,
  toRenderStar,
  type TilePayloadDto,
} from './tile.service';
import { CanvasDrawLayer } from './canvas-draw-layer';
import {
  type GalaxyDrawLayer,
  type RenderAggregate,
  type RenderRoute,
  type RenderScene,
  type RenderStar,
  type ViewTransform,
} from './render-model';

/**
 * Galaxy view — the small-galaxy-tier canvas renderer (E7-02).
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
 *     GalaxyDrawLayer (Canvas2D today, WebGL2 in E8 with zero changes here).
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
  ></canvas>`,
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
  private readonly tiles = inject(TileService);
  private readonly zone = inject(NgZone);

  private draw: GalaxyDrawLayer | null = null;
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
    this.draw = new CanvasDrawLayer(canvas);
    this.camera.setRMax(this.rMax());
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
    // coarse fetch key: rounded level + tile-grid cell of the bbox centre, so we
    // only refetch when the user crosses a tile/level boundary, not every frame.
    const level = Math.round(levelF + 3);
    const key =
      level +
      ':' +
      Math.round((bbox.minX + bbox.maxX) / 2 / Math.max(1, rMax) * 16) +
      ':' +
      Math.round((bbox.minY + bbox.maxY) / 2 / Math.max(1, rMax) * 16);
    if (key === this.lastFetchKey || this.fetchInFlight) {
      return;
    }
    this.lastFetchKey = key;
    this.fetchInFlight = true;
    this.tiles
      .fetchVisible(this.seed(), bbox, levelF, rMax)
      .then((payloads) => this.ingestTiles(payloads))
      .catch(() => {
        // swallow: a fetch failure leaves the previous scene in place
      })
      .finally(() => {
        this.fetchInFlight = false;
      });
  }

  private ingestTiles(payloads: readonly TilePayloadDto[]): void {
    const stars: RenderStar[] = [];
    const aggregates: RenderAggregate[] = [];
    for (const t of payloads) {
      if (t.kind === 'starlist') {
        for (const s of t.stars) {
          stars.push(toRenderStar(s));
        }
      } else {
        aggregates.push(...toRenderAggregates(t));
      }
    }
    this.sceneStars = stars;
    this.sceneAggregates = aggregates;
  }

  // --- render-input assembly (decoupled from the draw backend) ---

  private buildScene(): RenderScene {
    return {
      stars: this.sceneStars,
      aggregates: this.sceneAggregates,
      routes: this.buildRoutes(),
      rMax: this.rMax(),
    };
  }

  /**
   * Map the overlay store's active trade lanes into RenderRoute segments,
   * resolving each endpoint to its star's world position from the visible set.
   * Routes whose endpoints are not in view are skipped (bounded work).
   */
  private buildRoutes(): RenderRoute[] {
    const active = this.overlay.activeRoutes();
    if (active.length === 0) {
      return [];
    }
    const pos = new Map<string, RenderStar>();
    for (const s of this.sceneStars) {
      if (s.activeSystemId !== null) {
        pos.set(String(s.activeSystemId), s);
      }
    }
    const out: RenderRoute[] = [];
    for (const r of active) {
      const a = pos.get(r.fromSystemId);
      const b = pos.get(r.toSystemId);
      if (!a || !b) {
        continue;
      }
      out.push({
        id: r.routeId,
        ax: a.x,
        ay: a.y,
        bx: b.x,
        by: b.y,
        kind: r.kind,
        len: Math.hypot(a.x - b.x, a.y - b.y),
      });
    }
    return out;
  }

  private buildTransform(): ViewTransform {
    const scale = this.camera.scale();
    const { dpr, cssWidth, cssHeight } = this.camera.viewport();
    const w2s = this.camera.w2s();
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
    };
  }
}
