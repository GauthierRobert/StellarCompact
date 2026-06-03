import { Injectable, inject, signal } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { firstValueFrom } from 'rxjs';

// ---------------------------------------------------------------------------
// Types matching GET /api/me/games response (docs/specs/rest-api.md)
// ---------------------------------------------------------------------------

/**
 * A single match the authenticated user owns a seat in.
 * factionId is the compound `gameId:seatId` handle.
 */
export interface MyGameView {
  readonly gameId: string;
  readonly factionId: string;
  readonly seatId: string;
  readonly status: string;
  readonly tick: number;
  readonly gameSeed: number;
  readonly balanceProfile: string;
  readonly factionCount: number;
}

interface MyGamesResponse {
  readonly username: string;
  readonly games: readonly MyGameView[];
}

// ---------------------------------------------------------------------------
// Service
// ---------------------------------------------------------------------------

/**
 * REST client for the authenticated user's game list (feat/dev-jwt-auth).
 *
 * Covers:
 *   GET /api/me/games  (requires Bearer token — injected by authInterceptor)
 *
 * The auth interceptor adds the Authorization header automatically; this
 * service stays display-only and never mutates game state.
 */
@Injectable({ providedIn: 'root' })
export class MeRestClientService {
  private readonly http = inject(HttpClient);

  /** Reactive game list signal. Null until first successful fetch. */
  readonly myGames = signal<readonly MyGameView[] | null>(null);

  /**
   * Fetch the authenticated user's game list and update the signal.
   * Returns the full response so callers can also read the username.
   * Throws on 401 (let the caller handle — the guard already redirected).
   */
  async fetchMyGames(): Promise<{ username: string; games: MyGameView[] }> {
    const resp = await firstValueFrom(
      this.http.get<MyGamesResponse>('/api/me/games'),
    );
    this.myGames.set(resp.games);
    return { username: resp.username, games: [...resp.games] };
  }

  /** Reset signal (called on logout / navigation away). */
  reset(): void {
    this.myGames.set(null);
  }
}
