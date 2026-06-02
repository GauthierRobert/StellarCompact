import { computed, Injectable, signal } from '@angular/core';

/**
 * Selection store — UI-local signal state for the currently selected system
 * and planet. This is pure client-side selection state; it never mutates
 * game state and is only used to drive the HUD panel display.
 *
 * Scale tier is derived from the camera levelF signal by callers; the
 * selection store is agnostic of zoom. Panels read it via the computed
 * signals below.
 */

// ---------------------------------------------------------------------------
// Types
// ---------------------------------------------------------------------------

/** The four scale tiers from docs/game-design/01-world-and-map.md §5. */
export type ScaleTier = 'galaxy' | 'region' | 'system' | 'planet';

/** Minimal planet summary stored client-side (from tile or overlay data). */
export interface SelectedPlanetInfo {
  readonly planetId: string;
  readonly name: string;
  readonly biome: string;
  readonly slotCount: number;
  readonly usedSlots: number;
}

/** Minimal system summary stored client-side (from tile or overlay data). */
export interface SelectedSystemInfo {
  readonly systemId: string;
  readonly name: string;
  /** Faction id that owns this system, or null if unclaimed. */
  readonly ownerFactionId: string | null;
  readonly starClass: string;
  readonly planets: readonly SelectedPlanetInfo[];
  readonly blockaded: boolean;
  readonly battle: boolean;
}

// ---------------------------------------------------------------------------
// Store
// ---------------------------------------------------------------------------

@Injectable({ providedIn: 'root' })
export class SelectionStore {
  private readonly _selectedSystem = signal<SelectedSystemInfo | null>(null);
  private readonly _selectedPlanet = signal<SelectedPlanetInfo | null>(null);

  // ---- public read-only signals ----

  /** The currently selected system, or null if none. */
  readonly selectedSystem = this._selectedSystem.asReadonly();

  /** The currently selected planet within the selected system, or null. */
  readonly selectedPlanet = this._selectedPlanet.asReadonly();

  /** True when a system is selected. */
  readonly hasSystemSelection = computed(() => this._selectedSystem() !== null);

  /** True when a planet within a system is selected. */
  readonly hasPlanetSelection = computed(() => this._selectedPlanet() !== null);

  /** Number of planets in the selected system. */
  readonly planetCount = computed(() => this._selectedSystem()?.planets.length ?? 0);

  // ---- mutators (UI-local only — never writes to game state) ----

  /** Select a system from an overlay/tile click. Clears planet selection. */
  selectSystem(info: SelectedSystemInfo): void {
    this._selectedSystem.set(info);
    this._selectedPlanet.set(null);
  }

  /** Select a planet within the currently selected system. */
  selectPlanet(info: SelectedPlanetInfo): void {
    this._selectedPlanet.set(info);
  }

  /** Clear system + planet selection. */
  clearSelection(): void {
    this._selectedSystem.set(null);
    this._selectedPlanet.set(null);
  }

  /** Clear only planet selection (return to system panel). */
  clearPlanetSelection(): void {
    this._selectedPlanet.set(null);
  }
}
