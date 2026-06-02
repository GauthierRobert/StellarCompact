import { Injectable, InjectionToken, OnDestroy, inject, signal } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { firstValueFrom } from 'rxjs';
import { Client, IMessage, StompConfig, StompSubscription } from '@stomp/stompjs';
import { EventsStore, PublicEvent, TickEvent } from '../stores/events.store';
import { OverlayStore, OverlayDelta, SystemOverlay, RouteOverlay } from '../stores/overlay.store';
import { FactionStore } from '../stores/faction.store';
import { CameraStore } from '../stores/camera.store';

// ---------------------------------------------------------------------------
// Client factory token — allows tests to inject a mock STOMP client factory.
// ---------------------------------------------------------------------------

/**
 * Factory function type that creates a STOMP Client from config.
 * The default factory uses the real `@stomp/stompjs` Client.
 * Tests override this token to inject a mock without a real WebSocket.
 */
export type StompClientFactory = (config: StompConfig) => Client;

export const STOMP_CLIENT_FACTORY = new InjectionToken<StompClientFactory>(
  'STOMP_CLIENT_FACTORY',
  {
    providedIn: 'root',
    factory: () => (config: StompConfig) => new Client(config),
  },
);

// ---------------------------------------------------------------------------
// WorldView types (owner-only /user/queue/faction/{id}/view)
// ---------------------------------------------------------------------------

/**
 * Per-faction fog-filtered perception payload received over the owner-only
 * STOMP queue `/user/queue/faction/{factionId}/view`.
 *
 * The backend `WorldViewBuilder` applies fog-of-war before serialising; the
 * client never requests, computes, or renders another faction's hidden state.
 * Field names match the expected JSON wire format from the server.
 */
export interface WorldView {
  readonly factionId: string;
  readonly tick: number;
  /** Visible systems (own + fog-limited others). */
  readonly visibleSystems: readonly WorldViewSystem[];
  /** Known diplomatic relationships. */
  readonly diplomacy: readonly WorldViewDiplomacy[];
}

export interface WorldViewSystem {
  readonly systemId: string;
  readonly ownerFactionId: string | null;
  readonly contested: boolean;
  readonly fleets: number;
}

export interface WorldViewDiplomacy {
  readonly factionId: string;
  readonly status: 'allied' | 'neutral' | 'war' | 'tribute';
  readonly reputation: number;
}

// ---------------------------------------------------------------------------
// Connection config
// ---------------------------------------------------------------------------

export interface StompConnectionConfig {
  /** Game ID to subscribe for. */
  readonly gameId: string;
  /**
   * Principal name used as query param at handshake (current thin-auth stand-in).
   * Omit if not yet known.
   */
  readonly principal?: string;
  /**
   * Faction ID owned by this session, if any.
   * When set, the service subscribes to the owner-only WorldView queue.
   */
  readonly factionId?: string;
  /** WebSocket endpoint URL (defaults to `/ws`). */
  readonly brokerURL?: string;
}

// REST overlay resync response shape (E6-03 endpoint).
interface OverlayResyncDto {
  readonly gameId: string;
  readonly asOfTick: number;
  readonly sinceTick: number;
  readonly bbox: string;
  readonly systems: readonly OverlayResyncSystem[];
  readonly blockades: readonly { systemId: string }[];
}

interface OverlayResyncSystem {
  readonly systemId: string;
  readonly owner: string | null;
  readonly tint: string | null;
  readonly contested: boolean;
  readonly fleets: readonly { factionId: string; count: number }[];
  readonly routes: readonly { toSystemId: string }[];
}

// ---------------------------------------------------------------------------
// Service
// ---------------------------------------------------------------------------

/** Connection state exposed via signal for reactive UI. */
export type StompConnectionState = 'disconnected' | 'connecting' | 'connected' | 'error';

/**
 * STOMP/WebSocket client service for Stellar Compact.
 *
 * Connects to `/ws`, subscribes to public topics (ticks / events / overlay) and,
 * when the session owns a faction, the owner-only WorldView queue.  All inbound
 * frames are mapped directly to signal-store mutations — no Zone.js, no manual
 * change detection, fully zoneless-compatible.
 *
 * Reconnect flow:
 *   1. `@stomp/stompjs` Client handles transport-level reconnect with exponential
 *      back-off (reconnectDelay).
 *   2. `onConnect` re-establishes all STOMP subscriptions.
 *   3. After resubscribing the service issues a REST
 *      `GET /api/galaxy/{gameId}/overlay?bbox=...&sinceTick={lastAsOfTick}` to
 *      backfill any overlay deltas missed during the gap.
 */
@Injectable({ providedIn: 'root' })
export class StompClientService implements OnDestroy {
  private readonly eventsStore = inject(EventsStore);
  private readonly overlayStore = inject(OverlayStore);
  // FactionStore injected for future tick-payload routing; presently ticks only
  // carry TickEvent (heartbeat). Reserved here so wiring is in one place.
  private readonly factionStore = inject(FactionStore);
  private readonly cameraStore = inject(CameraStore);
  private readonly http = inject(HttpClient);
  private readonly clientFactory = inject(STOMP_CLIENT_FACTORY);

  // ---- signals ----

  /** Current STOMP connection state — reactive, zoneless-safe. */
  readonly connectionState = signal<StompConnectionState>('disconnected');

  /** Last WorldView received on the owner-only queue, or null. */
  readonly ownerWorldView = signal<WorldView | null>(null);

  // ---- private state ----

  private client: Client | null = null;
  private config: StompConnectionConfig | null = null;
  private subscriptions: StompSubscription[] = [];

  // ---- public API ----

  /**
   * Connect to the STOMP broker and start feeding signals.
   * Safe to call multiple times: if already connected with the same gameId this
   * is a no-op; to reconnect with a different config call `disconnect()` first.
   */
  connect(config: StompConnectionConfig): void {
    if (this.client?.connected && this.config?.gameId === config.gameId) {
      return;
    }
    this.disconnectInternal();
    this.config = config;
    this.connectionState.set('connecting');

    const principal = config.principal ?? 'anonymous';
    const rawUrl = config.brokerURL ?? '/ws';
    // Append handshake query params as required by websocket-protocol.md.
    const brokerURL = `${rawUrl}?principal=${encodeURIComponent(principal)}&gameId=${encodeURIComponent(config.gameId)}`;

    this.client = this.clientFactory({
      brokerURL,
      reconnectDelay: 5000,
      onConnect: () => this.onConnected(),
      onDisconnect: () => this.connectionState.set('disconnected'),
      onStompError: () => this.connectionState.set('error'),
      onWebSocketError: () => this.connectionState.set('error'),
    });

    this.client.activate();
  }

  /**
   * Send a spectate bbox hint to the server so it can scope overlay deltas.
   * Call whenever the camera bbox changes.
   */
  sendSpectateBbox(gameId: string, bbox: { minX: number; minY: number; maxX: number; maxY: number }): void {
    if (!this.client?.connected) return;
    this.client.publish({
      destination: `/app/games/${gameId}/spectate`,
      body: JSON.stringify(bbox),
    });
  }

  /** Gracefully disconnect and clean up subscriptions. */
  disconnect(): void {
    this.disconnectInternal();
  }

  ngOnDestroy(): void {
    this.disconnectInternal();
  }

  // ---- private helpers ----

  private onConnected(): void {
    this.connectionState.set('connected');
    this.resubscribe();
    // Resync overlay for any missed deltas during the disconnect window.
    void this.resyncOverlay();
  }

  private resubscribe(): void {
    // Clear any stale subscriptions from the previous session.
    this.unsubscribeAll();
    const { gameId, factionId } = this.config!;

    // Public tick heartbeat.
    this.subscriptions.push(
      this.client!.subscribe(
        `/topic/games/${gameId}/ticks`,
        (msg: IMessage) => this.handleTick(msg),
      ),
    );

    // Public event log.
    this.subscriptions.push(
      this.client!.subscribe(
        `/topic/games/${gameId}/events`,
        (msg: IMessage) => this.handleEvent(msg),
      ),
    );

    // Overlay delta pointer (thin; heavy detail fetched via REST).
    this.subscriptions.push(
      this.client!.subscribe(
        `/topic/games/${gameId}/overlay`,
        (msg: IMessage) => this.handleOverlayDelta(msg),
      ),
    );

    // Owner-only WorldView — skipped if this session has no owned faction.
    if (factionId) {
      this.subscriptions.push(
        this.client!.subscribe(
          `/user/queue/faction/${factionId}/view`,
          (msg: IMessage) => this.handleWorldView(msg),
        ),
      );
    }

    // Hint the server with the current camera bbox so overlay deltas are scoped.
    const bbox = this.cameraStore.visibleBbox();
    if (bbox) {
      this.sendSpectateBbox(gameId, bbox);
    }
  }

  private handleTick(msg: IMessage): void {
    try {
      const tick = JSON.parse(msg.body) as TickEvent;
      this.eventsStore.applyTick(tick);
    } catch {
      // Malformed frame — discard silently; engine is authoritative.
    }
  }

  private handleEvent(msg: IMessage): void {
    try {
      const event = JSON.parse(msg.body) as PublicEvent;
      this.eventsStore.appendEvents([event]);
    } catch {
      // Malformed frame — discard silently.
    }
  }

  private handleOverlayDelta(msg: IMessage): void {
    try {
      const delta = JSON.parse(msg.body) as OverlayDelta;
      this.overlayStore.applyOverlayDelta(delta);
    } catch {
      // Malformed frame — discard silently.
    }
  }

  private handleWorldView(msg: IMessage): void {
    try {
      const view = JSON.parse(msg.body) as WorldView;
      this.ownerWorldView.set(view);
    } catch {
      // Malformed frame — discard silently.
    }
  }

  /**
   * REST resync after reconnect: fetch the overlay diff since the last tick
   * we received, bounded to the current camera bbox.
   *
   * Maps the REST shape (E6-03 spec) into `OverlayDelta` and applies it via
   * `applyOverlayDelta` so the store stays consistent.
   */
  private async resyncOverlay(): Promise<void> {
    if (!this.config) return;
    const { gameId } = this.config;
    const sinceTick = this.overlayStore.asOfTick();
    const bbox = this.cameraStore.visibleBbox();

    if (!bbox) return;

    const bboxParam = `${bbox.minX},${bbox.minY},${bbox.maxX},${bbox.maxY}`;
    const url = `/api/galaxy/${gameId}/overlay?bbox=${encodeURIComponent(bboxParam)}&sinceTick=${sinceTick}`;

    try {
      const dto = await firstValueFrom(this.http.get<OverlayResyncDto>(url));
      const delta = mapResyncDtoToOverlayDelta(dto);
      this.overlayStore.applyOverlayDelta(delta);
    } catch {
      // Best-effort resync — if it fails the next STOMP delta will catch up.
    }
  }

  private unsubscribeAll(): void {
    for (const sub of this.subscriptions) {
      try {
        sub.unsubscribe();
      } catch {
        // Ignore — connection may already be gone.
      }
    }
    this.subscriptions = [];
  }

  private disconnectInternal(): void {
    this.unsubscribeAll();
    if (this.client) {
      try {
        this.client.deactivate();
      } catch {
        // Ignore.
      }
      this.client = null;
    }
    this.config = null;
    this.connectionState.set('disconnected');
    this.ownerWorldView.set(null);
  }
}

// ---------------------------------------------------------------------------
// Mapping helpers
// ---------------------------------------------------------------------------

/**
 * Map the REST overlay resync DTO (E6-03 shape) into the store's `OverlayDelta`
 * format so the same `applyOverlayDelta` path handles both live socket deltas
 * and REST backfill.
 */
export function mapResyncDtoToOverlayDelta(dto: OverlayResyncDto): OverlayDelta {
  const blockadedIds = new Set(dto.blockades.map((b) => b.systemId));

  const changedSystems: SystemOverlay[] = dto.systems.map((s) => ({
    systemId: s.systemId,
    ownerFactionId: s.owner,
    activityLevel: s.fleets.reduce((sum, f) => sum + f.count, 0) > 0 ? 1 : 0,
    blockaded: blockadedIds.has(s.systemId),
    battle: s.contested,
    asOfTick: dto.asOfTick,
  }));

  // The REST endpoint does not carry route overlay data; route state comes only
  // over the STOMP delta.  Supply an empty changedRoutes so the caller does not
  // need special-casing.
  const changedRoutes: RouteOverlay[] = [];

  return { changedSystems, changedRoutes, asOfTick: dto.asOfTick };
}
