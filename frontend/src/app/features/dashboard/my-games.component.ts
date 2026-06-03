import {
  ChangeDetectionStrategy,
  Component,
  OnDestroy,
  OnInit,
  inject,
  signal,
} from '@angular/core';
import { Router, RouterLink } from '@angular/router';
import { AuthService } from '../../services/auth.service';
import { MeRestClientService, type MyGameView } from '../../services/me-rest-client.service';

/**
 * Dashboard component -- shows the authenticated user's game list (feat/dev-jwt-auth).
 *
 * Mounted at /me (protected by authGuard). Auto-refreshes every 4 s so the
 * user sees tick and status changes evolve in near-real-time. Each row links
 * to /spectate/:gameId ("Watch").
 *
 * Constraints (angular21-signals skill):
 * - Standalone component; no NgModules.
 * - All state in signals; zoneless CD.
 * - inject() not constructor DI.
 * - Display-only: never mutates game state.
 */
@Component({
  selector: 'app-my-games',
  changeDetection: ChangeDetectionStrategy.OnPush,
  standalone: true,
  imports: [RouterLink],
  template: `
    <div class="dash-root">
      <!-- Top bar -->
      <div class="dash-top-bar">
        <span class="dash-title">STELLAR COMPACT</span>
        <span class="dash-subtitle">Commander Dashboard</span>
        <span class="dash-spacer"></span>
        @if (username()) {
          <span class="dash-user">{{ username() }}</span>
        }
        <button type="button" class="dash-logout-btn" (click)="onLogout()">
          Logout
        </button>
      </div>

      <!-- Main content -->
      <div class="dash-content">
        <div class="dash-panel">
          <div class="dash-panel-header">
            Your Sovereigns
            @if (loading()) {
              <span class="dash-spinner" aria-label="Refreshing"></span>
            }
          </div>

          @if (error()) {
            <div class="dash-error" role="alert">{{ error() }}</div>
          }

          @if (!loading() && games().length === 0 && !error()) {
            <div class="dash-empty">
              <div class="dash-empty-msg">
                You don't own a Sovereign in any game yet.
              </div>
              <div class="dash-empty-hint">
                Create a match via the API or wait for the demo autostart to seat you.
              </div>
            </div>
          }

          @if (games().length > 0) {
            <div class="dash-table-wrap" role="table" aria-label="Your games">
              <div class="dash-table-head" role="row">
                <span role="columnheader">Game</span>
                <span role="columnheader">Faction / Seat</span>
                <span role="columnheader">Status</span>
                <span role="columnheader">Tick</span>
                <span role="columnheader">Balance</span>
                <span role="columnheader">Seed</span>
                <span role="columnheader"></span>
              </div>
              @for (g of games(); track g.gameId) {
                <div class="dash-table-row" role="row">
                  <span class="dash-cell dash-cell--game" [title]="g.gameId" role="cell">
                    {{ shortId(g.gameId) }}
                  </span>
                  <span class="dash-cell dash-cell--faction" [title]="g.factionId" role="cell">
                    {{ g.seatId }}
                  </span>
                  <span class="dash-cell" role="cell">
                    <span class="dash-status-badge" [class]="'dash-status-badge--' + statusClass(g.status)">
                      {{ g.status }}
                    </span>
                  </span>
                  <span class="dash-cell dash-cell--num" role="cell">{{ g.tick }}</span>
                  <span class="dash-cell dash-cell--profile" role="cell">{{ g.balanceProfile }}</span>
                  <span class="dash-cell dash-cell--num" role="cell">{{ g.gameSeed }}</span>
                  <span class="dash-cell dash-cell--action" role="cell">
                    <a
                      class="dash-watch-link"
                      [routerLink]="['/spectate', g.gameId]"
                      aria-label="Watch game {{ g.gameId }}"
                    >Watch</a>
                  </span>
                </div>
              }
            </div>
          }
        </div>
      </div>
    </div>
  `,
  styles: [`
    :host {
      display: flex;
      flex-direction: column;
      width: 100%;
      height: 100%;
      background: #000308;
      font-family: 'Courier New', monospace;
      color: #b0d8f0;
    }
    .dash-root {
      display: flex;
      flex-direction: column;
      width: 100%;
      height: 100%;
    }
    .dash-top-bar {
      display: flex;
      align-items: center;
      gap: 12px;
      padding: 8px 20px;
      background: rgba(0, 3, 10, 0.95);
      border-bottom: 1px solid rgba(80, 180, 255, 0.18);
      flex-shrink: 0;
    }
    .dash-title {
      font-size: 11px;
      letter-spacing: 3px;
      color: rgba(80, 180, 255, 0.7);
      text-transform: uppercase;
    }
    .dash-subtitle {
      font-size: 9px;
      letter-spacing: 1.5px;
      color: rgba(80, 180, 255, 0.35);
      text-transform: uppercase;
    }
    .dash-spacer { flex: 1; }
    .dash-user {
      font-size: 10px;
      color: rgba(80, 180, 255, 0.6);
      letter-spacing: 0.5px;
    }
    .dash-logout-btn {
      padding: 3px 10px;
      font-family: 'Courier New', monospace;
      font-size: 9px;
      letter-spacing: 1px;
      text-transform: uppercase;
      color: rgba(240, 96, 96, 0.75);
      background: rgba(240, 96, 96, 0.08);
      border: 1px solid rgba(240, 96, 96, 0.3);
      border-radius: 3px;
      cursor: pointer;
      transition: background 0.15s, border-color 0.15s;
    }
    .dash-logout-btn:hover {
      background: rgba(240, 96, 96, 0.18);
      border-color: rgba(240, 96, 96, 0.55);
    }
    .dash-content {
      flex: 1;
      overflow-y: auto;
      padding: 24px 20px;
    }
    .dash-panel {
      max-width: 900px;
      margin: 0 auto;
      border: 1px solid rgba(80, 180, 255, 0.15);
      border-radius: 6px;
      background: rgba(0, 3, 10, 0.7);
      overflow: hidden;
    }
    .dash-panel-header {
      display: flex;
      align-items: center;
      gap: 10px;
      font-size: 9px;
      letter-spacing: 2px;
      text-transform: uppercase;
      color: rgba(80, 180, 255, 0.5);
      padding: 10px 16px;
      border-bottom: 1px solid rgba(80, 180, 255, 0.1);
      background: rgba(0, 3, 10, 0.5);
    }
    .dash-spinner {
      display: inline-block;
      width: 10px;
      height: 10px;
      border: 2px solid rgba(80, 180, 255, 0.2);
      border-top-color: rgba(80, 180, 255, 0.8);
      border-radius: 50%;
      animation: spin 0.8s linear infinite;
    }
    @keyframes spin {
      to { transform: rotate(360deg); }
    }
    .dash-error {
      padding: 16px;
      font-size: 10px;
      color: #f06060;
    }
    .dash-empty {
      padding: 32px 20px;
      text-align: center;
    }
    .dash-empty-msg {
      font-size: 12px;
      color: rgba(80, 180, 255, 0.5);
      margin-bottom: 10px;
    }
    .dash-empty-hint {
      font-size: 10px;
      color: rgba(80, 180, 255, 0.3);
      line-height: 1.6;
    }
    .dash-table-wrap {
      width: 100%;
    }
    .dash-table-head,
    .dash-table-row {
      display: grid;
      grid-template-columns: 1fr 1fr 100px 60px 1fr 80px 70px;
      gap: 0;
      align-items: center;
    }
    .dash-table-head {
      font-size: 8px;
      letter-spacing: 1.5px;
      text-transform: uppercase;
      color: rgba(80, 180, 255, 0.4);
      padding: 6px 16px;
      border-bottom: 1px solid rgba(80, 180, 255, 0.08);
    }
    .dash-table-row {
      font-size: 10px;
      padding: 7px 16px;
      border-bottom: 1px solid rgba(80, 180, 255, 0.05);
      transition: background 0.1s;
    }
    .dash-table-row:hover {
      background: rgba(80, 180, 255, 0.04);
    }
    .dash-table-row:last-child {
      border-bottom: none;
    }
    .dash-cell {
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
      color: #b0d8f0;
    }
    .dash-cell--game {
      color: rgba(80, 180, 255, 0.75);
      font-size: 9px;
    }
    .dash-cell--faction {
      color: rgba(80, 180, 255, 0.6);
      font-size: 9px;
    }
    .dash-cell--num {
      font-variant-numeric: tabular-nums;
      color: rgba(80, 180, 255, 0.5);
      font-size: 9px;
    }
    .dash-cell--profile {
      font-size: 9px;
      color: rgba(80, 180, 255, 0.55);
    }
    .dash-cell--action {
      text-align: right;
    }
    .dash-status-badge {
      display: inline-block;
      padding: 1px 5px;
      border-radius: 2px;
      font-size: 8px;
      letter-spacing: 0.5px;
      border: 1px solid transparent;
    }
    .dash-status-badge--running   { color: #4ad6a0; border-color: rgba(74,214,160,0.4); }
    .dash-status-badge--paused    { color: #f0a060; border-color: rgba(240,160,96,0.4); }
    .dash-status-badge--concluded { color: #ffd700; border-color: rgba(255,215,0,0.4); }
    .dash-status-badge--other     { color: #b0d8f0; border-color: rgba(80,180,255,0.2); }
    .dash-watch-link {
      display: inline-block;
      padding: 2px 8px;
      font-family: 'Courier New', monospace;
      font-size: 8px;
      letter-spacing: 1px;
      text-transform: uppercase;
      color: rgba(80, 180, 255, 0.75);
      background: rgba(80, 180, 255, 0.08);
      border: 1px solid rgba(80, 180, 255, 0.25);
      border-radius: 3px;
      text-decoration: none;
      transition: background 0.1s, border-color 0.1s;
    }
    .dash-watch-link:hover {
      background: rgba(80, 180, 255, 0.15);
      border-color: rgba(80, 180, 255, 0.45);
    }
  `],
})
export class MyGamesComponent implements OnInit, OnDestroy {
  private readonly auth = inject(AuthService);
  private readonly me = inject(MeRestClientService);
  private readonly router = inject(Router);

  /** Authenticated username (from AuthService signal). */
  readonly username = this.auth.username;
  /** Current game list. */
  readonly games = signal<readonly MyGameView[]>([]);
  /** True while a fetch is in flight. */
  readonly loading = signal<boolean>(false);
  /** Non-null if the last fetch failed. */
  readonly error = signal<string | null>(null);

  private _pollInterval: ReturnType<typeof setInterval> | null = null;

  ngOnInit(): void {
    void this._fetchGames();
    // Auto-refresh every 4 s so the user sees tick / status evolve.
    this._pollInterval = setInterval(() => {
      void this._fetchGames();
    }, 4000);
  }

  ngOnDestroy(): void {
    if (this._pollInterval !== null) {
      clearInterval(this._pollInterval);
      this._pollInterval = null;
    }
    this.me.reset();
  }

  onLogout(): void {
    this.auth.logout();
    void this.router.navigate(['/login']);
  }

  // ---- display helpers ----

  shortId(id: string): string {
    return id.length > 14 ? id.slice(0, 14) + '…' : id;
  }

  statusClass(status: string): string {
    const s = status.toLowerCase();
    if (s === 'running') return 'running';
    if (s === 'paused') return 'paused';
    if (s === 'concluded' || s === 'archived') return 'concluded';
    return 'other';
  }

  // ---- private ----

  private async _fetchGames(): Promise<void> {
    this.loading.set(true);
    this.error.set(null);
    try {
      const resp = await this.me.fetchMyGames();
      this.games.set(resp.games);
    } catch {
      this.error.set('Could not load your games. Retrying...');
    } finally {
      this.loading.set(false);
    }
  }
}
