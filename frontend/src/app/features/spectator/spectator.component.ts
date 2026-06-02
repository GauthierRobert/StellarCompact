import {
  ChangeDetectionStrategy,
  Component,
  OnDestroy,
  OnInit,
  computed,
  inject,
  signal,
} from '@angular/core';
import { CommonModule } from '@angular/common';
import { ActivatedRoute } from '@angular/router';
import { GalaxyComponent } from '../galaxy/galaxy.component';
import { EventsStore, type PublicEvent } from '../../stores/events.store';
import { FactionStore, type FactionSnapshot } from '../../stores/faction.store';
import { StompClientService } from '../../services/stomp-client.service';
import { MatchRestClientService, type LeaderboardEntry } from '../../services/match-rest-client.service';

/**
 * Spectator view — "AI as sport" mode (E7-06).
 *
 * Layout:
 *   - Galaxy renderer (<app-galaxy>) fills the viewport as the base layer.
 *   - Left panel: scrolling public event timeline (newest-first).
 *   - Right panel: leaderboard standings + victory-progress.
 *   - Top bar: game status + tick counter.
 *
 * Data sources:
 *   - STOMP public topics (ticks / events / overlay) via StompClientService.
 *     No owner faction => NO subscription to /user/queue/faction/{id}/view.
 *   - REST leaderboard polled after each tick heartbeat.
 *   - EventsStore (fed by STOMP) drives the event timeline signal.
 *   - FactionStore (fed by STOMP tick payloads) drives the standings.
 *
 * Constraints:
 *   - Display-only; never mutates game state.
 *   - Never requests or renders hidden faction state.
 *   - Standalone, signals, zoneless.
 */
@Component({
  selector: 'app-spectator',
  changeDetection: ChangeDetectionStrategy.OnPush,
  standalone: true,
  imports: [CommonModule, GalaxyComponent],
  template: `
    <div class="spectator-root">
      <!-- Base layer: galaxy renderer -->
      <app-galaxy
        class="galaxy-layer"
        [seed]="seed()"
        [rMax]="rMax()"
      />

      <!-- Top bar -->
      <div class="spec-top-bar">
        <span class="spec-title">SPECTATING</span>
        <span class="spec-game-id" [title]="gameId()">{{ gameId() }}</span>
        <span class="spec-status" [class]="'spec-status--' + statusClass()">
          {{ status() }}
        </span>
        @if (latestTick(); as t) {
          <span class="spec-tick">T{{ t.tick }}</span>
        }
        <span class="spec-conn" [class]="'spec-conn--' + connectionState()">
          {{ connectionState() }}
        </span>
      </div>

      <!-- Left panel: event timeline -->
      <div class="spec-events-panel">
        <div class="spec-panel-header">Public Events</div>
        <div class="spec-events-list" role="log" aria-live="polite" aria-label="Public event timeline">
          @for (evt of spectatorEvents(); track evtKey(evt)) {
            <div class="spec-event-row" [class]="'spec-event-row--' + evtClass(evt)">
              <span class="spec-event-tick">T{{ evt.tick }}</span>
              <span class="spec-event-label">{{ evtLabel(evt) }}</span>
            </div>
          }
          @empty {
            <div class="spec-events-empty">Waiting for events...</div>
          }
        </div>
      </div>

      <!-- Right panel: leaderboard + victory progress -->
      <div class="spec-right-panel">
        <div class="spec-panel-header">Standings</div>
        <div class="spec-leaderboard" role="list" aria-label="Faction standings">
          @if (leaderboardEntries().length > 0) {
            @for (entry of leaderboardEntries(); track entry.factionId) {
              <div class="spec-lb-row" role="listitem">
                <span class="spec-lb-rank">#{{ entry.rank }}</span>
                <span
                  class="spec-lb-swatch"
                  [style.background]="factionColour(entry.factionId)"
                  aria-hidden="true"
                ></span>
                <span class="spec-lb-name" [title]="entry.factionId">
                  {{ factionName(entry.factionId) }}
                </span>
                <span class="spec-lb-score">{{ entry.score | number:'1.0-0' }}</span>
              </div>
            }
          } @else {
            @for (f of factionStandings(); track f.factionId) {
              <div class="spec-lb-row spec-lb-row--live" role="listitem">
                <span class="spec-lb-rank">{{ factionRank(f) }}</span>
                <span
                  class="spec-lb-swatch"
                  [style.background]="f.colour"
                  aria-hidden="true"
                ></span>
                <span class="spec-lb-name" [class.spec-lb-name--elim]="f.eliminated"
                      [title]="f.factionId">
                  {{ f.name }}
                </span>
                <span class="spec-lb-systems">{{ f.systemCount }} sys</span>
              </div>
            }
          }
        </div>

        <!-- Victory progress bars -->
        @if (factionStandings().length > 0) {
          <div class="spec-victory-section">
            <div class="spec-panel-subheader">Victory Progress</div>
            @for (f of factionStandings(); track f.factionId) {
              <div class="spec-vp-row" [title]="f.factionId">
                <span class="spec-vp-name">{{ f.name }}</span>
                <div class="spec-vp-bar-track">
                  <div
                    class="spec-vp-bar-fill"
                    [style.width]="victoryProgressPct(f) + '%'"
                    [style.background]="f.colour"
                  ></div>
                </div>
                <span class="spec-vp-pct">{{ victoryProgressPct(f) | number:'1.0-0' }}%</span>
              </div>
            }
          </div>
        }
      </div>
    </div>
  `,
  styles: [`
    :host {
      display: block;
      position: relative;
      width: 100%;
      height: 100%;
      font-family: 'Courier New', monospace;
    }
    .spectator-root {
      position: relative;
      width: 100%;
      height: 100%;
      overflow: hidden;
      background: #000308;
    }
    .galaxy-layer {
      position: absolute;
      inset: 0;
    }
    .spec-top-bar {
      position: absolute;
      top: 0;
      left: 0;
      right: 0;
      z-index: 20;
      display: flex;
      align-items: center;
      gap: 12px;
      padding: 6px 14px;
      background: rgba(0, 3, 10, 0.85);
      border-bottom: 1px solid rgba(80, 180, 255, 0.18);
      font-size: 11px;
      color: #b0d8f0;
    }
    .spec-title {
      font-size: 10px;
      letter-spacing: 2px;
      text-transform: uppercase;
      color: rgba(80, 180, 255, 0.55);
    }
    .spec-game-id {
      max-width: 120px;
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
      color: rgba(80, 180, 255, 0.75);
      font-size: 10px;
    }
    .spec-status {
      padding: 1px 6px;
      border-radius: 2px;
      font-size: 9px;
      letter-spacing: 1px;
      border: 1px solid transparent;
    }
    .spec-status--running   { color: #4ad6a0; border-color: rgba(74,214,160,0.4); }
    .spec-status--paused    { color: #f0a060; border-color: rgba(240,160,96,0.4); }
    .spec-status--concluded { color: #ffd700; border-color: rgba(255,215,0,0.4); }
    .spec-status--other     { color: #b0d8f0; border-color: rgba(80,180,255,0.2); }
    .spec-tick {
      margin-left: auto;
      font-variant-numeric: tabular-nums;
      color: rgba(80, 180, 255, 0.6);
      font-size: 10px;
    }
    .spec-conn {
      font-size: 9px;
      letter-spacing: 1px;
    }
    .spec-conn--connected    { color: #4ad6a0; }
    .spec-conn--connecting   { color: #f0a060; }
    .spec-conn--error        { color: #f06060; }
    .spec-conn--disconnected { color: rgba(80,180,255,0.35); }
    .spec-panel-header {
      font-size: 9px;
      letter-spacing: 2px;
      text-transform: uppercase;
      color: rgba(80, 180, 255, 0.5);
      padding: 8px 10px 4px;
      border-bottom: 1px solid rgba(80, 180, 255, 0.1);
    }
    .spec-panel-subheader {
      font-size: 8px;
      letter-spacing: 1.5px;
      text-transform: uppercase;
      color: rgba(80, 180, 255, 0.4);
      padding: 8px 10px 3px;
    }
    .spec-events-panel {
      position: absolute;
      top: 38px;
      left: 0;
      width: 260px;
      bottom: 0;
      z-index: 10;
      background: rgba(0, 3, 10, 0.78);
      border-right: 1px solid rgba(80, 180, 255, 0.12);
      display: flex;
      flex-direction: column;
      overflow: hidden;
    }
    .spec-events-list {
      flex: 1;
      overflow-y: auto;
      overflow-x: hidden;
      padding: 4px 0;
      scrollbar-width: thin;
      scrollbar-color: rgba(80,180,255,0.2) transparent;
    }
    .spec-event-row {
      display: flex;
      align-items: baseline;
      gap: 6px;
      padding: 3px 10px;
      font-size: 10px;
      border-bottom: 1px solid rgba(80,180,255,0.04);
    }
    .spec-event-tick {
      font-size: 9px;
      color: rgba(80,180,255,0.4);
      flex-shrink: 0;
      min-width: 32px;
      font-variant-numeric: tabular-nums;
    }
    .spec-event-label { flex: 1; }
    .spec-event-row--war      { color: #f06060; }
    .spec-event-row--treaty   { color: #4ad6a0; }
    .spec-event-row--battle   { color: #f0a060; }
    .spec-event-row--capture  { color: #a0c0f0; }
    .spec-event-row--route    { color: rgba(80,180,255,0.8); }
    .spec-event-row--victory  { color: #ffd700; }
    .spec-event-row--elim     { color: #c060c0; }
    .spec-event-row--default  { color: #b0d8f0; }
    .spec-events-empty {
      padding: 16px 10px;
      font-size: 10px;
      color: rgba(80,180,255,0.3);
      font-style: italic;
    }
    .spec-right-panel {
      position: absolute;
      top: 38px;
      right: 0;
      width: 220px;
      bottom: 0;
      z-index: 10;
      background: rgba(0, 3, 10, 0.78);
      border-left: 1px solid rgba(80, 180, 255, 0.12);
      display: flex;
      flex-direction: column;
      overflow: hidden;
    }
    .spec-leaderboard {
      padding: 4px 0;
    }
    .spec-lb-row {
      display: flex;
      align-items: center;
      gap: 6px;
      padding: 4px 10px;
      font-size: 10px;
      color: #b0d8f0;
    }
    .spec-lb-rank {
      font-size: 9px;
      color: rgba(80,180,255,0.5);
      min-width: 22px;
    }
    .spec-lb-swatch {
      width: 8px;
      height: 8px;
      border-radius: 50%;
      flex-shrink: 0;
    }
    .spec-lb-name {
      flex: 1;
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
    }
    .spec-lb-name--elim {
      opacity: 0.45;
      text-decoration: line-through;
    }
    .spec-lb-score, .spec-lb-systems {
      font-size: 9px;
      color: rgba(80,180,255,0.6);
      font-variant-numeric: tabular-nums;
    }
    .spec-victory-section {
      border-top: 1px solid rgba(80,180,255,0.1);
      padding-bottom: 8px;
    }
    .spec-vp-row {
      display: flex;
      align-items: center;
      gap: 6px;
      padding: 3px 10px;
      font-size: 9px;
      color: rgba(80,180,255,0.7);
    }
    .spec-vp-name {
      min-width: 60px;
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
    }
    .spec-vp-bar-track {
      flex: 1;
      height: 4px;
      background: rgba(80,180,255,0.1);
      border-radius: 2px;
      overflow: hidden;
    }
    .spec-vp-bar-fill {
      height: 100%;
      border-radius: 2px;
      transition: width 0.4s ease;
      min-width: 2px;
    }
    .spec-vp-pct {
      min-width: 28px;
      text-align: right;
      font-variant-numeric: tabular-nums;
    }
  `],
})
export class SpectatorComponent implements OnInit, OnDestroy {
  private readonly route = inject(ActivatedRoute);
  private readonly eventsStore = inject(EventsStore);
  private readonly factionStore = inject(FactionStore);
  private readonly stompClient = inject(StompClientService);
  private readonly matchRest = inject(MatchRestClientService);

  // ---- route params ----
  readonly gameId = signal<string>('');
  readonly seed = signal<string>('1');
  readonly rMax = signal<number>(1000);

  // ---- derived signals ----

  /** All events newest-first for the event timeline (up to 200). */
  readonly spectatorEvents = computed<readonly PublicEvent[]>(() =>
    [...this.eventsStore.events()].reverse().slice(0, 200)
  );

  /** Latest tick event for the top-bar tick counter. */
  readonly latestTick = this.eventsStore.latestTick;

  /** Factions sorted by systemCount descending (live from faction store). */
  readonly factionStandings = this.factionStore.factions;

  /** Leaderboard entries from REST (preferred; may be empty until polled). */
  readonly leaderboardEntries = computed<readonly LeaderboardEntry[]>(() => {
    const lb = this.matchRest.leaderboard();
    return lb?.entries ?? [];
  });

  /** STOMP connection state string. */
  readonly connectionState = this.stompClient.connectionState;

  /** Match status from REST game summary. */
  readonly status = computed<string>(() => {
    const s = this.matchRest.gameSummary();
    return s?.status ?? '-';
  });

  readonly statusClass = computed<string>(() => {
    const s = this.status().toLowerCase();
    if (s === 'running') return 'running';
    if (s === 'paused') return 'paused';
    if (s === 'concluded' || s === 'archived') return 'concluded';
    return 'other';
  });

  // ---- lifecycle ----

  ngOnInit(): void {
    const gameId = this.route.snapshot.paramMap.get('gameId') ?? '';
    this.gameId.set(gameId);

    if (!gameId) return;

    // Connect STOMP public topics only; omit factionId => spectator-only, no owner queue.
    this.stompClient.connect({ gameId });

    // Fetch initial REST data.
    void this.matchRest.fetchGameSummary(gameId);
    void this.matchRest.fetchLeaderboard(gameId);

    this._startLeaderboardPolling(gameId);
  }

  ngOnDestroy(): void {
    this._stopLeaderboardPolling();
    this.stompClient.disconnect();
    this.matchRest.reset();
  }

  // ---- leaderboard polling ----

  private _lastPolledTick = -1;
  private _pollInterval: ReturnType<typeof setInterval> | null = null;

  private _startLeaderboardPolling(gameId: string): void {
    // Refresh every 5 s; also detect tick advancement.
    this._pollInterval = setInterval(() => {
      const tick = this.eventsStore.latestTick();
      if (tick && tick.tick !== this._lastPolledTick) {
        this._lastPolledTick = tick.tick;
        void this.matchRest.fetchLeaderboard(gameId);
        void this.matchRest.fetchGameSummary(gameId);
      }
    }, 5000);
  }

  private _stopLeaderboardPolling(): void {
    if (this._pollInterval !== null) {
      clearInterval(this._pollInterval);
      this._pollInterval = null;
    }
  }

  // ---- display helpers ----

  evtKey(e: PublicEvent): string {
    return `${e.tick}:${e.type}:${e.parties[0] ?? ''}:${e.systemId ?? ''}`;
  }

  evtClass(e: PublicEvent): string {
    switch (e.type) {
      case 'WarDeclared':        return 'war';
      case 'TreatySigned':
      case 'AllianceFormed':
      case 'TreatyBroken':       return 'treaty';
      case 'BattleResolved':     return 'battle';
      case 'SystemCaptured':     return 'capture';
      case 'RouteEstablished':
      case 'RouteRaided':        return 'route';
      case 'VictoryAchieved':    return 'victory';
      case 'FactionEliminated':  return 'elim';
      default:                   return 'default';
    }
  }

  evtLabel(e: PublicEvent): string {
    const p0 = e.parties[0] ?? '?';
    const p1 = e.parties[1];
    switch (e.type) {
      case 'WarDeclared':        return `WAR: ${p0} vs ${p1 ?? '?'}`;
      case 'TreatySigned':       return `TREATY: ${p0} + ${p1 ?? '?'}`;
      case 'TreatyBroken':       return `BROKEN: ${p0}`;
      case 'AllianceFormed':     return `ALLIANCE: ${p0} + ${p1 ?? '?'}`;
      case 'SystemCaptured':     return `CAPTURED: ${e.systemId ?? '?'} by ${p0}`;
      case 'BattleResolved':     return `BATTLE @ ${e.systemId ?? '?'}`;
      case 'RouteEstablished':   return `ROUTE: ${p0}`;
      case 'RouteRaided':        return `RAID @ ${e.systemId ?? '?'}`;
      case 'FactionEliminated':  return `ELIMINATED: ${p0}`;
      case 'VictoryAchieved':    return `VICTORY: ${p0}`;
      default:                   return e.type;
    }
  }

  factionColour(factionId: string): string {
    return this.factionStore.getById(factionId)?.colour ?? '#888888';
  }

  factionName(factionId: string): string {
    return this.factionStore.getById(factionId)?.name ?? factionId;
  }

  factionRank(f: FactionSnapshot): string {
    const idx = this.factionStandings().findIndex(
      (s) => s.factionId === f.factionId
    );
    return `#${idx + 1}`;
  }

  /**
   * Victory progress as a percentage of total system share.
   * Display-only proxy; the engine is authoritative on win conditions.
   */
  victoryProgressPct(f: FactionSnapshot): number {
    const total = this.factionStore.totalSystems();
    if (total === 0) return 0;
    return Math.min(100, (f.systemCount / total) * 100);
  }
}
