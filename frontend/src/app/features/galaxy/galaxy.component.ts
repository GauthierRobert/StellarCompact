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
  CAMERA_ZMAX,
  FactionStore,
  OverlayStore,
  SelectionStore,
  type SelectedSystemInfo,
  type SystemOverlay,
  type ZoomAnchor,
} from '../../stores';
import { ViewSettingsStore } from '../../stores/view-settings.store';
import { DemoModeService } from '../../services/demo-mode.service';
import { lodTransition } from './tile.service';
import { TileManager, type LayeredScene } from './tile-manager';
import { CanvasDrawLayer, proceduralPlanets } from './canvas-draw-layer';
import { WebglDrawLayer } from './webgl-draw-layer';
import {
  buildFogReveals,
  buildOverlayMarks,
  buildOverlayRoutes,
  buildTerritories,
  hexToRgb01,
  indexStarsBySystemId,
} from './overlay-layer';
import {
  toRenderObject,
  toSelectedObject,
} from './interstellar-objects';
import {
  buildVisibleSectors,
  sectorBounds,
  sectorColRow,
  sectorDesignation,
  sectorId,
  sectorName,
  type SectorNode,
} from './sectors';
import {
  SPECTRAL_ORDER,
  type GalaxyDrawLayer,
  type RenderAggregate,
  type RenderFog,
  type RenderObject,
  type RenderOverlayMark,
  type RenderRoute,
  type RenderScene,
  type RenderSectors,
  type RenderStar,
  type RenderTerritory,
  type ViewTransform,
} from './render-model';
import type {
  SelectedObjectInfo,
  SelectedSectorInfo,
} from '../../stores/selection.store';

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
    @if (hoveredSectorLabel(); as lbl) {
      <div
        class="sector-tooltip"
        [style.left.px]="lbl.x"
        [style.top.px]="lbl.y"
      >
        <span class="sector-tooltip-name">{{ lbl.name }}</span>
        <span class="sector-tooltip-desig">{{ lbl.desig }}</span>
      </div>
    }
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
      .sector-tooltip {
        position: absolute;
        z-index: 5;
        pointer-events: none;
        display: flex;
        flex-direction: column;
        gap: 1px;
        padding: 4px 8px;
        background: rgba(4, 8, 16, 0.82);
        border: 1px solid rgba(96, 170, 255, 0.35);
        border-radius: 4px;
        backdrop-filter: blur(4px);
        -webkit-backdrop-filter: blur(4px);
        transform: translateZ(0);
        white-space: nowrap;
      }
      .sector-tooltip-name {
        font: 700 11px/1.1 'Courier New', monospace;
        letter-spacing: 0.5px;
        color: #d6e8ff;
      }
      .sector-tooltip-desig {
        font: 600 9px/1 'Courier New', monospace;
        letter-spacing: 1.5px;
        text-transform: uppercase;
        color: rgba(120, 170, 235, 0.7);
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
  private readonly settings = inject(ViewSettingsStore);
  private readonly selection = inject(SelectionStore);
  private readonly demo = inject(DemoModeService);
  private readonly tileManager = inject(TileManager);
  private readonly zone = inject(NgZone);

  private draw: GalaxyDrawLayer | null = null;
  /** True when no draw backend (WebGL2 nor Canvas2D) could be created. */
  readonly unsupported = signal(false);
  /** Which backend is active — exposed for diagnostics/tests. */
  readonly backend = signal<'webgl2' | 'canvas2d' | 'none'>('none');
  /**
   * The hovered sector's name + atlas designation + cursor-anchored screen
   * position, shown as a floating label while the sector grid is on. Null when
   * not hovering a sector (or the grid is off). Backend-agnostic (works for both
   * the WebGL and Canvas renderers) since it is a DOM overlay.
   */
  readonly hoveredSectorLabel = signal<{
    name: string;
    desig: string;
    x: number;
    y: number;
  } | null>(null);
  private rafId = 0;
  private lastFrame = 0;
  private running = false;

  // pointer drag state
  private dragging = false;
  private lastX = 0;
  private lastY = 0;
  private lastT = 0;
  // click vs drag discrimination (for system selection on a tap)
  private downX = 0;
  private downY = 0;
  private downT = 0;
  private moved = 0;

  // pending zoom anchor (consumed by stepFrame while easing)
  private anchor: ZoomAnchor | null = null;

  // resolved render-input from the most recent tile fetch
  private sceneStars: readonly RenderStar[] = [];
  private sceneAggregates: readonly RenderAggregate[] = [];

  // sector grid hover (only tracked while the grid overlay is enabled)
  private hoveredSectorId: string | null = null;
  // last pointer position (CSS px, canvas-relative) for hover hit-testing
  private hoverX = 0;
  private hoverY = 0;

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
    this.downX = e.clientX;
    this.downY = e.clientY;
    this.downT = e.timeStamp;
    this.moved = 0;
    this.camera.resetMomentum();
    (e.target as HTMLElement).setPointerCapture?.(e.pointerId);
  }

  onPointerMove(e: PointerEvent): void {
    // Track the pointer for sector-hover highlighting even when not dragging,
    // but only do the work while the sector grid overlay is actually enabled.
    const rect = this.canvasRef().nativeElement.getBoundingClientRect();
    this.hoverX = e.clientX - rect.left;
    this.hoverY = e.clientY - rect.top;
    if (!this.dragging) {
      if (this.settings.showSectors()) {
        this.updateHoveredSector();
      } else if (this.hoveredSectorLabel() !== null) {
        this.hoveredSectorId = null;
        this.hoveredSectorLabel.set(null);
      }
      return;
    }
    // While dragging, suppress the hover label (panning, not inspecting).
    if (this.hoveredSectorLabel() !== null) {
      this.hoveredSectorLabel.set(null);
    }
    const dt = Math.max(1, e.timeStamp - this.lastT) / 1000;
    const dx = e.clientX - this.lastX;
    const dy = e.clientY - this.lastY;
    this.moved += Math.abs(dx) + Math.abs(dy);
    this.camera.applyPanDelta(dx, dy, dt);
    this.lastX = e.clientX;
    this.lastY = e.clientY;
    this.lastT = e.timeStamp;
  }

  onPointerUp(e: PointerEvent): void {
    this.dragging = false;
    (e.target as HTMLElement).releasePointerCapture?.(e.pointerId);
    // A short, low-movement press is a tap → select the system under it.
    const heldMs = e.timeStamp - this.downT;
    if (this.moved < 6 && heldMs < 400) {
      this.handleSelectTap(e);
    }
  }

  /**
   * Tap-to-select: pick the nearest active (promoted) system within a small
   * screen radius and push it to the SelectionStore so the detail panel opens.
   * Tapping empty space clears the selection. Fog-correct: only systems present
   * in the (server/demo-fed) overlay are selectable.
   */
  private handleSelectTap(e: PointerEvent): void {
    const rect = this.canvasRef().nativeElement.getBoundingClientRect();
    const sx = e.clientX - rect.left;
    const sy = e.clientY - rect.top;

    // Priority 1: a discrete interstellar object (nebula, black hole, …).
    const obj = this.hitObject(sx, sy);
    if (obj) {
      this.selection.selectObject(obj);
      return;
    }

    // Priority 2: an active (promoted) star → system detail panel.
    const w2s = this.camera.w2s();
    let best: RenderStar | null = null;
    let bestD = 26 * 26; // screen px hit radius (squared)
    for (const st of this.sceneStars) {
      if (st.activeSystemId === null) {
        continue;
      }
      const px = w2s.x(st.x);
      const py = w2s.y(st.y);
      const d = (px - sx) ** 2 + (py - sy) ** 2;
      if (d < bestD) {
        bestD = d;
        best = st;
      }
    }
    if (best) {
      this.selection.selectSystem(this.toSelectedSystem(best));
      return;
    }

    // Priority 3: when the sector grid is on, an empty tap selects the sector.
    if (this.settings.showSectors()) {
      const sector = this.hitSector(sx, sy);
      if (sector) {
        this.selection.selectSector(sector);
        return;
      }
    }

    // Otherwise clear the selection.
    this.selection.clearSelection();
  }

  /**
   * Hit-test the interstellar objects under a screen point (CSS px). Returns the
   * nearest object whose drawn disk contains the point, as a ready-to-display
   * {@link SelectedObjectInfo}, or null. Objects are large landmarks so the hit
   * radius is their world radius (with a small floor for the compact kinds).
   */
  private hitObject(sx: number, sy: number): SelectedObjectInfo | null {
    const objects = this.demo.objects();
    if (objects.length === 0) {
      return null;
    }
    const w2s = this.camera.w2s();
    const scale = this.camera.scale();
    let best: (typeof objects)[number] | null = null;
    let bestD = Infinity;
    for (const o of objects) {
      const px = w2s.x(o.x);
      const py = w2s.y(o.y);
      const hitR = Math.max(16, o.r * scale * 0.85);
      const d = (px - sx) ** 2 + (py - sy) ** 2;
      if (d <= hitR * hitR && d < bestD) {
        bestD = d;
        best = o;
      }
    }
    return best ? toSelectedObject(best) : null;
  }

  /**
   * Build a summary of the sector under a screen point (CSS px): how many active
   * systems + interstellar objects fall inside it, the per-faction control
   * breakdown, and a sample of named systems. Bounded by the in-sector entities.
   */
  private hitSector(sx: number, sy: number): SelectedSectorInfo | null {
    const s2w = this.camera.s2w();
    const wx = s2w.x(sx);
    const wy = s2w.y(sy);
    const rMax = this.rMax();
    const cr = sectorColRow(wx, wy, rMax);
    if (!cr) {
      return null;
    }
    const { cx, cy, half } = sectorBounds(cr.col, cr.row, rMax);
    const inside = (x: number, y: number) =>
      x >= cx - half && x < cx + half && y >= cy - half && y < cy + half;

    const ownerCount = new Map<string, number>();
    const systemNames: string[] = [];
    for (const sys of this.demo.worldSystems()) {
      if (!inside(sys.x, sys.y)) {
        continue;
      }
      systemNames.push(sys.name);
      const owner = this.overlay.getSystem(String(sys.id))?.ownerFactionId ?? null;
      if (owner) {
        ownerCount.set(owner, (ownerCount.get(owner) ?? 0) + 1);
      }
    }
    let objectCount = 0;
    for (const o of this.demo.objects()) {
      if (inside(o.x, o.y)) {
        objectCount++;
      }
    }
    const owners = [...ownerCount.entries()]
      .map(([factionId, count]) => ({ factionId, count }))
      .sort((a, b) => b.count - a.count);
    return {
      sectorId: sectorId(cr.col, cr.row),
      name: `${sectorName(cr.col, cr.row)} (${sectorDesignation(cr.col, cr.row)})`,
      systemCount: systemNames.length,
      objectCount,
      owners,
      systems: systemNames.slice(0, 8),
    };
  }

  /** Recompute which sector the pointer is over (for the hover highlight). */
  private updateHoveredSector(): void {
    const s2w = this.camera.s2w();
    const wx = s2w.x(this.hoverX);
    const wy = s2w.y(this.hoverY);
    const cr = sectorColRow(wx, wy, this.rMax());
    this.hoveredSectorId = cr ? sectorId(cr.col, cr.row) : null;
    if (cr) {
      this.hoveredSectorLabel.set({
        name: sectorName(cr.col, cr.row),
        desig: sectorDesignation(cr.col, cr.row),
        x: this.hoverX + 14,
        y: this.hoverY + 14,
      });
    } else if (this.hoveredSectorLabel() !== null) {
      this.hoveredSectorLabel.set(null);
    }
  }

  /** Compose a SelectedSystemInfo for a tapped active star (display-only). */
  private toSelectedSystem(star: RenderStar): SelectedSystemInfo {
    const idStr = String(star.activeSystemId);
    const ov = this.overlay.getSystem(idStr);
    const demoSys = this.demo.systemById(idStr);
    const name = demoSys?.name ?? `System ${idStr}`;
    const planets = proceduralPlanets(star).map((_, i) => ({
      planetId: `${idStr}-${i}`,
      name: `${name} ${ROMAN[i] ?? i + 1}`,
      biome: BIOMES[(star.id + i) % BIOMES.length],
      slotCount: 3 + (i % 3),
      usedSlots: ov?.ownerFactionId ? 1 + (i % 2) : 0,
    }));
    return {
      systemId: idStr,
      name,
      ownerFactionId: ov?.ownerFactionId ?? null,
      starClass: SPECTRAL_ORDER[star.k] ?? 'G',
      planets,
      blockaded: ov?.blockaded ?? false,
      battle: ov?.battle ?? false,
    };
  }

  onWheel(e: WheelEvent): void {
    e.preventDefault();
    const rect = this.canvasRef().nativeElement.getBoundingClientRect();
    const sx = e.clientX - rect.left;
    const sy = e.clientY - rect.top;
    // Normalise the wheel delta across devices so each event applies a
    // consistent, BOUNDED zoom step. Raw deltaY magnitude varies enormously —
    // a mouse notch is ~100-120px, a precision touchpad ~1-10px, but high-res
    // mice / OS smooth-scroll can spike a single event to 300-500px. Feeding
    // that straight into the zoom (old behaviour) made zoom lurch in huge,
    // inconsistent jumps. Convert to pixels by deltaMode, scale, then CLAMP so
    // no single event can jump more than ~one notch.
    const px =
      e.deltaMode === 1
        ? e.deltaY * 16 // lines → approx px
        : e.deltaMode === 2
          ? e.deltaY * 120 // pages → approx px
          : e.deltaY; // already pixels
    const dz = Math.max(-0.26, Math.min(0.26, -px * 0.0018));
    // Cancel any residual pan-fling momentum so the view doesn't keep sliding
    // while zooming (that drift reads as "strange" zoom).
    this.camera.resetMomentum();
    this.anchor = this.camera.zoomTo(sx, sy, dz);
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
    // Double-click always dives IN toward the target — never out. The old
    // `min(tz+2.2, 9)` then `max(_, 9)` collapsed to EXACTLY z=9, so double-
    // clicking while already deeper than 9 zoomed you back OUT to 9 (the strange
    // behaviour). Now: step in by +2.4, with a floor of 9 when diving onto a star
    // (the PoC system-dive depth) that only ever raises the target, so a deeper
    // current zoom keeps zooming in instead of snapping out.
    const cur = this.camera.state().tz;
    const target = best ? Math.max(cur + 2.4, 9) : cur + 2.4;
    const tz = Math.min(CAMERA_ZMAX, target);
    this.camera.jumpTo(best ? best.x : wx, best ? best.y : wy, tz);
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
    // Drop a lingering sector hover label if the grid was just toggled off (it
    // is otherwise only refreshed on pointer move). Cheap: a no-op once cleared.
    if (!this.settings.showSectors() && this.hoveredSectorLabel() !== null) {
      this.hoveredSectorId = null;
      this.hoveredSectorLabel.set(null);
    }
    const overlay = this.buildOverlay();
    return {
      stars: this.sceneStars,
      aggregates: this.sceneAggregates,
      routes: overlay.routes,
      overlayMarks: overlay.marks,
      territories: overlay.territories,
      fog: overlay.fog,
      objects: this.buildObjects(),
      sectors: this.buildSectors(),
      rMax: this.rMax(),
    };
  }

  /**
   * Map the (demo) interstellar-object roster to {@link RenderObject}s, culled to
   * the visible bbox (padded by each object's extent) and flagged with the
   * current selection so the renderer can draw a focus ring. Gated by the
   * Objects view toggle. A live backend would feed these through the tile path.
   */
  private buildObjects(): readonly RenderObject[] {
    if (!this.settings.showObjects()) {
      return [];
    }
    const objects = this.demo.objects();
    if (objects.length === 0) {
      return [];
    }
    // Zoom gate (galaxy-rendering principle #1: detail is a function of zoom).
    // Interstellar objects are *landmarks*, not galaxy-scale structure — drawing
    // them at full-galaxy zoom is the "petri dish" failure (giant nebulae/rings
    // dwarfing the disc). Fade them in only once you have zoomed toward them, on
    // the same regime where individual systems start to resolve. zoomFactor is
    // relative to fit (1 = whole galaxy on screen); fade 0→1 across 3×..8×.
    const zf = this.camera.zoomFactor();
    const t = Math.min(1, Math.max(0, (zf - 3) / (8 - 3)));
    const objAlpha = t * t * (3 - 2 * t); // smoothstep(3, 8, zoomFactor)
    if (objAlpha <= 0.001) {
      return [];
    }
    const bbox = this.camera.visibleBbox();
    const selectedId = this.selection.selectedObject()?.objectId ?? null;
    const out: RenderObject[] = [];
    for (const o of objects) {
      if (
        o.x + o.r < bbox.minX ||
        o.x - o.r > bbox.maxX ||
        o.y + o.r < bbox.minY ||
        o.y - o.r > bbox.maxY
      ) {
        continue;
      }
      out.push({ ...toRenderObject(o, selectedId), a: objAlpha });
    }
    return out;
  }

  /**
   * Assemble the sector-grid overlay: the in-view cells with their dominant-owner
   * tint, plus hover/selection flags. Owner tint is joined from the (fog-filtered)
   * overlay store to the systems' world positions. Gated by the Sectors toggle.
   */
  private buildSectors(): RenderSectors {
    if (!this.settings.showSectors()) {
      return { enabled: false, cells: [] };
    }
    const rMax = this.rMax();
    const bbox = this.camera.visibleBbox();
    const nodes: SectorNode[] = [];
    for (const sys of this.demo.worldSystems()) {
      const owner = this.overlay.getSystem(String(sys.id))?.ownerFactionId ?? null;
      nodes.push({
        x: sys.x,
        y: sys.y,
        ownerFactionId: owner,
        tint: owner ? hexToRgb01(this.factions.getById(owner)?.colour) : null,
      });
    }
    const selectedId = this.selection.selectedSector()?.sectorId ?? null;
    const cells = buildVisibleSectors(
      bbox,
      rMax,
      nodes,
      selectedId,
      this.hoveredSectorId,
    );
    return { enabled: true, cells };
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
    territories: readonly RenderTerritory[];
    fog: RenderFog;
  } {
    const fogEnabled = this.settings.fogOfWar();
    const systems = this.overlay.allSystems();
    const routes = this.overlay.activeRoutes();
    // Camera world centre — the fog reveal helper anchors a local disk here when
    // camRevealRadius > 0; we pass 0 so only known systems are revealed.
    const camState = this.camera.state();
    const camWorld = { x: camState.x, y: camState.y };

    if (systems.length === 0 && routes.length === 0) {
      // No overlay state in view: still honour the fog veil (everything hidden
      // except — with camRevealRadius 0 — nothing), so unexplored space darkens.
      return {
        marks: [],
        routes: [],
        territories: [],
        fog: { enabled: fogEnabled, reveals: [] },
      };
    }
    const byId = indexStarsBySystemId(this.sceneStars);
    const colourOf = (id: string) => this.factions.getById(id)?.colour;
    return {
      marks: this.settings.showConflict()
        ? buildOverlayMarks(systems, byId, colourOf)
        : this.buildOwnershipOnlyMarks(systems, byId, colourOf),
      routes: this.settings.showRoutes()
        ? buildOverlayRoutes(routes, byId)
        : [],
      territories: this.settings.showTerritories()
        ? buildTerritories(systems, byId, colourOf)
        : [],
      fog: {
        enabled: fogEnabled,
        reveals: fogEnabled
          ? buildFogReveals(systems, byId, camWorld, 0)
          : [],
      },
    };
  }

  /**
   * Ownership-tint marks only (no battle/blockade) — used when the conflict
   * overlay is toggled off so empire tints still read but combat markers do not.
   */
  private buildOwnershipOnlyMarks(
    systems: readonly SystemOverlay[],
    byId: ReadonlyMap<string, RenderStar>,
    colourOf: (id: string) => string | undefined,
  ): readonly RenderOverlayMark[] {
    return buildOverlayMarks(systems, byId, colourOf).map((m) => ({
      ...m,
      battle: false,
      blockaded: false,
    }));
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

/** Roman numerals for synthesised planet names (display-only). */
const ROMAN = ['I', 'II', 'III', 'IV', 'V', 'VI', 'VII', 'VIII'];
/** Biome labels for the selection panel (display-only). */
const BIOMES = ['Ocean', 'Arid', 'Verdant', 'Tundra', 'Volcanic', 'Gas Giant', 'Barren'];
