import {
  ChangeDetectionStrategy,
  Component,
  OnDestroy,
  OnInit,
  computed,
  inject,
  input,
} from '@angular/core';
import { DemoModeService } from '../../services/demo-mode.service';
import { CommonModule } from '@angular/common';
import { RouterLink } from '@angular/router';
import { GalaxyComponent } from '../galaxy/galaxy.component';
import { SystemPanelComponent } from './system-panel.component';
import { PlanetPanelComponent } from './planet-panel.component';
import { ObjectPanelComponent } from './object-panel.component';
import { SectorPanelComponent } from './sector-panel.component';
import { ScaleTierComponent } from './scale-tier.component';
import { SelectionStore } from '../../stores/selection.store';
import { FactionStore } from '../../stores';
import { CommandBarComponent } from '../dashboard/command-bar.component';
import { EmpireRailComponent } from '../dashboard/empire-rail.component';
import { StandingsPanelComponent } from '../dashboard/standings-panel.component';
import { EventTickerComponent } from '../dashboard/event-ticker.component';

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
    RouterLink,
    GalaxyComponent,
    SystemPanelComponent,
    PlanetPanelComponent,
    ObjectPanelComponent,
    SectorPanelComponent,
    ScaleTierComponent,
    CommandBarComponent,
    EmpireRailComponent,
    StandingsPanelComponent,
    EventTickerComponent,
  ],
  template: `
    <div class="hud-root">
      <!-- Galaxy canvas layer (fills everything) -->
      <app-galaxy
        class="galaxy-layer"
        [seed]="seed()"
        [rMax]="rMax()"
      />

      <!-- Top bar: command bar (replaces old resource-bar) -->
      <div class="hud-top">
        <app-command-bar />
      </div>

      <!-- Left rail: empire dashboard (below command-bar, above ticker) -->
      <div class="hud-left">
        <app-empire-rail />
      </div>

      <!-- Right rail: standings (below command-bar, above ticker) -->
      <div class="hud-right-rail">
        <app-standings-panel />
      </div>

      <!-- Selection panels: planet > system > object > sector (above standings rail) -->
      <div class="hud-selection" [class.hud-selection--visible]="hasPanelVisible()">
        @if (hasPlanetSelection()) {
          <app-planet-panel />
        } @else if (hasSystemSelection()) {
          <app-system-panel />
        } @else if (hasObjectSelection()) {
          <app-object-panel />
        } @else if (hasSectorSelection()) {
          <app-sector-panel />
        }
      </div>

      <!-- Top-right corner: scale tier badge -->
      <div class="hud-scale-tier">
        <app-scale-tier />
      </div>

      <!-- Command-pages entry: opens the deep empire management screens (E13) -->
      <a class="hud-command-link" routerLink="/empire" title="Open empire command (planets, fleets, trade, wars, tech)">
        <span class="cmd-glyph">◆</span> Command
      </a>

      <!-- Bottom: rich event ticker (replaces old event-feed) -->
      <div class="hud-bottom">
        <app-event-ticker />
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
    .galaxy-layer {
      position: absolute;
      inset: 0;
    }
    /* Command bar pinned to top */
    .hud-top {
      position: absolute;
      top: 0; left: 0; right: 0;
      z-index: 20;
      pointer-events: auto;
    }
    /* Left empire rail */
    .hud-left {
      position: absolute;
      top: 48px; /* below command bar */
      left: 0;
      bottom: 36px; /* above ticker */
      width: 300px;
      z-index: 10;
      pointer-events: auto;
      overflow-y: auto;
      overflow-x: hidden;
      scrollbar-width: thin;
      scrollbar-color: rgba(255,255,255,0.2) transparent;
      padding: 8px 8px 8px 8px;
      display: flex;
      flex-direction: column;
      gap: 0;
    }
    .hud-left::-webkit-scrollbar { width: 5px; }
    .hud-left::-webkit-scrollbar-thumb { background: rgba(255,255,255,0.2); border-radius: 4px; }
    /* Right standings panel */
    .hud-right-rail {
      position: absolute;
      top: 48px;
      right: 0;
      bottom: 36px;
      width: 260px;
      z-index: 10;
      pointer-events: auto;
      padding: 8px;
      overflow-y: auto;
      scrollbar-width: thin;
      scrollbar-color: rgba(255,255,255,0.2) transparent;
    }
    .hud-right-rail::-webkit-scrollbar { width: 5px; }
    .hud-right-rail::-webkit-scrollbar-thumb { background: rgba(255,255,255,0.2); border-radius: 4px; }
    /* Selection panels (system/planet) — above right rail */
    .hud-selection {
      position: absolute;
      top: 56px;
      right: 268px; /* left of standings rail */
      z-index: 15;
      pointer-events: auto;
      opacity: 0;
      transform: translateX(12px);
      transition: opacity 0.18s ease, transform 0.18s ease;
    }
    .hud-selection--visible {
      opacity: 1;
      transform: translateX(0);
    }
    /* Scale tier badge */
    .hud-scale-tier {
      position: absolute;
      top: 56px;
      right: 268px;
      z-index: 11;
      pointer-events: none;
    }
    /* Bottom event ticker */
    .hud-bottom {
      position: absolute;
      bottom: 0; left: 0; right: 0;
      z-index: 20;
      pointer-events: auto;
    }
    /* Command-pages entry pill (bottom-right, above the ticker) */
    .hud-command-link {
      position: absolute;
      bottom: 46px;
      right: 12px;
      z-index: 18;
      pointer-events: auto;
      display: flex;
      align-items: center;
      gap: 6px;
      padding: 8px 14px;
      border: 1px solid var(--sc-border-bright);
      border-radius: var(--rounded-pill);
      background: rgba(10, 12, 18, 0.85);
      backdrop-filter: blur(7px);
      color: var(--sc-text);
      text-decoration: none;
      font: 600 12px/1 var(--din-body);
      letter-spacing: 0.4px;
      transition: border-color 0.15s, background 0.15s;
    }
    .hud-command-link:hover { border-color: var(--sc-energy); background: rgba(127,216,239,0.12); }
    .cmd-glyph { color: var(--sc-energy); }
  `],
})
export class HudComponent implements OnInit, OnDestroy {
  /** Galaxy seed passed through to the renderer. */
  readonly seed = input<string>('1');
  /** Galaxy radius in world units. */
  readonly rMax = input<number>(1000);
  /** The player's own faction id — drives the resource bar. */
  readonly playerFactionId = input<string>('');

  private readonly selection = inject(SelectionStore);
  private readonly factionStore = inject(FactionStore);
  private readonly demo = inject(DemoModeService);

  readonly hasSystemSelection = this.selection.hasSystemSelection;
  readonly hasPlanetSelection = this.selection.hasPlanetSelection;
  readonly hasObjectSelection = this.selection.hasObjectSelection;
  readonly hasSectorSelection = this.selection.hasSectorSelection;

  /** Player faction id: explicit input wins; otherwise the demo's own faction. */
  readonly activePlayerId = computed(
    () => this.playerFactionId() || this.demo.playerFactionId(),
  );

  readonly hasPanelVisible = computed(
    () =>
      this.hasSystemSelection() ||
      this.hasPlanetSelection() ||
      this.hasObjectSelection() ||
      this.hasSectorSelection(),
  );

  ngOnInit(): void {
    // No live match wired into this route yet → run the offline demo so the
    // galaxy and dashboard are populated. A real STOMP connection would take
    // precedence (the demo only synthesises tiles + stores when active).
    if (!this.playerFactionId()) {
      this.demo.start(Number(this.seed()) || 1);
    }
  }

  ngOnDestroy(): void {
    this.demo.stop();
  }
}
