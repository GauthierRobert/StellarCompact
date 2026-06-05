import {
  ChangeDetectionStrategy,
  Component,
  inject,
  output,
  HostListener,
} from '@angular/core';
import { CommonModule } from '@angular/common';
import { ViewSettingsStore } from '../../stores/view-settings.store';
import { DemoModeService } from '../../services/demo-mode.service';

/**
 * HUD settings popover — overlay toggles and demo playback controls.
 * Emits closeRequest when the user clicks outside or presses close.
 */
@Component({
  selector: 'app-hud-settings',
  changeDetection: ChangeDetectionStrategy.OnPush,
  standalone: true,
  imports: [CommonModule],
  template: `
    <div class="settings-panel sc-panel">
      <div class="sc-head">
        View Settings
        <span class="sc-head-rule"></span>
        <button class="close-btn" (click)="closeRequest.emit()" title="Close">&#10005;</button>
      </div>

      <div class="section">
        <div class="section-label">Overlay Layers</div>
        <div class="toggles">
          <button class="sc-btn" [class.sc-btn--active]="vs.fogOfWar()" (click)="vs.toggleFogOfWar()">
            War Fog
          </button>
          <button class="sc-btn" [class.sc-btn--active]="vs.showTerritories()" (click)="vs.toggleTerritories()">
            Territories
          </button>
          <button class="sc-btn" [class.sc-btn--active]="vs.showRoutes()" (click)="vs.toggleRoutes()">
            Routes
          </button>
          <button class="sc-btn" [class.sc-btn--active]="vs.showConflict()" (click)="vs.toggleConflict()">
            Conflict
          </button>
          <button class="sc-btn" [class.sc-btn--active]="vs.showLabels()" (click)="vs.toggleLabels()">
            Labels
          </button>
          <button class="sc-btn" [class.sc-btn--active]="vs.showObjects()" (click)="vs.toggleObjects()">
            Objects
          </button>
          <button class="sc-btn" [class.sc-btn--active]="vs.showSectors()" (click)="vs.toggleSectors()">
            Sectors
          </button>
        </div>
      </div>

      @if (demo.enabled()) {
        <div class="sep-line"></div>
        <div class="section">
          <div class="section-label">Demo Playback</div>
          <div class="play-row">
            <button class="sc-btn play-btn" [class.sc-btn--active]="!demo.paused()" (click)="demo.togglePause()">
              {{ demo.paused() ? '&#9654; Play' : '&#9646;&#9646; Pause' }}
            </button>
          </div>
          <div class="speed-row">
            <span class="speed-label">Speed</span>
            @for (s of speeds; track s) {
              <button class="sc-btn speed-btn"
                [class.sc-btn--active]="demo.speed() === s"
                (click)="demo.setSpeed(s)">{{ s }}&#215;</button>
            }
          </div>
        </div>
      }
    </div>
  `,
  styles: [`
    :host { display: block; width: 240px; }
    .settings-panel { padding-bottom: 10px; }
    .close-btn {
      background: none; border: none; cursor: pointer;
      color: var(--sc-text-dim); font-size: 12px; padding: 0 2px;
      transition: color 0.15s; line-height: 1;
    }
    .close-btn:hover { color: var(--sc-text); }
    .section { padding: 4px 12px 6px; }
    .section-label {
      font: 400 9px/1 var(--din-body); letter-spacing: 1.6px;
      text-transform: uppercase; color: var(--sc-text-faint); margin-bottom: 8px;
    }
    .toggles { display: flex; flex-wrap: wrap; gap: 5px; }
    .sc-btn { font-size: 10px; padding: 5px 9px; }
    .sep-line { height: 1px; background: var(--sc-border); margin: 6px 12px; }
    .play-row { margin-bottom: 7px; }
    .play-btn { min-width: 80px; }
    .speed-row { display: flex; align-items: center; gap: 5px; }
    .speed-label {
      font: 400 9px/1 var(--din-body); letter-spacing: 1.6px;
      color: var(--sc-text-faint); text-transform: uppercase; flex-shrink: 0;
    }
    .speed-btn { min-width: 36px; padding: 5px 6px; font-size: 10px; }
  `],
})
export class HudSettingsComponent {
  protected readonly vs = inject(ViewSettingsStore);
  protected readonly demo = inject(DemoModeService);

  readonly closeRequest = output<void>();

  protected readonly speeds = [0.5, 1, 2, 4];

  @HostListener('document:click', ['$event'])
  onDocClick(e: MouseEvent): void {
    const el = (e.target as HTMLElement).closest('app-hud-settings, .gear-btn');
    if (!el) {
      this.closeRequest.emit();
    }
  }
}
