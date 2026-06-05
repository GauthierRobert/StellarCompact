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
    this.drawTerritories(ctx, scene, view, s);
    this.drawAggregates(ctx, scene, view);
    // Interstellar objects sit with/under the star field (so stars stay crisp on
    // top) but BEFORE the fog veil (so distant objects get veiled like scenery).
    this.drawObjects(ctx, scene, view, s, timeSeconds);
    this.drawRoutes(ctx, scene, view, s, timeSeconds);
    this.drawStars(ctx, scene, view, s, timeSeconds);
    this.drawSystems(ctx, scene, view, s, timeSeconds);
    this.drawOverlayMarks(ctx, scene, view, s, timeSeconds);
    this.drawFog(ctx, scene, view, s);
    // Sector grid is a navigational UI overlay: the LAST thing drawn, on top of
    // the fog veil, so it stays readable everywhere.
    this.drawSectors(ctx, scene, view, s, timeSeconds);
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

  /**
   * Empire influence fields (E-immersive): soft faction-tinted radial gradients
   * summed per controlled system, drawn UNDER the stars so factions read as
   * coloured regions. Additive (lighter). Bounded by the visible owned systems.
   */
  private drawTerritories(
    ctx: CanvasRenderingContext2D,
    scene: RenderScene,
    view: ViewTransform,
    s: number,
  ): void {
    const territories = scene.territories;
    if (!territories || territories.length === 0) {
      return;
    }
    const W = view.widthPx;
    const H = view.heightPx;
    const radius = Math.max(40 * view.dpr, Math.min(220 * view.dpr, s * 60));
    ctx.save();
    ctx.globalCompositeOperation = 'lighter';
    for (const t of territories) {
      const [cr, cg, cb] = t.tint;
      const R = Math.round(cr * 255);
      const G = Math.round(cg * 255);
      const B = Math.round(cb * 255);
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
        // Centre-weighted fill PLUS a faint mid-radius rim so a faction reads
        // as territory with a soft *edge* (border falloff), not just a blob —
        // mirrors the WebGL territory shader's fill + rim.
        const g = ctx.createRadialGradient(p.x, p.y, 0, p.x, p.y, radius);
        g.addColorStop(0, `rgba(${R},${G},${B},0.15)`);
        g.addColorStop(0.45, `rgba(${R},${G},${B},0.06)`);
        g.addColorStop(0.62, `rgba(${R},${G},${B},0.085)`);
        g.addColorStop(0.78, `rgba(${R},${G},${B},0.03)`);
        g.addColorStop(1, `rgba(${R},${G},${B},0)`);
        ctx.fillStyle = g;
        ctx.beginPath();
        ctx.arc(p.x, p.y, radius, 0, 7);
        ctx.fill();
      }
    }
    ctx.restore();
    ctx.globalAlpha = 1;
  }

  /**
   * Fog-of-war veil (E-immersive): fill a dark veil over the whole canvas, then
   * punch holes at each reveal disk via destination-out radial gradients. The
   * veil tops out below full opacity so faint scenery still shimmers through
   * unexplored space. Skipped when fog is undefined or disabled.
   */
  private drawFog(
    ctx: CanvasRenderingContext2D,
    scene: RenderScene,
    view: ViewTransform,
    s: number,
  ): void {
    const fog = scene.fog;
    if (!fog || !fog.enabled) {
      return;
    }
    const W = view.widthPx;
    const H = view.heightPx;
    // The galaxy's luminous structure is scenery, not secret intel: the veil
    // fades out at galaxy scale (so the disc is fully visible when zoomed out)
    // and fades in across ~1.5x..4x zoom to hide unexplored region/system
    // detail. Mirrors the WebGL fog pass. (Fog-correctness comes from the
    // overlay store, already server-side fog-filtered; the veil is visual only.)
    const rMax = scene.rMax || 1000;
    const fitScaleCss = Math.min(W, H) / Math.max(view.dpr, 1e-6) / (rMax * 2.4);
    const zoomFactor = view.scale / Math.max(fitScaleCss, 1e-6);
    const veilStrength = Math.min(1, Math.max(0, (zoomFactor - 1.5) / 2.5));
    if (veilStrength <= 0.001) {
      return; // galaxy scale: no veil, the whole galaxy is visible
    }
    const MAX_VEIL = 0.82 * veilStrength;
    ctx.save();
    // 1) Lay down the veil over the whole canvas.
    ctx.globalCompositeOperation = 'source-over';
    ctx.fillStyle = `rgba(0,3,8,${MAX_VEIL})`;
    ctx.fillRect(0, 0, W, H);
    // 2) Punch holes where space is revealed (soft falloff over ~35% of radius).
    ctx.globalCompositeOperation = 'destination-out';
    for (const rv of fog.reveals) {
      const p = view.w2s(rv.x, rv.y);
      const r = rv.r * s;
      if (r <= 0 || p.x < -r || p.x > W + r || p.y < -r || p.y > H + r) {
        continue;
      }
      const inner = Math.max(0, r * 0.65);
      const g = ctx.createRadialGradient(p.x, p.y, inner, p.x, p.y, r);
      g.addColorStop(0, 'rgba(0,0,0,1)');
      g.addColorStop(1, 'rgba(0,0,0,0)');
      ctx.fillStyle = g;
      ctx.beginPath();
      ctx.arc(p.x, p.y, r, 0, 7);
      ctx.fill();
    }
    ctx.restore();
    ctx.globalAlpha = 1;
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

  /**
   * Active-overlay compositing (E8-06): ownership tint / fleet+battle / blockade
   * marks drawn ON TOP of the star field. The marks were joined to their stars
   * by system id upstream (overlay-layer); here we just draw the accent at each
   * mark's world position. Fog-correct: only marks the server-fed overlay store
   * produced exist in scene.overlayMarks — nothing hidden is drawn.
   */
  private drawOverlayMarks(
    ctx: CanvasRenderingContext2D,
    scene: RenderScene,
    view: ViewTransform,
    s: number,
    now: number,
  ): void {
    const marks = scene.overlayMarks;
    if (!marks || marks.length === 0) {
      return;
    }
    const W = view.widthPx;
    const H = view.heightPx;
    const r = Math.max(6 * view.dpr, Math.min(42 * view.dpr, s * 4));
    ctx.save();
    ctx.globalCompositeOperation = 'lighter';
    for (const m of marks) {
      const p = view.w2s(m.x, m.y);
      if (p.x < -r || p.x > W + r || p.y < -r || p.y > H + r) {
        continue;
      }
      // Ownership tint: a soft halo ring around the star.
      if (m.tint) {
        const [cr, cg, cb] = m.tint;
        const R = Math.round(cr * 255);
        const G = Math.round(cg * 255);
        const B = Math.round(cb * 255);
        const amp = 0.28 + 0.1 * Math.min(2, m.activity);
        const g = ctx.createRadialGradient(p.x, p.y, r * 0.3, p.x, p.y, r);
        g.addColorStop(0, `rgba(${R},${G},${B},0)`);
        g.addColorStop(0.55, `rgba(${R},${G},${B},${amp})`);
        g.addColorStop(1, `rgba(${R},${G},${B},0)`);
        ctx.fillStyle = g;
        ctx.beginPath();
        ctx.arc(p.x, p.y, r, 0, 7);
        ctx.fill();
      }
      // Battle: red pulsing core PLUS an expanding shock ring so a contested
      // system visibly throbs and draws the eye (matches the WebGL overlay).
      if (m.battle) {
        const pulse = 0.55 + 0.45 * Math.sin(now * 6);
        ctx.globalAlpha = pulse * 0.85;
        const g = ctx.createRadialGradient(p.x, p.y, 0, p.x, p.y, r * 0.5);
        g.addColorStop(0, 'rgba(255,70,50,0.9)');
        g.addColorStop(1, 'rgba(255,70,50,0)');
        ctx.fillStyle = g;
        ctx.beginPath();
        ctx.arc(p.x, p.y, r * 0.5, 0, 7);
        ctx.fill();
        // Outward ping ring on a ~1.4s cycle.
        const ping = (now * 0.7) % 1;
        ctx.globalAlpha = (1 - ping) * 0.8;
        ctx.strokeStyle = 'rgba(255,76,56,0.9)';
        ctx.lineWidth = Math.max(1, 1.4 * view.dpr);
        ctx.beginPath();
        ctx.arc(p.x, p.y, r * (0.1 + 0.85 * ping), 0, 7);
        ctx.stroke();
        ctx.globalAlpha = 1;
      }
      // Blockade: a pulsing segmented amber cordon ring.
      if (m.blockaded) {
        const pulse = 0.65 + 0.35 * Math.sin(now * 3.5);
        ctx.globalAlpha = 0.8 * pulse;
        ctx.strokeStyle = 'rgba(255,184,64,0.9)';
        ctx.lineWidth = Math.max(1, 1.5 * view.dpr);
        const rr = r * 0.78;
        const segs = 8;
        for (let k = 0; k < segs; k++) {
          const a0 = (k / segs) * Math.PI * 2 + now * 0.2;
          ctx.beginPath();
          ctx.arc(p.x, p.y, rr, a0, a0 + Math.PI / segs);
          ctx.stroke();
        }
        ctx.globalAlpha = 1;
      }
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

  /**
   * Discrete interstellar objects (nebulae, black holes, pulsars, wormholes,
   * asteroid fields, rogue planets, supernova remnants). NASA-photo inspired,
   * additive over black except where a kind explicitly punches a dark core.
   * Bounded per-frame work: only the passed (already bbox-culled) objects are
   * drawn, each is screen-culled, and every internal sub-element count is capped.
   * Scatter is deterministic (hashStr of id+index), never Math.random.
   */
  private drawObjects(
    ctx: CanvasRenderingContext2D,
    scene: RenderScene,
    view: ViewTransform,
    s: number,
    now: number,
  ): void {
    const objects = scene.objects;
    if (!objects || objects.length === 0) {
      return;
    }
    const W = view.widthPx;
    const H = view.heightPx;
    ctx.save();
    for (const o of objects) {
      const p = view.w2s(o.x, o.y);
      const R = o.r * s; // drawn radius in device px
      if (R <= 0 || p.x < -R || p.x > W + R || p.y < -R || p.y > H + R) {
        continue;
      }
      const fade = o.a === undefined ? 1 : o.a;
      const t = o.phase + now;
      const tint = o.tint;
      const tint2 = o.tint2 ?? o.tint;
      switch (o.kind) {
        case 'nebula':
          this.objNebula(ctx, o, p.x, p.y, R, tint, tint2, t, fade);
          break;
        case 'blackhole':
          this.objBlackhole(ctx, o, p.x, p.y, R, tint, tint2, t, fade);
          break;
        case 'pulsar':
          this.objPulsar(ctx, o, p.x, p.y, R, tint, tint2, t, fade);
          break;
        case 'wormhole':
          this.objWormhole(ctx, o, p.x, p.y, R, tint, tint2, t, fade);
          break;
        case 'asteroidField':
          this.objAsteroids(ctx, o, p.x, p.y, R, tint, tint2, fade);
          break;
        case 'roguePlanet':
          this.objRoguePlanet(ctx, o, p.x, p.y, R, tint, t, fade);
          break;
        case 'supernovaRemnant':
          this.objSupernova(ctx, o, p.x, p.y, R, tint, tint2, t, fade);
          break;
      }
      if (o.selected) {
        this.objFocusRing(ctx, p.x, p.y, R, view.dpr);
      }
    }
    ctx.restore();
    ctx.globalAlpha = 1;
    ctx.globalCompositeOperation = 'source-over';
  }

  /** Selection focus ring (thin bright circle just outside the object). */
  private objFocusRing(
    ctx: CanvasRenderingContext2D,
    x: number,
    y: number,
    R: number,
    dpr: number,
  ): void {
    ctx.globalCompositeOperation = 'lighter';
    ctx.globalAlpha = 0.9;
    ctx.strokeStyle = 'rgba(150,200,255,0.95)';
    ctx.lineWidth = Math.max(1, 1.4 * dpr);
    ctx.setLineDash([]);
    ctx.beginPath();
    ctx.arc(x, y, R * 1.25, 0, 7);
    ctx.stroke();
    ctx.globalAlpha = 1;
  }

  /** Layered soft fbm-ish clouds with a few embedded young-star points. */
  private objNebula(
    ctx: CanvasRenderingContext2D,
    o: { id: string },
    x: number,
    y: number,
    R: number,
    tint: readonly [number, number, number],
    tint2: readonly [number, number, number],
    t: number,
    fade: number,
  ): void {
    ctx.globalCompositeOperation = 'lighter';
    const [r1, g1, b1] = rgb255(tint);
    const [r2, g2, b2] = rgb255(tint2);
    // Several overlapping irregular lobes drifting around the centre (capped ≤6).
    const lobes = 6;
    for (let i = 0; i < lobes; i++) {
      const h1 = hashStr(o.id, i * 2 + 1);
      const h2 = hashStr(o.id, i * 2 + 2);
      const ang = h1 * Math.PI * 2 + t * 0.05;
      const dist = (0.12 + 0.5 * h2) * R;
      const lx = x + Math.cos(ang) * dist;
      const ly = y + Math.sin(ang) * dist;
      const lr = R * (0.42 + 0.4 * h1);
      const warm = i % 2 === 0;
      const cr = warm ? r1 : r2;
      const cg = warm ? g1 : g2;
      const cb = warm ? b1 : b2;
      const breathe = 0.8 + 0.2 * Math.sin(t * 0.5 + i);
      const a = (warm ? 0.1 : 0.075) * breathe * fade;
      const g = ctx.createRadialGradient(lx, ly, 0, lx, ly, lr);
      g.addColorStop(0, `rgba(${cr},${cg},${cb},${a})`);
      g.addColorStop(0.45, `rgba(${cr},${cg},${cb},${a * 0.45})`);
      g.addColorStop(1, `rgba(${cr},${cg},${cb},0)`);
      ctx.fillStyle = g;
      ctx.beginPath();
      ctx.arc(lx, ly, lr, 0, 7);
      ctx.fill();
    }
    // Soft bright core haze.
    const cg = ctx.createRadialGradient(x, y, 0, x, y, R * 0.5);
    cg.addColorStop(0, `rgba(${r1},${g1},${b1},${0.12 * fade})`);
    cg.addColorStop(1, `rgba(${r1},${g1},${b1},0)`);
    ctx.fillStyle = cg;
    ctx.beginPath();
    ctx.arc(x, y, R * 0.5, 0, 7);
    ctx.fill();
    // Embedded young stars — a few crisp bright points (deterministic, ≤7).
    if (R > 22) {
      const stars = 7;
      for (let i = 0; i < stars; i++) {
        const hx = hashStr(o.id, 100 + i * 3);
        const hy = hashStr(o.id, 101 + i * 3);
        const hb = hashStr(o.id, 102 + i * 3);
        const ang = hx * Math.PI * 2;
        const d = Math.sqrt(hy) * R * 0.7;
        const sx = x + Math.cos(ang) * d;
        const sy = y + Math.sin(ang) * d;
        const tw = 0.7 + 0.3 * Math.sin(t * 2 + i * 1.7);
        const sr = Math.max(0.8, R * 0.018 * (0.6 + hb));
        const sg = ctx.createRadialGradient(sx, sy, 0, sx, sy, sr * 4);
        sg.addColorStop(0, `rgba(255,255,255,${0.9 * tw * fade})`);
        sg.addColorStop(0.4, `rgba(210,225,255,${0.4 * tw * fade})`);
        sg.addColorStop(1, 'rgba(180,200,255,0)');
        ctx.fillStyle = sg;
        ctx.beginPath();
        ctx.arc(sx, sy, sr * 4, 0, 7);
        ctx.fill();
      }
    }
  }

  /** Dark core + bright accretion-disk ring + sharp photon ring. */
  private objBlackhole(
    ctx: CanvasRenderingContext2D,
    o: { id: string },
    x: number,
    y: number,
    R: number,
    tint: readonly [number, number, number],
    tint2: readonly [number, number, number],
    t: number,
    fade: number,
  ): void {
    const [r1, g1, b1] = rgb255(tint);
    const [r2, g2, b2] = rgb255(tint2);
    const core = R * 0.34;
    // Accretion disk: a broad warm glow ring (additive) with slow Doppler asym.
    ctx.globalCompositeOperation = 'lighter';
    const rot = t * 0.25;
    const bright = 0.5 + 0.5 * Math.cos(rot); // one side brighter
    const disk = ctx.createRadialGradient(x, y, core, x, y, R);
    disk.addColorStop(0, `rgba(${r1},${g1},${b1},0)`);
    disk.addColorStop(0.32, `rgba(${r1},${g1},${b1},${0.55 * fade})`);
    disk.addColorStop(0.5, `rgba(${r2},${g2},${b2},${0.4 * fade})`);
    disk.addColorStop(1, `rgba(${r1},${g1},${b1},0)`);
    ctx.fillStyle = disk;
    ctx.beginPath();
    ctx.arc(x, y, R, 0, 7);
    ctx.fill();
    // Doppler-bright lobe on one side.
    const dx = x + Math.cos(rot) * R * 0.45;
    const dy = y + Math.sin(rot) * R * 0.45;
    const dl = ctx.createRadialGradient(dx, dy, 0, dx, dy, R * 0.5);
    dl.addColorStop(0, `rgba(${r2},${g2},${b2},${0.5 * bright * fade})`);
    dl.addColorStop(1, `rgba(${r2},${g2},${b2},0)`);
    ctx.fillStyle = dl;
    ctx.beginPath();
    ctx.arc(dx, dy, R * 0.5, 0, 7);
    ctx.fill();
    // Photon ring: a thin bright circle just outside the core.
    ctx.globalAlpha = 0.95 * fade;
    ctx.strokeStyle = `rgba(255,${Math.min(255, g2 + 30)},${Math.min(255, b2 + 30)},0.95)`;
    ctx.lineWidth = Math.max(1, R * 0.05);
    ctx.beginPath();
    ctx.arc(x, y, core * 1.12, 0, 7);
    ctx.stroke();
    ctx.globalAlpha = 1;
    // Dark core: punch a near-black disk on top (normal blend).
    ctx.globalCompositeOperation = 'source-over';
    const cg = ctx.createRadialGradient(x, y, 0, x, y, core);
    cg.addColorStop(0, 'rgba(0,1,3,1)');
    cg.addColorStop(0.82, 'rgba(0,1,3,1)');
    cg.addColorStop(1, 'rgba(0,1,3,0)');
    ctx.fillStyle = cg;
    ctx.beginPath();
    ctx.arc(x, y, core, 0, 7);
    ctx.fill();
    ctx.globalCompositeOperation = 'lighter';
  }

  /** Brilliant blue-white core + two opposing sweeping beams + synchrotron halo. */
  private objPulsar(
    ctx: CanvasRenderingContext2D,
    o: { id: string },
    x: number,
    y: number,
    R: number,
    tint: readonly [number, number, number],
    tint2: readonly [number, number, number],
    t: number,
    fade: number,
  ): void {
    ctx.globalCompositeOperation = 'lighter';
    const [r1, g1, b1] = rgb255(tint);
    const [r2, g2, b2] = rgb255(tint2);
    // Synchrotron halo.
    const halo = ctx.createRadialGradient(x, y, 0, x, y, R);
    halo.addColorStop(0, `rgba(${r2},${g2},${b2},${0.22 * fade})`);
    halo.addColorStop(1, `rgba(${r2},${g2},${b2},0)`);
    ctx.fillStyle = halo;
    ctx.beginPath();
    ctx.arc(x, y, R, 0, 7);
    ctx.fill();
    // Twin sweeping beams (long thin cones), pulsing in intensity.
    const ang = t * 1.1;
    const pulse = 0.45 + 0.55 * Math.abs(Math.sin(t * 3));
    const beamLen = R * 2.6;
    for (let k = 0; k < 2; k++) {
      const a = ang + k * Math.PI;
      const ex = x + Math.cos(a) * beamLen;
      const ey = y + Math.sin(a) * beamLen;
      const grad = ctx.createLinearGradient(x, y, ex, ey);
      grad.addColorStop(0, `rgba(255,255,255,${0.5 * pulse * fade})`);
      grad.addColorStop(0.25, `rgba(${r1},${g1},${b1},${0.3 * pulse * fade})`);
      grad.addColorStop(1, `rgba(${r1},${g1},${b1},0)`);
      const perp = a + Math.PI / 2;
      const w = R * 0.16;
      ctx.fillStyle = grad;
      ctx.beginPath();
      ctx.moveTo(x + Math.cos(perp) * w * 0.4, y + Math.sin(perp) * w * 0.4);
      ctx.lineTo(x - Math.cos(perp) * w * 0.4, y - Math.sin(perp) * w * 0.4);
      ctx.lineTo(ex - Math.cos(perp) * w, ey - Math.sin(perp) * w);
      ctx.lineTo(ex + Math.cos(perp) * w, ey + Math.sin(perp) * w);
      ctx.closePath();
      ctx.fill();
    }
    // Brilliant blue-white core.
    const cr = Math.max(2, R * 0.14);
    const cg = ctx.createRadialGradient(x, y, 0, x, y, cr * 3);
    cg.addColorStop(0, `rgba(255,255,255,${fade})`);
    cg.addColorStop(0.4, `rgba(${r1},${g1},${b1},${0.7 * fade})`);
    cg.addColorStop(1, `rgba(${r1},${g1},${b1},0)`);
    ctx.fillStyle = cg;
    ctx.beginPath();
    ctx.arc(x, y, cr * 3, 0, 7);
    ctx.fill();
  }

  /** Luminous ring with a swirling interior and a darker centre (an aperture). */
  private objWormhole(
    ctx: CanvasRenderingContext2D,
    o: { id: string },
    x: number,
    y: number,
    R: number,
    tint: readonly [number, number, number],
    tint2: readonly [number, number, number],
    t: number,
    fade: number,
  ): void {
    const [r1, g1, b1] = rgb255(tint);
    const [r2, g2, b2] = rgb255(tint2);
    // Swirling interior: concentric rotating arcs, tint→tint2.
    ctx.globalCompositeOperation = 'lighter';
    const arcs = 5;
    for (let i = 0; i < arcs; i++) {
      const f = i / (arcs - 1);
      const rr = R * (0.2 + 0.62 * f);
      const cr = Math.round(r1 + (r2 - r1) * f);
      const cg = Math.round(g1 + (g2 - g1) * f);
      const cb = Math.round(b1 + (b2 - b1) * f);
      const a0 = (t * (0.4 + i * 0.25)) % (Math.PI * 2);
      ctx.globalAlpha = (0.28 - f * 0.16) * fade;
      ctx.strokeStyle = `rgba(${cr},${cg},${cb},0.9)`;
      ctx.lineWidth = Math.max(1, R * 0.05);
      ctx.beginPath();
      ctx.arc(x, y, rr, a0, a0 + Math.PI * 1.3);
      ctx.stroke();
    }
    ctx.globalAlpha = 1;
    // Luminous outer ring (torus rim).
    ctx.strokeStyle = `rgba(${r2},${g2},${b2},${0.85 * fade})`;
    ctx.lineWidth = Math.max(1.5, R * 0.08);
    ctx.beginPath();
    ctx.arc(x, y, R * 0.82, 0, 7);
    ctx.stroke();
    const rim = ctx.createRadialGradient(x, y, R * 0.7, x, y, R);
    rim.addColorStop(0, `rgba(${r1},${g1},${b1},0)`);
    rim.addColorStop(0.7, `rgba(${r1},${g1},${b1},${0.4 * fade})`);
    rim.addColorStop(1, `rgba(${r1},${g1},${b1},0)`);
    ctx.fillStyle = rim;
    ctx.beginPath();
    ctx.arc(x, y, R, 0, 7);
    ctx.fill();
    // Darker centre (aperture), normal blend.
    ctx.globalCompositeOperation = 'source-over';
    const dark = ctx.createRadialGradient(x, y, 0, x, y, R * 0.45);
    dark.addColorStop(0, 'rgba(2,3,10,0.92)');
    dark.addColorStop(1, 'rgba(2,3,10,0)');
    ctx.fillStyle = dark;
    ctx.beginPath();
    ctx.arc(x, y, R * 0.45, 0, 7);
    ctx.fill();
    ctx.globalCompositeOperation = 'lighter';
  }

  /** A scattered cloud of small rocky dots in a loose annulus (deterministic). */
  private objAsteroids(
    ctx: CanvasRenderingContext2D,
    o: { id: string },
    x: number,
    y: number,
    R: number,
    tint: readonly [number, number, number],
    tint2: readonly [number, number, number],
    fade: number,
  ): void {
    // No glow — rocks are dim, drawn with normal blend so they read as solid.
    ctx.globalCompositeOperation = 'source-over';
    const [r1, g1, b1] = rgb255(tint);
    const [r2, g2, b2] = rgb255(tint2);
    // Cap dot count; thin out tiny far fields to stay cheap.
    const count = Math.min(120, Math.max(0, Math.floor(R * 1.4)));
    for (let i = 0; i < count; i++) {
      const ha = hashStr(o.id, i * 2 + 1);
      const hr = hashStr(o.id, i * 2 + 2);
      const ang = ha * Math.PI * 2;
      // Loose belt/annulus: bias radius toward the outer band.
      const rr = R * (0.35 + 0.6 * Math.sqrt(hr));
      const dx = x + Math.cos(ang) * rr;
      const dy = y + Math.sin(ang) * rr;
      const sz = Math.max(0.6, R * 0.012 * (0.5 + hashStr(o.id, 1000 + i)));
      const warm = (i & 1) === 0;
      const cr = warm ? r1 : r2;
      const cg = warm ? g1 : g2;
      const cb = warm ? b1 : b2;
      ctx.fillStyle = `rgba(${cr},${cg},${cb},${(0.45 + 0.4 * hr) * fade})`;
      ctx.beginPath();
      ctx.arc(dx, dy, sz, 0, 7);
      ctx.fill();
    }
    ctx.globalCompositeOperation = 'lighter';
  }

  /** A small dim lit sphere — a lonely sunless world (no star glow). */
  private objRoguePlanet(
    ctx: CanvasRenderingContext2D,
    o: { id: string; phase: number },
    x: number,
    y: number,
    R: number,
    tint: readonly [number, number, number],
    t: number,
    fade: number,
  ): void {
    ctx.globalCompositeOperation = 'source-over';
    const [r1, g1, b1] = rgb255(tint);
    const pr = R * 0.7;
    // Light comes from a slowly drifting direction so the terminator reads.
    const la = o.phase + t * 0.1;
    const lx = x + Math.cos(la) * pr * 0.6;
    const ly = y + Math.sin(la) * pr * 0.6;
    const g = ctx.createRadialGradient(lx, ly, pr * 0.1, x, y, pr);
    g.addColorStop(0, `rgba(${r1},${g1},${b1},${0.95 * fade})`);
    g.addColorStop(0.5, `rgba(${Math.round(r1 * 0.6)},${Math.round(g1 * 0.6)},${Math.round(b1 * 0.6)},${0.85 * fade})`);
    g.addColorStop(1, 'rgba(2,3,7,1)');
    ctx.fillStyle = g;
    ctx.beginPath();
    ctx.arc(x, y, pr, 0, 7);
    ctx.fill();
    // Faint rim.
    ctx.globalCompositeOperation = 'lighter';
    ctx.globalAlpha = 0.25 * fade;
    ctx.strokeStyle = `rgba(${r1},${g1},${b1},0.6)`;
    ctx.lineWidth = Math.max(0.75, R * 0.02);
    ctx.beginPath();
    ctx.arc(x, y, pr, 0, 7);
    ctx.stroke();
    ctx.globalAlpha = 1;
  }

  /** Expanding shock shell — bright irregular filaments + wispy interior. */
  private objSupernova(
    ctx: CanvasRenderingContext2D,
    o: { id: string },
    x: number,
    y: number,
    R: number,
    tint: readonly [number, number, number],
    tint2: readonly [number, number, number],
    t: number,
    fade: number,
  ): void {
    ctx.globalCompositeOperation = 'lighter';
    const [r1, g1, b1] = rgb255(tint);
    const [r2, g2, b2] = rgb255(tint2);
    // Wispy interior (tint2).
    const inner = ctx.createRadialGradient(x, y, R * 0.1, x, y, R * 0.92);
    inner.addColorStop(0, `rgba(${r2},${g2},${b2},${0.05 * fade})`);
    inner.addColorStop(0.7, `rgba(${r2},${g2},${b2},${0.12 * fade})`);
    inner.addColorStop(1, `rgba(${r2},${g2},${b2},0)`);
    ctx.fillStyle = inner;
    ctx.beginPath();
    ctx.arc(x, y, R * 0.92, 0, 7);
    ctx.fill();
    // Bright irregular shock shell: a ring of filament arcs (capped ≤ 16).
    const seg = 16;
    const shimmer = 0.8 + 0.2 * Math.sin(t * 0.8);
    ctx.lineWidth = Math.max(1, R * 0.04);
    for (let i = 0; i < seg; i++) {
      const h = hashStr(o.id, i + 1);
      const a0 = (i / seg) * Math.PI * 2;
      const a1 = a0 + (Math.PI * 2) / seg;
      const rr = R * (0.78 + 0.18 * h);
      ctx.globalAlpha = (0.18 + 0.5 * h) * shimmer * fade;
      ctx.strokeStyle = `rgba(${r1},${g1},${b1},0.9)`;
      ctx.beginPath();
      ctx.arc(x, y, rr, a0, a1);
      ctx.stroke();
    }
    ctx.globalAlpha = 1;
  }

  /**
   * Named sector grid — a navigational UI overlay drawn LAST (over the fog veil).
   * Thin cell boundaries with a very subtle owner-tinted fill; hover/selection
   * brighten the cell. Gated by apparent on-screen cell size so the grid only
   * shows when cells are a reasonable size. No text (the HUD draws names).
   */
  private drawSectors(
    ctx: CanvasRenderingContext2D,
    scene: RenderScene,
    view: ViewTransform,
    s: number,
    now: number,
  ): void {
    const sectors = scene.sectors;
    if (!sectors || !sectors.enabled || sectors.cells.length === 0) {
      return;
    }
    const W = view.widthPx;
    const H = view.heightPx;
    const DPR = view.dpr;
    ctx.save();
    ctx.globalCompositeOperation = 'source-over';
    ctx.setLineDash([]);
    for (const cell of sectors.cells) {
      const sidePx = cell.half * 2 * s;
      // Gate by apparent cell size: skip when a cell is tiny or fills the view.
      if (sidePx < 48 * DPR || sidePx > 2.4 * Math.max(W, H)) {
        continue;
      }
      const c = view.w2s(cell.cx, cell.cy);
      const half = cell.half * s;
      const left = c.x - half;
      const top = c.y - half;
      const size = half * 2;
      // Cull cells fully off-screen.
      if (left > W || top > H || left + size < 0 || top + size < 0) {
        continue;
      }
      const tint = cell.tint;
      let br = 120;
      let bg = 150;
      let bb = 190;
      if (tint) {
        br = Math.round(tint[0] * 255);
        bg = Math.round(tint[1] * 255);
        bb = Math.round(tint[2] * 255);
      }
      // Base look; hover/selection escalate boundary + fill.
      let lineA = 0.12;
      let fillA = tint ? 0.04 : 0;
      if (cell.hovered) {
        lineA = 0.4;
        fillA = tint ? 0.07 : 0.04;
      }
      if (cell.selected) {
        lineA = 0.7;
        fillA = 0.1;
        if (!tint) {
          br = 90;
          bg = 170;
          bb = 255;
        }
      }
      if (fillA > 0) {
        ctx.fillStyle = `rgba(${br},${bg},${bb},${fillA})`;
        ctx.fillRect(left, top, size, size);
      }
      ctx.strokeStyle = `rgba(${br},${bg},${bb},${lineA})`;
      ctx.lineWidth = cell.selected ? 1.5 * DPR : 1 * DPR;
      ctx.strokeRect(left, top, size, size);
      // Selected: corner ticks for a clear "this cell" read.
      if (cell.selected) {
        const tick = Math.min(size * 0.18, 18 * DPR);
        ctx.lineWidth = 2 * DPR;
        ctx.beginPath();
        // four corners
        for (const [cxp, cyp, sx, sy] of [
          [left, top, 1, 1],
          [left + size, top, -1, 1],
          [left, top + size, 1, -1],
          [left + size, top + size, -1, -1],
        ] as const) {
          ctx.moveTo(cxp + sx * tick, cyp);
          ctx.lineTo(cxp, cyp);
          ctx.lineTo(cxp, cyp + sy * tick);
        }
        ctx.stroke();
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

/**
 * Deterministic 0..1 hash from a string id + integer index. Used to scatter
 * asteroid dots / nebula sub-points / shell filaments WITHOUT Math.random, so
 * an object's procedural detail is identical every frame and across backends.
 */
export function hashStr(id: string, index: number): number {
  let h = 0x811c9dc5 ^ (index | 0);
  for (let i = 0; i < id.length; i++) {
    h ^= id.charCodeAt(i);
    h = Math.imul(h, 0x01000193);
  }
  h = Math.imul(h ^ (h >>> 15), 2246822519);
  h = (h ^ (h >>> 13)) >>> 0;
  return h / 4294967296;
}

/** Convert a 0..1 RGB tint to integer 0..255 channels. */
export function rgb255(
  c: readonly [number, number, number],
): [number, number, number] {
  return [
    Math.round(c[0] * 255),
    Math.round(c[1] * 255),
    Math.round(c[2] * 255),
  ];
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
