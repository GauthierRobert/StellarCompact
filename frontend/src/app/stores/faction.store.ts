import { computed, Injectable, signal } from '@angular/core';

/**
 * Faction store — holds the public per-faction state broadcast each tick.
 *
 * Shape matches the websocket-protocol.md WorldView / overlay faction payload.
 * The store is display-only; all rule enforcement lives server-side.
 * Transport (E7-03 STOMP client) will call applyTick() each tick.
 */

// ---------------------------------------------------------------------------
// Types
// ---------------------------------------------------------------------------

/** Resources owned by a faction (public/visible portion). */
export interface FactionResources {
  readonly credits: number;
  readonly minerals: number;
  readonly influence: number;
}

/**
 * Public faction state pushed over the socket each tick.
 * Field names match the expected JSON wire format from the backend.
 */
export interface FactionSnapshot {
  readonly factionId: string;
  readonly name: string;
  /** Colour hex string for HUD rendering (e.g. "#4ad6a0"). */
  readonly colour: string;
  readonly resources: FactionResources;
  /** Normalised reputation score in [-1, 1]. */
  readonly reputation: number;
  /** Number of systems controlled. */
  readonly systemCount: number;
  /** Whether this faction has been eliminated. */
  readonly eliminated: boolean;
}

/** A tick payload that updates faction snapshots. */
export interface TickFactionPayload {
  readonly tick: number;
  readonly factions: readonly FactionSnapshot[];
}

// ---------------------------------------------------------------------------
// Store
// ---------------------------------------------------------------------------

@Injectable({ providedIn: 'root' })
export class FactionStore {
  /** Current tick number (0 until first tick arrives). */
  private readonly _tick = signal<number>(0);

  /** Map of factionId → snapshot. */
  private readonly _factions = signal<ReadonlyMap<string, FactionSnapshot>>(
    new Map(),
  );

  // ---- public read-only ----

  readonly tick = this._tick.asReadonly();

  /** All factions as an immutable array, sorted by systemCount descending (leaderboard order). */
  readonly factions = computed<readonly FactionSnapshot[]>(() =>
    [...this._factions().values()].sort((a, b) => b.systemCount - a.systemCount),
  );

  /** Active (non-eliminated) factions. */
  readonly activeFactions = computed<readonly FactionSnapshot[]>(() =>
    this.factions().filter((f) => !f.eliminated),
  );

  /** Eliminated factions. */
  readonly eliminatedFactions = computed<readonly FactionSnapshot[]>(() =>
    this.factions().filter((f) => f.eliminated),
  );

  /** Total system count across all active factions. */
  readonly totalSystems = computed<number>(() =>
    this.activeFactions().reduce((sum, f) => sum + f.systemCount, 0),
  );

  /**
   * Lookup a faction by id — returns undefined if not found.
   * Returning a computed is not practical here because of the dynamic key;
   * consumers can use inject(FactionStore).factions() and filter.
   */
  getById(factionId: string): FactionSnapshot | undefined {
    return this._factions().get(factionId);
  }

  // ---- mutators ----

  /**
   * Replace all faction snapshots from a full tick payload.
   * Called by the STOMP tick handler (E7-03).
   */
  applyTick(payload: TickFactionPayload): void {
    this._tick.set(payload.tick);
    const map = new Map<string, FactionSnapshot>();
    for (const f of payload.factions) {
      map.set(f.factionId, f);
    }
    this._factions.set(map);
  }

  /**
   * Patch a single faction snapshot (e.g. from an overlay delta that carries
   * partial faction state updates). Unknown fields are preserved from the existing snapshot.
   */
  patchFaction(patch: Partial<FactionSnapshot> & { factionId: string }): void {
    this._factions.update((current) => {
      const existing = current.get(patch.factionId);
      const merged: FactionSnapshot = { ...(existing ?? emptySnapshot(patch.factionId)), ...patch };
      const next = new Map(current);
      next.set(patch.factionId, merged);
      return next;
    });
  }

  /** Reset to initial state (used in tests). */
  reset(): void {
    this._tick.set(0);
    this._factions.set(new Map());
  }
}

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

function emptySnapshot(factionId: string): FactionSnapshot {
  return {
    factionId,
    name: factionId,
    colour: '#888888',
    resources: { credits: 0, minerals: 0, influence: 0 },
    reputation: 0,
    systemCount: 0,
    eliminated: false,
  };
}
