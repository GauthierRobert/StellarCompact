import { ComponentFixture, TestBed } from '@angular/core/testing';
import { signal, provideZonelessChangeDetection } from '@angular/core';
import { vi } from 'vitest';
import { MatchPickerComponent } from './match-picker.component';
import { MatchRestClientService, type GameSummary } from '../../services/match-rest-client.service';

function makeSummary(
  overrides: Partial<GameSummary> & { gameId: string }
): GameSummary {
  return {
    gameSeed: '42',
    status: 'RUNNING',
    tick: 3,
    balanceProfile: 'small-default',
    factions: [],
    ...overrides,
  };
}

class RestStub {
  readonly leaderboard = signal(null);
  readonly gameSummary = signal(null);
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  fetchGameList = vi.fn() as any;
  fetchGameSummary = vi.fn();
  fetchLeaderboard = vi.fn();
  fetchEvents = vi.fn();
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  createGame = vi.fn() as any;
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  startGame = vi.fn() as any;
  reset = vi.fn();
}

describe('MatchPickerComponent', () => {
  let fixture: ComponentFixture<MatchPickerComponent>;
  let component: MatchPickerComponent;
  let restStub: RestStub;

  beforeEach(async () => {
    restStub = new RestStub();
    restStub.fetchGameList.mockResolvedValue([]);

    await TestBed.configureTestingModule({
      imports: [MatchPickerComponent],
      providers: [
        provideZonelessChangeDetection(),
        { provide: MatchRestClientService, useValue: restStub },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(MatchPickerComponent);
    component = fixture.componentInstance;
  });

  afterEach(() => {
    TestBed.resetTestingModule();
  });

  it('creates', () => {
    fixture.detectChanges();
    expect(component).toBeTruthy();
  });

  it('fetches the match list on init', async () => {
    fixture.detectChanges();
    await fixture.whenStable();
    expect(restStub.fetchGameList).toHaveBeenCalledOnce();
  });

  it('renders a row for each match returned', async () => {
    restStub.fetchGameList.mockResolvedValue([
      makeSummary({ gameId: 'game-1', status: 'RUNNING' }),
      makeSummary({ gameId: 'game-2', status: 'PAUSED' }),
    ]);
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
    const rows = fixture.nativeElement.querySelectorAll('.mp-match-row');
    expect(rows.length).toBe(2);
  });

  it('highlights the active game row', async () => {
    restStub.fetchGameList.mockResolvedValue([
      makeSummary({ gameId: 'game-active' }),
      makeSummary({ gameId: 'game-other' }),
    ]);
    fixture.componentRef.setInput('activeGameId', 'game-active');
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
    const activeRow = fixture.nativeElement.querySelector('.mp-match-row--active');
    expect(activeRow).toBeTruthy();
    expect(activeRow.textContent).toContain('game-active');
  });

  it('emits matchSelected when a different match row is clicked', async () => {
    restStub.fetchGameList.mockResolvedValue([
      makeSummary({ gameId: 'game-1' }),
      makeSummary({ gameId: 'game-2' }),
    ]);
    fixture.componentRef.setInput('activeGameId', 'game-1');
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();

    const selected: string[] = [];
    component.matchSelected.subscribe((id) => selected.push(id));

    const rows = fixture.nativeElement.querySelectorAll('.mp-match-row');
    (rows[1] as HTMLButtonElement).click();

    expect(selected).toEqual(['game-2']);
  });

  it('does NOT emit matchSelected when clicking the already-active row', async () => {
    restStub.fetchGameList.mockResolvedValue([
      makeSummary({ gameId: 'game-1' }),
    ]);
    fixture.componentRef.setInput('activeGameId', 'game-1');
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();

    const selected: string[] = [];
    component.matchSelected.subscribe((id) => selected.push(id));

    const row = fixture.nativeElement.querySelector('.mp-match-row') as HTMLButtonElement;
    row.click();

    expect(selected).toHaveLength(0);
  });

  it('shows empty state when no matches found', async () => {
    restStub.fetchGameList.mockResolvedValue([]);
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('.mp-empty')).toBeTruthy();
    expect(fixture.nativeElement.textContent).toContain('No matches found');
  });

  it('shows error state when fetchGameList fails', async () => {
    restStub.fetchGameList.mockRejectedValue(new Error('network'));
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('.mp-error')).toBeTruthy();
    expect(fixture.nativeElement.textContent).toContain('Could not load match list');
  });

  it('refreshes the list when the refresh button is clicked', async () => {
    restStub.fetchGameList.mockResolvedValue([]);
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges(); // flush loading=false so the button is re-enabled

    const btn = fixture.nativeElement.querySelector('[aria-label="Refresh match list"]') as HTMLButtonElement;
    expect(btn.disabled).toBe(false);
    btn.click();

    // fetchGameList is called synchronously when the async _fetchList starts.
    expect(restStub.fetchGameList).toHaveBeenCalledTimes(2);
  });

  it('createAndStart calls createGame then startGame and emits the new gameId', async () => {
    restStub.fetchGameList.mockResolvedValue([]);
    const newGame = makeSummary({ gameId: 'new-game', status: 'CREATED' });
    const startedGame = makeSummary({ gameId: 'new-game', status: 'RUNNING' });
    restStub.createGame.mockResolvedValue(newGame);
    restStub.startGame.mockResolvedValue(startedGame);
    fixture.detectChanges();
    await fixture.whenStable();

    const selected: string[] = [];
    component.matchSelected.subscribe((id) => selected.push(id));

    const createBtn = fixture.nativeElement.querySelector('.mp-btn--create') as HTMLButtonElement;
    createBtn.click();
    await fixture.whenStable();

    expect(restStub.createGame).toHaveBeenCalledOnce();
    expect(restStub.startGame).toHaveBeenCalledWith('new-game');
    expect(selected).toEqual(['new-game']);
  });

  it('shows createError when createAndStart fails', async () => {
    restStub.fetchGameList.mockResolvedValue([]);
    restStub.createGame.mockRejectedValue(new Error('backend down'));
    fixture.detectChanges();
    await fixture.whenStable();

    const createBtn = fixture.nativeElement.querySelector('.mp-btn--create') as HTMLButtonElement;
    createBtn.click();
    await fixture.whenStable();
    fixture.detectChanges();

    expect(component.createError()).toBe('backend down');
    expect(fixture.nativeElement.querySelector('.mp-error--create')).toBeTruthy();
  });

  it('statusClass maps RUNNING to running', () => {
    expect(component.statusClass('RUNNING')).toBe('running');
  });

  it('statusClass maps CONCLUDED to concluded', () => {
    expect(component.statusClass('CONCLUDED')).toBe('concluded');
  });

  it('statusClass maps ARCHIVED to concluded', () => {
    expect(component.statusClass('ARCHIVED')).toBe('concluded');
  });

  it('statusClass maps CREATED to other', () => {
    expect(component.statusClass('CREATED')).toBe('other');
  });

  it('shortId truncates long ids', () => {
    expect(component.shortId('a'.repeat(20))).toBe('a'.repeat(14) + '…');
  });

  it('shortId keeps short ids unchanged', () => {
    expect(component.shortId('game-1')).toBe('game-1');
  });
});
