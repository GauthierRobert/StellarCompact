import {
  ChangeDetectionStrategy,
  Component,
  ElementRef,
  OnDestroy,
  ViewChild,
  effect,
  inject,
  untracked,
} from '@angular/core';
import { CommonModule } from '@angular/common';
import { CameraStore } from '../../stores/camera.store';
import { OverlayStore } from '../../stores/overlay.store';
import { FactionStore } from '../../stores/faction.store';
import { DemoModeService } from '../../services/demo-mode.service';
import {
  densityAt,
  DENSITY_PEAK,
  CATALOG_CONSTANTS,
} from '../galaxy/catalog-generator';

/** Minimap canvas size in CSS pixels (square). */
const MAP_SIZE = 260;

/**
 * Galaxy minimap — an OGAME-style top-down overview drawn on a small canvas.
 *
 * Draws: (a) faint galaxy disc; (b) active systems as owner-tinted dots;
 * (c) interstellar objects as tiny kind-tinted marks; (d) the current camera
 * viewport as a bright outline so the player sees where they are in the galaxy.
 *
 * Clicking the minimap re-centres the camera at the clicked world position.
 *
 * Redraws reactively via an effect() reading camera.state(), overlay.asOfTick(),
 * and faction.tick() — so any tick or pan/zoom automatically re-paints.
 * Runs OnPush; the effect() drives all updates without Zone.js.
 */
@Component({
  selector: 'app-galaxy-minimap',
  changeDetection: ChangeDetectionStrategy.OnPush,
  standalone: true,
  imports: [CommonModule],
  template: `
    <canvas
      #canvas
      [width]="size"
      [height]="size"
      class="minimap-canvas"
      (click)="onCanvasClick($event)"
      title="Click to jump to location"
    ></canvas>
  `,
  styles: [`
    :host { display: block; }
    .minimap-canvas {
      display: block;
      width: 260px;
      height: 260px;
      cursor: crosshair;
      border-radius: var(--rounded-xs);
    }
  `],
})
export class GalaxyMinimapComponent implements OnDestroy {
  // `static: true` resolves the canvas before the first change detection, so it
  // is available by the time the redraw effect first runs.
  @ViewChild('canvas', { static: true })
  private readonly canvasRef!: ElementRef<HTMLCanvasElement>;

  protected readonly size = MAP_SIZE;

  private readonly camera = inject(CameraStore);
  private readonly overlay = inject(OverlayStore);
  private readonly factions = inject(FactionStore);
  private readonly demo = inject(DemoModeService);

  /** Cached procedural galaxy backdrop (static for a seed); built lazily. */
  private backdrop: HTMLCanvasElement | null = null;

  /**
   * Reactive redraw — created in a field initializer (a valid injection context;
   * effect() must NOT be called from ngAfterViewInit). Reads camera/overlay/
   * faction signals so any pan/zoom or tick re-paints; world data is read inside
   * untracked() to avoid over-subscription. The redraw guards a missing canvas,
   * so the initial run before the view exists is a harmless no-op.
   */
  private readonly effectRef = effect(() => {
    const camState = this.camera.state();
    const bbox = this.camera.visibleBbox();
    this.overlay.asOfTick();
    this.factions.tick();
    untracked(() => this.redraw(camState, bbox));
  });

  ngOnDestroy(): void {
    this.effectRef.destroy();
  }

  /** Convert a canvas-pixel click to world space and re-centre the camera. */
  protected onCanvasClick(event: MouseEvent): void {
    const rect = this.canvasRef.nativeElement.getBoundingClientRect();
    const cx = event.clientX - rect.left;
    const cy = event.clientY - rect.top;
    const { worldX, worldY } = this.canvasToWorld(cx, cy);
    this.camera.jumpTo(worldX, worldY, this.camera.state().tz);
  }

  // ---------------------------------------------------------------------------
  // Drawing
  // ---------------------------------------------------------------------------

  private redraw(
    _camState: ReturnType<typeof this.camera.state>,
    bbox: ReturnType<typeof this.camera.visibleBbox>,
  ): void {
    const canvas = this.canvasRef?.nativeElement;
    if (!canvas) return;
    const ctx = canvas.getContext('2d');
    if (!ctx) return;

    const size = MAP_SIZE;
    const worldSystems = this.demo.worldSystems();

    // Galaxy extent = the catalog radius, so the minimap shows the full spiral
    // disc (the colonised systems sit within the bright core region).
    const rMax = CATALOG_CONSTANTS.R_MAX;

    // World-to-canvas coordinate helpers: galaxy [-rMax, rMax] → canvas [0, size].
    const toCanvasX = (wx: number) => (wx / rMax) * (size / 2) + size / 2;
    const toCanvasY = (wy: number) => (wy / rMax) * (size / 2) + size / 2;

    // Clear canvas.
    ctx.clearRect(0, 0, size, size);

    // (a) Procedural galaxy backdrop — the 4-arm spiral density field with a
    // warm core → blue arm gradient (mirrors the main renderer). Built once and
    // cached (static for a seed); blitted scaled each redraw.
    if (!this.backdrop) {
      this.backdrop = this.buildBackdrop(rMax);
    }
    ctx.imageSmoothingEnabled = true;
    ctx.drawImage(this.backdrop, 0, 0, size, size);

    // Build faction colour + overlay owner lookups (non-reactive reads).
    const factionColours = new Map<string, string>();
    for (const f of this.factions.factions()) {
      factionColours.set(f.factionId, f.colour);
    }
    const ownerMap = new Map<string, string | null>();
    for (const s of this.overlay.allSystems()) {
      ownerMap.set(s.systemId, s.ownerFactionId);
    }

    // (b) Active systems as owner-tinted dots.
    for (const sys of worldSystems) {
      const sx = toCanvasX(sys.x);
      const sy = toCanvasY(sys.y);
      const ownerId = ownerMap.get(String(sys.id)) ?? null;
      ctx.beginPath();
      ctx.arc(sx, sy, 1.6, 0, Math.PI * 2);
      if (ownerId) {
        ctx.fillStyle = factionColours.get(ownerId) ?? 'rgba(255,255,255,0.5)';
      } else {
        ctx.fillStyle = 'rgba(255,255,255,0.2)';
      }
      ctx.fill();
    }

    // (c) Interstellar objects as slightly larger kind-tinted dots.
    for (const obj of this.demo.objects()) {
      const sx = toCanvasX(obj.x);
      const sy = toCanvasY(obj.y);
      const [r, g, b] = obj.tint;
      ctx.beginPath();
      ctx.arc(sx, sy, 1.6, 0, Math.PI * 2);
      ctx.fillStyle = `rgba(${Math.round(r*255)},${Math.round(g*255)},${Math.round(b*255)},0.5)`;
      ctx.fill();
    }

    // (d) Camera viewport rectangle — bright sc-accent outline.
    const vx0 = toCanvasX(bbox.minX);
    const vy0 = toCanvasY(bbox.minY);
    const vx1 = toCanvasX(bbox.maxX);
    const vy1 = toCanvasY(bbox.maxY);
    ctx.save();
    ctx.strokeStyle = 'rgba(255,255,255,0.75)';
    ctx.lineWidth = 1;
    ctx.strokeRect(
      Math.min(vx0, vx1),
      Math.min(vy0, vy1),
      Math.abs(vx1 - vx0),
      Math.abs(vy1 - vy0),
    );
    ctx.restore();
  }

  /**
   * Render the procedural galaxy disc once to an offscreen canvas: sample the
   * shared spiral density field (the SAME `densityAt` the catalog + renderer
   * use, so the minimap agrees with the main view) and paint a warm-core →
   * blue-arm gradient. Low-res + cached, then blitted scaled — bounded cost.
   */
  private buildBackdrop(rMax: number): HTMLCanvasElement {
    const RES = 140;
    const off = document.createElement('canvas');
    off.width = RES;
    off.height = RES;
    const octx = off.getContext('2d')!;
    const img = octx.createImageData(RES, RES);
    const data = img.data;
    for (let py = 0; py < RES; py++) {
      for (let px = 0; px < RES; px++) {
        const x = ((px + 0.5) / RES * 2 - 1) * rMax;
        const y = ((py + 0.5) / RES * 2 - 1) * rMax;
        const rr = Math.hypot(x, y) / rMax;
        const dens = densityAt(x, y) / DENSITY_PEAK; // 0..1 (0 beyond R_MAX)
        // Radial blackbody ramp: warm yellow core → blue arms/outskirts.
        const tw = Math.min(1, Math.max(0, (rr - 0.08) / 0.5));
        const cr = 1.0 + (0.55 - 1.0) * tw;
        const cg = 0.82 + (0.72 - 0.82) * tw;
        const cb = 0.52 + (1.0 - 0.52) * tw;
        const bulge = Math.exp(-rr * rr * 18) * 0.6; // blazing nucleus
        const inten = Math.min(1, Math.pow(Math.max(0, dens), 0.85) * 0.85 + bulge);
        const i = (py * RES + px) * 4;
        data[i] = Math.min(255, cr * inten * 255);
        data[i + 1] = Math.min(255, cg * inten * 255);
        data[i + 2] = Math.min(255, cb * inten * 255);
        data[i + 3] = Math.min(255, inten * 235);
      }
    }
    octx.putImageData(img, 0, 0);
    return off;
  }

  // ---------------------------------------------------------------------------
  // Coordinate helpers
  // ---------------------------------------------------------------------------

  /** Convert a canvas click position to galaxy world coordinates. */
  private canvasToWorld(cx: number, cy: number): { worldX: number; worldY: number } {
    const rMax = CATALOG_CONSTANTS.R_MAX;
    const worldX = ((cx - MAP_SIZE / 2) / (MAP_SIZE / 2)) * rMax;
    const worldY = ((cy - MAP_SIZE / 2) / (MAP_SIZE / 2)) * rMax;
    return { worldX, worldY };
  }
}
