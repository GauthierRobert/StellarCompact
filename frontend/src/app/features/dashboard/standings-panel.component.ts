import {
  ChangeDetectionStrategy,
  Component,
  computed,
  inject,
} from '@angular/core';
import { CommonModule } from '@angular/common';
import { FactionStore } from '../../stores';
import { DemoModeService } from '../../services/demo-mode.service';

const VICTORY_THRESHOLD = 0.6;

@Component({
  selector: 'app-standings-panel',
  changeDetection: ChangeDetectionStrategy.OnPush,
  standalone: true,
  imports: [CommonModule],
  template: `
    <div class="sc-panel standings-panel">
      <div class="sc-head">Galaxy Standings<span class="sc-head-rule"></span></div>

      <div class="leaderboard sc-scroll">
        @for (f of factions(); track f.factionId; let i = $index) {
          <div class="faction-row" [class.eliminated]="f.eliminated">
            <span class="rank">{{ i + 1 }}</span>
            <span class="faction-swatch" [style.background]="f.colour"></span>
            <span class="faction-name-lb">{{ f.name }}</span>
            <span class="sys-count">{{ f.systemCount }}</span>
            <div class="share-bar-wrap">
              <div class="share-bar-track">
                <div class="share-bar-fill"
                  [style.width]="shareWidth(f.systemCount)"
                  [style.background]="f.eliminated ? 'rgba(150,180,220,0.15)' : f.colour + 'aa'">
                </div>
                <div class="victory-line"></div>
              </div>
            </div>
          </div>
        }
        @empty {
          <div class="empty-row">No factions yet</div>
        }
      </div>

      <div class="victory-section">
        <div class="vs-head">
          <span class="vs-label">Victory Progress</span>
          <span class="vs-threshold">60% target</span>
        </div>
        @if (playerShare() !== null) {
          <div class="sc-bar-track victory-track">
            <div class="sc-bar-fill victory-fill" [style.width]="(playerShare()! * 100) + '%'" [style.background]="playerColour() ?? 'var(--sc-accent)'"></div>
            <div class="victory-marker"></div>
          </div>
          <div class="victory-pct">{{ (playerShare()! * 100).toFixed(1) }}% of galaxy</div>
        }
      </div>

      <div class="galaxy-stats">
        <span class="gstat">{{ totalSystems() }} systems</span>
        <span class="gstat-sep">·</span>
        <span class="gstat">{{ activeFactionCount() }} empires</span>
      </div>
    </div>
  `,
  styles: [`
    :host { display: block; }
    .standings-panel { display: flex; flex-direction: column; gap: 0; overflow: hidden; }
    .leaderboard { max-height: 220px; overflow-y: auto; padding: 0 10px 6px; }
    .faction-row { display: flex; align-items: center; gap: 6px; padding: 4px 0; }
    .faction-row.eliminated { opacity: 0.38; }
    .rank { font: 600 9px/1 var(--sc-mono); color: var(--sc-text-faint); width: 12px; text-align: right; flex-shrink: 0; }
    .faction-swatch { width: 8px; height: 8px; border-radius: 2px; flex-shrink: 0; }
    .faction-name-lb { flex: 1; font: 700 11px/1 var(--din-display); letter-spacing: 0.8px; text-transform: uppercase; color: var(--sc-text); white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
    .sys-count { font: 600 11px/1 var(--sc-mono); font-variant-numeric: tabular-nums; color: var(--sc-text-dim); width: 24px; text-align: right; flex-shrink: 0; }
    .share-bar-wrap { width: 60px; flex-shrink: 0; }
    .share-bar-track { position: relative; height: 4px; background: rgba(255,255,255,0.08); border-radius: 2px; overflow: visible; }
    .share-bar-fill { position: absolute; top: 0; bottom: 0; left: 0; border-radius: 2px; transition: width 0.5s cubic-bezier(0.22,1,0.36,1); }
    .victory-line { position: absolute; left: 60%; top: -3px; bottom: -3px; width: 1px; background: rgba(232,192,97,0.5); }
    .empty-row { font: italic 11px var(--din-body); color: var(--sc-text-faint); padding: 8px; text-align: center; }
    .victory-section { padding: 8px 10px; border-top: 1px solid var(--sc-border); }
    .vs-head { display: flex; justify-content: space-between; margin-bottom: 6px; }
    .vs-label { font: 400 9px/1 var(--din-body); letter-spacing: 1.6px; text-transform: uppercase; color: var(--sc-text-faint); }
    .vs-threshold { font: 600 9px/1 var(--sc-mono); color: var(--sc-warn); }
    .victory-track { height: 8px; position: relative; }
    .victory-fill { height: 100%; }
    .victory-marker { position: absolute; left: 60%; top: -2px; bottom: -2px; width: 2px; background: var(--sc-warn); border-radius: 1px; }
    .victory-pct { font: 600 10px/1 var(--sc-mono); font-variant-numeric: tabular-nums; color: var(--sc-text-dim); margin-top: 4px; }
    .galaxy-stats { display: flex; align-items: center; gap: 6px; padding: 6px 10px 8px; border-top: 1px solid var(--sc-border); }
    .gstat { font: 400 10px/1 var(--din-body); letter-spacing: 1px; text-transform: uppercase; color: var(--sc-text-faint); }
    .gstat-sep { color: var(--sc-border-bright); font-size: 10px; }
  `],
})
export class StandingsPanelComponent {
  private readonly factionStore = inject(FactionStore);
  private readonly demo = inject(DemoModeService);

  protected readonly factions = this.factionStore.factions;
  protected readonly totalSystems = this.factionStore.totalSystems;
  protected readonly activeFactionCount = computed(() => this.factionStore.activeFactions().length);

  protected readonly playerShare = computed(() => {
    const id = this.demo.playerFactionId();
    if (!id) return null;
    const f = this.factionStore.getById(id);
    if (!f) return null;
    const total = this.totalSystems();
    if (total === 0) return 0;
    return f.systemCount / total;
  });

  protected readonly playerColour = computed(() => {
    const id = this.demo.playerFactionId();
    return id ? (this.factionStore.getById(id)?.colour ?? null) : null;
  });

  protected shareWidth(systemCount: number): string {
    const total = this.totalSystems();
    if (total === 0) return '0%';
    return Math.min(100, (systemCount / total) / VICTORY_THRESHOLD * 100) + '%';
  }
}
