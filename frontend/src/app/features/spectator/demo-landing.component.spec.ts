import { ComponentFixture, TestBed } from '@angular/core/testing';
import { Router } from '@angular/router';
import { signal, provideZonelessChangeDetection } from '@angular/core';
import { vi } from 'vitest';
import { DemoLandingComponent } from './demo-landing.component';
import { MatchRestClientService, type GameSummary } from '../../services/match-rest-client.service';

function makeSummary(status: GameSummary['status'], gameId = 'game-1'): GameSummary {
  return {
    gameId,
    gameSeed: '42',
    status,
    tick: 0,
    balanceProfile: 'small-default',
    factions: [],
  };
}

class RestStub {
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  fetchGameList = vi.fn() as any;
  readonly leaderboard = signal(null);
  readonly gameSummary = signal(null);
  fetchGameSummary = vi.fn();
  fetchLeaderboard = vi.fn();
  fetchEvents = vi.fn();
  reset = vi.fn();
}

describe('DemoLandingComponent', () => {
  let fixture: ComponentFixture<DemoLandingComponent>;
  let component: DemoLandingComponent;
  let restStub: RestStub;
  let routerSpy: { navigate: ReturnType<typeof vi.fn> };

  beforeEach(async () => {
    restStub = new RestStub();
    routerSpy = { navigate: vi.fn().mockResolvedValue(true) };

    await TestBed.configureTestingModule({
      imports: [DemoLandingComponent],
      providers: [
        provideZonelessChangeDetection(),
        { provide: MatchRestClientService, useValue: restStub },
        { provide: Router, useValue: routerSpy },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(DemoLandingComponent);
    component = fixture.componentInstance;
  });

  afterEach(() => {
    component.ngOnDestroy();
    TestBed.resetTestingModule();
  });

  it('creates', () => {
    restStub.fetchGameList.mockResolvedValue([]);
    fixture.detectChanges();
    expect(component).toBeTruthy();
  });

  it('navigates to first RUNNING match immediately', async () => {
    restStub.fetchGameList.mockResolvedValue([makeSummary('RUNNING', 'game-99')]);
    fixture.detectChanges();
    await fixture.whenStable();
    expect(routerSpy.navigate).toHaveBeenCalledWith(['/spectate', 'game-99']);
  });

  it('navigates to first PAUSED match if no RUNNING one', async () => {
    restStub.fetchGameList.mockResolvedValue([
      makeSummary('CONCLUDED', 'game-old'),
      makeSummary('PAUSED', 'game-paused'),
    ]);
    fixture.detectChanges();
    await fixture.whenStable();
    expect(routerSpy.navigate).toHaveBeenCalledWith(['/spectate', 'game-paused']);
  });

  it('prefers RUNNING over PAUSED', async () => {
    restStub.fetchGameList.mockResolvedValue([
      makeSummary('PAUSED', 'game-paused'),
      makeSummary('RUNNING', 'game-running'),
    ]);
    fixture.detectChanges();
    await fixture.whenStable();
    expect(routerSpy.navigate).toHaveBeenCalledWith(['/spectate', 'game-running']);
  });

  it('shows waiting message when no running match found', async () => {
    restStub.fetchGameList.mockResolvedValue([makeSummary('CONCLUDED')]);
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
    expect(routerSpy.navigate).not.toHaveBeenCalled();
    expect(fixture.nativeElement.textContent).toContain('No running match found');
  });

  it('shows error message on fetch failure', async () => {
    restStub.fetchGameList.mockRejectedValue(new Error('network error'));
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
    expect(component.error()).toBe('Could not reach the backend.');
    expect(routerSpy.navigate).not.toHaveBeenCalled();
  });

  it('shows searching state on init before resolve', () => {
    // Don't resolve the promise — the component should be in searching state.
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    let resolve!: (v: any) => void;
    restStub.fetchGameList.mockReturnValue(new Promise((r) => { resolve = r; }));
    fixture.detectChanges();
    expect(component.searching()).toBe(true);
    // Resolve to avoid dangling promise.
    resolve([]);
  });
});
