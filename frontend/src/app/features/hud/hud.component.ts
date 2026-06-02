import {
  ChangeDetectionStrategy,
  Component,
  computed,
  inject,
  input,
} from '@angular/core';
import { CommonModule } from '@angular/common';
import { GalaxyComponent } from '../galaxy/galaxy.component';
import { ResourceBarComponent } from './resource-bar.component';
import { SystemPanelComponent } from './system-panel.component';
import { PlanetPanelComponent } from './planet-panel.component';
import { EventFeedComponent } from './event-feed.component';
import { ScaleTierComponent } from './scale-tier.component';
import { SelectionStore } from '../../stores/selection.store';
import { FactionStore } from '../../stores';

/**
 * HUD component — the galaxy view plus all DOM overlay HUD elements.
 *
 * Layout:
 *   - Resource bar fixed to top
 *   - Galaxy canvas fills the viewport
 *   - System / planet side panels on the right (visible when selection exists)
 *   - Scale-tier badge top-right corner
 *   - Event feed strip fixed to bottom
 *
 * All panels are driven by signals; no manual change detection needed.
 * This component is the single entry point for the game view route.
 * The galaxy renderer internals are untouched — this component only
 * overlays DOM elements on top of the canvas.
 */
@Component({
  selector: 'app-hud',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    CommonModule,
    GalaxyComponent,
    ResourceBarComponent,
    SystemPanelComponent,
    PlanetPanelComponent,
    EventFeedComponent,
    ScaleTierComponent,
  ],
  template: `
    <div class="hud-root">
      <!-- Galaxy canvas layer (fills everything) -->
      <app-galaxy
        class="galaxy-layer"
        [seed]="seed()"
        [rMax]="rMax()"
      />

      <!-- Top bar: resources -->
      <div class="hud-top">
        <app-resource-bar [factionId]="playerFactionId()" />
      </div>

      <!-- Top-right: scale tier badge -->
      <div class="hud-top-right">
        <app-scale-tier />
      </div>

      <!-- Right-side panels: system or planet detail -->
      <div class="hud-right" [class.hud-right--visible]="hasPanelVisible()">
        @if (hasPlanetSelection()) {
          <app-planet-panel />
        } @else if (hasSystemSelection()) {
          <app-system-panel />
        }
      </div>

      <!-- Bottom bar: event feed -->
      <div class="hud-bottom">
        <app-event-feed />
      </div>
    </div>
  `,
  styles: [`
    :host {
      display: block;
      position: relative;
      width: 100%;
      height: 100%;
    }
    .hud-root {
      position: relative;
      width: 100%;
      height: 100%;
      overflow: hidden;
    }
    /* Galaxy canvas is the base layer, fills everything */
    .galaxy-layer {
      position: absolute;
      inset: 0;
    }
    /* HUD overlays sit above the canvas via z-index */
    .hud-top {
      position: absolute;
      top: 0;
      left: 0;
      right: 0;
      z-index: 10;
      pointer-events: auto;
    }
    .hud-top-right {
      position: absolute;
      top: 44px; /* below resource bar (~38px) + a small gap */
      right: 12px;
      z-index: 10;
      pointer-events: none;
    }
    .hud-right {
      position: absolute;
      top: 50px;
      right: 12px;
      z-index: 10;
      pointer-events: auto;
      opacity: 0;
      transform: translateX(12px);
      transition: opacity 0.15s ease, transform 0.15s ease;
    }
    .hud-right--visible {
      opacity: 1;
      transform: translateX(0);
    }
    .hud-bottom {
      position: absolute;
      bottom: 0;
      left: 0;
      right: 0;
      z-index: 10;
      pointer-events: auto;
    }
  `],
})
export class HudComponent {
  /** Galaxy seed passed through to the renderer. */
  readonly seed = input<string>('1');
  /** Galaxy radius in world units. */
  readonly rMax = input<number>(1000);
  /** The player's own faction id — drives the resource bar. */
  readonly playerFactionId = input<string>('');

  private readonly selection = inject(SelectionStore);
  private readonly factionStore = inject(FactionStore);

  readonly hasSystemSelection = this.selection.hasSystemSelection;
  readonly hasPlanetSelection = this.selection.hasPlanetSelection;

  readonly hasPanelVisible = computed(
    () => this.hasSystemSelection() || this.hasPlanetSelection(),
  );
}
