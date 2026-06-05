import {
  ChangeDetectionStrategy,
  Component,
  computed,
  inject,
  signal,
} from '@angular/core';
import { CommonModule } from '@angular/common';
import { EmpireStore } from '../../stores/empire.store';
import { FactionStore } from '../../stores';
import { DemoModeService } from '../../services/demo-mode.service';
import { HudSettingsComponent } from './hud-settings.component';

function fmtCompact(n: number): string {
  if (Math.abs(n) >= 1_000_000) return (n / 1_000_000).toFixed(1) + 'M';
  if (Math.abs(n) >= 1_000) return (n / 1_000).toFixed(1) + 'k';
  return String(Math.round(n));
}
function fmtDelta(d: number): string {
  return (d >= 0 ? '+' : '') + d.toFixed(1);
}

@Component({
  selector: 'app-command-bar',
  changeDetection: ChangeDetectionStrategy.OnPush,
  standalone: true,
  imports: [CommonModule, HudSettingsComponent],
  template: `
    <div class="cmd-bar">
      @if (playerFaction(); as f) {
        <div class="faction-crest">
          <span class="crest-dot"
            [style.background]="f.colour"
            [style.box-shadow]="'0 0 0 1px rgba(255,255,255,0.25)'">
          </span>
          <span class="faction-name">{{ f.name }}</span>
        </div>
      } @else {
        <div class="faction-crest">
          <span class="crest-dot" style="background:var(--sc-accent);box-shadow:0 0 0 1px rgba(255,255,255,0.25)"></span>
          <span class="faction-name">Sovereign</span>
        </div>
      }
      <div class="divider"></div>
      @if (ledger(); as L) {
        <div class="resources">
          <div class="res-pill res-credits" title="Credits">
            <span class="res-glyph">&#9672;</span>
            <span class="res-val">{{ fmt(L.credits.value) }}</span>
            <span class="res-delta" [class.pos]="L.credits.delta>=0" [class.neg]="L.credits.delta<0">{{ fmtD(L.credits.delta) }}</span>
          </div>
          <div class="res-pill res-minerals" title="Minerals">
            <span class="res-glyph">&#11041;</span>
            <span class="res-val">{{ fmt(L.minerals.value) }}</span>
            <span class="res-delta" [class.pos]="L.minerals.delta>=0" [class.neg]="L.minerals.delta<0">{{ fmtD(L.minerals.delta) }}</span>
          </div>
          <div class="res-pill res-energy" title="Energy">
            <span class="res-glyph">&#9889;</span>
            <span class="res-val">{{ fmt(L.energy.value) }}</span>
            <span class="res-delta" [class.pos]="L.energy.delta>=0" [class.neg]="L.energy.delta<0">{{ fmtD(L.energy.delta) }}</span>
          </div>
          <div class="res-pill res-alloys" title="Alloys">
            <span class="res-glyph">&#9670;</span>
            <span class="res-val">{{ fmt(L.alloys.value) }}</span>
            <span class="res-delta" [class.pos]="L.alloys.delta>=0" [class.neg]="L.alloys.delta<0">{{ fmtD(L.alloys.delta) }}</span>
          </div>
          <div class="res-pill res-influence" title="Influence">
            <span class="res-glyph">&#10022;</span>
            <span class="res-val">{{ fmt(L.influence.value) }}</span>
            <span class="res-delta" [class.pos]="L.influence.delta>=0" [class.neg]="L.influence.delta<0">{{ fmtD(L.influence.delta) }}</span>
          </div>
        </div>
      } @else {
        <div class="resources resources--empty">
          <span class="await-text">Awaiting data&hellip;</span>
        </div>
      }
      <div class="spacer"></div>
      <div class="tick-area">
        <span class="tick-clock">T{{ demo.tick() }}</span>
        @if (demo.enabled()) {
          <span class="sc-live-dot"></span>
          <span class="sc-tag demo-tag">DEMO</span>
        }
      </div>
      <div class="divider"></div>
      <div class="gear-wrap">
        <button class="sc-btn gear-btn"
          (click)="toggleSettings()"
          [class.sc-btn--active]="settingsOpen()"
          title="View Settings">&#9881;</button>
        @if (settingsOpen()) {
          <app-hud-settings class="settings-popover" (closeRequest)="settingsOpen.set(false)" />
        }
      </div>
    </div>
  `,
  styles: [`
    :host { display: block; }
    .cmd-bar {
      display: flex; align-items: center; gap: 10px; padding: 0 14px; height: 48px;
      background: var(--sc-panel); border-bottom: 1px solid var(--sc-border);
      font-family: var(--din-body); user-select: none; white-space: nowrap;
      /* visible so the gear popover (absolutely positioned below the bar) is
         not clipped; the flex spacer keeps the bar's own content contained. */
      overflow: visible;
    }
    .faction-crest { display: flex; align-items: center; gap: 8px; flex-shrink: 0; }
    .crest-dot { width: 14px; height: 14px; border-radius: 50%; flex-shrink: 0; transition: box-shadow 0.4s ease; }
    .faction-name { font: 700 12px/1 var(--din-display); letter-spacing: 1px; text-transform: uppercase; color: var(--sc-text); }
    .divider { width: 1px; height: 28px; background: var(--sc-border); flex-shrink: 0; }
    .resources { display: flex; align-items: center; gap: 6px; flex-shrink: 0; }
    .resources--empty { opacity: 0.4; }
    .await-text { font: 11px var(--sc-mono); color: var(--sc-text-dim); font-style: italic; }
    .res-pill {
      display: inline-flex; align-items: center; gap: 4px; padding: 3px 8px;
      border-radius: var(--rounded-pill); background: transparent; border: 1px solid var(--sc-border);
      font: 600 11px/1 var(--sc-mono); font-variant-numeric: tabular-nums; transition: border-color 0.2s;
    }
    .res-pill:hover { border-color: var(--sc-border-bright); }
    .res-glyph { font-size: 12px; }
    .res-val { color: var(--sc-text); }
    .res-delta { font-size: 9.5px; }
    .pos { color: var(--sc-good); }
    .neg { color: var(--sc-bad); }
    .res-credits .res-glyph, .res-credits .res-val { color: var(--sc-credits); }
    .res-minerals .res-glyph, .res-minerals .res-val { color: var(--sc-minerals); }
    .res-energy .res-glyph, .res-energy .res-val { color: var(--sc-energy); }
    .res-alloys .res-glyph, .res-alloys .res-val { color: var(--sc-alloys); }
    .res-influence .res-glyph, .res-influence .res-val { color: var(--sc-influence); }
    .spacer { flex: 1; }
    .tick-area { display: flex; align-items: center; gap: 6px; flex-shrink: 0; }
    .tick-clock { font: 600 12px/1 var(--sc-mono); font-variant-numeric: tabular-nums; color: var(--sc-text-dim); letter-spacing: 1px; }
    .demo-tag { color: var(--sc-warn); border-color: rgba(255,194,77,0.4); background: rgba(255,194,77,0.08); }
    .gear-wrap { position: relative; flex-shrink: 0; }
    .gear-btn { font-size: 16px; padding: 5px 9px; }
    .settings-popover { position: absolute; top: calc(100% + 6px); right: 0; z-index: 200; }
  `],
})
export class CommandBarComponent {
  protected readonly empire = inject(EmpireStore);
  protected readonly factionStore = inject(FactionStore);
  protected readonly demo = inject(DemoModeService);
  protected readonly ledger = this.empire.ledger;
  protected readonly settingsOpen = signal(false);

  protected readonly playerFaction = computed(() => {
    const id = this.demo.playerFactionId();
    return id ? (this.factionStore.getById(id) ?? null) : null;
  });

  protected fmt(n: number): string { return fmtCompact(n); }
  protected fmtD(d: number): string { return fmtDelta(d); }
  protected toggleSettings(): void { this.settingsOpen.update((v) => !v); }
}
