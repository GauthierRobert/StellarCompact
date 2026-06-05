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

/** One labelled readout shown in the interstellar-object detail panel. */
export interface ObjectStat {
  readonly label: string;
  readonly value: string;
}

/** A clicked interstellar object (nebula, black hole, pulsar, …). */
export interface SelectedObjectInfo {
  readonly objectId: string;
  /** Object kind discriminator (matches RenderObject.kind). */
  readonly kind: string;
  /** Human display name, e.g. "Veil of Cygnus". */
  readonly name: string;
  /** Short kind label, e.g. "Emission Nebula", "Black Hole". */
  readonly kindLabel: string;
  /** One-paragraph flavour/scientific blurb for the panel body. */
  readonly description: string;
  /** Tabular readouts (composition, mass, hazards, strategic value…). */
  readonly stats: readonly ObjectStat[];
  /** Accent colour hex for the panel header (derived from the object tint). */
  readonly accent: string;
}

/** A clicked sector cell — a summary of everything inside its bounds. */
export interface SelectedSectorInfo {
  readonly sectorId: string;
  readonly name: string;
  /** Active systems whose centres fall inside this sector. */
  readonly systemCount: number;
  /** Interstellar objects inside this sector. */
  readonly objectCount: number;
  /** Per-faction control breakdown (sorted, dominant first). */
  readonly owners: readonly { readonly factionId: string; readonly count: number }[];
  /** Notable named systems inside the sector (display sample). */
  readonly systems: readonly string[];
}

// ---------------------------------------------------------------------------
// Store
// ---------------------------------------------------------------------------

@Injectable({ providedIn: 'root' })
export class SelectionStore {
  private readonly _selectedSystem = signal<SelectedSystemInfo | null>(null);
  private readonly _selectedPlanet = signal<SelectedPlanetInfo | null>(null);
  private readonly _selectedObject = signal<SelectedObjectInfo | null>(null);
  private readonly _selectedSector = signal<SelectedSectorInfo | null>(null);

  // ---- public read-only signals ----

  /** The currently selected system, or null if none. */
  readonly selectedSystem = this._selectedSystem.asReadonly();

  /** The currently selected planet within the selected system, or null. */
  readonly selectedPlanet = this._selectedPlanet.asReadonly();

  /** The currently selected interstellar object, or null. */
  readonly selectedObject = this._selectedObject.asReadonly();

  /** The currently selected sector cell, or null. */
  readonly selectedSector = this._selectedSector.asReadonly();

  /** True when a system is selected. */
  readonly hasSystemSelection = computed(() => this._selectedSystem() !== null);

  /** True when a planet within a system is selected. */
  readonly hasPlanetSelection = computed(() => this._selectedPlanet() !== null);

  /** True when an interstellar object is selected. */
  readonly hasObjectSelection = computed(() => this._selectedObject() !== null);

  /** True when a sector is selected. */
  readonly hasSectorSelection = computed(() => this._selectedSector() !== null);

  /** Number of planets in the selected system. */
  readonly planetCount = computed(() => this._selectedSystem()?.planets.length ?? 0);

  // ---- mutators (UI-local only — never writes to game state) ----

  /** Select a system from an overlay/tile click. Clears all other selections. */
  selectSystem(info: SelectedSystemInfo): void {
    this._selectedSystem.set(info);
    this._selectedPlanet.set(null);
    this._selectedObject.set(null);
    this._selectedSector.set(null);
  }

  /** Select a planet within the currently selected system. */
  selectPlanet(info: SelectedPlanetInfo): void {
    this._selectedPlanet.set(info);
  }

  /** Select an interstellar object. Clears system/sector selections. */
  selectObject(info: SelectedObjectInfo): void {
    this._selectedObject.set(info);
    this._selectedSystem.set(null);
    this._selectedPlanet.set(null);
    this._selectedSector.set(null);
  }

  /** Select a sector cell. Clears system/object selections. */
  selectSector(info: SelectedSectorInfo): void {
    this._selectedSector.set(info);
    this._selectedSystem.set(null);
    this._selectedPlanet.set(null);
    this._selectedObject.set(null);
  }

  /** Clear every selection (system, planet, object, sector). */
  clearSelection(): void {
    this._selectedSystem.set(null);
    this._selectedPlanet.set(null);
    this._selectedObject.set(null);
    this._selectedSector.set(null);
  }

  /** Clear only planet selection (return to system panel). */
  clearPlanetSelection(): void {
    this._selectedPlanet.set(null);
  }

  /** Clear only the object selection. */
  clearObjectSelection(): void {
    this._selectedObject.set(null);
  }

  /** Clear only the sector selection. */
  clearSectorSelection(): void {
    this._selectedSector.set(null);
  }
}
