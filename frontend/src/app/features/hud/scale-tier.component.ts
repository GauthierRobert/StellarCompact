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
      padding: 3px 9px;
      border-radius: 2px;
      font-family: 'Courier New', monospace;
      font-size: 10px;
      letter-spacing: 1.5px;
      text-transform: uppercase;
      border: 1px solid transparent;
      white-space: nowrap;
      pointer-events: none;
    }
    .scale-badge--galaxy {
      color: rgba(80,180,255,0.6);
      border-color: rgba(80,180,255,0.2);
      background: rgba(0,3,8,0.7);
    }
    .scale-badge--region {
      color: rgba(74,214,160,0.7);
      border-color: rgba(74,214,160,0.25);
      background: rgba(0,3,8,0.7);
    }
    .scale-badge--system {
      color: rgba(255,210,100,0.75);
      border-color: rgba(255,210,100,0.25);
      background: rgba(0,3,8,0.7);
    }
    .scale-badge--planet {
      color: rgba(200,140,255,0.75);
      border-color: rgba(200,140,255,0.25);
      background: rgba(0,3,8,0.7);
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
