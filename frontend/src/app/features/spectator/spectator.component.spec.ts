import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ActivatedRoute } from '@angular/router';
import { signal, Component, input, provideZonelessChangeDetection } from '@angular/core';
import { CommonModule } from '@angular/common';
import { vi } from 'vitest';
import { SpectatorComponent } from './spectator.component';
import { EventsStore } from '../../stores/events.store';
import { FactionStore } from '../../stores/faction.store';
import { StompClientService } from '../../services/stomp-client.service';
import { MatchRestClientService } from '../../services/match-rest-client.service';
import { ReplayClientService } from '../../services/replay-client.service';
import type { PublicEvent, TickEvent } from '../../stores/events.store';
import type { FactionSnapshot } from '../../stores/faction.store';
import type { LeaderboardResponse } from '../../services/match-rest-client.service';

function makeEvent(overrides: Partial<PublicEvent> = {}): PublicEvent {
  return { type: 'WarDeclared', parties: ['faction-1', 'faction-2'], tick: 5, ...overrides };
}
function makeFaction(overrides: Partial<FactionSnapshot> = {}): FactionSnapshot {
  return {
    factionId: 'faction-1', name: 'The Borg', colour: '#4ad6a0',
    resources: { credits: 100, minerals: 50, influence: 20 },
    reputation: 0.5, systemCount: 4, eliminated: false, ...overrides,
  };
}
class StompStub {
  readonly connectionState = signal<string>('disconnected');
  connect = vi.fn();
  disconnect = vi.fn();
}
class MatchRestStub {
  readonly leaderboard = signal<LeaderboardResponse | null>(null);
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  readonly gameSummary = signal<any>(null);
  fetchGameSummary = vi.fn().mockResolvedValue(undefined);
  fetchLeaderboard = vi.fn().mockResolvedValue(undefined);
  fetchEvents = vi.fn().mockResolvedValue({ events: [], nextFromTick: -1 });
  reset = vi.fn();
}
class ReplayStub {
  readonly active = signal<boolean>(false);
  readonly playing = signal<boolean>(false);
  readonly currentTick = signal<number>(0);
  readonly firstTick = signal<number>(0);
  readonly lastTick = signal<number>(0);
  readonly status = signal<string>('-');
  readonly leaderboard = signal<LeaderboardResponse | null>(null);
  load = vi.fn().mockResolvedValue(true);
  seek = vi.fn().mockResolvedValue(undefined);
  stepForward = vi.fn().mockResolvedValue(undefined);
  stepBack = vi.fn().mockResolvedValue(undefined);
  play = vi.fn();
  pause = vi.fn();
  toggle = vi.fn();
  reset = vi.fn();
}
@Component({ selector: 'app-galaxy', template: '', standalone: true })
class GalaxyStub {
  seed = input<string>('1');
  rMax = input<number>(1000);
}

describe('SpectatorComponent', () => {
  let fixture: ComponentFixture<SpectatorComponent>;
  let component: SpectatorComponent;
  let eventsStore: EventsStore;
  let factionStore: FactionStore;
  let stompStub: StompStub;
  let restStub: MatchRestStub;
  let replayStub: ReplayStub;
  let queryParams: Record<string, string | null>;

  beforeEach(async () => {
    stompStub = new StompStub();
    restStub = new MatchRestStub();
    replayStub = new ReplayStub();
    queryParams = {};
    await TestBed.configureTestingModule({
      imports: [SpectatorComponent],
      providers: [
        provideZonelessChangeDetection(),
        EventsStore,
        FactionStore,
        {
          provide: ActivatedRoute,
          useValue: {
            snapshot: {
              paramMap: { get: () => 'game-42' },
              queryParamMap: { get: (k: string) => queryParams[k] ?? null },
            },
          },
        },
        { provide: StompClientService, useValue: stompStub },
        { provide: MatchRestClientService, useValue: restStub },
        { provide: ReplayClientService, useValue: replayStub },
      ],
    })
    .overrideComponent(SpectatorComponent, { set: { imports: [CommonModule, GalaxyStub] } })
    .compileComponents();
    fixture = TestBed.createComponent(SpectatorComponent);
    component = fixture.componentInstance;
    eventsStore = TestBed.inject(EventsStore);
    factionStore = TestBed.inject(FactionStore);
  });

  afterEach(() => { TestBed.resetTestingModule(); });

  it('creates', () => { fixture.detectChanges(); expect(component).toBeTruthy(); });

  it('connects STOMP without factionId (spectator-only)', () => {
    fixture.detectChanges();
    expect(stompStub.connect).toHaveBeenCalledWith({ gameId: 'game-42' });
    const arg = (stompStub.connect as ReturnType<typeof vi.fn>).mock.calls[0][0];
    expect(arg.factionId).toBeUndefined();
  });

  it('fetches summary and leaderboard on init', () => {
    fixture.detectChanges();
    expect(restStub.fetchGameSummary).toHaveBeenCalledWith('game-42');
    expect(restStub.fetchLeaderboard).toHaveBeenCalledWith('game-42');
  });

  it('sets gameId from route', () => {
    fixture.detectChanges();
    expect(component.gameId()).toBe('game-42');
  });

  it('renders events newest-first', () => {
    eventsStore.appendEvents([
      makeEvent({ tick: 1, type: 'TreatySigned' }),
      makeEvent({ tick: 3, type: 'WarDeclared' }),
      makeEvent({ tick: 2, type: 'SystemCaptured', systemId: 'sys-9' }),
    ]);
    fixture.detectChanges();
    const rows = fixture.nativeElement.querySelectorAll('.spec-event-row');
    expect(rows.length).toBe(3);
    expect(rows[0].textContent).toContain('T3');
    expect(rows[2].textContent).toContain('T1');
  });

  it('shows waiting message when no events', () => {
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('.spec-events-empty')).toBeTruthy();
  });

  it('updates reactively on new events', () => {
    fixture.detectChanges();
    eventsStore.appendEvents([makeEvent({ tick: 10, type: 'VictoryAchieved' })]);
    fixture.detectChanges();
    const rows = fixture.nativeElement.querySelectorAll('.spec-event-row');
    expect(rows.length).toBe(1);
    expect(rows[0].textContent).toContain('VICTORY');
  });

  it('renders REST leaderboard entries', () => {
    restStub.leaderboard.set({
      gameId: 'game-42', tick: 5,
      entries: [
        { rank: 1, factionId: 'faction-1', score: 450 },
        { rank: 2, factionId: 'faction-2', score: 300 },
      ],
    });
    fixture.detectChanges();
    const rows = fixture.nativeElement.querySelectorAll('.spec-lb-row');
    expect(rows.length).toBe(2);
    expect(rows[0].textContent).toContain('#1');
  });

  it('falls back to faction store standings when REST empty', () => {
    factionStore.applyTick({
      tick: 3,
      factions: [
        makeFaction({ factionId: 'faction-1', systemCount: 6 }),
        makeFaction({ factionId: 'faction-2', systemCount: 2, name: 'Borg II' }),
      ],
    });
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelectorAll('.spec-lb-row').length).toBe(2);
  });

  it('renders victory progress bars', () => {
    factionStore.applyTick({
      tick: 2,
      factions: [
        makeFaction({ factionId: 'faction-1', systemCount: 3 }),
        makeFaction({ factionId: 'faction-2', systemCount: 1 }),
      ],
    });
    fixture.detectChanges();
    const bars = fixture.nativeElement.querySelectorAll('.spec-vp-bar-fill');
    expect(bars.length).toBe(2);
    expect(bars[0].style.width).toMatch(/75%/);
    expect(bars[1].style.width).toMatch(/25%/);
  });

  it('shows tick counter', () => {
    const t: TickEvent = { tick: 7, phase: 'RESOLUTION', startedAt: '0' };
    eventsStore.applyTick(t);
    fixture.detectChanges();
    const el = fixture.nativeElement.querySelector('.spec-tick');
    expect(el).toBeTruthy();
    expect(el.textContent.trim()).toBe('T7');
  });

  it('reflects STOMP connection state', () => {
    fixture.detectChanges();
    stompStub.connectionState.set('connected');
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('.spec-conn--connected')).toBeTruthy();
  });

  it('applies RUNNING status class', () => {
    restStub.gameSummary.set({
      gameId: 'game-42', gameSeed: '1', status: 'RUNNING',
      tick: 1, balanceProfile: 'small-default', factions: [],
    });
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('.spec-status--running')).toBeTruthy();
  });

  it('disconnects and resets on destroy', () => {
    fixture.detectChanges();
    fixture.destroy();
    expect(stompStub.disconnect).toHaveBeenCalled();
    expect(restStub.reset).toHaveBeenCalled();
  });

  it('spectatorEvents: newest-first, max 200', () => {
    const many: PublicEvent[] = Array.from({ length: 250 }, (_, i) =>
      makeEvent({ tick: i, type: 'RouteEstablished', parties: ['f-' + String(i)] })
    );
    eventsStore.appendEvents(many);
    fixture.detectChanges();
    expect(component.spectatorEvents().length).toBe(200);
    expect(component.spectatorEvents()[0].tick).toBe(249);
  });

  it('evtClass: war for WarDeclared', () => {
    expect(component.evtClass(makeEvent({ type: 'WarDeclared' }))).toBe('war');
  });
  it('evtClass: victory for VictoryAchieved', () => {
    expect(component.evtClass(makeEvent({ type: 'VictoryAchieved' }))).toBe('victory');
  });
  it('evtLabel includes party names for WarDeclared', () => {
    const label = component.evtLabel(makeEvent({ type: 'WarDeclared', parties: ['A', 'B'] }));
    expect(label).toContain('WAR');
    expect(label).toContain('A');
  });

  // ---- replay mode (E9-02) ----

  it('live mode (no ?replay) does NOT load the replay and connects STOMP', () => {
    fixture.detectChanges();
    expect(replayStub.load).not.toHaveBeenCalled();
    expect(stompStub.connect).toHaveBeenCalled();
    expect(component.replayMode()).toBe(false);
  });

  it('?replay enters replay mode, loads replay, and does NOT connect STOMP', () => {
    queryParams['replay'] = '1';
    fixture.detectChanges();
    expect(component.replayMode()).toBe(true);
    expect(replayStub.load).toHaveBeenCalledWith('game-42');
    expect(stompStub.connect).not.toHaveBeenCalled();
  });

  it('renders the scrub bar with controls when replay is active', () => {
    queryParams['replay'] = '1';
    replayStub.active.set(true);
    replayStub.firstTick.set(0);
    replayStub.lastTick.set(8);
    replayStub.currentTick.set(3);
    fixture.detectChanges();
    const bar = fixture.nativeElement.querySelector('.spec-replay-bar');
    expect(bar).toBeTruthy();
    const scrub = bar.querySelector('.spec-replay-scrub') as HTMLInputElement;
    expect(scrub.max).toBe('8');
    expect(scrub.value).toBe('3');
    expect(fixture.nativeElement.querySelector('.spec-replay-badge')).toBeTruthy();
  });

  it('dragging the scrub bar seeks to that tick (and pauses)', () => {
    queryParams['replay'] = '1';
    replayStub.active.set(true);
    replayStub.lastTick.set(8);
    fixture.detectChanges();
    const scrub = fixture.nativeElement.querySelector('.spec-replay-scrub') as HTMLInputElement;
    scrub.value = '5';
    scrub.dispatchEvent(new Event('input'));
    expect(replayStub.pause).toHaveBeenCalled();
    expect(replayStub.seek).toHaveBeenCalledWith(5);
  });

  it('step + play buttons drive the replay service', () => {
    queryParams['replay'] = '1';
    replayStub.active.set(true);
    replayStub.lastTick.set(8);
    fixture.detectChanges();
    const buttons = fixture.nativeElement.querySelectorAll('.spec-replay-btn');
    // order: stepBack, play/pause, stepForward
    (buttons[0] as HTMLButtonElement).click();
    expect(replayStub.stepBack).toHaveBeenCalled();
    (buttons[1] as HTMLButtonElement).click();
    expect(replayStub.toggle).toHaveBeenCalled();
    (buttons[2] as HTMLButtonElement).click();
    expect(replayStub.stepForward).toHaveBeenCalled();
  });

  it('replay leaderboard feeds the standings in replay mode', () => {
    queryParams['replay'] = '1';
    replayStub.active.set(true);
    replayStub.leaderboard.set({
      gameId: 'game-42', tick: 4,
      entries: [
        { rank: 1, factionId: 'faction-2', score: 220 },
        { rank: 2, factionId: 'faction-1', score: 110 },
      ],
    });
    fixture.detectChanges();
    const rows = fixture.nativeElement.querySelectorAll('.spec-lb-row');
    expect(rows.length).toBe(2);
    expect(rows[0].textContent).toContain('#1');
    expect(component.leaderboardEntries()[0].factionId).toBe('faction-2');
  });

  it('replay status drives the top-bar status (not the REST summary)', () => {
    queryParams['replay'] = '1';
    replayStub.active.set(true);
    replayStub.status.set('CONCLUDED');
    fixture.detectChanges();
    expect(component.status()).toBe('CONCLUDED');
    expect(fixture.nativeElement.querySelector('.spec-status--concluded')).toBeTruthy();
  });

  it('resets the replay service on destroy in replay mode', () => {
    queryParams['replay'] = '1';
    fixture.detectChanges();
    fixture.destroy();
    expect(replayStub.reset).toHaveBeenCalled();
    expect(stompStub.disconnect).not.toHaveBeenCalled();
  });
});