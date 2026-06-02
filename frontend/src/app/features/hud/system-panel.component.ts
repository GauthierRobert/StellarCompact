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
      width: 220px;
      background: rgba(0, 3, 8, 0.88);
      border: 1px solid rgba(80, 180, 255, 0.22);
      border-radius: 4px;
      padding: 0;
      font-family: 'Courier New', monospace;
      font-size: 12px;
      color: #b0d8f0;
      overflow: hidden;
    }
    .panel-header {
      display: flex;
      justify-content: space-between;
      align-items: center;
      padding: 6px 10px;
      background: rgba(80, 180, 255, 0.08);
      border-bottom: 1px solid rgba(80, 180, 255, 0.12);
    }
    .panel-title {
      font-size: 10px;
      text-transform: uppercase;
      letter-spacing: 1.5px;
      color: rgba(80, 180, 255, 0.6);
    }
    .close-btn {
      background: none;
      border: none;
      color: rgba(80, 180, 255, 0.5);
      font-size: 16px;
      cursor: pointer;
      padding: 0 2px;
      line-height: 1;
    }
    .close-btn:hover { color: #b0d8f0; }
    .system-name {
      padding: 10px 10px 2px;
      font-size: 14px;
      font-weight: bold;
      color: #e8f4ff;
      letter-spacing: 0.5px;
    }
    .star-class {
      padding: 0 10px 6px;
      font-size: 11px;
      color: rgba(80, 180, 255, 0.5);
    }
    .badges {
      display: flex;
      gap: 6px;
      padding: 0 10px 6px;
    }
    .badge {
      font-size: 9px;
      letter-spacing: 1.5px;
      text-transform: uppercase;
      padding: 1px 5px;
      border-radius: 2px;
    }
    .badge--battle  { color: #f06060; border: 1px solid #f06060; }
    .badge--blockade { color: #f0a060; border: 1px solid #f0a060; }
    .section-label {
      padding: 6px 10px 3px;
      font-size: 10px;
      text-transform: uppercase;
      letter-spacing: 1px;
      color: rgba(80, 180, 255, 0.45);
      border-top: 1px solid rgba(80, 180, 255, 0.08);
    }
    .owner-row {
      display: flex;
      align-items: center;
      gap: 6px;
      padding: 2px 10px 6px;
    }
    .owner-dot {
      width: 8px;
      height: 8px;
      border-radius: 50%;
      flex-shrink: 0;
    }
    .owner-name { color: #e8f4ff; }
    .owner-unclaimed {
      padding: 2px 10px 6px;
      color: rgba(80, 180, 255, 0.4);
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
      padding: 4px 10px;
      cursor: pointer;
      transition: background 0.1s;
    }
    .planet-item:hover {
      background: rgba(80, 180, 255, 0.08);
    }
    .planet-item:focus-visible {
      outline: 1px solid rgba(80, 180, 255, 0.5);
      outline-offset: -1px;
    }
    .planet-item--empty {
      color: rgba(80, 180, 255, 0.3);
      font-style: italic;
      cursor: default;
    }
    .planet-item--empty:hover { background: none; }
    .planet-name {
      flex: 1;
      color: #c8e8ff;
      font-size: 11px;
    }
    .planet-biome {
      font-size: 10px;
      color: rgba(80, 180, 255, 0.5);
    }
    .planet-slots {
      font-size: 10px;
      color: rgba(80, 180, 255, 0.4);
      font-variant-numeric: tabular-nums;
    }
    .action-surface {
      padding: 4px 10px 10px;
    }
    .action-hint {
      font-size: 11px;
      color: rgba(80, 180, 255, 0.55);
    }
    .action-note {
      margin-top: 2px;
      font-size: 10px;
      color: rgba(80, 180, 255, 0.28);
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
