import {
  ChangeDetectionStrategy,
  Component,
  OnInit,
  computed,
  inject,
  signal,
} from '@angular/core';
import { Router } from '@angular/router';
import { MatchRestClientService } from '../../services/match-rest-client.service';

/**
 * Demo landing component (E11-07 — demo-match autostart + spectator defaults).
 *
 * Mounted at the bare {@code /spectator} route (no gameId). On init it fetches
 * {@code GET /api/games}, finds the first RUNNING (or PAUSED) match and navigates
 * straight to {@code /spectate/:gameId}. If no match is available yet, it shows a
 * "waiting for a game to start" message and retries every 3 s.
 *
 * Constraints (angular21-signals skill):
 * - Standalone component; no NgModules.
 * - All state in signals; zoneless CD.
 * - Display-only: never mutates game state.
 */
@Component({
  selector: 'app-demo-landing',
  changeDetection: ChangeDetectionStrategy.OnPush,
  standalone: true,
  imports: [],
  template: `
    <div class="demo-landing">
      <div class="demo-landing__box">
        <div class="demo-landing__title">STELLAR COMPACT</div>
        @if (searching()) {
          <div class="demo-landing__status">Searching for a running match...</div>
          <div class="demo-landing__spinner" aria-label="Searching"></div>
        } @else if (error()) {
          <div class="demo-landing__status demo-landing__status--error">
            {{ error() }}
          </div>
          <div class="demo-landing__hint">
            Retrying in {{ retryCountdown() }}s...
          </div>
        } @else {
          <div class="demo-landing__status">No running match found.</div>
          <div class="demo-landing__hint">
            Start the backend with<br>
            <code>stellar-compact.demo.autostart=true</code><br>
            or create a match via the API.
          </div>
          <div class="demo-landing__hint">
            Retrying in {{ retryCountdown() }}s...
          </div>
        }
      </div>
    </div>
  `,
  styles: [`
    :host {
      display: flex;
      align-items: center;
      justify-content: center;
      width: 100%;
      height: 100%;
      background: #000308;
      font-family: 'Courier New', monospace;
    }
    .demo-landing {
      display: flex;
      align-items: center;
      justify-content: center;
      width: 100%;
      height: 100%;
    }
    .demo-landing__box {
      text-align: center;
      padding: 40px 48px;
      border: 1px solid rgba(80, 180, 255, 0.2);
      border-radius: 6px;
      background: rgba(0, 3, 10, 0.85);
      min-width: 320px;
    }
    .demo-landing__title {
      font-size: 13px;
      letter-spacing: 4px;
      color: rgba(80, 180, 255, 0.7);
      text-transform: uppercase;
      margin-bottom: 24px;
    }
    .demo-landing__status {
      font-size: 11px;
      color: #b0d8f0;
      margin-bottom: 12px;
    }
    .demo-landing__status--error {
      color: #f06060;
    }
    .demo-landing__hint {
      font-size: 10px;
      color: rgba(80, 180, 255, 0.45);
      line-height: 1.6;
    }
    .demo-landing__hint code {
      color: rgba(80, 180, 255, 0.75);
      font-family: inherit;
    }
    .demo-landing__spinner {
      width: 18px;
      height: 18px;
      border: 2px solid rgba(80, 180, 255, 0.2);
      border-top-color: rgba(80, 180, 255, 0.8);
      border-radius: 50%;
      animation: spin 0.8s linear infinite;
      margin: 0 auto;
    }
    @keyframes spin {
      to { transform: rotate(360deg); }
    }
  `],
})
export class DemoLandingComponent implements OnInit {
  private readonly router = inject(Router);
  private readonly matchRest = inject(MatchRestClientService);

  /** True while the first fetch is in flight. */
  readonly searching = signal<boolean>(true);
  /** Non-null when the last fetch returned an HTTP error. */
  readonly error = signal<string | null>(null);
  /** Countdown seconds until the next retry. */
  readonly retryCountdown = signal<number>(3);

  private _retryTimer: ReturnType<typeof setInterval> | null = null;
  private _countdownTimer: ReturnType<typeof setInterval> | null = null;

  ngOnInit(): void {
    void this._tryFindMatch();
  }

  private async _tryFindMatch(): Promise<void> {
    this.searching.set(true);
    this.error.set(null);

    try {
      const games = await this.matchRest.fetchGameList();
      this.searching.set(false);

      // Prefer RUNNING, then PAUSED — either is watchable.
      const target =
        games.find((g) => g.status === 'RUNNING') ??
        games.find((g) => g.status === 'PAUSED');

      if (target) {
        void this.router.navigate(['/spectate', target.gameId]);
        return;
      }
    } catch (e) {
      this.searching.set(false);
      this.error.set('Could not reach the backend.');
    }

    // No match found — schedule a retry every 3 s with a visible countdown.
    this._scheduleRetry();
  }

  private _scheduleRetry(): void {
    this._clearTimers();
    this.retryCountdown.set(3);
    this._countdownTimer = setInterval(() => {
      const next = this.retryCountdown() - 1;
      this.retryCountdown.set(next);
    }, 1000);
    this._retryTimer = setTimeout(() => {
      this._clearTimers();
      void this._tryFindMatch();
    }, 3000) as unknown as ReturnType<typeof setInterval>;
  }

  private _clearTimers(): void {
    if (this._retryTimer !== null) {
      clearTimeout(this._retryTimer as unknown as ReturnType<typeof setTimeout>);
      this._retryTimer = null;
    }
    if (this._countdownTimer !== null) {
      clearInterval(this._countdownTimer);
      this._countdownTimer = null;
    }
  }

  ngOnDestroy(): void {
    this._clearTimers();
  }
}
