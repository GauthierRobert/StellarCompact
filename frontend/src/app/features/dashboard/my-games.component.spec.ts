import { ComponentFixture, TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { provideZonelessChangeDetection, signal } from '@angular/core';
import { vi } from 'vitest';
import { MyGamesComponent } from './my-games.component';
import { AuthService } from '../../services/auth.service';
import { MeRestClientService, type MyGameView } from '../../services/me-rest-client.service';

function makeGame(overrides: Partial<MyGameView> & { gameId: string }): MyGameView {
  return {
    factionId: overrides.gameId + ':seat-0',
    seatId: 'seat-0',
    status: 'RUNNING',
    tick: 5,
    gameSeed: 42,
    balanceProfile: 'small-default',
    factionCount: 2,
    ...overrides,
  };
}

class AuthStub {
  readonly token = signal<string | null>('tok');
  readonly username = signal<string | null>('alice');
  readonly expiresAt = signal<string | null>(null);
  readonly isAuthenticated = signal<boolean>(true);
  login = vi.fn();
  logout = vi.fn();
}

class MeStub {
  readonly myGames = signal<readonly MyGameView[] | null>(null);
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  fetchMyGames = vi.fn() as any;
  reset = vi.fn();
}

describe('MyGamesComponent', () => {
  let fixture: ComponentFixture<MyGamesComponent>;
  let component: MyGamesComponent;
  let authStub: AuthStub;
  let meStub: MeStub;
  let router: Router;

  beforeEach(async () => {
    authStub = new AuthStub();
    meStub = new MeStub();

    await TestBed.configureTestingModule({
      imports: [MyGamesComponent],
      providers: [
        provideZonelessChangeDetection(),
        // Real router so `routerLink` (which injects ActivatedRoute) resolves; we spy on
        // navigate below to assert the Logout redirect.
        provideRouter([]),
        { provide: AuthService, useValue: authStub },
        { provide: MeRestClientService, useValue: meStub },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(MyGamesComponent);
    component = fixture.componentInstance;
    router = TestBed.inject(Router);
    vi.spyOn(router, 'navigate').mockResolvedValue(true);
  });

  afterEach(() => {
    component.ngOnDestroy();
    TestBed.resetTestingModule();
  });

  it('creates', () => {
    meStub.fetchMyGames.mockResolvedValue({ username: 'alice', games: [] });
    fixture.detectChanges();
    expect(component).toBeTruthy();
  });

  it('shows the logged-in username', async () => {
    meStub.fetchMyGames.mockResolvedValue({ username: 'alice', games: [] });
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('alice');
  });

  it('shows empty state when games list is empty', async () => {
    meStub.fetchMyGames.mockResolvedValue({ username: 'alice', games: [] });
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain("don't own a Sovereign");
  });

  it('renders a row for each game', async () => {
    meStub.fetchMyGames.mockResolvedValue({
      username: 'alice',
      games: [
        makeGame({ gameId: 'game-1' }),
        makeGame({ gameId: 'game-2' }),
      ],
    });
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
    const rows = fixture.nativeElement.querySelectorAll('.dash-table-row');
    expect(rows.length).toBe(2);
  });

  it('shows an error message when fetchMyGames fails', async () => {
    meStub.fetchMyGames.mockRejectedValue(new Error('network'));
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('.dash-error')).toBeTruthy();
    expect(component.error()).toBeTruthy();
  });

  it('calls logout and navigates to /login when Logout is clicked', async () => {
    meStub.fetchMyGames.mockResolvedValue({ username: 'alice', games: [] });
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();

    const btn = fixture.nativeElement.querySelector('.dash-logout-btn') as HTMLButtonElement;
    btn.click();

    expect(authStub.logout).toHaveBeenCalledOnce();
    expect(router.navigate).toHaveBeenCalledWith(['/login']);
  });

  it('statusClass maps RUNNING to running', () => {
    expect(component.statusClass('RUNNING')).toBe('running');
  });

  it('statusClass maps CONCLUDED to concluded', () => {
    expect(component.statusClass('CONCLUDED')).toBe('concluded');
  });

  it('shortId truncates long ids', () => {
    expect(component.shortId('a'.repeat(20))).toBe('a'.repeat(14) + '…');
  });
});
