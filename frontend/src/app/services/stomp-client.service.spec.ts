/**
 * StompClientService unit tests.
 *
 * Strategy: the @stomp/stompjs Client is replaced via STOMP_CLIENT_FACTORY injection
 * token so no real WebSocket is needed.  A MockStompClient captures the callbacks
 * passed by the service and exposes helpers to drive them from tests.
 *
 * This lets us assert:
 *   - STOMP subscriptions are created for the three public topics.
 *   - Inbound STOMP frames update the signal stores (EventsStore, OverlayStore).
 *   - On reconnect the service resubscribes AND issues a REST overlay resync.
 *   - Owner-only WorldView subscription is wired when factionId is provided.
 *   - mapResyncDtoToOverlayDelta converts the REST DTO correctly.
 */

import { TestBed } from '@angular/core/testing';
import { provideZonelessChangeDetection } from '@angular/core';
import {
  HttpClientTestingModule,
  HttpTestingController,
} from '@angular/common/http/testing';

import {
  StompClientService,
  STOMP_CLIENT_FACTORY,
  mapResyncDtoToOverlayDelta,
  WorldView,
} from './stomp-client.service';
import { EventsStore } from '../stores/events.store';
import { OverlayStore } from '../stores/overlay.store';
import { CameraStore } from '../stores/camera.store';

// ---------------------------------------------------------------------------
// Mock STOMP client
// ---------------------------------------------------------------------------

interface StompCallbacks {
  onConnect?: () => void;
  onDisconnect?: () => void;
  onStompError?: () => void;
  onWebSocketError?: () => void;
}

class MockStompClient {
  connected = false;
  deactivateCallCount = 0;
  publishCallCount = 0;
  lastPublishDestination = '';
  lastPublishBody = '';

  subscriptions: { destination: string; handler: (msg: { body: string }) => void }[] = [];

  private readonly callbacks: StompCallbacks;

  constructor(config: StompCallbacks) {
    this.callbacks = config;
  }

  activate(): void {
    this.connected = true;
    // Fire onConnect synchronously so service methods run in the same test tick.
    this.callbacks.onConnect?.();
  }

  deactivate(): void {
    this.deactivateCallCount++;
    this.connected = false;
  }

  subscribe(destination: string, handler: (msg: { body: string }) => void): { unsubscribe: () => void } {
    this.subscriptions.push({ destination, handler });
    return { unsubscribe: () => { /* unsubscribe is a no-op in the mock */ } };
  }

  publish(options: { destination: string; body: string }): void {
    this.publishCallCount++;
    this.lastPublishDestination = options.destination;
    this.lastPublishBody = options.body;
  }

  /** Deliver a raw body to a subscribed destination (simulates inbound frame). */
  deliver(destination: string, body: string): void {
    const sub = this.subscriptions.find((s) => s.destination === destination);
    sub?.handler({ body });
  }

  /** Simulate transport disconnect then reconnect. */
  simulateReconnect(): void {
    this.callbacks.onDisconnect?.();
    this.subscriptions = [];
    this.callbacks.onConnect?.();
  }
}

let lastMockClient: MockStompClient | null = null;

// ---------------------------------------------------------------------------
// Test setup
// ---------------------------------------------------------------------------

function buildService(): {
  service: StompClientService;
  eventsStore: EventsStore;
  overlayStore: OverlayStore;
  http: HttpTestingController;
} {
  lastMockClient = null;

  TestBed.configureTestingModule({
    imports: [HttpClientTestingModule],
    providers: [
      provideZonelessChangeDetection(),
      StompClientService,
      EventsStore,
      OverlayStore,
      CameraStore,
      {
        provide: STOMP_CLIENT_FACTORY,
        useValue: (config: StompCallbacks) => {
          const c = new MockStompClient(config);
          lastMockClient = c;
          return c;
        },
      },
    ],
  });

  return {
    service: TestBed.inject(StompClientService),
    eventsStore: TestBed.inject(EventsStore),
    overlayStore: TestBed.inject(OverlayStore),
    http: TestBed.inject(HttpTestingController),
  };
}

// ---------------------------------------------------------------------------
// Tests
// ---------------------------------------------------------------------------

describe('StompClientService', () => {
  afterEach(() => {
    TestBed.resetTestingModule();
  });

  // ---- connection state ----

  it('starts in disconnected state', () => {
    const { service } = buildService();
    expect(service.connectionState()).toBe('disconnected');
  });

  it('transitions to connected after broker activates', () => {
    const { service } = buildService();
    service.connect({ gameId: 'game-1', principal: 'alice' });
    expect(service.connectionState()).toBe('connected');
  });

  it('transitions to disconnected on explicit disconnect', () => {
    const { service } = buildService();
    service.connect({ gameId: 'game-1' });
    service.disconnect();
    expect(service.connectionState()).toBe('disconnected');
  });

  // ---- subscriptions ----

  it('subscribes to all three public topics on connect', () => {
    const { service, http } = buildService();
    service.connect({ gameId: 'game-42', principal: 'alice' });

    const mock = lastMockClient!;
    const destinations = mock.subscriptions.map((s) => s.destination);

    expect(destinations).toContain('/topic/games/game-42/ticks');
    expect(destinations).toContain('/topic/games/game-42/events');
    expect(destinations).toContain('/topic/games/game-42/overlay');

    // Absorb the REST resync request triggered by onConnected.
    http.expectOne((req) => req.url.includes('/overlay')).flush({
      gameId: 'game-42', asOfTick: 0, sinceTick: 0, bbox: '', systems: [], blockades: [],
    });
    http.verify();
  });

  it('subscribes to owner-only WorldView queue when factionId is provided', () => {
    const { service, http } = buildService();
    service.connect({ gameId: 'g1', factionId: 'faction-7' });

    const mock = lastMockClient!;
    const destinations = mock.subscriptions.map((s) => s.destination);
    expect(destinations).toContain('/user/queue/faction/faction-7/view');

    http.expectOne((req) => req.url.includes('/overlay')).flush({
      gameId: 'g1', asOfTick: 0, sinceTick: 0, bbox: '', systems: [], blockades: [],
    });
    http.verify();
  });

  it('does NOT subscribe to owner-only queue when no factionId', () => {
    const { service, http } = buildService();
    service.connect({ gameId: 'g2' });

    const mock = lastMockClient!;
    const ownerSubs = mock.subscriptions.filter((s) =>
      s.destination.includes('/user/queue/faction'),
    );
    expect(ownerSubs.length).toBe(0);

    http.expectOne((req) => req.url.includes('/overlay')).flush({
      gameId: 'g2', asOfTick: 0, sinceTick: 0, bbox: '', systems: [], blockades: [],
    });
    http.verify();
  });

  // ---- inbound frame → signal mapping ----

  it('tick frame updates EventsStore.latestTick signal', () => {
    const { service, eventsStore, http } = buildService();
    service.connect({ gameId: 'gx' });

    lastMockClient!.deliver(
      '/topic/games/gx/ticks',
      JSON.stringify({ tick: 5, phase: 'RESOLUTION', startedAt: '2026-01-01T00:00:00Z' }),
    );

    expect(eventsStore.latestTick()!.tick).toBe(5);

    http.expectOne((req) => req.url.includes('/overlay')).flush({
      gameId: 'gx', asOfTick: 0, sinceTick: 0, bbox: '', systems: [], blockades: [],
    });
    http.verify();
  });

  it('event frame updates EventsStore.events signal', () => {
    const { service, eventsStore, http } = buildService();
    service.connect({ gameId: 'gy' });

    lastMockClient!.deliver(
      '/topic/games/gy/events',
      JSON.stringify({ type: 'WarDeclared', parties: ['F1', 'F2'], tick: 3 }),
    );

    expect(eventsStore.events().length).toBe(1);
    expect(eventsStore.events()[0].type).toBe('WarDeclared');

    http.expectOne((req) => req.url.includes('/overlay')).flush({
      gameId: 'gy', asOfTick: 0, sinceTick: 0, bbox: '', systems: [], blockades: [],
    });
    http.verify();
  });

  it('overlay delta frame updates OverlayStore signal', () => {
    const { service, overlayStore, http } = buildService();
    service.connect({ gameId: 'gz' });

    lastMockClient!.deliver(
      '/topic/games/gz/overlay',
      JSON.stringify({
        changedSystems: [
          { systemId: 'S1', ownerFactionId: 'F1', activityLevel: 1, blockaded: false, battle: false, asOfTick: 2 },
        ],
        changedRoutes: [],
        asOfTick: 2,
      }),
    );

    expect(overlayStore.allSystems().length).toBe(1);
    expect(overlayStore.allSystems()[0].systemId).toBe('S1');
    expect(overlayStore.asOfTick()).toBe(2);

    http.expectOne((req) => req.url.includes('/overlay')).flush({
      gameId: 'gz', asOfTick: 0, sinceTick: 0, bbox: '', systems: [], blockades: [],
    });
    http.verify();
  });

  it('WorldView frame updates ownerWorldView signal', () => {
    const { service, http } = buildService();
    service.connect({ gameId: 'g3', factionId: 'f99' });

    const view: WorldView = {
      factionId: 'f99',
      tick: 10,
      visibleSystems: [],
      diplomacy: [],
    };
    lastMockClient!.deliver(
      '/user/queue/faction/f99/view',
      JSON.stringify(view),
    );

    expect(service.ownerWorldView()!.factionId).toBe('f99');
    expect(service.ownerWorldView()!.tick).toBe(10);

    http.expectOne((req) => req.url.includes('/overlay')).flush({
      gameId: 'g3', asOfTick: 0, sinceTick: 0, bbox: '', systems: [], blockades: [],
    });
    http.verify();
  });

  it('malformed JSON frame is discarded without throwing', () => {
    const { service, eventsStore, http } = buildService();
    service.connect({ gameId: 'gm' });

    expect(() => {
      lastMockClient!.deliver('/topic/games/gm/events', 'not-json{{{');
    }).not.toThrow();

    expect(eventsStore.events().length).toBe(0);

    http.expectOne((req) => req.url.includes('/overlay')).flush({
      gameId: 'gm', asOfTick: 0, sinceTick: 0, bbox: '', systems: [], blockades: [],
    });
    http.verify();
  });

  // ---- reconnect + resync ----

  it('on reconnect: resubscribes to all public topics', () => {
    const { service, http } = buildService();
    service.connect({ gameId: 'gr' });

    // Flush initial resync.
    http.expectOne((req) => req.url.includes('/overlay')).flush({
      gameId: 'gr', asOfTick: 0, sinceTick: 0, bbox: '', systems: [], blockades: [],
    });

    const beforeCount = lastMockClient!.subscriptions.length;
    // Simulate disconnect + reconnect.
    lastMockClient!.simulateReconnect();

    // After reconnect: fresh subscriptions (old ones cleared, new ones added).
    expect(lastMockClient!.subscriptions.length).toBe(beforeCount);
    const destinations = lastMockClient!.subscriptions.map((s) => s.destination);
    expect(destinations).toContain('/topic/games/gr/ticks');
    expect(destinations).toContain('/topic/games/gr/events');
    expect(destinations).toContain('/topic/games/gr/overlay');

    // REST resync request issued after reconnect.
    http.expectOne((req) => req.url.includes('/overlay')).flush({
      gameId: 'gr', asOfTick: 0, sinceTick: 0, bbox: '', systems: [], blockades: [],
    });
    http.verify();
  });

  it('on reconnect REST resync uses sinceTick from overlayStore', async () => {
    const { service, overlayStore, http } = buildService();
    service.connect({ gameId: 'grsync' });

    // Apply a tick so asOfTick = 7.
    overlayStore.applyOverlayDelta({ changedSystems: [], changedRoutes: [], asOfTick: 7 });

    // Flush initial resync.
    http.expectOne((req) => req.url.includes('/overlay')).flush({
      gameId: 'grsync', asOfTick: 0, sinceTick: 0, bbox: '', systems: [], blockades: [],
    });
    await Promise.resolve();

    // Simulate reconnect.
    lastMockClient!.simulateReconnect();

    const req = http.expectOne((r) => r.url.includes('/overlay'));
    expect(req.request.urlWithParams).toContain('sinceTick=7');
    req.flush({ gameId: 'grsync', asOfTick: 7, sinceTick: 7, bbox: '', systems: [], blockades: [] });
    await Promise.resolve();

    http.verify();
  });

  it('REST resync response updates OverlayStore with mapped delta', async () => {
    const { service, overlayStore, http } = buildService();
    service.connect({ gameId: 'gmap' });

    // Flush the HTTP response for the resync request.
    http.expectOne((req) => req.url.includes('/overlay')).flush({
      gameId: 'gmap',
      asOfTick: 3,
      sinceTick: 0,
      bbox: '',
      systems: [
        {
          systemId: 'SYS-A',
          owner: 'faction-1',
          tint: null,
          contested: true,
          fleets: [{ factionId: 'faction-1', count: 5 }],
          routes: [],
        },
      ],
      blockades: [{ systemId: 'SYS-A' }],
    });

    // Allow the async resyncOverlay() Promise chain to settle.
    await Promise.resolve();

    expect(overlayStore.allSystems().length).toBe(1);
    const sys = overlayStore.getSystem('SYS-A')!;
    expect(sys.ownerFactionId).toBe('faction-1');
    expect(sys.battle).toBe(true);
    expect(sys.blockaded).toBe(true);
    expect(sys.activityLevel).toBe(1);
    expect(overlayStore.asOfTick()).toBe(3);

    http.verify();
  });
});

// ---------------------------------------------------------------------------
// mapResyncDtoToOverlayDelta unit tests
// ---------------------------------------------------------------------------

describe('mapResyncDtoToOverlayDelta', () => {
  it('maps a system with no fleets to activityLevel 0', () => {
    const delta = mapResyncDtoToOverlayDelta({
      gameId: 'g', asOfTick: 5, sinceTick: 0, bbox: '',
      systems: [{ systemId: 'S1', owner: 'F1', tint: null, contested: false, fleets: [], routes: [] }],
      blockades: [],
    });
    expect(delta.changedSystems[0].activityLevel).toBe(0);
    expect(delta.changedSystems[0].battle).toBe(false);
    expect(delta.changedSystems[0].blockaded).toBe(false);
    expect(delta.asOfTick).toBe(5);
  });

  it('marks blockaded system correctly', () => {
    const delta = mapResyncDtoToOverlayDelta({
      gameId: 'g', asOfTick: 2, sinceTick: 0, bbox: '',
      systems: [{ systemId: 'S2', owner: null, tint: null, contested: false, fleets: [], routes: [] }],
      blockades: [{ systemId: 'S2' }],
    });
    expect(delta.changedSystems[0].blockaded).toBe(true);
  });

  it('uses contested field as battle flag', () => {
    const delta = mapResyncDtoToOverlayDelta({
      gameId: 'g', asOfTick: 1, sinceTick: 0, bbox: '',
      systems: [{ systemId: 'S3', owner: 'F2', tint: null, contested: true, fleets: [], routes: [] }],
      blockades: [],
    });
    expect(delta.changedSystems[0].battle).toBe(true);
  });

  it('produces empty changedRoutes (REST endpoint does not carry routes)', () => {
    const delta = mapResyncDtoToOverlayDelta({
      gameId: 'g', asOfTick: 1, sinceTick: 0, bbox: '', systems: [], blockades: [],
    });
    expect(delta.changedRoutes).toEqual([]);
  });

  it('maps multiple systems independently', () => {
    const delta = mapResyncDtoToOverlayDelta({
      gameId: 'g', asOfTick: 4, sinceTick: 0, bbox: '',
      systems: [
        { systemId: 'A', owner: 'F1', tint: null, contested: false, fleets: [{ factionId: 'F1', count: 3 }], routes: [] },
        { systemId: 'B', owner: 'F2', tint: null, contested: true,  fleets: [], routes: [] },
      ],
      blockades: [{ systemId: 'A' }],
    });
    expect(delta.changedSystems.length).toBe(2);
    expect(delta.changedSystems[0].activityLevel).toBe(1);
    expect(delta.changedSystems[0].blockaded).toBe(true);
    expect(delta.changedSystems[1].battle).toBe(true);
    expect(delta.changedSystems[1].activityLevel).toBe(0);
  });
});
