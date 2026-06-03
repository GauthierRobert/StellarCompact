import {
  ChangeDetectionStrategy,
  Component,
  OnInit,
  OnDestroy,
  computed,
  inject,
  input,
  output,
  signal,
} from '@angular/core';
import { CommonModule } from '@angular/common';
import { MatchRestClientService, type GameSummary } from '../../services/match-rest-client.service';

/**
 * Match-picker overlay component (E11-08 — match picker + create controls in spectator HUD).
 *
 * Renders an inline panel that:
 *  - Lists all known matches fetched from GET /api/games.
 *  - Lets the spectator click any match to switch the spectated game.
 *  - Provides a "New Match" button that calls POST /api/games then
 *    POST /api/games/{id}/start and emits the new gameId via the
 *    {@link matchSelected} output so the parent spectator view can re-point its
 *    STOMP subscription without a full page navigation.
 *
 * The panel is designed to sit as a floating dropdown anchored to the top-bar
 * "Matches" button in the spectator view.  It refreshes the list on open and
 * exposes a manual refresh button.
 *
 * Constraints (angular21-signals skill):
 *  - Standalone; no NgModules.
 *  - All reactive state in signals; zoneless CD.
 *  - Display-only: does NOT mutate any running match's gameplay state.
 *    Creating + starting a new match is the only write operation and it goes
 *    through the REST lifecycle surface (POST /api/games + /start), which the
 *    engine treats as the authority (principle 2).
 *  - Never requests or renders another faction's hidden state.
 */
@Component({
  selector: 'app-match-picker',
  changeDetection: ChangeDetectionStrategy.OnPush,
  standalone: true,
  imports: [CommonModule],
  template: `
    <div class="mp-panel" role="dialog" aria-label="Match picker">
      <!-- Header row -->
      <div class="mp-header">
        <span class="mp-title">MATCHES</span>
        <button
          type="button"
          class="mp-btn mp-btn--icon"
          aria-label="Refresh match list"
          [disabled]="loading()"
          (click)="refresh()"
        >&#8635;</button>
      </div>

      <!-- Match list -->
      <div class="mp-list" role="list" aria-label="Available matches">
        @if (loading() && matches().length === 0) {
          <div class="mp-empty">Loading...</div>
        } @else if (fetchError()) {
          <div class="mp-error">{{ fetchError() }}</div>
        } @else if (matches().length === 0) {
          <div class="mp-empty">No matches found.</div>
        } @else {
          @for (m of matches(); track m.gameId) {
            <button
              type="button"
              class="mp-match-row"
              role="listitem"
              [class.mp-match-row--active]="m.gameId === activeGameId()"
              [attr.aria-current]="m.gameId === activeGameId() ? 'true' : null"
              [title]="m.gameId"
              (click)="selectMatch(m.gameId)"
            >
              <span class="mp-match-id">{{ shortId(m.gameId) }}</span>
              <span
                class="mp-match-status"
                [class]="'mp-match-status--' + statusClass(m.status)"
              >{{ m.status }}</span>
              <span class="mp-match-tick">T{{ m.tick }}</span>
              <span class="mp-match-factions">{{ m.factions.length }}f</span>
            </button>
          }
        }
      </div>

      <!-- Divider -->
      <div class="mp-divider"></div>

      <!-- New-match controls -->
      <div class="mp-create-row">
        @if (createError()) {
          <div class="mp-error mp-error--create">{{ createError() }}</div>
        }
        <button
          type="button"
          class="mp-btn mp-btn--create"
          [disabled]="creating()"
          aria-label="Create and start a new match"
          (click)="createAndStart()"
        >
          {{ creating() ? 'Starting...' : '+ New Match' }}
        </button>
      </div>
    </div>
  `,
  styles: [`
    :host {
      display: block;
      font-family: 'Courier New', monospace;
    }
    .mp-panel {
      width: 280px;
      background: rgba(0, 3, 12, 0.97);
      border: 1px solid rgba(80, 180, 255, 0.28);
      border-radius: 4px;
      overflow: hidden;
    }
    .mp-header {
      display: flex;
      align-items: center;
      justify-content: space-between;
      padding: 7px 10px 6px;
      border-bottom: 1px solid rgba(80, 180, 255, 0.12);
    }
    .mp-title {
      font-size: 9px;
      letter-spacing: 2px;
      color: rgba(80, 180, 255, 0.5);
      text-transform: uppercase;
    }
    .mp-list {
      max-height: 220px;
      overflow-y: auto;
      overflow-x: hidden;
      scrollbar-width: thin;
      scrollbar-color: rgba(80,180,255,0.2) transparent;
    }
    .mp-match-row {
      display: flex;
      align-items: center;
      gap: 6px;
      width: 100%;
      padding: 5px 10px;
      background: transparent;
      border: none;
      border-bottom: 1px solid rgba(80,180,255,0.05);
      color: #b0d8f0;
      font-family: 'Courier New', monospace;
      font-size: 10px;
      cursor: pointer;
      text-align: left;
      transition: background 0.1s;
    }
    .mp-match-row:hover {
      background: rgba(80, 180, 255, 0.07);
    }
    .mp-match-row--active {
      background: rgba(80, 180, 255, 0.12);
      border-left: 2px solid rgba(80, 180, 255, 0.6);
    }
    .mp-match-id {
      flex: 1;
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
      color: rgba(80, 180, 255, 0.85);
      font-size: 10px;
    }
    .mp-match-status {
      font-size: 8px;
      letter-spacing: 0.5px;
      padding: 1px 4px;
      border-radius: 2px;
      border: 1px solid transparent;
      flex-shrink: 0;
    }
    .mp-match-status--running   { color: #4ad6a0; border-color: rgba(74,214,160,0.35); }
    .mp-match-status--paused    { color: #f0a060; border-color: rgba(240,160,96,0.35); }
    .mp-match-status--concluded { color: #ffd700; border-color: rgba(255,215,0,0.35); }
    .mp-match-status--other     { color: rgba(80,180,255,0.55); border-color: rgba(80,180,255,0.2); }
    .mp-match-tick {
      font-size: 9px;
      color: rgba(80,180,255,0.45);
      font-variant-numeric: tabular-nums;
      flex-shrink: 0;
    }
    .mp-match-factions {
      font-size: 9px;
      color: rgba(80,180,255,0.35);
      flex-shrink: 0;
    }
    .mp-empty {
      padding: 12px 10px;
      font-size: 10px;
      color: rgba(80,180,255,0.3);
      font-style: italic;
      text-align: center;
    }
    .mp-error {
      padding: 6px 10px;
      font-size: 9px;
      color: #f06060;
    }
    .mp-divider {
      height: 1px;
      background: rgba(80,180,255,0.1);
      margin: 0;
    }
    .mp-create-row {
      padding: 8px 10px;
      display: flex;
      flex-direction: column;
      gap: 4px;
    }
    .mp-error--create {
      padding: 0;
      margin-bottom: 2px;
    }
    .mp-btn {
      background: rgba(80, 180, 255, 0.1);
      color: #b0d8f0;
      border: 1px solid rgba(80, 180, 255, 0.25);
      border-radius: 3px;
      cursor: pointer;
      font-family: 'Courier New', monospace;
      transition: background 0.1s;
    }
    .mp-btn:hover:not(:disabled) {
      background: rgba(80, 180, 255, 0.18);
    }
    .mp-btn:disabled {
      opacity: 0.45;
      cursor: default;
    }
    .mp-btn--icon {
      width: 24px;
      height: 20px;
      font-size: 13px;
      line-height: 1;
      padding: 0;
      display: flex;
      align-items: center;
      justify-content: center;
    }
    .mp-btn--create {
      width: 100%;
      padding: 5px 10px;
      font-size: 10px;
      letter-spacing: 0.5px;
      color: #4ad6a0;
      border-color: rgba(74,214,160,0.3);
      background: rgba(74,214,160,0.07);
    }
    .mp-btn--create:hover:not(:disabled) {
      background: rgba(74,214,160,0.14);
    }
  `],
})
export class MatchPickerComponent implements OnInit, OnDestroy {
  private readonly matchRest = inject(MatchRestClientService);

  /**
   * The currently spectated game id passed in by the parent spectator view.
   * Used to highlight the active row in the match list.
   */
  readonly activeGameId = input<string>('');

  /**
   * Emitted when the user selects a match or a newly-created match is ready.
   * The parent spectator view should re-point its STOMP subscription + REST
   * polling at the emitted gameId.
   */
  readonly matchSelected = output<string>();

  /** Full match list fetched from GET /api/games. */
  readonly matches = signal<readonly GameSummary[]>([]);

  /** True while a list fetch is in flight. */
  readonly loading = signal<boolean>(false);

  /** Non-null when the list fetch failed. */
  readonly fetchError = signal<string | null>(null);

  /** True while create+start is in flight. */
  readonly creating = signal<boolean>(false);

  /** Non-null when the create+start operation failed. */
  readonly createError = signal<string | null>(null);

  /** Derived: total match count for aria label. */
  readonly matchCount = computed(() => this.matches().length);

  ngOnInit(): void {
    void this._fetchList();
  }

  ngOnDestroy(): void {
    // Nothing to tear down — HTTP calls are fire-and-forget via firstValueFrom.
  }

  /** Reload the match list from the server. */
  refresh(): void {
    void this._fetchList();
  }

  /**
   * Emit the selected gameId so the parent can re-point the spectator view.
   * Switching to the already-active match is a no-op.
   */
  selectMatch(gameId: string): void {
    if (gameId === this.activeGameId()) return;
    this.matchSelected.emit(gameId);
  }

  /**
   * Create a new match (POST /api/games with defaults) and immediately start it
   * (POST /api/games/{id}/start).  On success, refreshes the list and emits the
   * new gameId so the parent spectator view switches to it.
   *
   * Uses default request body (no explicit seed/profile) so the backend applies
   * its own deterministic defaults (principle 1 — engine is the authority on seed
   * derivation; we never inject wall-clock entropy here).
   */
  async createAndStart(): Promise<void> {
    if (this.creating()) return;
    this.creating.set(true);
    this.createError.set(null);

    try {
      const created = await this.matchRest.createGame();
      const started = await this.matchRest.startGame(created.gameId);
      // Refresh the list so the new match appears in the picker.
      await this._fetchList();
      // Notify parent to switch the spectated view to the new match.
      this.matchSelected.emit(started.gameId);
    } catch (err) {
      const msg = err instanceof Error ? err.message : 'Failed to create match.';
      this.createError.set(msg);
    } finally {
      this.creating.set(false);
    }
  }

  // ---- display helpers ----

  /** Abbreviate a gameId for display (first 12 chars). */
  shortId(gameId: string): string {
    return gameId.length > 14 ? gameId.slice(0, 14) + '…' : gameId;
  }

  statusClass(status: GameSummary['status']): string {
    switch (status) {
      case 'RUNNING':  return 'running';
      case 'PAUSED':   return 'paused';
      case 'CONCLUDED':
      case 'ARCHIVED': return 'concluded';
      default:         return 'other';
    }
  }

  // ---- private ----

  private async _fetchList(): Promise<void> {
    this.loading.set(true);
    this.fetchError.set(null);
    try {
      const list = await this.matchRest.fetchGameList();
      this.matches.set(list);
    } catch {
      this.fetchError.set('Could not load match list.');
    } finally {
      this.loading.set(false);
    }
  }
}
