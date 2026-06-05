import {
  ChangeDetectionStrategy,
  Component,
  computed,
  inject,
} from '@angular/core';
import { CommonModule } from '@angular/common';
import { SelectionStore } from '../../stores/selection.store';
import { FactionStore } from '../../stores/faction.store';

/**
 * Sector panel — side panel shown when a sector cell is selected.
 *
 * Shows the sector name, stat tiles for system + object counts, a per-faction
 * control breakdown, and a list of notable systems inside the sector. Close
 * button clears the sector selection.
 *
 * Driven entirely by SelectionStore + FactionStore (both signal-based).
 * Display-only; never mutates game state.
 */
@Component({
  selector: 'app-sector-panel',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [CommonModule],
  template: `
    @if (selection.selectedSector(); as sec) {
      <div class="sector-panel sc-panel">
        <!-- Header -->
        <div class="panel-header">
          <span class="panel-title">Sector</span>
          <span class="sc-head-rule"></span>
          <button class="close-btn" (click)="close()" aria-label="Close panel">&#10005;</button>
        </div>

        <div class="sector-name">{{ sec.name }}</div>

        <!-- Stat tiles -->
        <div class="stat-tiles">
          <div class="stat-tile">
            <span class="stat-val">{{ sec.systemCount }}</span>
            <span class="stat-lbl">Systems</span>
          </div>
          <div class="stat-tile">
            <span class="stat-val">{{ sec.objectCount }}</span>
            <span class="stat-lbl">Objects</span>
          </div>
        </div>

        <!-- Owners breakdown -->
        <div class="section-label">Control</div>
        <div class="owners-list">
          @if (ownersWithFactions().length === 0) {
            <div class="uncontested">Uncontested</div>
          } @else {
            @for (entry of ownersWithFactions(); track entry.factionId) {
              <div class="owner-row">
                <span class="owner-dot" [style.background]="entry.colour"></span>
                <span class="owner-name">{{ entry.name }}</span>
                <span class="owner-count">{{ entry.count }}</span>
              </div>
            }
          }
        </div>

        <!-- Notable systems -->
        @if (sec.systems.length > 0) {
          <div class="section-label">Notable Systems</div>
          <ul class="systems-list">
            @for (sysName of sec.systems; track sysName) {
              <li class="system-item">{{ sysName }}</li>
            }
          </ul>
        }
      </div>
    }
  `,
  styles: [`
    :host { display: block; }
    .sector-panel {
      width: 240px;
      overflow: hidden;
      font-family: var(--din-body);
      font-size: 12px;
      color: var(--sc-text);
    }
    .panel-header {
      display: flex;
      align-items: center;
      gap: 6px;
      padding: 8px 10px 7px;
      background: rgba(255,255,255,0.03);
      border-bottom: 1px solid var(--sc-border);
    }
    .panel-title {
      font: 400 10px/1 var(--din-body);
      letter-spacing: 1.6px;
      text-transform: uppercase;
      color: var(--sc-text-dim);
      flex-shrink: 0;
    }
    .sc-head-rule {
      flex: 1;
      height: 1px;
      background: var(--hairline-on-dark);
    }
    .close-btn {
      background: none;
      border: none;
      color: var(--sc-text-dim);
      font-size: 12px;
      cursor: pointer;
      padding: 0 2px;
      line-height: 1;
      flex-shrink: 0;
      transition: color 0.15s;
    }
    .close-btn:hover { color: var(--sc-text); }
    .sector-name {
      padding: 10px 12px 6px;
      font: 700 16px/1.1 var(--din-display);
      text-transform: uppercase;
      color: var(--sc-text);
      letter-spacing: 1px;
    }
    .stat-tiles {
      display: grid;
      grid-template-columns: 1fr 1fr;
      gap: 4px;
      padding: 0 12px 8px;
      border-bottom: 1px solid var(--sc-border);
    }
    .stat-tile {
      display: flex;
      flex-direction: column;
      align-items: center;
      padding: 6px 4px;
      background: rgba(255,255,255,0.03);
      border-radius: var(--rounded-xs);
      border: 1px solid var(--sc-border);
    }
    .stat-val {
      font: 700 17px/1 var(--sc-mono);
      font-variant-numeric: tabular-nums;
      color: var(--sc-text);
    }
    .stat-lbl {
      font: 400 9px/1 var(--din-body);
      letter-spacing: 1.17px;
      text-transform: uppercase;
      color: var(--sc-text-faint);
      margin-top: 3px;
    }
    .section-label {
      padding: 7px 12px 3px;
      font: 400 10px/1 var(--din-body);
      letter-spacing: 1.6px;
      text-transform: uppercase;
      color: var(--sc-text-dim);
    }
    .owners-list {
      padding: 2px 12px 8px;
      display: flex;
      flex-direction: column;
      gap: 5px;
      border-bottom: 1px solid var(--sc-border);
    }
    .uncontested {
      font: 400 11px/1 var(--din-body);
      color: var(--sc-text-faint);
      font-style: italic;
    }
    .owner-row {
      display: flex;
      align-items: center;
      gap: 7px;
    }
    .owner-dot {
      width: 8px;
      height: 8px;
      border-radius: 50%;
      flex-shrink: 0;
    }
    .owner-name {
      flex: 1;
      font: 600 11px/1 var(--din-body);
      color: var(--sc-text);
      white-space: nowrap;
      overflow: hidden;
      text-overflow: ellipsis;
    }
    .owner-count {
      font: 600 11px/1 var(--sc-mono);
      font-variant-numeric: tabular-nums;
      color: var(--sc-text-dim);
      flex-shrink: 0;
    }
    .systems-list {
      list-style: none;
      margin: 0;
      padding: 2px 12px 10px;
      display: flex;
      flex-direction: column;
      gap: 3px;
    }
    .system-item {
      font: 400 11px/1.4 var(--din-body);
      color: var(--sc-text-dim);
      padding: 1px 0;
    }
    .system-item::before {
      content: '◦ ';
      color: var(--sc-text-faint);
    }
  `],
})
export class SectorPanelComponent {
  protected readonly selection = inject(SelectionStore);
  private readonly factionStore = inject(FactionStore);

  /** Resolved owner rows — faction colour + name merged with the sector owner counts. */
  protected readonly ownersWithFactions = computed(() => {
    const sec = this.selection.selectedSector();
    if (!sec || sec.owners.length === 0) return [];
    return sec.owners
      .filter((o) => o.count > 0)
      .map((o) => {
        const f = this.factionStore.getById(o.factionId);
        return {
          factionId: o.factionId,
          name: f?.name ?? o.factionId,
          colour: f?.colour ?? '#888',
          count: o.count,
        };
      });
  });

  close(): void {
    this.selection.clearSectorSelection();
  }
}
