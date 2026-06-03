import { Injectable, inject, signal } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { firstValueFrom } from 'rxjs';

/**
 * REST client for match-level public endpoints (E6-01, E11-07).
 *
 * Covers:
 *   GET /api/games                        -> GameSummary[] (list all matches, E11-07)
 *   GET /api/games/{gameId}               -> GameSummary
 *   GET /api/games/{gameId}/leaderboard   -> LeaderboardResponse
 *   GET /api/games/{gameId}/events        -> EventsPage (paged replay/spectate)
 *
 * Display-only: never mutates game state.  Fog-of-war is enforced server-side;
 * this client only fetches and caches public data.
 */

// ---------------------------------------------------------------------------
// Types matching the REST API spec (docs/specs/rest-api.md)
// ---------------------------------------------------------------------------

export interface GameSummary {
  readonly gameId: string;
  readonly gameSeed: string;
  readonly status: 'CREATED' | 'LOBBY' | 'RUNNING' | 'PAUSED' | 'CONCLUDED' | 'ARCHIVED';
  readonly tick: number;
  readonly balanceProfile: string;
  readonly factions: readonly { factionId: string; seatId: string }[];
}

export interface LeaderboardEntry {
  readonly rank: number;
  readonly factionId: string;
  readonly score: number;
}

export interface LeaderboardResponse {
  readonly gameId: string;
  readonly tick: number;
  readonly entries: readonly LeaderboardEntry[];
}

export interface EventsPageEvent {
  readonly type: string;
  readonly parties: readonly string[];
  readonly systemId?: string;
  readonly tick: number;
}

export interface EventsPage {
  readonly events: readonly EventsPageEvent[];
  readonly nextFromTick: number;
}

/**
 * Body for POST /api/games (rest-api spec, Match lifecycle, E6-01).
 * All fields are optional; omitting them lets the backend apply defaults.
 * `seats` is a per-seat agent-type token list from the closed whitelist
 * SCRIPTED | AGGRESSIVE | LLM; length must equal factionCount when provided.
 */
export interface CreateGameRequest {
  readonly seed?: number;
  readonly size?: string;
  readonly factionCount?: number;
  readonly tickIntervalMs?: number;
  readonly victoryCondition?: string;
  readonly balanceProfile?: string;
  readonly seats?: readonly string[];
}

// ---------------------------------------------------------------------------
// Service
// ---------------------------------------------------------------------------

@Injectable({ providedIn: 'root' })
export class MatchRestClientService {
  private readonly http = inject(HttpClient);

  /** Reactive leaderboard signal. Null until first fetch. */
  readonly leaderboard = signal<LeaderboardResponse | null>(null);

  /** Reactive game summary signal. Null until first fetch. */
  readonly gameSummary = signal<GameSummary | null>(null);

  /**
   * Fetch the game summary and update the signal.
   * Safe to call periodically; result replaces the previous value.
   */
  async fetchGameSummary(gameId: string): Promise<void> {
    try {
      const summary = await firstValueFrom(
        this.http.get<GameSummary>(`/api/games/${encodeURIComponent(gameId)}`)
      );
      this.gameSummary.set(summary);
    } catch {
      // Best-effort; previous value retained on error.
    }
  }

  /**
   * Fetch the leaderboard for a game and update the signal.
   * Safe to call after every tick heartbeat.
   */
  async fetchLeaderboard(gameId: string): Promise<void> {
    try {
      const lb = await firstValueFrom(
        this.http.get<LeaderboardResponse>(
          `/api/games/${encodeURIComponent(gameId)}/leaderboard`
        )
      );
      this.leaderboard.set(lb);
    } catch {
      // Best-effort.
    }
  }

  /**
   * Fetch a page of public events for replay/back-fill.
   * Does NOT update a store signal; returns the page for the caller to handle.
   */
  async fetchEvents(
    gameId: string,
    fromTick = 0,
    limit = 200
  ): Promise<EventsPage> {
    const url =
      `/api/games/${encodeURIComponent(gameId)}/events` +
      `?fromTick=${fromTick}&limit=${limit}`;
    return firstValueFrom(this.http.get<EventsPage>(url));
  }

  /**
   * Fetch the list of all known matches (E11-07 — demo discovery).
   * Returns the raw array for the caller to inspect; throws on network error so the
   * caller can show a retry UI.
   */
  async fetchGameList(): Promise<readonly GameSummary[]> {
    return firstValueFrom(this.http.get<GameSummary[]>('/api/games'));
  }

  /**
   * Create a new match (POST /api/games, E6-01).
   * All fields are optional; omitting them lets the backend apply sensible defaults
   * (2 scripted factions, small-default balance profile, service-derived seed).
   * Returns the created GameSummary (status: CREATED) or throws on error.
   */
  async createGame(request: CreateGameRequest = {}): Promise<GameSummary> {
    return firstValueFrom(
      this.http.post<GameSummary>('/api/games', request)
    );
  }

  /**
   * Start a CREATED match (POST /api/games/{gameId}/start, E6-01).
   * Drives CREATED→LOBBY→RUNNING; an illegal transition (e.g. already running) throws
   * an HTTP 409 which the caller should surface.
   */
  async startGame(gameId: string): Promise<GameSummary> {
    return firstValueFrom(
      this.http.post<GameSummary>(
        `/api/games/${encodeURIComponent(gameId)}/start`,
        null
      )
    );
  }

  /** Reset signals (used in tests / when navigating away). */
  reset(): void {
    this.leaderboard.set(null);
    this.gameSummary.set(null);
  }
}
