import { Injectable, computed, inject, signal } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { firstValueFrom } from 'rxjs';
import { EventsStore, type PublicEvent, type PublicEventType } from '../stores/events.store';
import type { LeaderboardResponse } from './match-rest-client.service';

/**
 * Replay client (E9-02) — drives the spectator view from a server-side deterministic replay
 * of an archived match instead of the live STOMP stream.
 *
 * The backend re-resolves the recorded `(seed, action log)` through the pure engine resolver
 * and serves per-tick frames:
 *   GET /api/games/{gameId}/replay          -> ReplayManifest  { firstTick, lastTick, tickCount, ... }
 *   GET /api/games/{gameId}/replay/{tick}   -> ReplayFrame     { tick, status, events[], leaderboard[], reputations[] }
 *
 * The client stays thin: it never re-resolves anything, never ships the action log. It seeks
 * by asking the server for tick N's frame and feeds the SAME stores/signals the live path
 * feeds — the EventsStore (events + tick heartbeat) and a `leaderboard` signal shaped exactly
 * like the live `LeaderboardResponse` — so the spectator component renders replay identically
 * to live. Seeking to tick N yields exactly tick N's frame (the server is deterministic and
 * order-independent), so scrub/step/play all reduce to "seek(tick)".
 *
 * Display-only: never mutates game state. Fog-of-war is enforced server-side; a replay frame
 * is a strictly-public projection.
 */

export interface ReplayManifest {
  readonly gameId: string;
  readonly gameSeed: string;
  readonly profile: string;
  readonly firstTick: number;
  readonly lastTick: number;
  readonly tickCount: number;
}

export interface ReplayFrameEvent {
  readonly tick: number;
  readonly seq: number;
  readonly type: string;
  readonly parties: readonly string[];
  readonly systemId?: string | null;
}

export interface ReplayLeaderboardEntry {
  readonly rank: number;
  readonly factionId: string;
  readonly score: number;
}

export interface ReplayReputation {
  readonly factionId: string;
  readonly reputation: number;
}

export interface ReplayFrame {
  readonly gameId: string;
  readonly tick: number;
  readonly status: string;
  readonly events: readonly ReplayFrameEvent[];
  readonly leaderboard: readonly ReplayLeaderboardEntry[];
  readonly reputations: readonly ReplayReputation[];
}

/** Wall-clock step between frames while auto-playing (ms). Display pacing only. */
const PLAYBACK_INTERVAL_MS = 800;

@Injectable({ providedIn: 'root' })
export class ReplayClientService {
  private readonly http = inject(HttpClient);
  private readonly eventsStore = inject(EventsStore);

  // ---- reactive state ----

  /** The replay manifest (scrub-bar range). Null until loaded. */
  readonly manifest = signal<ReplayManifest | null>(null);

  /** The tick currently being shown. */
  readonly currentTick = signal<number>(0);

  /** Whether auto-play is running. */
  readonly playing = signal<boolean>(false);

  /** The status at the current frame (mirrors the live game status). */
  readonly status = signal<string>('-');

  /**
   * The current frame's leaderboard, shaped exactly like the live `LeaderboardResponse` so
   * the spectator can read it interchangeably with the live REST leaderboard.
   */
  readonly leaderboard = signal<LeaderboardResponse | null>(null);

  /** Whether a replay has been loaded (manifest present + tickCount > 0). */
  readonly active = computed<boolean>(() => {
    const m = this.manifest();
    return m !== null && m.tickCount > 0;
  });

  /** The lowest/highest seekable ticks (0 when no manifest). */
  readonly firstTick = computed<number>(() => this.manifest()?.firstTick ?? 0);
  readonly lastTick = computed<number>(() => this.manifest()?.lastTick ?? 0);

  /** Whether the current tick is at the end of the timeline. */
  readonly atEnd = computed<boolean>(() => this.currentTick() >= this.lastTick());

  private gameId = '';
  private playTimer: ReturnType<typeof setInterval> | null = null;

  /**
   * Load the replay manifest for a match and seek to its first tick. Returns true if the
   * match has a replayable timeline.
   */
  async load(gameId: string): Promise<boolean> {
    this.gameId = gameId;
    try {
      const manifest = await firstValueFrom(
        this.http.get<ReplayManifest>(`/api/games/${encodeURIComponent(gameId)}/replay`),
      );
      this.manifest.set(manifest);
      if (manifest.tickCount > 0) {
        await this.seek(manifest.firstTick);
        return true;
      }
      return false;
    } catch {
      this.manifest.set(null);
      return false;
    }
  }

  /**
   * Seek to an absolute tick: fetch that tick's frame and feed the shared stores/signals.
   * The tick is clamped client-side to the manifest range (the server also clamps), so a
   * scrubbing UI can request any value. Pure read; the same tick always renders the same
   * frame (server-side determinism).
   */
  async seek(tick: number): Promise<void> {
    const m = this.manifest();
    if (!m || m.tickCount === 0) return;
    const clamped = Math.max(m.firstTick, Math.min(m.lastTick, Math.round(tick)));
    let frame: ReplayFrame;
    try {
      frame = await firstValueFrom(
        this.http.get<ReplayFrame>(
          `/api/games/${encodeURIComponent(this.gameId)}/replay/${clamped}`,
        ),
      );
    } catch {
      return; // best-effort; keep the previous frame on a transient error
    }
    this.applyFrame(frame);
  }

  /** Step one tick forward (stops at the end). */
  async stepForward(): Promise<void> {
    await this.seek(this.currentTick() + 1);
    if (this.atEnd()) this.pause();
  }

  /** Step one tick backward (stops at the start). */
  async stepBack(): Promise<void> {
    await this.seek(this.currentTick() - 1);
  }

  /** Start auto-playing forward from the current tick. */
  play(): void {
    if (this.playing() || !this.active()) return;
    this.playing.set(true);
    this.playTimer = setInterval(() => {
      if (this.atEnd()) {
        this.pause();
        return;
      }
      void this.stepForward();
    }, PLAYBACK_INTERVAL_MS);
  }

  /** Pause auto-play. */
  pause(): void {
    this.playing.set(false);
    if (this.playTimer !== null) {
      clearInterval(this.playTimer);
      this.playTimer = null;
    }
  }

  /** Toggle play/pause. */
  toggle(): void {
    if (this.playing()) this.pause();
    else this.play();
  }

  /** Tear down: stop playback and clear state. */
  reset(): void {
    this.pause();
    this.manifest.set(null);
    this.currentTick.set(0);
    this.status.set('-');
    this.leaderboard.set(null);
    this.gameId = '';
  }

  // ---- internals ----

  /**
   * Feed a replay frame into the shared stores/signals exactly as the live STOMP path would:
   * replace the visible event window, emit a tick heartbeat, publish the leaderboard. Replay
   * re-seeds the event window from scratch on each seek (a seek is a jump, not an append), so
   * a backward seek shows the past, not a superset.
   */
  private applyFrame(frame: ReplayFrame): void {
    this.currentTick.set(frame.tick);
    this.status.set(frame.status);

    // Rebuild the event window up to and including this tick is the server's job per-frame:
    // a frame carries that tick's events. We reset then append so scrubbing back never leaves
    // future events on screen.
    this.eventsStore.reset();
    const events: PublicEvent[] = frame.events.map((e) => ({
      type: e.type as PublicEventType,
      parties: e.parties,
      systemId: e.systemId ?? undefined,
      tick: e.tick,
    }));
    if (events.length > 0) this.eventsStore.appendEvents(events);
    this.eventsStore.applyTick({ tick: frame.tick, phase: 'RESOLUTION', startedAt: '0' });

    this.leaderboard.set({
      gameId: frame.gameId,
      tick: frame.tick,
      entries: frame.leaderboard.map((e) => ({
        rank: e.rank,
        factionId: e.factionId,
        score: e.score,
      })),
    });
  }
}
