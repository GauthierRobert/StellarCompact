import {
  ChangeDetectionStrategy,
  Component,
  inject,
} from '@angular/core';
import { CommonModule } from '@angular/common';
import { EmpireStore } from '../../stores/empire.store';
import type { WarSummary } from '../../stores/empire.store';
import { FactionStore } from '../../stores';
import { DemoModeService } from '../../services/demo-mode.service';

@Component({
  selector: 'app-empire-wars',
  changeDetection: ChangeDetectionStrategy.OnPush,
  standalone: true,
  imports: [CommonModule],
  template: `
    <header class="page-header">
      <h1>Wars &amp; Fronts</h1>
      <p class="subtitle">
        <span class="stat-chip">
          <span class="chip-val">{{ empire.wars().length }}</span>
          <span class="chip-lbl">Active Wars</span>
        </span>
        <span class="stat-chip">
          <span class="chip-val">{{ totalFronts() }}</span>
          <span class="chip-lbl">Total Fronts</span>
        </span>
        <span class="stat-chip">
          <span class="chip-val">{{ totalFleetsEngaged() }}</span>
          <span class="chip-lbl">Fleets Engaged</span>
        </span>
      </p>
    </header>

    @if (empire.wars().length === 0) {
      <div class="sc-panel empty-state">
        <span class="empty-icon">&#9651;</span>
        No active wars — the compact holds.
      </div>
    } @else {
      @for (w of empire.wars(); track w.enemyFactionId) {
        <article class="sc-panel war-card" [class.losing]="w.warScore < 0">
          <div class="war-header">
            @if (enemy(w.enemyFactionId); as f) {
              <span class="faction-swatch" [style.background]="f.colour"></span>
              <span class="faction-name">{{ f.name }}</span>
            } @else {
              <span class="faction-swatch"></span>
              <span class="faction-name">{{ w.enemyFactionId }}</span>
            }
            <span class="sc-tag rst-war">AT WAR</span>
          </div>

          <div class="war-since">
            Since T+{{ w.sinceTick }}&nbsp;&middot;&nbsp;{{ duration(w) }}t
          </div>

          <div class="score-section">
            <span class="score-label">WAR SCORE</span>
            <div class="score-bar-wrap">
              <div class="score-bar-track">
                <div
                  class="score-fill score-fill-neg"
                  [style.right]="'50%'"
                  [style.width]="scoreLoseW(w.warScore)"
                ></div>
                <div
                  class="score-fill score-fill-pos"
                  [style.left]="'50%'"
                  [style.width]="scoreWinW(w.warScore)"
                ></div>
                <div class="score-centre-line"></div>
              </div>
            </div>
            <span
              class="score-val"
              [class.pos]="w.warScore > 0.02"
              [class.neg]="w.warScore < -0.02"
            >{{ fmtScore(w.warScore) }}</span>
          </div>

          <div class="war-stats">
            <span class="stat-battles">
              {{ w.battlesWon }}<span class="wl-sep">W</span>
              /
              {{ w.battlesLost }}<span class="wl-sep">L</span>
            </span>
            <span class="stat-fleets">
              <span class="fleets-val">{{ w.fleetsEngaged }}</span>
              <span class="fleets-lbl">&nbsp;fleets engaged</span>
            </span>
          </div>

          <div class="fronts-section">
            <div class="sc-head fronts-head">Fronts<span class="sc-head-rule"></span></div>
            @if (w.fronts.length === 0) {
              <span class="no-fronts">No contested fronts</span>
            } @else {
              <ul class="fronts-list">
                @for (front of w.fronts; track front.systemId) {
                  <li class="front-item">
                    <span class="front-name">{{ front.systemName }}</span>
                    <span class="front-dots">
                      <span
                        class="dot"
                        [class.dot-active]="front.intensity >= 2"
                        [class.dot-warn]="front.intensity === 1"
                      ></span>
                      @if (front.intensity >= 2) {
                        <span class="dot dot-active"></span>
                      }
                    </span>
                    <span
                      class="sc-tag front-tag"
                      [class.int-active]="front.intensity >= 2"
                      [class.int-warn]="front.intensity === 1"
                    >{{ front.intensity >= 2 ? 'Active' : 'Contested' }}</span>
                  </li>
                }
              </ul>
            }
          </div>
        </article>
      }
    }

    @if (empire.relations().length > 0) {
      <section class="sc-panel diplomacy-card">
        <div class="sc-head">Diplomacy<span class="sc-head-rule"></span></div>
        <ul class="rel-list">
          @for (rel of empire.relations(); track rel.factionId) {
            <li class="rel-item">
              @if (enemy(rel.factionId); as f) {
                <span class="rel-swatch" [style.background]="f.colour"></span>
                <span class="rel-name">{{ f.name }}</span>
              } @else {
                <span class="rel-swatch"></span>
                <span class="rel-name">{{ rel.factionId }}</span>
              }
              <span class="sc-tag rel-tag" [class]="'rst-' + rel.status">{{ rel.status }}</span>
              <div class="opinion-bar">
                <div
                  class="opinion-fill"
                  [style.left]="opinionLeft(rel.opinion)"
                  [style.width]="opinionW(rel.opinion)"
                ></div>
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
    :host {
      display: flex;
      flex-direction: column;
      gap: 10px;
      padding: 18px 16px 32px;
    }

    /* ---- Page header ---- */
    .page-header { margin-bottom: 4px; }
    h1 {
      font: 700 22px/1 var(--din-display);
      letter-spacing: 1.2px;
      text-transform: uppercase;
      color: var(--sc-text);
      margin: 0 0 10px;
    }
    .subtitle { display: flex; flex-wrap: wrap; gap: 8px; margin: 0; }
    .stat-chip {
      display: flex;
      align-items: baseline;
      gap: 5px;
      padding: 4px 10px;
      border: 1px solid var(--sc-border);
      border-radius: var(--rounded-xs);
    }
    .chip-val {
      font: 700 14px/1 var(--din-display);
      font-variant-numeric: tabular-nums;
      color: var(--sc-text);
    }
    .chip-lbl {
      font: 400 9px/1 var(--din-body);
      letter-spacing: 1.3px;
      text-transform: uppercase;
      color: var(--sc-text-faint);
    }

    /* ---- Empty state ---- */
    .empty-state {
      display: flex;
      align-items: center;
      gap: 10px;
      padding: 18px 16px;
      font: 400 12px/1.5 var(--din-body);
      color: var(--sc-text-dim);
    }
    .empty-icon { font-size: 16px; color: var(--sc-good); opacity: 0.7; }

    /* ---- War card ---- */
    .war-card { padding: 0 0 10px; overflow: hidden; }
    /* Subtle red hairline left edge when warScore < 0 (losing) */
    .war-card.losing { border-left: 2px solid rgba(255, 84, 104, 0.55); }

    .war-header { display: flex; align-items: center; gap: 7px; padding: 10px 12px 6px; }
    .faction-swatch { width: 10px; height: 10px; border-radius: 2px; flex-shrink: 0; }
    .faction-name {
      flex: 1;
      font: 700 13px/1 var(--din-display);
      letter-spacing: 0.6px;
      color: var(--sc-text);
      white-space: nowrap;
      overflow: hidden;
      text-overflow: ellipsis;
    }

    .war-since {
      padding: 0 12px 8px;
      font: 400 10px/1 var(--din-body);
      color: var(--sc-text-faint);
      font-variant-numeric: tabular-nums;
    }

    /* ---- War score bar ---- */
    .score-section { display: flex; align-items: center; gap: 7px; padding: 0 12px 8px; }
    .score-label {
      font: 400 8px/1 var(--din-body);
      letter-spacing: 1.5px;
      text-transform: uppercase;
      color: var(--sc-text-faint);
      flex-shrink: 0;
      width: 64px;
    }
    .score-bar-wrap { flex: 1; }
    .score-bar-track {
      position: relative;
      height: 6px;
      border-radius: var(--rounded-full);
      background: rgba(255, 255, 255, 0.07);
      overflow: hidden;
    }
    .score-fill { position: absolute; top: 0; bottom: 0; transition: width 0.5s ease; }
    .score-fill-neg {
      background: var(--sc-bad);
      border-radius: var(--rounded-full) 0 0 var(--rounded-full);
    }
    .score-fill-pos {
      background: var(--sc-good);
      border-radius: 0 var(--rounded-full) var(--rounded-full) 0;
    }
    .score-centre-line {
      position: absolute;
      left: 50%;
      top: 0;
      bottom: 0;
      width: 1px;
      background: var(--sc-border-bright);
    }
    .score-val {
      font: 700 10px/1 var(--din-body);
      font-variant-numeric: tabular-nums;
      color: var(--sc-text-dim);
      flex-shrink: 0;
      width: 36px;
      text-align: right;
    }
    .score-val.pos { color: var(--sc-good); }
    .score-val.neg { color: var(--sc-bad); }

    /* ---- Battle stats ---- */
    .war-stats { display: flex; align-items: center; justify-content: space-between; padding: 0 12px 6px; }
    .stat-battles {
      font: 600 11px/1 var(--din-body);
      font-variant-numeric: tabular-nums;
      color: var(--sc-text);
    }
    .wl-sep {
      font: 400 9px/1 var(--din-body);
      letter-spacing: 1px;
      color: var(--sc-text-faint);
      margin-left: 2px;
    }
    .stat-fleets { display: flex; align-items: baseline; gap: 2px; }
    .fleets-val {
      font: 700 12px/1 var(--din-display);
      font-variant-numeric: tabular-nums;
      color: var(--sc-text);
    }
    .fleets-lbl {
      font: 400 9px/1 var(--din-body);
      letter-spacing: 0.8px;
      text-transform: uppercase;
      color: var(--sc-text-faint);
    }

    /* ---- Fronts ---- */
    .fronts-section { padding: 0 0 2px; }
    .fronts-head { padding: 7px 12px 5px; font-size: 9px; }
    .no-fronts { padding: 2px 12px 4px; font: 400 10px/1 var(--din-body); color: var(--sc-text-faint); }
    .fronts-list {
      list-style: none;
      margin: 0;
      padding: 0 12px 2px;
      display: flex;
      flex-direction: column;
      gap: 5px;
    }
    .front-item { display: flex; align-items: center; gap: 6px; }
    .front-name {
      flex: 1;
      font: 600 10px/1 var(--din-body);
      color: var(--sc-text-dim);
      white-space: nowrap;
      overflow: hidden;
      text-overflow: ellipsis;
    }
    .front-dots { display: flex; gap: 3px; flex-shrink: 0; }
    .dot { width: 6px; height: 6px; border-radius: 50%; background: var(--sc-text-faint); }
    .dot.dot-active { background: var(--sc-bad); }
    .dot.dot-warn   { background: var(--sc-warn); }
    .front-tag { font-size: 7px; flex-shrink: 0; }
    .int-active {
      color: var(--sc-bad);
      border-color: rgba(255, 84, 104, 0.35);
      background: rgba(255, 84, 104, 0.08);
    }
    .int-warn { color: var(--sc-warn); border-color: rgba(232, 192, 97, 0.3); }

    /* ---- Diplomacy section ---- */
    .diplomacy-card { overflow: hidden; }
    .rel-list {
      list-style: none;
      margin: 0;
      padding: 0 12px 10px;
      display: flex;
      flex-direction: column;
      gap: 7px;
    }
    .rel-item { display: flex; align-items: center; gap: 5px; }
    .rel-swatch { width: 8px; height: 8px; border-radius: 2px; flex-shrink: 0; }
    .rel-name {
      flex: 1;
      font: 600 10px/1 var(--din-body);
      color: var(--sc-text);
      white-space: nowrap;
      overflow: hidden;
      text-overflow: ellipsis;
    }
    .rel-tag { font-size: 8px; flex-shrink: 0; }

    /* Status tag colours — mirrors empire-rail */
    .rst-war     { color: var(--sc-bad);      border-color: rgba(255, 84, 104, 0.4);  background: rgba(255, 84, 104, 0.08); }
    .rst-allied  { color: var(--sc-good);     border-color: rgba(95, 214, 164, 0.4);  background: rgba(95, 214, 164, 0.08); }
    .rst-truce,
    .rst-tribute { color: var(--sc-warn);     border-color: rgba(232, 192, 97, 0.35); }
    .rst-neutral { color: var(--sc-text-dim); border-color: var(--sc-border); }

    /* Opinion bar — mirrors empire-rail exactly */
    .opinion-bar {
      position: relative;
      width: 42px;
      height: 4px;
      background: rgba(255, 255, 255, 0.08);
      border-radius: 2px;
      flex-shrink: 0;
      overflow: hidden;
    }
    .opinion-fill {
      position: absolute;
      top: 0;
      bottom: 0;
      background: var(--sc-text-dim);
      border-radius: 2px;
      transition: left 0.4s ease, width 0.4s ease;
    }
    .opinion-mid {
      position: absolute;
      left: 50%;
      top: 0;
      bottom: 0;
      width: 1px;
      background: var(--sc-border-bright);
    }
    .opinion-val {
      font: 600 9px/1 var(--din-body);
      font-variant-numeric: tabular-nums;
      color: var(--sc-text-faint);
      width: 28px;
      text-align: right;
      flex-shrink: 0;
    }

    .pos { color: var(--sc-good); }
    .neg { color: var(--sc-bad); }
  `],
})
export class WarsComponent {
  protected readonly empire = inject(EmpireStore);
  protected readonly factionStore = inject(FactionStore);
  protected readonly demo = inject(DemoModeService);

  protected totalFronts(): number {
    return this.empire.wars().reduce((s, w) => s + w.fronts.length, 0);
  }

  protected totalFleetsEngaged(): number {
    return this.empire.wars().reduce((s, w) => s + w.fleetsEngaged, 0);
  }

  /** Look up a faction by id for swatch + name display. */
  protected enemy(id: string) {
    return this.factionStore.getById(id) ?? null;
  }

  /** War duration in ticks — reads DemoModeService.tick signal reactively. */
  protected duration(w: WarSummary): number {
    return Math.max(0, this.demo.tick() - w.sinceTick);
  }

  /** Width of the losing fill (grows leftward from centre, warScore < 0). */
  protected scoreLoseW(score: number): string {
    return (Math.max(0, -score) * 50) + '%';
  }

  /** Width of the winning fill (grows rightward from centre, warScore > 0). */
  protected scoreWinW(score: number): string {
    return (Math.max(0, score) * 50) + '%';
  }

  protected fmtScore(score: number): string {
    return (score >= 0 ? '+' : '') + score.toFixed(2);
  }

  /** Opinion bar left edge — mirrors EmpireRailComponent.opinionLeft(). */
  protected opinionLeft(op: number): string {
    return op >= 0 ? '50%' : (50 + op * 50) + '%';
  }

  /** Opinion bar width — mirrors EmpireRailComponent.opinionW(). */
  protected opinionW(op: number): string {
    return (Math.abs(op) * 50) + '%';
  }
}
