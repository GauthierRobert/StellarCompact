import {
  ChangeDetectionStrategy,
  Component,
  computed,
  inject,
} from '@angular/core';
import { CommonModule } from '@angular/common';
import { SelectionStore } from '../../stores/selection.store';

/**
 * Planet panel — detail panel shown when a planet is selected within a system.
 *
 * Scale tier: planet. Shows slots, biome, and the scale-tier actions the
 * Sovereign could take (build/upgrade/terraform) — display only.
 *
 * Driven by SelectionStore (signal-based). Never mutates game state.
 */
@Component({
  selector: 'app-planet-panel',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [CommonModule],
  template: `
    @if (planet(); as pl) {
      <div class="planet-panel">
        <div class="panel-header">
          <button class="back-btn" (click)="back()" aria-label="Back to system">←</button>
          <span class="panel-title">Planet</span>
          <button class="close-btn" (click)="close()" aria-label="Close panel">×</button>
        </div>

        <div class="planet-name">{{ pl.name }}</div>
        <div class="planet-biome">{{ pl.biome }}</div>

        <!-- Slot usage bar -->
        <div class="section-label">Build Slots</div>
        <div class="slot-bar-wrap">
          <div class="slot-bar">
            @for (slot of slotArray(); track $index) {
              <div class="slot" [class.slot--used]="slot === 'used'"></div>
            }
          </div>
          <span class="slot-label">{{ pl.usedSlots }} / {{ pl.slotCount }} used</span>
        </div>

        <!-- Scale-tier action surfacing (display-only) -->
        <div class="section-label">Sovereign Activity</div>
        <div class="action-surface">
          <div class="action-hint">Build · Upgrade · Terraform</div>
          <div class="action-note">Actions resolved by the Sovereign each tick</div>
        </div>

        <!-- Biome yield note -->
        <div class="section-label">Biome Yields</div>
        <div class="biome-desc">{{ biomeDesc() }}</div>
      </div>
    }
  `,
  styles: [`
    :host {
      display: block;
    }
    .planet-panel {
      width: 220px;
      background: rgba(0, 3, 8, 0.88);
      border: 1px solid rgba(80, 180, 255, 0.22);
      border-radius: 4px;
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
    .back-btn, .close-btn {
      background: none;
      border: none;
      color: rgba(80, 180, 255, 0.5);
      font-size: 15px;
      cursor: pointer;
      padding: 0 2px;
      line-height: 1;
    }
    .back-btn:hover, .close-btn:hover { color: #b0d8f0; }
    .planet-name {
      padding: 10px 10px 2px;
      font-size: 14px;
      font-weight: bold;
      color: #e8f4ff;
    }
    .planet-biome {
      padding: 0 10px 6px;
      font-size: 11px;
      color: rgba(80, 180, 255, 0.5);
    }
    .section-label {
      padding: 6px 10px 3px;
      font-size: 10px;
      text-transform: uppercase;
      letter-spacing: 1px;
      color: rgba(80, 180, 255, 0.45);
      border-top: 1px solid rgba(80, 180, 255, 0.08);
    }
    .slot-bar-wrap {
      padding: 4px 10px 8px;
    }
    .slot-bar {
      display: flex;
      gap: 3px;
      flex-wrap: wrap;
      margin-bottom: 4px;
    }
    .slot {
      width: 14px;
      height: 8px;
      border-radius: 1px;
      background: rgba(80, 180, 255, 0.12);
      border: 1px solid rgba(80, 180, 255, 0.2);
    }
    .slot--used {
      background: rgba(74, 214, 160, 0.35);
      border-color: rgba(74, 214, 160, 0.5);
    }
    .slot-label {
      font-size: 10px;
      color: rgba(80, 180, 255, 0.4);
    }
    .action-surface {
      padding: 4px 10px 8px;
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
    .biome-desc {
      padding: 2px 10px 10px;
      font-size: 10px;
      color: rgba(80, 180, 255, 0.4);
      line-height: 1.5;
    }
  `],
})
export class PlanetPanelComponent {
  private readonly selection = inject(SelectionStore);

  readonly planet = this.selection.selectedPlanet;

  /** Array of 'used'|'free' for each slot — drives the slot bar display. */
  readonly slotArray = computed(() => {
    const pl = this.planet();
    if (!pl) return [];
    return Array.from({ length: pl.slotCount }, (_, i) =>
      i < pl.usedSlots ? 'used' : 'free',
    );
  });

  /** Short biome yield description (matches game-design table). */
  readonly biomeDesc = computed(() => {
    const biome = this.planet()?.biome ?? '';
    return BIOME_DESCS[biome] ?? biome;
  });

  back(): void {
    this.selection.clearPlanetSelection();
  }

  close(): void {
    this.selection.clearSelection();
  }
}

// ---------------------------------------------------------------------------
// Biome descriptions — mirrors docs/game-design/01-world-and-map.md §2
// ---------------------------------------------------------------------------
const BIOME_DESCS: Readonly<Record<string, string>> = {
  Oceanic:   'Food +. Low colonise difficulty. Comfortable cradle worlds.',
  Terran:    'Balanced yields. Low colonise difficulty. Generalist.',
  Arid:      'Minerals +. Medium colonise difficulty.',
  Desert:    'Energy (solar) +. Medium colonise difficulty.',
  Volcanic:  'Minerals + Energy. High difficulty, high reward.',
  Frozen:    'Tech (research stations). High difficulty, sparse but valuable.',
  Toxic:     'No direct yield. Requires terraforming first.',
  'Gas giant': 'Energy (skimming). Orbital only, no ground slots.',
};
