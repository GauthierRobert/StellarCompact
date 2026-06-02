import { computed, Injectable, signal } from '@angular/core';

/**
 * Overlay store — per-system active-state diff used to colour the galaxy map.
 *
 * Wire shape matches `/topic/games/{gameId}/overlay` in websocket-protocol.md:
 *   OverlayDelta { changedSystems[], changedRoutes[], asOfTick }
 *
 * The store keeps an accumulated map of system/route state keyed by id.
 * The galaxy renderer reads visibleSystems/visibleRoutes (filtered to camera bbox)
 * to colour systems and draw route overlays.
 * Transport (E7-03) will call applyOverlayDelta() per STOMP message.
 */

// ---------------------------------------------------------------------------
// Types
// ---------------------------------------------------------------------------

/** Active state of a single star system in the overlay. */
export interface SystemOverlay {
  readonly systemId: string;
  /** Faction that currently controls this system, or null if unclaimed. */
  readonly ownerFactionId: string | null;
  /**
   * System activity level: 0 = no activity, 1 = light, 2 = heavy.
   * Used to scale the glow effect on the map.
   */
  readonly activityLevel: number;
  /** Whether the system is under blockade. */
  readonly blockaded: boolean;
  /** Whether there is an active battle at this system. */
  readonly battle: boolean;
  /** Tick of the last change (for staleness check). */
  readonly asOfTick: number;
}

/** Active state of a trade route in the overlay. */
export interface RouteOverlay {
  readonly routeId: string;
  readonly fromSystemId: string;
  readonly toSystemId: string;
  readonly ownerFactionId: string;
  readonly kind: 'allied' | 'trade' | 'contested';
  readonly active: boolean;
  readonly asOfTick: number;
}

/** Delta payload from the STOMP overlay topic. */
export interface OverlayDelta {
  readonly changedSystems: readonly SystemOverlay[];
  readonly changedRoutes: readonly RouteOverlay[];
  readonly asOfTick: number;
}

// ---------------------------------------------------------------------------
// Store
// ---------------------------------------------------------------------------

@Injectable({ providedIn: 'root' })
export class OverlayStore {
  private readonly _systems = signal<ReadonlyMap<string, SystemOverlay>>(
    new Map(),
  );
  private readonly _routes = signal<ReadonlyMap<string, RouteOverlay>>(
    new Map(),
  );
  private readonly _asOfTick = signal<number>(0);

  // ---- public read-only ----

  readonly asOfTick = this._asOfTick.asReadonly();

  /** All system overlays as an array. Used by the renderer to colour tiles. */
  readonly allSystems = computed<readonly SystemOverlay[]>(() => [
    ...this._systems().values(),
  ]);

  /** All route overlays as an array. Used by the renderer to draw lanes. */
  readonly allRoutes = computed<readonly RouteOverlay[]>(() => [
    ...this._routes().values(),
  ]);

  /** Active routes only (not soft-deleted). */
  readonly activeRoutes = computed<readonly RouteOverlay[]>(() =>
    this.allRoutes().filter((r) => r.active),
  );

  /** Systems under active battle. */
  readonly battleSystems = computed<readonly SystemOverlay[]>(() =>
    this.allSystems().filter((s) => s.battle),
  );

  /** Systems under blockade. */
  readonly blockadedSystems = computed<readonly SystemOverlay[]>(() =>
    this.allSystems().filter((s) => s.blockaded),
  );

  /** Systems owned by a specific faction. */
  systemsOwnedBy(factionId: string): readonly SystemOverlay[] {
    return this.allSystems().filter((s) => s.ownerFactionId === factionId);
  }

  /** Look up a single system overlay by id. */
  getSystem(systemId: string): SystemOverlay | undefined {
    return this._systems().get(systemId);
  }

  /** Look up a single route overlay by id. */
  getRoute(routeId: string): RouteOverlay | undefined {
    return this._routes().get(routeId);
  }

  // ---- mutators ----

  /**
   * Merge an overlay delta into the accumulated store.
   * Only updates systems/routes that are in the delta (sparse diff semantics).
   * Called by the STOMP overlay handler (E7-03).
   */
  applyOverlayDelta(delta: OverlayDelta): void {
    if (delta.changedSystems.length > 0) {
      this._systems.update((current) => {
        const next = new Map(current);
        for (const s of delta.changedSystems) {
          next.set(s.systemId, s);
        }
        return next;
      });
    }
    if (delta.changedRoutes.length > 0) {
      this._routes.update((current) => {
        const next = new Map(current);
        for (const r of delta.changedRoutes) {
          next.set(r.routeId, r);
        }
        return next;
      });
    }
    if (delta.asOfTick > this._asOfTick()) {
      this._asOfTick.set(delta.asOfTick);
    }
  }

  /**
   * Replace the entire overlay with a full snapshot.
   * Used on reconnect after REST resync (`GET /overlay?sinceTick=0`).
   */
  replaceAll(systems: readonly SystemOverlay[], routes: readonly RouteOverlay[], asOfTick: number): void {
    const sMap = new Map<string, SystemOverlay>();
    for (const s of systems) sMap.set(s.systemId, s);
    this._systems.set(sMap);

    const rMap = new Map<string, RouteOverlay>();
    for (const r of routes) rMap.set(r.routeId, r);
    this._routes.set(rMap);

    this._asOfTick.set(asOfTick);
  }

  /** Clear all overlay data (used in tests / game-reset). */
  reset(): void {
    this._systems.set(new Map());
    this._routes.set(new Map());
    this._asOfTick.set(0);
  }
}
