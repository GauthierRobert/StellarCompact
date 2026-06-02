import { Injectable, InjectionToken, inject } from '@angular/core';
import {
  HttpClient,
  HttpHeaders,
  HttpErrorResponse,
} from '@angular/common/http';
import { firstValueFrom } from 'rxjs';

// ---------------------------------------------------------------------------
// Dev owner-token injection token — stand-in until session auth lands.
// Override in tests or app config with a real token value.
// ---------------------------------------------------------------------------

export const OWNER_TOKEN = new InjectionToken<string>('OWNER_TOKEN', {
  providedIn: 'root',
  factory: () => '',
});

// ---------------------------------------------------------------------------
// Contract types — mirror the E6-02 backend DTOs exactly
// ---------------------------------------------------------------------------

/** Model tier enum — orchestration routing only, never a vendor name. */
export type ModelTier = 'SMALL' | 'MEDIUM' | 'LARGE';

/** Body for POST /api/games/{gameId}/factions */
export interface CreateFactionRequest {
  /** One of personaPreset or customPersona must be provided. */
  personaPreset?: string;
  customPersona?: string;
  goals: string[];
  hardConstraints: string[];
  modelTier: ModelTier;
}

/** Response from POST /api/games/{gameId}/factions */
export interface AttachFactionResponse {
  factionId: string;
  gameId: string;
  seatId: string;
}

/** Full / redacted view from GET /api/factions/{factionId} */
export interface FactionConfigView {
  factionId: string;
  gameId: string;
  seatId: string;
  configured: boolean;
  /** true when the requester is the registered owner. */
  owner: boolean;
  /** null when redacted */
  persona: string | null;
  goals: string[];
  hardConstraints: string[];
  /** null when redacted */
  modelTier: ModelTier | null;
}

/** Body for PATCH /api/factions/{factionId} */
export interface UpdateDirectivesRequest {
  goals?: string[];
  hardConstraints?: string[];
  modelTier?: ModelTier;
  customPersona?: string;
}

/** Structured error for the between-matches lifecycle gate (409). */
export class DirectivesLockedException extends Error {
  constructor() {
    super('Faction directives are locked while the match is RUNNING.');
    this.name = 'DirectivesLockedException';
  }
}

// ---------------------------------------------------------------------------
// Service
// ---------------------------------------------------------------------------

/**
 * Faction config service — thin HTTP wrapper around the E6-02 faction config
 * REST surface.
 *
 *   POST /api/games/{gameId}/factions   → attach/create Sovereign config
 *   GET  /api/factions/{factionId}      → read config view (owner-sensitive)
 *   PATCH /api/factions/{factionId}     → update standing directives
 *
 * The `X-Owner-Token` dev header is injected from the OWNER_TOKEN token so it
 * can be overridden per-environment without code changes (principle 4).
 * Owner identity is resolved server-side from this header (or real auth later).
 */
@Injectable({ providedIn: 'root' })
export class FactionConfigService {
  private readonly http = inject(HttpClient);
  private readonly ownerToken = inject(OWNER_TOKEN);

  private get headers(): HttpHeaders {
    let h = new HttpHeaders({ 'Content-Type': 'application/json' });
    if (this.ownerToken) {
      h = h.set('X-Owner-Token', this.ownerToken);
    }
    return h;
  }

  /**
   * Attach a Sovereign config to the first free seat in the given game.
   * Returns 201 AttachFactionResponse on success.
   */
  async attach(
    gameId: string,
    body: CreateFactionRequest,
  ): Promise<AttachFactionResponse> {
    return firstValueFrom(
      this.http.post<AttachFactionResponse>(
        `/api/games/${gameId}/factions`,
        body,
        { headers: this.headers },
      ),
    );
  }

  /**
   * Read the faction config view for a given factionId.
   * Returns the full view when the requester is the owner; otherwise returns
   * the public identity only (persona/goals/constraints/tier are redacted).
   */
  async getConfig(factionId: string): Promise<FactionConfigView> {
    return firstValueFrom(
      this.http.get<FactionConfigView>(`/api/factions/${factionId}`, {
        headers: this.headers,
      }),
    );
  }

  /**
   * Update standing directives for a faction (between matches only).
   * Throws DirectivesLockedException on 409 (match is RUNNING).
   * Throws on 403 when the requester is not the owner.
   */
  async updateDirectives(
    factionId: string,
    body: UpdateDirectivesRequest,
  ): Promise<FactionConfigView> {
    try {
      return await firstValueFrom(
        this.http.patch<FactionConfigView>(`/api/factions/${factionId}`, body, {
          headers: this.headers,
        }),
      );
    } catch (err) {
      if (err instanceof HttpErrorResponse && err.status === 409) {
        throw new DirectivesLockedException();
      }
      throw err;
    }
  }
}
