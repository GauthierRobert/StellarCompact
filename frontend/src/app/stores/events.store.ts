import { computed, Injectable, signal } from '@angular/core';

/**
 * Events store — append-only list of PublicEvents received over the STOMP socket.
 *
 * Wire shape matches `/topic/games/{gameId}/events` in websocket-protocol.md.
 * The closed set of ten event types mirrors PublicEvent.java (backend sealed interface).
 * The store is display-only; it never mutates game state.
 * Transport (E7-03) will call appendEvents() per STOMP message.
 */

// ---------------------------------------------------------------------------
// Types
// ---------------------------------------------------------------------------

/** The ten public event types — wire discriminator from the backend. */
export type PublicEventType =
  | 'WarDeclared'
  | 'TreatySigned'
  | 'TreatyBroken'
  | 'AllianceFormed'
  | 'SystemCaptured'
  | 'BattleResolved'
  | 'RouteEstablished'
  | 'RouteRaided'
  | 'FactionEliminated'
  | 'VictoryAchieved';

/**
 * Public event wire payload — matches the backend JSON:
 * `{ type, parties[], systemId?, tick }`.
 */
export interface PublicEvent {
  /** Wire discriminator. */
  readonly type: PublicEventType;
  /** Faction IDs involved. */
  readonly parties: readonly string[];
  /** System ID the event concerns, or undefined for faction-scoped events. */
  readonly systemId?: string;
  /** Tick number on which the event occurred. */
  readonly tick: number;
}

/** Tick event payload from `/topic/games/{gameId}/ticks`. */
export interface TickEvent {
  readonly tick: number;
  readonly phase: string;
  readonly startedAt: string;
}

// ---------------------------------------------------------------------------
// Store
// ---------------------------------------------------------------------------

/** Maximum number of events kept in memory (ring-buffer eviction). */
const MAX_EVENTS = 500;

@Injectable({ providedIn: 'root' })
export class EventsStore {
  private readonly _events = signal<readonly PublicEvent[]>([]);
  private readonly _latestTick = signal<TickEvent | null>(null);

  // ---- public read-only ----

  /** Full ordered event log (oldest first). */
  readonly events = this._events.asReadonly();

  /** Most-recently received tick event. */
  readonly latestTick = this._latestTick.asReadonly();

  /** The 20 most recent events (newest first) — for the HUD event feed panel. */
  readonly recentEvents = computed<readonly PublicEvent[]>(() =>
    [...this._events()].reverse().slice(0, 20),
  );

  /** Events involving a specific faction, newest first. */
  eventsForFaction(factionId: string): readonly PublicEvent[] {
    return [...this._events()]
      .filter((e) => e.parties.includes(factionId))
      .reverse();
  }

  /** Events of a specific type (newest first). */
  eventsOfType(type: PublicEventType): readonly PublicEvent[] {
    return [...this._events()].filter((e) => e.type === type).reverse();
  }

  /** Count of events per type — useful for stats panels. */
  readonly eventCounts = computed<Readonly<Record<PublicEventType, number>>>(
    () => {
      const counts: Record<string, number> = {};
      for (const e of this._events()) {
        counts[e.type] = (counts[e.type] ?? 0) + 1;
      }
      return counts as Readonly<Record<PublicEventType, number>>;
    },
  );

  // ---- mutators ----

  /**
   * Append one or more public events received from the STOMP socket.
   * Events are deduplicated by (tick, type, parties[0]) to guard against
   * reconnect-resync double-delivery. Oldest events are evicted past MAX_EVENTS.
   */
  appendEvents(incoming: readonly PublicEvent[]): void {
    if (incoming.length === 0) return;
    this._events.update((current) => {
      // Dedup against existing events AND within the incoming batch itself — a
      // single tick can legitimately produce two events that collapse to the
      // same key (same tick/type/party/system), and duplicate keys would break
      // @for tracking (NG0955) downstream.
      const seen = new Set(current.map(eventKey));
      const toAdd: PublicEvent[] = [];
      for (const e of incoming) {
        const k = eventKey(e);
        if (seen.has(k)) continue;
        seen.add(k);
        toAdd.push(e);
      }
      if (toAdd.length === 0) return current;
      const merged = [...current, ...toAdd].sort(
        (a, b) => a.tick - b.tick || a.type.localeCompare(b.type),
      );
      return merged.length > MAX_EVENTS
        ? merged.slice(merged.length - MAX_EVENTS)
        : merged;
    });
  }

  /**
   * Update the latest tick event (from `/topic/games/{gameId}/ticks`).
   */
  applyTick(tick: TickEvent): void {
    this._latestTick.set(tick);
  }

  /** Clear all events (used in tests / game-reset). */
  reset(): void {
    this._events.set([]);
    this._latestTick.set(null);
  }
}

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

function eventKey(e: PublicEvent): string {
  return `${e.tick}:${e.type}:${e.parties[0] ?? ''}:${e.systemId ?? ''}`;
}
