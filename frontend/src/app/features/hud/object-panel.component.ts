import {
  ChangeDetectionStrategy,
  Component,
  inject,
} from '@angular/core';
import { CommonModule } from '@angular/common';
import { SelectionStore } from '../../stores/selection.store';

/** Glyph per interstellar-object kind (tasteful unicode). */
const KIND_GLYPHS: Record<string, string> = {
  nebula: '✦',
  blackhole: '⬤',
  pulsar: '✸',
  wormhole: '◉',
  asteroidField: '⁂',
  roguePlanet: '◍',
  supernovaRemnant: '✺',
};

/**
 * Object panel — side panel shown when an interstellar object is selected.
 *
 * Header tinted with the object's accent colour; shows the kind label +
 * glyph, the display name (large), a flavour blurb, and a two-column stat
 * grid. Close button clears the object selection.
 *
 * Driven entirely by SelectionStore (signal-based). Display-only; never
 * mutates game state.
 */
@Component({
  selector: 'app-object-panel',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [CommonModule],
  template: `
    @if (selection.selectedObject(); as obj) {
      <div class="object-panel sc-panel">
        <!-- Header tinted by the object's accent colour -->
        <div class="panel-header" [style.border-color]="obj.accent + '55'">
          <span class="kind-glyph" [style.color]="obj.accent">{{ glyph(obj.kind) }}</span>
          <span class="kind-label" [style.color]="obj.accent">{{ obj.kindLabel }}</span>
          <span class="sc-head-rule"></span>
          <button class="close-btn" (click)="close()" aria-label="Close panel">&#10005;</button>
        </div>

        <!-- Name -->
        <div class="obj-name">{{ obj.name }}</div>

        <!-- Description blurb -->
        <div class="obj-description">{{ obj.description }}</div>

        <!-- Stat grid -->
        @if (obj.stats.length > 0) {
          <div class="section-label">Details</div>
          <div class="stat-grid">
            @for (stat of obj.stats; track stat.label) {
              <div class="stat-row">
                <span class="stat-label">{{ stat.label }}</span>
                <span class="stat-value">{{ stat.value }}</span>
              </div>
            }
          </div>
        }
      </div>
    }
  `,
  styles: [`
    :host { display: block; }
    .object-panel {
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
    .kind-glyph {
      font-size: 14px;
      flex-shrink: 0;
      line-height: 1;
    }
    .kind-label {
      font: 700 9px/1 var(--din-body);
      letter-spacing: 1.6px;
      text-transform: uppercase;
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
    .obj-name {
      padding: 10px 12px 4px;
      font: 700 16px/1.1 var(--din-display);
      text-transform: uppercase;
      color: var(--sc-text);
      letter-spacing: 1px;
    }
    .obj-description {
      padding: 4px 12px 8px;
      font: 400 11px/1.55 var(--din-body);
      color: var(--sc-text-dim);
      border-bottom: 1px solid var(--sc-border);
    }
    .section-label {
      padding: 6px 12px 3px;
      font: 400 10px/1 var(--din-body);
      letter-spacing: 1.6px;
      text-transform: uppercase;
      color: var(--sc-text-dim);
    }
    .stat-grid {
      padding: 2px 12px 10px;
      display: flex;
      flex-direction: column;
      gap: 4px;
    }
    .stat-row {
      display: flex;
      justify-content: space-between;
      align-items: baseline;
      gap: 8px;
    }
    .stat-label {
      font: 400 10px/1 var(--din-body);
      color: var(--sc-text-faint);
      flex-shrink: 0;
    }
    .stat-value {
      font: 600 10px/1 var(--sc-mono);
      color: var(--sc-text);
      text-align: right;
      font-variant-numeric: tabular-nums;
    }
  `],
})
export class ObjectPanelComponent {
  protected readonly selection = inject(SelectionStore);

  protected glyph(kind: string): string {
    return KIND_GLYPHS[kind] ?? '✦';
  }

  close(): void {
    this.selection.clearObjectSelection();
  }
}
