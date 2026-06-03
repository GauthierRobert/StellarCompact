import { Injectable, computed, inject, signal } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { firstValueFrom } from 'rxjs';

// ---------------------------------------------------------------------------
// Types matching POST /api/auth/login response (docs/specs/rest-api.md)
// ---------------------------------------------------------------------------

interface LoginResponse {
  readonly token: string;
  readonly username: string;
  readonly expiresAt: string;
  readonly tokenType: 'Bearer';
}

/** Shape persisted to localStorage under STORAGE_KEY. */
interface StoredAuth {
  readonly token: string;
  readonly username: string;
  readonly expiresAt: string;
}

// ---------------------------------------------------------------------------
// Service
// ---------------------------------------------------------------------------

const STORAGE_KEY = 'stellar-compact.auth';

/**
 * Username-only dev JWT auth service (feat/dev-jwt-auth).
 *
 * Holds three primary signals — token, username, expiresAt — hydrated from
 * localStorage on construction.  The computed `isAuthenticated` is true when a
 * non-expired token is present.  All signal mutations are synchronous so zoneless
 * change detection picks them up immediately.
 *
 * Only /api endpoints that require auth are intercepted (see auth.interceptor.ts).
 * Anonymous spectator reads (GET /api/games/**, tiles, /ws/**) remain open.
 */
@Injectable({ providedIn: 'root' })
export class AuthService {
  private readonly http = inject(HttpClient);

  // ---- primary signals ----

  readonly token = signal<string | null>(null);
  readonly username = signal<string | null>(null);
  readonly expiresAt = signal<string | null>(null);

  // ---- derived ----

  /** True when a non-expired token is present. */
  readonly isAuthenticated = computed(
    () => !!this.token() && !this._isExpired(),
  );

  // ---- construction: hydrate from localStorage ----

  constructor() {
    if (typeof localStorage === 'undefined') return; // SSR guard
    try {
      const raw = localStorage.getItem(STORAGE_KEY);
      if (!raw) return;
      const stored = JSON.parse(raw) as StoredAuth;
      if (!stored.token || !stored.username || !stored.expiresAt) return;
      // Drop stale tokens immediately rather than relying on 401 later.
      if (this._isPast(stored.expiresAt)) return;
      this.token.set(stored.token);
      this.username.set(stored.username);
      this.expiresAt.set(stored.expiresAt);
    } catch {
      // Corrupted entry — ignore and start fresh.
    }
  }

  // ---- public API ----

  /**
   * POST /api/auth/login with the given username.
   * On success, updates signals and persists to localStorage.
   * Throws on HTTP error (400 for invalid username, 5xx, network).
   */
  async login(username: string): Promise<void> {
    const resp = await firstValueFrom(
      this.http.post<LoginResponse>('/api/auth/login', { username }),
    );
    this.token.set(resp.token);
    this.username.set(resp.username);
    this.expiresAt.set(resp.expiresAt);
    this._persist();
  }

  /** Clear all auth state and remove the localStorage entry. */
  logout(): void {
    this.token.set(null);
    this.username.set(null);
    this.expiresAt.set(null);
    if (typeof localStorage !== 'undefined') {
      localStorage.removeItem(STORAGE_KEY);
    }
  }

  // ---- private helpers ----

  private _isExpired(): boolean {
    const exp = this.expiresAt();
    if (!exp) return true;
    return this._isPast(exp);
  }

  private _isPast(isoString: string): boolean {
    try {
      return new Date(isoString).getTime() <= Date.now();
    } catch {
      return true;
    }
  }

  private _persist(): void {
    if (typeof localStorage === 'undefined') return;
    const stored: StoredAuth = {
      token: this.token()!,
      username: this.username()!,
      expiresAt: this.expiresAt()!,
    };
    localStorage.setItem(STORAGE_KEY, JSON.stringify(stored));
  }
}
