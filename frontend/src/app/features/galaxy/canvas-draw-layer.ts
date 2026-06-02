import {
  SPECTRAL_PALETTE,
  type GalaxyDrawLayer,
  type RenderScene,
  type RenderStar,
  type ViewTransform,
} from './render-model';

/**
 * Canvas2D galaxy draw layer — a faithful port of poc/galaxy-navigator.html.
 *
 * This is the small-galaxy tier draw backend. It implements the GalaxyDrawLayer
 * contract so the WebGL2 backend (E8) can replace it without the component (which
 * owns the camera wiring + tile fetching) changing at all. Plain TypeScript with
 * NO Angular dependency, so it is trivially unit-constructable and portable.
 *
 * Principles carried over from the PoC / galaxy-rendering skill: black space,
 * additive (lighter) glow blending; stars stay point-like; detail is a function
 * of zoom (planets/orbits fade in via detailA; routes fade in at mid zoom);
 * bounded work (only a screenful is drawn).
 *
 * All draw maths operate in device-pixel space; s below is scale * dpr.
 */
export class CanvasDrawLayer implements GalaxyDrawLayer {
  private ctx: CanvasRenderingContext2D | null = null;
  private dpr = 1;

  constructor(private canvas: HTMLCanvasElement | null = null) {
    if (canvas) {
      this.ctx = canvas.getContext('2d');
    }
  }

  attach(canvas: HTMLCanvasElement): void {
    this.canvas = canvas;
    this.ctx = canvas.getContext('2d');
  }

  resize(widthPx: number, heightPx: number, dpr: number): void {
    this.dpr = dpr;
    if (!this.canvas) {
      return;
    }
    this.canvas.width = widthPx;
    this.canvas.height = heightPx;
  }

  draw(scene: RenderScene, view: ViewTransform, timeSeconds: number): void {
    const ctx = this.ctx;
    if (!ctx) {
      return;
    }
    const W = view.widthPx;
    const H = view.heightPx;
    const s = view.scale * view.dpr;
    ctx.fillStyle = '#000308';
    ctx.fillRect(0, 0, W, H);
    this.drawNebula(ctx, scene, view, s);
    this.drawAggregates(ctx, scene, view);
    this.drawRoutes(ctx, scene, view, s, timeSeconds);
    this.drawStars(ctx, scene, view, s, timeSeconds);
    this.drawSystems(ctx, scene, view, s, timeSeconds);
  }

  dispose(): void {
    this.ctx = null;
    this.canvas = null;
  }

  private rgb(k: number, a: number): string {
    const c = SPECTRAL_PALETTE[k] ?? SPECTRAL_PALETTE[4];
    return 'rgba(' + c[0] + ',' + c[1] + ',' + c[2] + ',' + a + ')';
  }

  private drawNebula(
    ctx: CanvasRenderingContext2D,
    scene: RenderScene,
    view: ViewTransform,
    s: number,
  ): void {
    const c = view.w2s(0, 0);
    const R = scene.rMax * s;
    if (R <= 0) {
      return;
    }
    ctx.save();
    ctx.globalCompositeOperation = 'lighter';
    let g = ctx.createRadialGradient(c.x, c.y, 0, c.x, c.y, R);
    g.addColorStop(0, 'rgba(90,100,170,0.10)');
    g.addColorStop(0.2, 'rgba(120,95,160,0.09)');
    g.addColorStop(0.55, 'rgba(50,70,150,0.035)');
    g.addColorStop(1, 'rgba(20,40,110,0)');
    ctx.fillStyle = g;
    ctx.beginPath();
    ctx.arc(c.x, c.y, R, 0, 7);
    ctx.fill();
    g = ctx.createRadialGradient(c.x, c.y, 0, c.x, c.y, R * 0.14);
    g.addColorStop(0, 'rgba(255,238,205,0.28)');
    g.addColorStop(1, 'rgba(255,205,150,0)');
    ctx.fillStyle = g;
    ctx.beginPath();
    ctx.arc(c.x, c.y, R * 0.14, 0, 7);
    ctx.fill();
    ctx.restore();
  }

  private drawAggregates(
    ctx: CanvasRenderingContext2D,
    scene: RenderScene,
    view: ViewTransform,
  ): void {
    if (scene.aggregates.length === 0) {
      return;
    }
    const W = view.widthPx;
    const H = view.heightPx;
    ctx.save();
    ctx.globalCompositeOperation = 'lighter';
    const r = Math.max(8, 0.04 * Math.min(W, H));
    for (const a of scene.aggregates) {
      const p = view.w2s(a.x, a.y);
      if (p.x < -r || p.x > W + r || p.y < -r || p.y > H + r) {
        continue;
      }
      const fade = a.a === undefined ? 1 : a.a;
      const alpha = Math.min(0.5, 0.08 + a.weight * 0.4) * fade;
      const g = ctx.createRadialGradient(p.x, p.y, 0, p.x, p.y, r);
      g.addColorStop(0, 'rgba(225,225,255,' + alpha + ')');
      g.addColorStop(0.4, 'rgba(170,180,235,' + alpha * 0.4 + ')');
      g.addColorStop(1, 'rgba(120,140,210,0)');
      ctx.fillStyle = g;
      ctx.beginPath();
      ctx.arc(p.x, p.y, r, 0, 7);
      ctx.fill();
    }
    ctx.restore();
  }

  private drawStars(
    ctx: CanvasRenderingContext2D,
    scene: RenderScene,
    view: ViewTransform,
    s: number,
    now: number,
  ): void {
    if (scene.stars.length === 0) {
      return;
    }
    const W = view.widthPx;
    const H = view.heightPx;
    const DPR = view.dpr;
    ctx.save();
    ctx.globalCompositeOperation = 'lighter';
    for (const st of scene.stars) {
      const p = view.w2s(st.x, st.y);
      if (p.x < -50 || p.x > W + 50 || p.y < -50 || p.y > H + 50) {
        continue;
      }
      const r = Math.max(
        0.55 * DPR,
        (0.45 + st.sz * 0.55) * DPR * (0.85 + Math.min(1.6, s * 0.02)),
      );
      const tw = 0.88 + 0.12 * Math.sin(now * 1.6 + st.id);
      // LOD cross-fade weight (default 1): scales every alpha so an outgoing /
      // incoming tile level fades smoothly and never pops (E8-05).
      const fade = st.a === undefined ? 1 : st.a;
      const gr = r * 4.2;
      const g = ctx.createRadialGradient(p.x, p.y, 0, p.x, p.y, gr);
      g.addColorStop(0, this.rgb(st.k, 1));
      g.addColorStop(0.25, this.rgb(st.k, 0.5));
      g.addColorStop(1, this.rgb(st.k, 0));
      ctx.globalAlpha = (0.2 + 0.5 * st.b) * tw * fade;
      ctx.fillStyle = g;
      ctx.beginPath();
      ctx.arc(p.x, p.y, gr, 0, 7);
      ctx.fill();
      const spikeT = clamp01((st.b - 0.6) / 0.3) * clamp01((r - 1.6 * DPR) / 2);
      if (spikeT > 0.03) {
        const L = gr * (2 + st.g * 1.6) * spikeT;
        ctx.globalAlpha = 0.45 * spikeT * tw * fade;
        ctx.strokeStyle = this.rgb(st.k, 1);
        ctx.lineWidth = Math.max(0.5, r * 0.16);
        for (let a = 0; a < 4; a++) {
          const ang = (a * Math.PI) / 2;
          ctx.beginPath();
          ctx.moveTo(p.x, p.y);
          ctx.lineTo(p.x + Math.cos(ang) * L, p.y + Math.sin(ang) * L);
          ctx.stroke();
        }
      }
      ctx.globalAlpha = Math.min(1, (0.55 + st.b) * tw) * fade;
      ctx.fillStyle = '#fff';
      ctx.beginPath();
      ctx.arc(p.x, p.y, r * 0.6, 0, 7);
      ctx.fill();
    }
    ctx.restore();
    ctx.globalAlpha = 1;
  }

  private drawRoutes(
    ctx: CanvasRenderingContext2D,
    scene: RenderScene,
    view: ViewTransform,
    s: number,
    now: number,
  ): void {
    if (scene.routes.length === 0) {
      return;
    }
    const a = clamp01((s - 0.9) / 1.0) * (1 - clamp01((s - 30) / 24));
    if (a <= 0.02) {
      return;
    }
    const DPR = view.dpr;
    const maxLen = 60 + 400 * clamp01((s - 1.0) / 6);
    const col: Record<string, string> = {
      allied: '#4ad6a0',
      trade: '#ffcf73',
      contested: '#ff6b6b',
    };
    ctx.save();
    for (const rt of scene.routes) {
      if (rt.len > maxLen) {
        continue;
      }
      const A = view.w2s(rt.ax, rt.ay);
      const B = view.w2s(rt.bx, rt.by);
      const c = col[rt.kind] ?? '#ffcf73';
      ctx.globalAlpha = a * 0.22;
      ctx.strokeStyle = c;
      ctx.lineWidth = 1 * DPR;
      ctx.setLineDash(rt.kind === 'contested' ? [5 * DPR, 5 * DPR] : []);
      ctx.beginPath();
      ctx.moveTo(A.x, A.y);
      ctx.lineTo(B.x, B.y);
      ctx.stroke();
      ctx.setLineDash([]);
      const f = (now * 0.25) % 1;
      const fx = A.x + (B.x - A.x) * f;
      const fy = A.y + (B.y - A.y) * f;
      ctx.globalAlpha = a * (0.9 - Math.abs(f - 0.5));
      ctx.fillStyle = c;
      ctx.beginPath();
      ctx.arc(fx, fy, 1.8 * DPR, 0, 7);
      ctx.fill();
    }
    ctx.restore();
    ctx.globalAlpha = 1;
  }

  private drawSystems(
    ctx: CanvasRenderingContext2D,
    scene: RenderScene,
    view: ViewTransform,
    s: number,
    now: number,
  ): void {
    const detailA = clamp01((s - 2.2) / 3.5);
    if (detailA <= 0.01 || scene.stars.length === 0) {
      return;
    }
    const W = view.widthPx;
    const H = view.heightPx;
    const DPR = view.dpr;
    ctx.save();
    for (const st of scene.stars) {
      if (st.activeSystemId === null) {
        continue;
      }
      const p = view.w2s(st.x, st.y);
      if (p.x < -300 || p.x > W + 300 || p.y < -300 || p.y > H + 300) {
        continue;
      }
      const planets = proceduralPlanets(st);
      for (const pl of planets) {
        const or = pl.o * 18 * s;
        if (or < 14) {
          continue;
        }
        ctx.globalAlpha = detailA * 0.3;
        ctx.strokeStyle = 'rgba(150,185,235,.5)';
        ctx.lineWidth = 1;
        ctx.beginPath();
        ctx.arc(p.x, p.y, or, 0, 7);
        ctx.stroke();
        const ang = pl.ph + now * pl.sp;
        const Px = p.x + Math.cos(ang) * or;
        const Py = p.y + Math.sin(ang) * or;
        const pr =
          Math.max(2 * DPR, pl.r * 2.4 * DPR * Math.min(1.6, s * 0.05)) *
          detailA;
        if (pr < 1.2) {
          continue;
        }
        const pg = ctx.createRadialGradient(Px, Py, pr * 0.1, Px, Py, pr);
        pg.addColorStop(0, 'rgba(150,180,220,' + detailA + ')');
        pg.addColorStop(0.5, 'rgba(110,140,190,' + detailA * 0.82 + ')');
        pg.addColorStop(1, 'rgba(3,5,10,1)');
        ctx.globalAlpha = detailA;
        ctx.fillStyle = pg;
        ctx.beginPath();
        ctx.arc(Px, Py, pr, 0, 7);
        ctx.fill();
      }
    }
    ctx.restore();
    ctx.globalAlpha = 1;
  }
}

export function clamp01(v: number): number {
  return v < 0 ? 0 : v > 1 ? 1 : v;
}

export function hashId(i: number): number {
  let h = Math.imul(i ^ 0x9e3779b9, 2246822519);
  h = (h ^ (h >>> 15)) >>> 0;
  return h / 4294967296;
}

export interface ProceduralPlanet {
  readonly o: number;
  readonly r: number;
  readonly ph: number;
  readonly sp: number;
}

export function proceduralPlanets(st: RenderStar): ProceduralPlanet[] {
  const count = 1 + Math.floor(hashId(st.id * 17) * 5);
  const out: ProceduralPlanet[] = [];
  for (let i = 0; i < count; i++) {
    out.push({
      o: 1.2 + i * 1.1 + hashId(st.id * 31 + i) * 0.6,
      r: 0.5 + hashId(st.id * 53 + i) * 0.9,
      ph: hashId(st.id * 71 + i) * Math.PI * 2,
      sp: 0.05 + hashId(st.id * 97 + i) * 0.25,
    });
  }
  return out;
}
