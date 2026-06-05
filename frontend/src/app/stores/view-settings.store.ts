import { Injectable, signal } from '@angular/core';

/**
 * View-settings store — UI-local, client-only display preferences for the
 * galaxy view and dashboard. None of this touches game state; it only governs
 * what the renderer/HUD chooses to draw.
 *
 * The defining flag is {@link fogOfWar}: when true (the default) the galaxy is
 * obscured outside explored/monitored space, so the player only sees activity in
 * systems the server has disclosed. The debug toggle in the HUD flips this off to
 * reveal the whole galaxy — purely a presentation switch; it can never expose
 * data the server did not send (fog-correctness is enforced upstream, the fog
 * layer is only a visual veil over already-fog-filtered data).
 */
@Injectable({ providedIn: 'root' })
export class ViewSettingsStore {
  /** War-fog veil over unexplored space. Default ON; debug toggle flips it. */
  private readonly _fogOfWar = signal<boolean>(true);
  /** Draw animated trade-route lanes. */
  private readonly _showRoutes = signal<boolean>(true);
  /** Draw empire territory / influence fields. */
  private readonly _showTerritories = signal<boolean>(true);
  /** Draw system name + owner labels at close zoom. */
  private readonly _showLabels = signal<boolean>(true);
  /** Draw battle / blockade conflict markers. */
  private readonly _showConflict = signal<boolean>(true);
  /** Draw discrete interstellar objects (nebulae, black holes, pulsars…). */
  private readonly _showObjects = signal<boolean>(true);
  /** Draw the named sector grid overlay. */
  private readonly _showSectors = signal<boolean>(false);

  readonly fogOfWar = this._fogOfWar.asReadonly();
  readonly showRoutes = this._showRoutes.asReadonly();
  readonly showTerritories = this._showTerritories.asReadonly();
  readonly showLabels = this._showLabels.asReadonly();
  readonly showConflict = this._showConflict.asReadonly();
  readonly showObjects = this._showObjects.asReadonly();
  readonly showSectors = this._showSectors.asReadonly();

  toggleFogOfWar(): void {
    this._fogOfWar.update((v) => !v);
  }
  setFogOfWar(on: boolean): void {
    this._fogOfWar.set(on);
  }
  toggleRoutes(): void {
    this._showRoutes.update((v) => !v);
  }
  toggleTerritories(): void {
    this._showTerritories.update((v) => !v);
  }
  toggleLabels(): void {
    this._showLabels.update((v) => !v);
  }
  toggleConflict(): void {
    this._showConflict.update((v) => !v);
  }
  toggleObjects(): void {
    this._showObjects.update((v) => !v);
  }
  toggleSectors(): void {
    this._showSectors.update((v) => !v);
  }
}
