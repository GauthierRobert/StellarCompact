import {
  ChangeDetectionStrategy,
  Component,
  computed,
  inject,
} from '@angular/core';
import { CommonModule } from '@angular/common';
import { EmpireStore } from '../../stores/empire.store';
import {
  TECH_TREE,
  TECH_BY_ID,
  TIER_ORDER,
  TIER_TIME,
  TIER_LABEL,
  BRANCH_ORDER,
  BRANCH_LABEL,
  type TechDef,
  type TechBranch,
} from './tech-tree';
import { KARDASHEV_BANDS, formatK, formatWatts } from './kardashev';
import { FIELD_COLOUR } from './ui-format';
import type { TechNodeState } from '../../stores/empire.store';

interface TechCardVM extends TechDef {
  status: TechNodeState['status'];
  progress: number;
  etaTicks: number;
  prereqNames: string[];
}

/**
 * Technology & Ascension — the research centerpiece. The tiered DAG makes the
 * defining rule visible at a glance: research time grows steeply by tier, so
 * advanced tech takes far longer than simple tech. The Ascension column is the
 * Kardashev climb; the live K-gauge and the three ascension gates sit on top,
 * with the megastructure roster below.
 */
@Component({
  selector: 'app-empire-tech',
  changeDetection: ChangeDetectionStrategy.OnPush,
  standalone: true,
  imports: [CommonModule],
  template: `
    <div class="page">
      <header class="page-head">
        <h1>Technology &amp; Ascension</h1>
        <p class="sub">
          The research web is a tiered DAG — simple tech is fast, advanced tech is slow.
          Climb the Ascension branch to ascend the Kardashev scale.
        </p>
      </header>

      <!-- KARDASHEV LADDER -->
      <section class="sc-panel ladder">
        <div class="ladder-top">
          <div class="ladder-tier" [style.color]="bandAccent()">
            <span class="lt-label">{{ k().tierLabel }}</span>
            <span class="lt-k">{{ formatK(k().k) }}</span>
          </div>
          <div class="ladder-gauge">
            <div class="gauge-track">
              @for (b of bands; track b.id) {
                <div class="gauge-seg"></div>
              }
              <div class="gauge-fill" [style.width]="markerLeft()" [style.background]="bandAccent()"></div>
              <div class="gauge-marker" [style.left]="markerLeft()" [style.background]="bandAccent()"></div>
            </div>
            <div class="gauge-ticks"><span>0</span><span>I</span><span>II</span><span>III</span></div>
          </div>
          <div class="ladder-watts">{{ formatWatts(k().totalWatts) }}</div>
        </div>
        <!-- Ascension gates -->
        <div class="gates">
          @for (g of gates(); track g.id) {
            <div class="gate" [class]="'tn-' + g.status">
              <span class="gate-k">K{{ g.kardashevGate }}</span>
              <span class="gate-name">{{ g.name }}</span>
              <span class="gate-status">{{ statusLabel(g) }}</span>
            </div>
          }
        </div>
      </section>

      <!-- RESEARCH-TIME LEGEND (makes "advanced takes longer" explicit) -->
      <section class="sc-panel legend">
        <div class="sc-head">Research Time by Tier<span class="sc-head-rule"></span>
          <span class="legend-note">ticks to research — steep by design</span>
        </div>
        <div class="legend-row">
          @for (tier of tiers; track tier) {
            <div class="legend-item">
              <span class="legend-tier">{{ tierLabel(tier) }}</span>
              <div class="legend-bar"><div class="legend-fill" [style.width]="legendW(tier)"></div></div>
              <span class="legend-time">{{ tierTime(tier) }}t</span>
            </div>
          }
        </div>
      </section>

      <!-- TECH TREE: branch columns × tier -->
      <section class="tree">
        @for (col of byBranch(); track col.branch) {
          <div class="branch" [class.ascension]="col.branch === 'ascension'">
            <div class="branch-head">
              <span class="branch-name">{{ col.label }}</span>
              <span class="branch-count">{{ col.doneCount }}/{{ col.nodes.length }}</span>
            </div>
            <div class="branch-nodes">
              @for (n of col.nodes; track n.id) {
                <div class="node" [class]="'tn-' + n.status">
                  <div class="node-top">
                    <span class="field-dot" [style.background]="fieldColour(n)"></span>
                    <span class="node-name">{{ n.name }}</span>
                    <span class="tier-badge">{{ tierShort(n.tier) }}·{{ n.timeTicks }}t</span>
                  </div>
                  <div class="node-unlocks">{{ n.unlocks }}</div>
                  @if (n.status === 'researching') {
                    <div class="node-bar"><div class="node-fill" [style.width]="(n.progress*100)+'%'"></div></div>
                    <div class="node-eta">researching · {{ n.etaTicks }}t left</div>
                  } @else if (n.status === 'done') {
                    <div class="node-done">✓ unlocked</div>
                  } @else if (n.status === 'available') {
                    <div class="node-avail">▸ available</div>
                  } @else {
                    <div class="node-locked">needs {{ n.prereqNames.join(', ') || '—' }}</div>
                  }
                </div>
              }
            </div>
          </div>
        }
      </section>

      <!-- MEGASTRUCTURES -->
      <section class="sc-panel">
        <div class="sc-head">Megastructures<span class="sc-head-rule"></span>
          <span class="legend-note">Kardashev engines — they raise captured power</span>
        </div>
        @if (empire.megastructures().length === 0) {
          <p class="muted pad">None yet. Research <strong>Orbital Collectors</strong> to begin the first megastructure.</p>
        } @else {
          <div class="mega-grid">
            @for (m of empire.megastructures(); track m.id) {
              <div class="mega">
                <div class="mega-top">
                  <span class="mega-name">{{ m.name }}</span>
                  <span class="sc-tag mega-tier">Type {{ romanTier(m.tier) }}</span>
                </div>
                <div class="mega-stage">Stage {{ m.stage }}/{{ m.maxStage }} · {{ m.systemName }}</div>
                <div class="mega-bar"><div class="mega-fill" [style.width]="(m.progress*100)+'%'"></div></div>
                <div class="mega-out">+{{ formatWatts(m.outputWatts) }} captured</div>
              </div>
            }
          </div>
        }
      </section>
    </div>
  `,
  styles: [`
    :host { display: block; }
    .page { max-width: 1280px; margin: 0 auto; padding: 22px 20px 60px; }
    .page-head { margin-bottom: 16px; }
    h1 { font: 700 24px/1.1 var(--din-display); margin: 0; color: var(--sc-text); }
    .sub { font: 400 13px/1.5 var(--din-body); color: var(--sc-text-dim); margin: 6px 0 0; max-width: 680px; }
    .muted { color: var(--sc-text-faint); font: 400 12px/1.5 var(--din-body); }
    .pad { padding: 4px 14px 14px; }
    section { margin-bottom: 14px; }
    .legend-note { margin-left: auto; font: 400 10px/1 var(--din-body); color: var(--sc-text-faint); }

    /* Kardashev ladder */
    .ladder { padding: 16px 18px 14px; }
    .ladder-top { display: flex; align-items: center; gap: 16px; margin-bottom: 14px; }
    .ladder-tier { display: flex; flex-direction: column; gap: 2px; flex-shrink: 0; }
    .lt-label { font: 700 22px/1 var(--din-display); }
    .lt-k { font: 500 12px/1 var(--din-body); color: var(--sc-text-dim); font-variant-numeric: tabular-nums; }
    .ladder-gauge { flex: 1; }
    .gauge-track { position: relative; display: flex; height: 12px; border-radius: var(--rounded-full); overflow: hidden; border: 1px solid var(--sc-border); background: rgba(255,255,255,0.04); }
    .gauge-seg { flex: 1; }
    .gauge-seg + .gauge-seg { border-left: 1px solid rgba(255,255,255,0.12); }
    .gauge-fill { position: absolute; left: 0; top: 0; bottom: 0; opacity: 0.5; transition: width 0.6s ease; }
    .gauge-marker { position: absolute; top: -3px; width: 4px; height: 18px; border-radius: 2px; transform: translateX(-2px); box-shadow: 0 0 8px currentColor; transition: left 0.6s ease; }
    .gauge-ticks { display: flex; justify-content: space-between; margin-top: 4px; font: 600 9px/1 var(--din-body); color: var(--sc-text-faint); }
    .ladder-watts { flex-shrink: 0; font: 700 15px/1 var(--din-display); color: var(--sc-text); font-variant-numeric: tabular-nums; }
    .gates { display: grid; grid-template-columns: repeat(3, 1fr); gap: 8px; }
    .gate { display: flex; flex-direction: column; gap: 2px; padding: 9px 11px; border: 1px solid var(--sc-border); border-radius: var(--rounded-xs); }
    .gate-k { font: 700 11px/1 var(--din-display); color: var(--sc-text-faint); }
    .gate-name { font: 600 12px/1.1 var(--din-body); color: var(--sc-text); }
    .gate-status { font: 500 10px/1 var(--din-body); color: var(--sc-text-dim); }

    /* Time legend */
    .legend-row { display: flex; flex-wrap: wrap; gap: 14px; padding: 4px 14px 14px; }
    .legend-item { display: flex; align-items: center; gap: 7px; flex: 1; min-width: 130px; }
    .legend-tier { width: 78px; flex-shrink: 0; font: 600 10px/1.1 var(--din-body); color: var(--sc-text-dim); }
    .legend-bar { flex: 1; height: 6px; background: rgba(255,255,255,0.07); border-radius: var(--rounded-full); overflow: hidden; }
    .legend-fill { height: 100%; background: linear-gradient(90deg, var(--sc-energy), var(--sc-influence)); }
    .legend-time { width: 38px; text-align: right; font: 700 10px/1 var(--din-body); color: var(--sc-text); font-variant-numeric: tabular-nums; }

    /* Tech tree */
    .tree { display: grid; grid-template-columns: repeat(5, 1fr); gap: 10px; }
    @media (max-width: 1080px) { .tree { grid-template-columns: repeat(2, 1fr); } }
    @media (max-width: 560px) { .tree { grid-template-columns: 1fr; } }
    .branch { background: var(--sc-panel); border: 1px solid var(--sc-border); border-radius: var(--rounded-sm); padding: 10px; display: flex; flex-direction: column; gap: 8px; }
    .branch.ascension { border-color: rgba(232,154,196,0.35); background: linear-gradient(180deg, rgba(232,154,196,0.06), rgba(10,10,10,0.82)); }
    .branch-head { display: flex; align-items: baseline; justify-content: space-between; padding-bottom: 6px; border-bottom: 1px solid var(--sc-border); }
    .branch-name { font: 700 11px/1 var(--din-display); letter-spacing: 1px; text-transform: uppercase; color: var(--sc-text); }
    .branch-count { font: 600 10px/1 var(--din-body); color: var(--sc-text-faint); font-variant-numeric: tabular-nums; }
    .branch-nodes { display: flex; flex-direction: column; gap: 7px; }
    .node { padding: 8px 9px; border: 1px solid var(--sc-border); border-radius: var(--rounded-xs); display: flex; flex-direction: column; gap: 4px; transition: border-color 0.2s, background 0.2s; }
    .node-top { display: flex; align-items: center; gap: 6px; }
    .field-dot { width: 7px; height: 7px; border-radius: 50%; flex-shrink: 0; }
    .node-name { flex: 1; font: 600 11px/1.15 var(--din-body); color: var(--sc-text); }
    .tier-badge { font: 700 8.5px/1 var(--din-body); color: var(--sc-text-faint); letter-spacing: 0.3px; flex-shrink: 0; font-variant-numeric: tabular-nums; }
    .node-unlocks { font: 400 9.5px/1.3 var(--din-body); color: var(--sc-text-faint); }
    .node-bar { height: 4px; background: rgba(255,255,255,0.08); border-radius: var(--rounded-full); overflow: hidden; }
    .node-fill { height: 100%; background: var(--sc-energy); transition: width 0.5s ease; }
    .node-eta { font: 600 9px/1 var(--din-body); color: var(--sc-energy); }
    .node-done { font: 600 9px/1 var(--din-body); color: var(--sc-good); }
    .node-avail { font: 600 9px/1 var(--din-body); color: var(--sc-warn); }
    .node-locked { font: 400 9px/1.2 var(--din-body); color: var(--sc-text-faint); }
    /* status skins */
    .tn-done { background: rgba(95,214,164,0.06); border-color: rgba(95,214,164,0.25); }
    .tn-researching { border-color: rgba(127,216,239,0.45); background: rgba(127,216,239,0.06); box-shadow: 0 0 0 1px rgba(127,216,239,0.12) inset; }
    .tn-available { border-color: rgba(232,192,97,0.40); }
    .tn-locked { opacity: 0.62; }

    /* megastructures */
    .mega-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(220px,1fr)); gap: 10px; padding: 4px 14px 14px; }
    .mega { display: flex; flex-direction: column; gap: 4px; padding: 10px; border: 1px solid var(--sc-border); border-radius: var(--rounded-xs); }
    .mega-top { display: flex; align-items: center; gap: 8px; }
    .mega-name { flex: 1; font: 600 12px/1.1 var(--din-body); color: var(--sc-text); }
    .mega-tier { font-size: 8px; color: var(--sc-influence); border-color: rgba(232,154,196,0.35); }
    .mega-stage { font: 400 10px/1 var(--din-body); color: var(--sc-text-faint); }
    .mega-bar { height: 6px; background: rgba(255,255,255,0.08); border-radius: var(--rounded-full); overflow: hidden; }
    .mega-fill { height: 100%; background: var(--sc-influence); transition: width 0.5s ease; }
    .mega-out { font: 600 10px/1 var(--din-body); color: var(--sc-influence); font-variant-numeric: tabular-nums; }
  `],
})
export class TechComponent {
  protected readonly empire = inject(EmpireStore);
  protected readonly formatK = formatK;
  protected readonly formatWatts = formatWatts;
  protected readonly bands = KARDASHEV_BANDS;
  protected readonly tiers = TIER_ORDER;

  protected readonly k = this.empire.kardashev;

  private readonly stateById = computed(
    () => new Map(this.empire.tech().map((s) => [s.id, s])),
  );

  private toVM(def: TechDef): TechCardVM {
    const st = this.stateById().get(def.id);
    return {
      ...def,
      status: st?.status ?? 'locked',
      progress: st?.progress ?? 0,
      etaTicks: st?.etaTicks ?? def.timeTicks,
      prereqNames: def.prereqs.map((p) => TECH_BY_ID.get(p)?.name ?? p),
    };
  }

  protected readonly byBranch = computed(() =>
    BRANCH_ORDER.map((branch: TechBranch) => {
      const nodes = TECH_TREE.filter((d) => d.branch === branch)
        .sort((a, b) => TIER_ORDER.indexOf(a.tier) - TIER_ORDER.indexOf(b.tier))
        .map((d) => this.toVM(d));
      return {
        branch,
        label: BRANCH_LABEL[branch],
        nodes,
        doneCount: nodes.filter((n) => n.status === 'done').length,
      };
    }),
  );

  protected readonly gates = computed(() =>
    TECH_TREE.filter((d) => d.kardashevGate).map((d) => this.toVM(d)),
  );

  protected bandAccent(): string {
    const b = KARDASHEV_BANDS.find((x) => x.id === this.k().tierId);
    return b?.accent ?? '#9fb2c8';
  }
  protected markerLeft(): string {
    return (Math.min(this.k().k / 3, 1) * 100).toFixed(1) + '%';
  }
  protected fieldColour(n: TechCardVM): string {
    return FIELD_COLOUR[n.field];
  }
  protected tierLabel(tier: string): string {
    return TIER_LABEL[tier as keyof typeof TIER_LABEL] ?? tier;
  }
  protected tierShort(tier: string): string {
    return tier;
  }
  protected tierTime(tier: string): number {
    return TIER_TIME[tier as keyof typeof TIER_TIME] ?? 0;
  }
  protected legendW(tier: string): string {
    const max = TIER_TIME.K3;
    return Math.max(3, (this.tierTime(tier) / max) * 100) + '%';
  }
  protected statusLabel(g: TechCardVM): string {
    switch (g.status) {
      case 'done': return 'Achieved';
      case 'researching': return `Researching · ${g.etaTicks}t`;
      case 'available': return 'Available';
      default: return 'Locked';
    }
  }
  protected romanTier(tier: number): string {
    return tier === 1 ? 'I' : tier === 2 ? 'II' : 'III';
  }
}
