import {
  ChangeDetectionStrategy,
  Component,
  computed,
  inject,
} from '@angular/core';
import { CameraStore } from '../../stores';
import type { ScaleTier } from '../../stores/selection.store';

/**
 * Scale tier indicator — small HUD badge showing the current zoom tier
 * (Galaxy / Region / System / Planet) derived from camera.levelF.
 *
 * Thresholds match the game-design §5 table and the PoC zoom model:
 *   levelF < 1    → Galaxy
 *   1 ≤ levelF < 4 → Region
 *   4 ≤ levelF < 8 → System
 *   levelF ≥ 8    → Planet
 *
 * Display-only.
 */
@Component({
  selector: 'app-scale-tier',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="scale-badge" [class]="'scale-badge--' + tier()">
      {{ tierLabel() }}
    </div>
  `,
  styles: [`
    :host { display: block; }
    .scale-badge {
      padding: 4px 10px;
      border-radius: var(--rounded-pill);
      font: 700 10px/1 var(--din-body);
      letter-spacing: 1.17px;
      text-transform: uppercase;
      border: 1px solid var(--sc-border);
      background: var(--sc-panel);
      color: var(--sc-text-dim);
      white-space: nowrap;
      pointer-events: none;
    }
  `],
})
export class ScaleTierComponent {
  private readonly camera = inject(CameraStore);

  /** Current scale tier derived from camera.levelF. */
  readonly tier = computed<ScaleTier>(() => {
    const lf = this.camera.levelF();
    if (lf < 1)  return 'galaxy';
    if (lf < 4)  return 'region';
    if (lf < 8)  return 'system';
    return 'planet';
  });

  readonly tierLabel = computed(() => {
    switch (this.tier()) {
      case 'galaxy': return 'Galaxy';
      case 'region': return 'Region';
      case 'system': return 'System';
      case 'planet': return 'Planet';
    }
  });
}
