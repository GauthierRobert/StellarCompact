import {
  ChangeDetectionStrategy,
  Component,
  computed,
  inject,
} from '@angular/core';
import { CommonModule } from '@angular/common';
import { FactionStore } from '../../stores/faction.store';
import { SelectionStore } from '../../stores/selection.store';

/**
 * System panel — side panel shown when a system is selected.
 *
 * Display-only: shows system details, owner faction, planets list,
 * and scale-tier-appropriate actions the Sovereign could take.
 * It never mutates game state; action items are informational readouts only.
 *
 * Driven by SelectionStore and FactionStore (both signal-based).
 */
@Component({
  selector: 'app-system-panel',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [CommonModule],
  template: `
    @if (system(); as sys) {
      <div class="system-panel">
        <div class="panel-header">
          <span class="panel-title">System</span>
          <button class="close-btn" (click)="close()" aria-label="Close panel">×</button>
        </div>

        <div class="system-name">{{ sys.name }}</div>
        <div class="star-class">{{ sys.starClass }} star</div>

        <!-- Status badges -->
        <div class="badges">
          @if (sys.battle) {
            <span class="badge badge--battle">BATTLE</span>
          }
          @if (sys.blockaded) {
            <span class="badge badge--blockade">BLOCKADED</span>
          }
        </div>

        <!-- Owner -->
        <div class="section-label">Owner</div>
        @if (ownerFaction(); as owner) {
          <div class="owner-row">
            <span class="owner-dot" [style.background]="owner.colour"></span>
            <span class="owner-name">{{ owner.name }}</span>
          </div>
        } @else {
          <div class="owner-unclaimed">Unclaimed</div>
        }

        <!-- Planets list -->
        <div class="section-label">Planets ({{ sys.planets.length }})</div>
        <ul class="planet-list">
          @for (planet of sys.planets; track planet.planetId) {
            <li class="planet-item"
                (click)="selectPlanet(planet)"
                role="button"
                tabindex="0"
                (keydown.enter)="selectPlanet(planet)">
              <span class="planet-name">{{ planet.name }}</span>
              <span class="planet-biome">{{ planet.biome }}</span>
              <span class="planet-slots">{{ planet.usedSlots }}/{{ planet.slotCount }}</span>
            </li>
          }
          @empty {
            <li class="planet-item planet-item--empty">No planets</li>
          }
        </ul>

        <!-- Scale-tier action surfacing (display-only — Sovereign reads these) -->
        <div class="section-label">Sovereign Activity</div>
        <div class="action-surface">
          <div class="action-hint">Colonise · Build · Defend · Attack</div>
          <div class="action-note">Actions resolved by the Sovereign each tick</div>
        </div>
      </div>
    }
  `,
  styles: [`
    :host {
      display: block;
    }
    .system-panel {
      width: 240px;
      background: var(--sc-panel);
      border: 1px solid var(--sc-border);
      border-radius: var(--rounded-sm);
      padding: 0;
      font-family: var(--din-body);
      font-size: 12px;
      color: var(--sc-text);
      overflow: hidden;
    }
    .panel-header {
      display: flex;
      justify-content: space-between;
      align-items: center;
      padding: 7px 10px 6px;
      background: rgba(255,255,255,0.03);
      border-bottom: 1px solid var(--sc-border);
    }
    .panel-title {
      font: 400 10px/1 var(--din-body);
      text-transform: uppercase;
      letter-spacing: 1.6px;
      color: var(--sc-text-dim);
    }
    .close-btn {
      background: none;
      border: none;
      color: var(--sc-text-dim);
      font-size: 14px;
      cursor: pointer;
      padding: 0 2px;
      line-height: 1;
      transition: color 0.15s;
    }
    .close-btn:hover { color: var(--sc-text); }
    .system-name {
      padding: 10px 12px 2px;
      font: 700 15px/1.1 var(--din-display);
      text-transform: uppercase;
      color: var(--sc-text);
      letter-spacing: 1px;
    }
    .star-class {
      padding: 0 12px 6px;
      font: 400 11px/1 var(--din-body);
      color: var(--sc-text-faint);
    }
    .badges {
      display: flex;
      gap: 6px;
      padding: 0 12px 6px;
    }
    .badge {
      font: 700 9px/1 var(--din-body);
      letter-spacing: 1.17px;
      text-transform: uppercase;
      padding: 2px 6px;
      border-radius: var(--rounded-xs);
    }
    .badge--battle  { color: var(--sc-bad); border: 1px solid var(--sc-bad); }
    .badge--blockade { color: var(--sc-warn); border: 1px solid var(--sc-warn); }
    .section-label {
      padding: 6px 12px 3px;
      font: 400 10px/1 var(--din-body);
      text-transform: uppercase;
      letter-spacing: 1.6px;
      color: var(--sc-text-dim);
      border-top: 1px solid var(--sc-border);
    }
    .owner-row {
      display: flex;
      align-items: center;
      gap: 6px;
      padding: 3px 12px 7px;
    }
    .owner-dot {
      width: 8px;
      height: 8px;
      border-radius: 50%;
      flex-shrink: 0;
    }
    .owner-name {
      font: 600 11px/1 var(--din-body);
      color: var(--sc-text);
    }
    .owner-unclaimed {
      padding: 3px 12px 7px;
      font: 400 11px/1 var(--din-body);
      color: var(--sc-text-faint);
      font-style: italic;
    }
    .planet-list {
      list-style: none;
      margin: 0;
      padding: 0 0 4px;
    }
    .planet-item {
      display: flex;
      align-items: center;
      gap: 6px;
      padding: 4px 12px;
      cursor: pointer;
      transition: background 0.1s;
    }
    .planet-item:hover {
      background: rgba(255,255,255,0.05);
    }
    .planet-item:focus-visible {
      outline: 1px solid var(--sc-border-bright);
      outline-offset: -1px;
    }
    .planet-item--empty {
      color: var(--sc-text-faint);
      font-style: italic;
      cursor: default;
    }
    .planet-item--empty:hover { background: none; }
    .planet-name {
      flex: 1;
      font: 400 11px/1 var(--din-body);
      color: var(--sc-text);
    }
    .planet-biome {
      font: 400 10px/1 var(--din-body);
      color: var(--sc-text-faint);
    }
    .planet-slots {
      font: 400 10px/1 var(--din-body);
      color: var(--sc-text-faint);
      font-variant-numeric: tabular-nums;
    }
    .action-surface {
      padding: 4px 12px 10px;
    }
    .action-hint {
      font: 400 11px/1.4 var(--din-body);
      color: var(--sc-text-dim);
    }
    .action-note {
      margin-top: 3px;
      font: 400 10px/1.4 var(--din-body);
      color: var(--sc-text-faint);
      font-style: italic;
    }
  `],
})
export class SystemPanelComponent {
  private readonly selection = inject(SelectionStore);
  private readonly factionStore = inject(FactionStore);

  readonly system = this.selection.selectedSystem;

  /** Owner faction snapshot derived reactively. */
  readonly ownerFaction = computed(() => {
    const ownerId = this.system()?.ownerFactionId;
    if (!ownerId) return null;
    return this.factionStore.getById(ownerId) ?? null;
  });

  selectPlanet(planet: import('../../stores/selection.store').SelectedPlanetInfo): void {
    this.selection.selectPlanet(planet);
  }

  close(): void {
    this.selection.clearSelection();
  }
}
