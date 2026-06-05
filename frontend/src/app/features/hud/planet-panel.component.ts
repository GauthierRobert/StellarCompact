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
      width: 240px;
      background: var(--sc-panel);
      border: 1px solid var(--sc-border);
      border-radius: var(--rounded-sm);
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
    .back-btn, .close-btn {
      background: none;
      border: none;
      color: var(--sc-text-dim);
      font-size: 14px;
      cursor: pointer;
      padding: 0 2px;
      line-height: 1;
      transition: color 0.15s;
    }
    .back-btn:hover, .close-btn:hover { color: var(--sc-text); }
    .planet-name {
      padding: 10px 12px 2px;
      font: 700 15px/1.1 var(--din-display);
      text-transform: uppercase;
      color: var(--sc-text);
      letter-spacing: 1px;
    }
    .planet-biome {
      padding: 0 12px 6px;
      font: 400 11px/1 var(--din-body);
      color: var(--sc-text-faint);
    }
    .section-label {
      padding: 6px 12px 3px;
      font: 400 10px/1 var(--din-body);
      text-transform: uppercase;
      letter-spacing: 1.6px;
      color: var(--sc-text-dim);
      border-top: 1px solid var(--sc-border);
    }
    .slot-bar-wrap {
      padding: 4px 12px 8px;
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
      border-radius: 2px;
      background: rgba(255,255,255,0.07);
      border: 1px solid var(--sc-border);
    }
    .slot--used {
      background: rgba(95, 214, 164, 0.28);
      border-color: rgba(95, 214, 164, 0.5);
    }
    .slot-label {
      font: 400 10px/1 var(--din-body);
      color: var(--sc-text-faint);
      font-variant-numeric: tabular-nums;
    }
    .action-surface {
      padding: 4px 12px 8px;
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
    .biome-desc {
      padding: 2px 12px 10px;
      font: 400 10px/1.5 var(--din-body);
      color: var(--sc-text-faint);
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
