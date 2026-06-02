import { TestBed } from '@angular/core/testing';
import { provideZonelessChangeDetection } from '@angular/core';
import {
  HttpTestingController,
  provideHttpClientTesting,
} from '@angular/common/http/testing';
import { provideHttpClient } from '@angular/common/http';
import {
  ReplayClientService,
  type ReplayFrame,
  type ReplayManifest,
} from './replay-client.service';
import { EventsStore } from '../stores/events.store';

function manifest(overrides: Partial<ReplayManifest> = {}): ReplayManifest {
  return {
    gameId: 'game-1', gameSeed: '4242', profile: 'small-default',
    firstTick: 0, lastTick: 5, tickCount: 6, ...overrides,
  };
}
function frame(tick: number, overrides: Partial<ReplayFrame> = {}): ReplayFrame {
  return {
    gameId: 'game-1', tick, status: 'RUNNING',
    events: [{ tick, seq: 0, type: 'BattleResolved', parties: ['faction-1'], systemId: 'sys-3' }],
    leaderboard: [
      { rank: 1, factionId: 'faction-1', score: 100 + tick },
      { rank: 2, factionId: 'faction-2', score: 50 },
    ],
    reputations: [{ factionId: 'faction-1', reputation: 0.2 }],
    ...overrides,
  };
}

describe('ReplayClientService', () => {
  let svc: ReplayClientService;
  let http: HttpTestingController;
  let events: EventsStore;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        provideZonelessChangeDetection(),
        provideHttpClient(),
        provideHttpClientTesting(),
        ReplayClientService,
        EventsStore,
      ],
    });
    svc = TestBed.inject(ReplayClientService);
    http = TestBed.inject(HttpTestingController);
    events = TestBed.inject(EventsStore);
  });

  afterEach(() => {
    svc.pause();
    http.verify();
  });

  it('load() fetches the manifest and seeks the first tick', async () => {
    const p = svc.load('game-1');
    http.expectOne('/api/games/game-1/replay').flush(manifest());
    await Promise.resolve(); // let the manifest continuation issue the seek request
    http.expectOne('/api/games/game-1/replay/0').flush(frame(0));
    const ok = await p;
    expect(ok).toBe(true);
    expect(svc.active()).toBe(true);
    expect(svc.currentTick()).toBe(0);
    expect(svc.firstTick()).toBe(0);
    expect(svc.lastTick()).toBe(5);
  });

  it('seek(N) fetches tick N and feeds the shared stores/signals', async () => {
    await loadAt(0);
    const p = svc.seek(3);
    http.expectOne('/api/games/game-1/replay/3').flush(frame(3));
    await p;
    expect(svc.currentTick()).toBe(3);
    expect(events.latestTick()?.tick).toBe(3);
    expect(events.events().length).toBe(1);
    expect(events.events()[0].type).toBe('BattleResolved');
    expect(svc.leaderboard()?.entries[0].factionId).toBe('faction-1');
    expect(svc.leaderboard()?.entries[0].score).toBe(103);
  });

  it('seek clamps above-range ticks to the last tick', async () => {
    await loadAt(0);
    const p = svc.seek(9999);
    http.expectOne('/api/games/game-1/replay/5').flush(frame(5));
    await p;
    expect(svc.currentTick()).toBe(5);
    expect(svc.atEnd()).toBe(true);
  });

  it('seeking backward resets the event window (jump, not append)', async () => {
    await loadAt(0);
    let p = svc.seek(4);
    http.expectOne('/api/games/game-1/replay/4').flush(frame(4));
    await p;
    expect(events.events().every((e) => e.tick === 4)).toBe(true);

    p = svc.seek(1);
    http.expectOne('/api/games/game-1/replay/1').flush(frame(1));
    await p;
    expect(events.events().every((e) => e.tick === 1)).toBe(true);
    expect(svc.currentTick()).toBe(1);
  });

  it('stepForward/stepBack move one tick', async () => {
    await loadAt(0);
    let p = svc.stepForward();
    http.expectOne('/api/games/game-1/replay/1').flush(frame(1));
    await p;
    expect(svc.currentTick()).toBe(1);

    p = svc.stepBack();
    http.expectOne('/api/games/game-1/replay/0').flush(frame(0));
    await p;
    expect(svc.currentTick()).toBe(0);
  });

  it('load() returns false for a match with no replayable timeline', async () => {
    const p = svc.load('game-empty');
    http.expectOne('/api/games/game-empty/replay').flush(manifest({ tickCount: 0 }));
    const ok = await p;
    expect(ok).toBe(false);
    expect(svc.active()).toBe(false);
  });

  it('reset() stops playback and clears state', async () => {
    await loadAt(0);
    svc.reset();
    expect(svc.manifest()).toBeNull();
    expect(svc.playing()).toBe(false);
    expect(svc.leaderboard()).toBeNull();
  });

  async function loadAt(tick: number): Promise<void> {
    const p = svc.load('game-1');
    http.expectOne('/api/games/game-1/replay').flush(manifest());
    await Promise.resolve(); // drain the manifest continuation so the seek request is issued
    http.expectOne(`/api/games/game-1/replay/${tick}`).flush(frame(tick));
    await p;
  }
});
