import {
  ChangeDetectionStrategy,
  Component,
  computed,
  inject,
} from '@angular/core';
import { CommonModule } from '@angular/common';
import { RouterLink } from '@angular/router';
import { EmpireStore } from '../../stores/empire.store';
import { FactionStore } from '../../stores';
import {
  KARDASHEV_BANDS,
  formatK,
  formatWatts,
} from './kardashev';
import { fmtN } from './ui-format';

/**
 * Empire Overview — the "state of my civilization" screen, led by the Kardashev
 * hero gauge (the central progression metric). Shows the continuous K-value, the
 * energy-capture breakdown, headline empire stats, the resource ledger and the
 * active megastructure roster, with links into the deep command pages.
 */
@Component({
  selector: 'app-empire-overview',
  changeDetection: ChangeDetectionStrategy.OnPush,
  standalone: true,
  imports: [CommonModule, RouterLink],
  template: `
    <div class="page">
      <header class="page-head">
        <h1>{{ factionName() }}</h1>
        <p class="sub">Civilization overview — Kardashev standing, economy and grand projects.</p>
      </header>

      <!-- KARDASHEV HERO -->
      <section class="sc-panel hero">
        <div class="hero-top">
          <div class="hero-tier" [style.color]="band().accent">
            <span class="hero-tier-label">{{ k().tierLabel }}</span>
            <span class="hero-k">{{ formatK(k().k) }}</span>
          </div>
          <div class="hero-watts">
            <span class="watts-val">{{ formatWatts(k().totalWatts) }}</span>
            <span class="watts-lbl">captured power</span>
          </div>
        </div>

        <!-- Continuous Type 0 → III scale -->
        <div class="gauge">
          <div class="gauge-track">
            @for (b of bands; track b.id) {
              <div class="gauge-seg" [style.background]="segBg(b.accent)"></div>
            }
            <div class="gauge-fill" [style.width]="markerLeft()" [style.background]="fillBg()"></div>
            <div class="gauge-marker" [style.left]="markerLeft()" [style.background]="band().accent"></div>
          </div>
          <div class="gauge-ticks">
            <span>Type 0</span><span>I</span><span>II</span><span>III</span>
          </div>
        </div>

        <div class="hero-next">
          @if (k().tierId !== 'K3') {
            <span class="next-lbl">Progress to {{ nextTierLabel() }}</span>
            <div class="next-bar"><div class="next-fill" [style.width]="(k().progressToNext*100)+'%'" [style.background]="band().accent"></div></div>
            <span class="next-pct">{{ (k().progressToNext*100).toFixed(0) }}%</span>
            <span class="next-need">need {{ formatWatts(k().wattsToNext) }} more</span>
          } @else {
            <span class="next-lbl ascended">Galactic ascendancy achieved — a Type III civilization.</span>
          }
        </div>
        <p class="hero-blurb">{{ band().blurb }}</p>
      </section>

      <div class="cols">
        <!-- ENERGY BREAKDOWN -->
        <section class="sc-panel">
          <div class="sc-head">Energy Capture<span class="sc-head-rule"></span></div>
          <div class="breakdown">
            @if (k().sources.length === 0) {
              <p class="muted">No measurable capture yet — build the planetary grid.</p>
            }
            @for (s of k().sources; track s.kind) {
              <div class="src-row">
                <span class="src-label">{{ s.label }}</span>
                <div class="src-bar"><div class="src-fill" [style.width]="srcW(s.watts)" [style.background]="band().accent"></div></div>
                <span class="src-watts">{{ formatWatts(s.watts) }}</span>
              </div>
            }
          </div>
        </section>

        <!-- EMPIRE STATS -->
        <section class="sc-panel">
          <div class="sc-head">Empire<span class="sc-head-rule"></span></div>
          <div class="stat-grid">
            <a class="stat" routerLink="/empire/planets"><span class="stat-v">{{ empire.state()?.planetCount ?? 0 }}</span><span class="stat-l">Planets</span></a>
            <div class="stat"><span class="stat-v">{{ empire.state()?.systemCount ?? 0 }}</span><span class="stat-l">Systems</span></div>
            <div class="stat"><span class="stat-v">{{ fmtN(empire.state()?.population ?? 0) }}</span><span class="stat-l">Population</span></div>
            <a class="stat" routerLink="/empire/fleets"><span class="stat-v">{{ empire.totalFleetStrength() }}</span><span class="stat-l">Fleet Str.</span></a>
            <a class="stat" routerLink="/empire/wars" [class.alert]="empire.wars().length>0"><span class="stat-v">{{ empire.wars().length }}</span><span class="stat-l">Wars</span></a>
            <a class="stat" routerLink="/empire/tech"><span class="stat-v">{{ empire.megastructures().length }}</span><span class="stat-l">Megaprojects</span></a>
          </div>
        </section>
      </div>

      <!-- RESOURCE LEDGER -->
      <section class="sc-panel">
        <div class="sc-head">Resources<span class="sc-head-rule"></span></div>
        <div class="ledger">
          @for (r of ledgerRows(); track r.key) {
            <div class="led">
              <span class="led-dot" [style.background]="r.colour"></span>
              <span class="led-name">{{ r.label }}</span>
              <span class="led-val">{{ fmtN(r.value) }}</span>
              <span class="led-delta" [class.pos]="r.delta>=0" [class.neg]="r.delta<0">{{ r.delta>=0?'+':'' }}{{ r.delta }}/t</span>
            </div>
          }
        </div>
      </section>

      <!-- MEGASTRUCTURES -->
      <section class="sc-panel">
        <div class="sc-head">Grand Projects<span class="sc-head-rule"></span>
          <a class="head-link" routerLink="/empire/tech">Technology →</a>
        </div>
        @if (empire.megastructures().length === 0) {
          <p class="muted pad">No megastructures yet. Research the Ascension branch to begin the climb up the Kardashev scale.</p>
        } @else {
          <div class="mega-list">
            @for (m of empire.megastructures(); track m.id) {
              <div class="mega">
                <div class="mega-top">
                  <span class="mega-name">{{ m.name }}</span>
                  <span class="sc-tag mega-tier">Type {{ romanTier(m.tier) }}</span>
                  <span class="mega-stage">Stage {{ m.stage }}/{{ m.maxStage }}</span>
                </div>
                <div class="mega-bar"><div class="mega-fill" [style.width]="(m.progress*100)+'%'"></div></div>
                <div class="mega-foot">
                  <span class="mega-sys">{{ m.systemName }}</span>
                  <span class="mega-out">+{{ formatWatts(m.outputWatts) }}</span>
                </div>
              </div>
            }
          </div>
        }
      </section>
    </div>
  `,
  styles: [`
    :host { display: block; }
    .page { max-width: 1100px; margin: 0 auto; padding: 22px 20px 60px; }
    .page-head { margin-bottom: 16px; }
    h1 { font: 700 24px/1.1 var(--din-display); margin: 0; color: var(--sc-text); }
    .sub { font: 400 13px/1.4 var(--din-body); color: var(--sc-text-dim); margin: 6px 0 0; }
    .muted { color: var(--sc-text-faint); font: 400 12px/1.5 var(--din-body); }
    .pad { padding: 4px 14px 14px; }
    section { margin-bottom: 14px; }

    .hero { padding: 18px 20px 16px; }
    .hero-top { display: flex; align-items: flex-end; justify-content: space-between; margin-bottom: 16px; }
    .hero-tier { display: flex; flex-direction: column; gap: 2px; }
    .hero-tier-label { font: 700 30px/1 var(--din-display); letter-spacing: 0.5px; }
    .hero-k { font: 500 14px/1 var(--din-body); color: var(--sc-text-dim); font-variant-numeric: tabular-nums; }
    .hero-watts { text-align: right; display: flex; flex-direction: column; gap: 2px; }
    .watts-val { font: 700 20px/1 var(--din-display); color: var(--sc-text); font-variant-numeric: tabular-nums; }
    .watts-lbl { font: 400 9px/1 var(--din-body); letter-spacing: 1.4px; text-transform: uppercase; color: var(--sc-text-faint); }
    .gauge { margin-bottom: 14px; }
    .gauge-track { position: relative; display: flex; height: 14px; border-radius: var(--rounded-full); overflow: hidden; border: 1px solid var(--sc-border); }
    .gauge-seg { flex: 1; }
    .gauge-seg + .gauge-seg { border-left: 1px solid rgba(0,0,0,0.4); }
    .gauge-fill { position: absolute; left: 0; top: 0; bottom: 0; opacity: 0.5; transition: width 0.6s ease; }
    .gauge-marker { position: absolute; top: -3px; width: 4px; height: 20px; border-radius: 2px; transform: translateX(-2px); box-shadow: 0 0 8px currentColor; transition: left 0.6s ease; }
    .gauge-ticks { display: flex; justify-content: space-between; margin-top: 5px; font: 600 9px/1 var(--din-body); letter-spacing: 1px; color: var(--sc-text-faint); }
    .hero-next { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; }
    .next-lbl { font: 600 11px/1 var(--din-body); color: var(--sc-text-dim); }
    .next-lbl.ascended { color: var(--sc-influence); }
    .next-bar { flex: 1; min-width: 120px; height: 6px; background: rgba(255,255,255,0.08); border-radius: var(--rounded-full); overflow: hidden; }
    .next-fill { height: 100%; transition: width 0.6s ease; }
    .next-pct { font: 700 11px/1 var(--din-body); color: var(--sc-text); font-variant-numeric: tabular-nums; }
    .next-need { font: 400 10px/1 var(--din-body); color: var(--sc-text-faint); }
    .hero-blurb { margin: 12px 0 0; font: 400 12px/1.5 var(--din-body); color: var(--sc-text-dim); }

    .cols { display: grid; grid-template-columns: 1.2fr 1fr; gap: 14px; }
    @media (max-width: 760px) { .cols { grid-template-columns: 1fr; } }

    .breakdown { padding: 4px 14px 14px; display: flex; flex-direction: column; gap: 9px; }
    .src-row { display: flex; align-items: center; gap: 10px; }
    .src-label { width: 120px; flex-shrink: 0; font: 600 11px/1.2 var(--din-body); color: var(--sc-text); }
    .src-bar { flex: 1; height: 7px; background: rgba(255,255,255,0.07); border-radius: var(--rounded-full); overflow: hidden; }
    .src-fill { height: 100%; transition: width 0.5s ease; }
    .src-watts { width: 78px; text-align: right; flex-shrink: 0; font: 600 10px/1 var(--din-body); color: var(--sc-text-dim); font-variant-numeric: tabular-nums; }

    .stat-grid { display: grid; grid-template-columns: 1fr 1fr 1fr; gap: 6px; padding: 4px 12px 12px; }
    .stat { display: flex; flex-direction: column; align-items: center; gap: 3px; padding: 10px 4px; border: 1px solid var(--sc-border); border-radius: var(--rounded-xs); text-decoration: none; transition: border-color 0.15s, background 0.15s; }
    a.stat:hover { border-color: var(--sc-border-bright); background: rgba(255,255,255,0.04); }
    .stat.alert { border-color: rgba(255,84,104,0.4); background: rgba(255,84,104,0.06); }
    .stat-v { font: 700 18px/1 var(--din-display); color: var(--sc-text); font-variant-numeric: tabular-nums; }
    .stat-l { font: 400 8.5px/1 var(--din-body); letter-spacing: 1.4px; text-transform: uppercase; color: var(--sc-text-faint); }

    .ledger { display: grid; grid-template-columns: repeat(auto-fit, minmax(150px, 1fr)); gap: 8px; padding: 4px 14px 14px; }
    .led { display: flex; align-items: center; gap: 7px; }
    .led-dot { width: 8px; height: 8px; border-radius: 50%; flex-shrink: 0; }
    .led-name { flex: 1; font: 600 11px/1 var(--din-body); color: var(--sc-text-dim); }
    .led-val { font: 700 12px/1 var(--din-body); color: var(--sc-text); font-variant-numeric: tabular-nums; }
    .led-delta { font: 600 10px/1 var(--din-body); font-variant-numeric: tabular-nums; width: 52px; text-align: right; }
    .pos { color: var(--sc-good); } .neg { color: var(--sc-bad); }

    .head-link { margin-left: auto; font: 600 10px/1 var(--din-body); color: var(--sc-text-dim); text-decoration: none; }
    .head-link:hover { color: var(--sc-text); }
    .mega-list { display: flex; flex-direction: column; gap: 10px; padding: 4px 14px 14px; }
    .mega { display: flex; flex-direction: column; gap: 5px; }
    .mega-top { display: flex; align-items: center; gap: 8px; }
    .mega-name { font: 600 12px/1 var(--din-body); color: var(--sc-text); }
    .mega-tier { font-size: 8px; color: var(--sc-influence); border-color: rgba(232,154,196,0.35); }
    .mega-stage { margin-left: auto; font: 600 10px/1 var(--din-body); color: var(--sc-text-faint); }
    .mega-bar { height: 6px; background: rgba(255,255,255,0.08); border-radius: var(--rounded-full); overflow: hidden; }
    .mega-fill { height: 100%; background: var(--sc-influence); transition: width 0.5s ease; }
    .mega-foot { display: flex; justify-content: space-between; }
    .mega-sys { font: 400 10px/1 var(--din-body); color: var(--sc-text-faint); }
    .mega-out { font: 600 10px/1 var(--din-body); color: var(--sc-influence); font-variant-numeric: tabular-nums; }
  `],
})
export class OverviewComponent {
  protected readonly empire = inject(EmpireStore);
  private readonly factionStore = inject(FactionStore);
  protected readonly formatK = formatK;
  protected readonly formatWatts = formatWatts;
  protected readonly fmtN = fmtN;
  protected readonly bands = KARDASHEV_BANDS;

  protected readonly k = this.empire.kardashev;
  protected readonly band = computed(() => {
    const id = this.k().tierId;
    return KARDASHEV_BANDS.find((b) => b.id === id) ?? KARDASHEV_BANDS[0];
  });

  protected readonly factionName = computed(() => {
    const f = this.empire.state();
    return f ? (this.factionStore.getById(f.factionId)?.name ?? 'Command') : 'Command';
  });

  protected readonly ledgerRows = computed(() => {
    const l = this.empire.ledger();
    if (!l) return [];
    return [
      { key: 'energy', label: 'Energy', value: l.energy.value, delta: l.energy.delta, colour: 'var(--sc-energy)' },
      { key: 'minerals', label: 'Minerals', value: l.minerals.value, delta: l.minerals.delta, colour: 'var(--sc-minerals)' },
      { key: 'credits', label: 'Credits', value: l.credits.value, delta: l.credits.delta, colour: 'var(--sc-credits)' },
      { key: 'alloys', label: 'Alloys', value: l.alloys.value, delta: l.alloys.delta, colour: 'var(--sc-alloys)' },
      { key: 'influence', label: 'Influence', value: l.influence.value, delta: l.influence.delta, colour: 'var(--sc-influence)' },
    ];
  });

  protected markerLeft(): string {
    const pct = Math.min(this.k().k / 3, 1) * 100;
    return pct.toFixed(1) + '%';
  }
  protected segBg(accent: string): string {
    return `linear-gradient(180deg, ${accent}22, ${accent}11)`;
  }
  protected fillBg(): string {
    return `linear-gradient(90deg, ${this.bands[0].accent}, ${this.band().accent})`;
  }
  protected nextTierLabel(): string {
    const idx = this.bands.findIndex((b) => b.id === this.k().tierId);
    return this.bands[Math.min(idx + 1, this.bands.length - 1)].label;
  }
  protected srcW(watts: number): string {
    const total = this.k().totalWatts || 1;
    return Math.max(2, (watts / total) * 100) + '%';
  }
  protected romanTier(tier: number): string {
    return tier === 1 ? 'I' : tier === 2 ? 'II' : 'III';
  }
}
