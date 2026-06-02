import {
  ChangeDetectionStrategy,
  Component,
  computed,
  inject,
  input,
} from '@angular/core';
import { FactionStore } from '../../stores';
import { CommonModule } from '@angular/common';

/**
 * Resource bar — top-edge HUD strip showing credits, minerals, influence, and
 * reputation for the faction whose id is passed as `factionId`.
 *
 * Display-only: reads FactionStore signals, never writes to game state.
 * Uses OnPush change detection; all bindings flow through signals / computed.
 */
@Component({
  selector: 'app-resource-bar',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [CommonModule],
  template: `
    @if (faction(); as f) {
      <div class="resource-bar">
        <span class="faction-name" [style.color]="f.colour">{{ f.name }}</span>
        <span class="sep">|</span>
        <span class="res-item" title="Credits">
          <span class="icon">C</span>
          <span class="val">{{ f.resources.credits | number }}</span>
        </span>
        <span class="res-item" title="Minerals">
          <span class="icon">M</span>
          <span class="val">{{ f.resources.minerals | number }}</span>
        </span>
        <span class="res-item" title="Influence">
          <span class="icon">I</span>
          <span class="val">{{ f.resources.influence | number }}</span>
        </span>
        <span class="sep">|</span>
        <span class="rep-item" title="Reputation" [class]="repClass()">
          Rep: {{ reputationLabel() }}
        </span>
        <span class="sep">|</span>
        <span class="systems-item" title="Systems controlled">
          <span class="icon">S</span>
          <span class="val">{{ f.systemCount }}</span>
        </span>
        @if (f.eliminated) {
          <span class="eliminated-badge">ELIMINATED</span>
        }
      </div>
    } @else {
      <div class="resource-bar resource-bar--empty">
        <span class="placeholder">Awaiting faction data…</span>
      </div>
    }
  `,
  styles: [`
    :host {
      display: block;
    }
    .resource-bar {
      display: flex;
      align-items: center;
      gap: 10px;
      padding: 6px 14px;
      background: rgba(0, 3, 8, 0.82);
      border-bottom: 1px solid rgba(80, 180, 255, 0.18);
      font-family: 'Courier New', monospace;
      font-size: 12px;
      color: #b0d8f0;
      user-select: none;
      white-space: nowrap;
      overflow: hidden;
    }
    .resource-bar--empty {
      opacity: 0.4;
    }
    .faction-name {
      font-weight: bold;
      letter-spacing: 1px;
      text-transform: uppercase;
      font-size: 11px;
    }
    .sep {
      color: rgba(80, 180, 255, 0.3);
    }
    .res-item, .rep-item, .systems-item {
      display: flex;
      align-items: center;
      gap: 4px;
    }
    .icon {
      font-size: 10px;
      color: rgba(80, 180, 255, 0.6);
      text-transform: uppercase;
      letter-spacing: 0.5px;
    }
    .val {
      color: #e8f4ff;
      font-variant-numeric: tabular-nums;
    }
    .rep-positive { color: #4ad6a0; }
    .rep-negative { color: #f06060; }
    .rep-neutral  { color: #b0d8f0; }
    .eliminated-badge {
      color: #f06060;
      font-size: 10px;
      letter-spacing: 2px;
      text-transform: uppercase;
      border: 1px solid #f06060;
      padding: 1px 5px;
      border-radius: 2px;
    }
    .placeholder {
      opacity: 0.5;
      font-style: italic;
      font-size: 11px;
    }
  `],
})
export class ResourceBarComponent {
  /** The faction id to display. Typically the player's own faction. */
  readonly factionId = input<string>('');

  private readonly factionStore = inject(FactionStore);

  /** Reactive faction snapshot derived from the store. */
  readonly faction = computed(() => {
    const id = this.factionId();
    if (!id) return null;
    return this.factionStore.getById(id) ?? null;
  });

  /** Reputation CSS class based on sign. */
  readonly repClass = computed(() => {
    const r = this.faction()?.reputation ?? 0;
    if (r > 0.05) return 'rep-positive';
    if (r < -0.05) return 'rep-negative';
    return 'rep-neutral';
  });

  /** Human-readable reputation label in range -1..1 as a sign + 2dp string. */
  readonly reputationLabel = computed(() => {
    const r = this.faction()?.reputation ?? 0;
    const sign = r >= 0 ? '+' : '';
    return `${sign}${r.toFixed(2)}`;
  });
}
