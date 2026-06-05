import { ChangeDetectionStrategy, Component, computed, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { EmpireStore } from '../../stores/empire.store';
import { FactionStore } from '../../stores';
import { DemoModeService } from '../../services/demo-mode.service';
import type { BuildOrder, Relation, TradeAgreement } from '../../stores/empire.store';
import { GalaxyMinimapComponent } from './galaxy-minimap.component';

function fmtN(n: number): string {
  if (n >= 1_000_000) return (n / 1_000_000).toFixed(1) + 'M';
  if (n >= 1_000) return (n / 1_000).toFixed(1) + 'k';
  return String(Math.round(n));
}

@Component({
  selector: 'app-empire-rail',
  changeDetection: ChangeDetectionStrategy.OnPush,
  standalone: true,
  imports: [CommonModule, GalaxyMinimapComponent],
  template: `
    <!-- Galaxy minimap: top of the left rail -->
    <section class="sc-panel rail-card minimap-card">
      <div class="sc-head">Galaxy<span class="sc-head-rule"></span></div>
      <div class="minimap-wrap">
        <app-galaxy-minimap />
      </div>
    </section>

    <section class="sc-panel rail-card">
      <div class="sc-head">Empire<span class="sc-head-rule"></span></div>
      <div class="stat-grid">
        <div class="stat-tile">
          <span class="stat-val">{{ empire.state()?.systemCount ?? 0 }}</span>
          <span class="stat-lbl">Systems</span>
        </div>
        <div class="stat-tile">
          <span class="stat-val">{{ empire.state()?.planetCount ?? 0 }}</span>
          <span class="stat-lbl">Planets</span>
        </div>
        <div class="stat-tile">
          <span class="stat-val">{{ fmtN(empire.state()?.population ?? 0) }}</span>
          <span class="stat-lbl">Population</span>
        </div>
        <div class="stat-tile">
          <span class="stat-val">{{ empire.totalFleetStrength() }}</span>
          <span class="stat-lbl">Fleet Str.</span>
        </div>
      </div>
      @if (playerFaction(); as f) {
        <div class="rep-row">
          <span class="rep-lbl">Rep</span>
          <div class="rep-bar-wrap">
            <div class="rep-bar-track">
              <div class="rep-bar-neg" [style.width]="repNegW(f.reputation)"></div>
              <div class="rep-bar-pos" [style.width]="repPosW(f.reputation)"></div>
            </div>
          </div>
          <span class="rep-val" [class.pos]="f.reputation>0.05" [class.neg]="f.reputation<-0.05">
            {{ (f.reputation>=0?'+':'') + f.reputation.toFixed(2) }}
          </span>
        </div>
      }
    </section>
    @if (empire.builds().length > 0) {
      <section class="sc-panel rail-card">
        <div class="sc-head">Build Queue<span class="sc-head-rule"></span></div>
        <ul class="item-list">
          @for (b of empire.builds(); track b.id) {
            <li class="build-item">
              <div class="build-header">
                <span class="build-icon" [class]="'bcat-' + b.category">{{ buildGlyph(b) }}</span>
                <span class="build-name" [title]="b.name + ' @ ' + b.location">{{ b.name }}</span>
                <span class="build-eta">{{ b.etaTicks }}t</span>
              </div>
              <div class="sc-bar-track">
                <div class="sc-bar-fill" [class]="'bfill-' + b.category" [style.width]="(b.progress*100)+'%'"></div>
              </div>
              <span class="build-loc">{{ b.location }}</span>
            </li>
          }
        </ul>
      </section>
    }
    @if (empire.activeResearch().length > 0) {
      <section class="sc-panel rail-card">
        <div class="sc-head">Research<span class="sc-head-rule"></span>
          <span class="unlocked-badge">{{ empire.completedResearch().length }} done</span>
        </div>
        <ul class="item-list">
          @for (r of empire.activeResearch(); track r.id) {
            <li class="research-item">
              <div class="res-header">
                <span class="field-dot" [class]="'field-' + r.field"></span>
                <span class="research-name">{{ r.name }}</span>
                <span class="research-eta">{{ r.etaTicks }}t</span>
              </div>
              <div class="sc-bar-track">
                <div class="sc-bar-fill" [class]="'ffield-' + r.field" [style.width]="(r.progress*100)+'%'"></div>
              </div>
            </li>
          }
        </ul>
      </section>
    }
    @if (empire.fleets().length > 0) {
      <section class="sc-panel rail-card">
        <div class="sc-head">Fleets<span class="sc-head-rule"></span></div>
        <ul class="item-list">
          @for (f of empire.fleets(); track f.id) {
            <li class="fleet-item">
              <span class="fleet-dot" [class]="'fst-' + f.status"></span>
              <span class="fleet-name">{{ f.name }}</span>
              <span class="fleet-str">{{ f.strength }}</span>
              <span class="sc-tag fleet-tag" [class]="'ftag-' + f.status">{{ f.status }}</span>
            </li>
          }
        </ul>
      </section>
    }
    @if (empire.trades().length > 0) {
      <section class="sc-panel rail-card">
        <div class="sc-head">Trade<span class="sc-head-rule"></span></div>
        <ul class="item-list">
          @for (t of empire.trades(); track t.id) {
            <li class="trade-item">
              <div class="trade-partner">
                @if (tradeFactionColour(t); as col) {
                  <span class="partner-dot" [style.background]="col"></span>
                }
                <span class="partner-name">{{ tradePartnerName(t) }}</span>
                <span class="sc-tag tst-tag" [class]="'tst-' + t.status">{{ t.status }}</span>
              </div>
              <div class="trade-detail">
                <span class="trade-goods">{{ t.gives }} &#8594; {{ t.gets }}</span>
                <span class="trade-bal" [class.pos]="t.balancePerTick>=0" [class.neg]="t.balancePerTick<0">
                  {{ t.balancePerTick >= 0 ? '+' : '' }}{{ t.balancePerTick }}/t
                </span>
              </div>
            </li>
          }
        </ul>
      </section>
    }
    @if (empire.relations().length > 0) {
      <section class="sc-panel rail-card">
        <div class="sc-head">Diplomacy<span class="sc-head-rule"></span>
          @if (empire.wars().length > 0) {
            <span class="war-count sc-tag">AT WAR: {{ empire.wars().length }}</span>
          }
        </div>
        <ul class="item-list">
          @for (rel of empire.relations(); track rel.factionId) {
            <li class="relation-item">
              @if (relationFaction(rel); as f) {
                <span class="rel-swatch" [style.background]="f.colour"></span>
                <span class="rel-name">{{ f.name }}</span>
              } @else {
                <span class="rel-swatch"></span>
                <span class="rel-name">{{ rel.factionId }}</span>
              }
              <span class="sc-tag rel-tag" [class]="'rst-' + rel.status">{{ rel.status }}</span>
              <div class="opinion-bar">
                <div class="opinion-fill" [style.left]="opinionLeft(rel.opinion)" [style.width]="opinionW(rel.opinion)"></div>
                <div class="opinion-mid"></div>
              </div>
              <span class="opinion-val">{{ rel.opinion.toFixed(2) }}</span>
            </li>
          }
        </ul>
      </section>
    }
  `,
  styles: [`
    :host { display: flex; flex-direction: column; gap: 6px; }
    .rail-card { overflow: hidden; }
    .minimap-card { overflow: hidden; }
    .minimap-wrap { padding: 0 8px 8px; display: flex; justify-content: center; }
    .stat-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 4px; padding: 0 10px 8px; }
    .stat-tile { display: flex; flex-direction: column; align-items: center; padding: 7px 4px; background: transparent; border: 1px solid var(--sc-border); border-radius: var(--rounded-xs); }
    .stat-val { font: 700 17px/1 var(--sc-mono); font-variant-numeric: tabular-nums; color: var(--sc-text); }
    .stat-lbl { font: 400 8.5px/1 var(--din-body); letter-spacing: 1.6px; text-transform: uppercase; color: var(--sc-text-faint); margin-top: 3px; }
    .rep-row { display: flex; align-items: center; gap: 6px; padding: 2px 10px 8px; }
    .rep-lbl { font: 400 9px/1 var(--din-body); letter-spacing: 1.6px; text-transform: uppercase; color: var(--sc-text-faint); flex-shrink: 0; }
    .rep-bar-wrap { flex: 1; }
    .rep-bar-track { position: relative; height: 5px; border-radius: var(--rounded-full); background: rgba(255,255,255,0.08); overflow: hidden; }
    .rep-bar-neg { position: absolute; right: 50%; top: 0; bottom: 0; background: var(--sc-bad); border-radius: var(--rounded-full) 0 0 var(--rounded-full); transition: width 0.5s ease; }
    .rep-bar-pos { position: absolute; left: 50%; top: 0; bottom: 0; background: var(--sc-good); border-radius: 0 var(--rounded-full) var(--rounded-full) 0; transition: width 0.5s ease; }
    .rep-val { font: 600 10px/1 var(--sc-mono); font-variant-numeric: tabular-nums; flex-shrink: 0; }
    .item-list { list-style: none; margin: 0; padding: 0 10px 8px; display: flex; flex-direction: column; gap: 8px; }
    .build-item, .research-item, .trade-item { display: flex; flex-direction: column; gap: 3px; }
    .build-header, .res-header { display: flex; align-items: center; gap: 5px; }
    .build-icon { font-size: 11px; flex-shrink: 0; width: 14px; text-align: center; }
    .build-name, .research-name { flex: 1; font: 600 11px/1 var(--din-body); color: var(--sc-text); white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
    .build-eta, .research-eta { font: 600 9px/1 var(--sc-mono); color: var(--sc-text-dim); flex-shrink: 0; }
    .build-loc { font: 10px var(--sc-mono); color: var(--sc-text-faint); }
    .bcat-military { color: var(--sc-bad); }
    .bcat-science { color: var(--sc-energy); }
    .bcat-industry { color: var(--sc-minerals); }
    .bcat-economy { color: var(--sc-credits); }
    .bcat-defense { color: var(--sc-alloys); }
    .bfill-military { background: var(--sc-bad); }
    .bfill-science { background: var(--sc-energy); }
    .bfill-industry { background: var(--sc-minerals); }
    .bfill-economy { background: var(--sc-credits); }
    .bfill-defense { background: var(--sc-alloys); }
    .field-dot { width: 8px; height: 8px; border-radius: 50%; flex-shrink: 0; }
    .field-physics { background: var(--sc-energy); }
    .field-engineering { background: var(--sc-warn); }
    .field-society { background: var(--sc-influence); }
    .ffield-physics { background: var(--sc-energy); }
    .ffield-engineering { background: var(--sc-warn); }
    .ffield-society { background: var(--sc-influence); }
    .unlocked-badge { font: 600 9px/1 var(--sc-mono); color: var(--sc-good); flex-shrink: 0; }
    .fleet-item { display: flex; align-items: center; gap: 6px; }
    .fleet-dot { width: 7px; height: 7px; border-radius: 50%; flex-shrink: 0; }
    .fst-idle { background: var(--sc-text-faint); }
    .fst-moving { background: var(--sc-energy); }
    .fst-engaged { background: var(--sc-bad); }
    .fst-defending { background: var(--sc-alloys); }
    .fleet-name { flex: 1; font: 600 11px/1 var(--din-body); color: var(--sc-text); white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
    .fleet-str { font: 600 11px/1 var(--sc-mono); font-variant-numeric: tabular-nums; color: var(--sc-text-dim); flex-shrink: 0; }
    .fleet-tag { font-size: 8px; flex-shrink: 0; }
    .ftag-idle { color: var(--sc-text-faint); border-color: var(--sc-border); }
    .ftag-moving { color: var(--sc-energy); border-color: rgba(127,216,239,0.3); }
    .ftag-engaged { color: var(--sc-bad); border-color: rgba(255,84,104,0.3); }
    .ftag-defending { color: var(--sc-alloys); border-color: rgba(194,168,232,0.3); }
    .trade-partner { display: flex; align-items: center; gap: 5px; }
    .partner-dot { width: 8px; height: 8px; border-radius: 50%; flex-shrink: 0; }
    .partner-name { flex: 1; font: 600 11px/1 var(--din-body); color: var(--sc-text); white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
    .trade-detail { display: flex; align-items: center; justify-content: space-between; }
    .trade-goods { font: 10px var(--sc-mono); color: var(--sc-text-dim); }
    .trade-bal { font: 600 10px/1 var(--sc-mono); font-variant-numeric: tabular-nums; }
    .tst-tag { font-size: 8px; flex-shrink: 0; }
    .tst-active { color: var(--sc-good); border-color: rgba(95,214,164,0.35); }
    .tst-pending { color: var(--sc-warn); border-color: rgba(232,192,97,0.35); }
    .tst-strained { color: var(--sc-bad); border-color: rgba(255,84,104,0.35); }
    .relation-item { display: flex; align-items: center; gap: 5px; }
    .rel-swatch { width: 8px; height: 8px; border-radius: 2px; flex-shrink: 0; }
    .rel-name { flex: 1; font: 600 10px/1 var(--din-body); color: var(--sc-text); white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
    .rel-tag { font-size: 8px; flex-shrink: 0; }
    .rst-war { color: var(--sc-bad); border-color: rgba(255,84,104,0.4); background: rgba(255,84,104,0.08); }
    .rst-allied { color: var(--sc-good); border-color: rgba(95,214,164,0.4); background: rgba(95,214,164,0.08); }
    .rst-truce, .rst-tribute { color: var(--sc-warn); border-color: rgba(232,192,97,0.35); }
    .rst-neutral { color: var(--sc-text-dim); border-color: var(--sc-border); }
    .opinion-bar { position: relative; width: 42px; height: 4px; background: rgba(255,255,255,0.08); border-radius: 2px; flex-shrink: 0; overflow: hidden; }
    .opinion-fill { position: absolute; top: 0; bottom: 0; background: var(--sc-text-dim); border-radius: 2px; transition: left 0.4s ease, width 0.4s ease; }
    .opinion-mid { position: absolute; left: 50%; top: 0; bottom: 0; width: 1px; background: var(--sc-border-bright); }
    .opinion-val { font: 600 9px/1 var(--sc-mono); font-variant-numeric: tabular-nums; color: var(--sc-text-faint); width: 28px; text-align: right; flex-shrink: 0; }
    .war-count { color: var(--sc-bad); border-color: rgba(255,84,104,0.4); background: rgba(255,84,104,0.1); font-size: 8px; flex-shrink: 0; }
    .pos { color: var(--sc-good); }
    .neg { color: var(--sc-bad); }
  `],
})
export class EmpireRailComponent {
  protected readonly empire = inject(EmpireStore);
  protected readonly factionStore = inject(FactionStore);
  protected readonly demo = inject(DemoModeService);
  protected readonly fmtN = fmtN;

  protected readonly playerFaction = computed(() => {
    const id = this.demo.playerFactionId();
    return id ? (this.factionStore.getById(id) ?? null) : null;
  });

  protected relationFaction(rel: Relation) {
    return this.factionStore.getById(rel.factionId) ?? null;
  }

  protected tradePartnerName(t: TradeAgreement): string {
    if (t.partnerFactionId === 'MARKET') return 'Open Market';
    return this.factionStore.getById(t.partnerFactionId)?.name ?? t.partnerFactionId;
  }

  protected tradeFactionColour(t: TradeAgreement): string | null {
    if (t.partnerFactionId === 'MARKET') return '#ffd27a';
    return this.factionStore.getById(t.partnerFactionId)?.colour ?? null;
  }

  protected buildGlyph(b: BuildOrder): string {
    switch (b.category) {
      case 'military':  return '⚔';
      case 'science':   return '⚻';
      case 'industry':  return '⚙';
      case 'economy':   return '◈';
      case 'defense':   return '■';
    }
  }

  protected repPosW(r: number): string { return (Math.max(0, r) * 50) + '%'; }
  protected repNegW(r: number): string { return (Math.max(0, -r) * 50) + '%'; }
  protected opinionLeft(op: number): string { return op >= 0 ? '50%' : (50 + op * 50) + '%'; }
  protected opinionW(op: number): string { return (Math.abs(op) * 50) + '%'; }
}
