import {
  ChangeDetectionStrategy,
  Component,
  inject,
} from '@angular/core';
import { CommonModule } from '@angular/common';
import { EventsStore, type PublicEvent } from '../../stores';

/**
 * Event feed — bottom-edge HUD strip showing the 20 most recent public events.
 *
 * Display-only: reads EventsStore.recentEvents (signal-based). Never writes
 * to game state. Events are coloured by type.
 */
@Component({
  selector: 'app-event-feed',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [CommonModule],
  template: `
    <div class="event-feed">
      <span class="feed-label">Events</span>
      <div class="feed-scroll">
        @for (evt of events(); track evtKey(evt)) {
          <span class="event-chip" [class]="'event-chip--' + evtClass(evt)" [title]="evtTitle(evt)">
            {{ evtLabel(evt) }}
          </span>
        }
        @empty {
          <span class="feed-empty">No events yet</span>
        }
      </div>
      @if (tick(); as t) {
        <span class="tick-badge">T{{ t.tick }}</span>
      }
    </div>
  `,
  styles: [`
    :host { display: block; }
    .event-feed {
      display: flex;
      align-items: center;
      gap: 8px;
      padding: 4px 12px;
      background: rgba(0, 3, 8, 0.82);
      border-top: 1px solid rgba(80, 180, 255, 0.14);
      font-family: 'Courier New', monospace;
      font-size: 11px;
      color: #b0d8f0;
      overflow: hidden;
    }
    .feed-label {
      font-size: 9px;
      text-transform: uppercase;
      letter-spacing: 1.5px;
      color: rgba(80, 180, 255, 0.45);
      flex-shrink: 0;
    }
    .feed-scroll {
      display: flex;
      gap: 6px;
      overflow-x: auto;
      flex: 1;
      scrollbar-width: none;
    }
    .feed-scroll::-webkit-scrollbar { display: none; }
    .event-chip {
      white-space: nowrap;
      padding: 1px 6px;
      border-radius: 2px;
      font-size: 10px;
      border: 1px solid transparent;
    }
    .event-chip--war      { color: #f06060; border-color: rgba(240,96,96,0.4); }
    .event-chip--treaty   { color: #4ad6a0; border-color: rgba(74,214,160,0.4); }
    .event-chip--battle   { color: #f0a060; border-color: rgba(240,160,96,0.4); }
    .event-chip--capture  { color: #a0c0f0; border-color: rgba(160,192,240,0.4); }
    .event-chip--route    { color: rgba(80,180,255,0.8); border-color: rgba(80,180,255,0.25); }
    .event-chip--victory  { color: #ffd700; border-color: rgba(255,215,0,0.4); }
    .event-chip--elim     { color: #c060c0; border-color: rgba(192,96,192,0.4); }
    .event-chip--default  { color: #b0d8f0; border-color: rgba(80,180,255,0.2); }
    .tick-badge {
      font-size: 10px;
      color: rgba(80,180,255,0.4);
      flex-shrink: 0;
      font-variant-numeric: tabular-nums;
    }
    .feed-empty {
      font-style: italic;
      color: rgba(80,180,255,0.3);
    }
  `],
})
export class EventFeedComponent {
  private readonly eventsStore = inject(EventsStore);

  readonly events = this.eventsStore.recentEvents;
  readonly tick = this.eventsStore.latestTick;

  evtKey(e: PublicEvent): string {
    return `${e.tick}:${e.type}:${e.parties[0] ?? ''}`;
  }

  evtClass(e: PublicEvent): string {
    switch (e.type) {
      case 'WarDeclared':       return 'war';
      case 'TreatySigned':
      case 'AllianceFormed':
      case 'TreatyBroken':      return 'treaty';
      case 'BattleResolved':    return 'battle';
      case 'SystemCaptured':    return 'capture';
      case 'RouteEstablished':
      case 'RouteRaided':       return 'route';
      case 'VictoryAchieved':   return 'victory';
      case 'FactionEliminated': return 'elim';
      default:                  return 'default';
    }
  }

  evtLabel(e: PublicEvent): string {
    const p0 = e.parties[0] ?? '?';
    const p1 = e.parties[1];
    switch (e.type) {
      case 'WarDeclared':       return `WAR: ${p0}→${p1 ?? '?'}`;
      case 'TreatySigned':      return `TREATY: ${p0}+${p1 ?? '?'}`;
      case 'TreatyBroken':      return `BROKEN: ${p0}`;
      case 'AllianceFormed':    return `ALLY: ${p0}+${p1 ?? '?'}`;
      case 'SystemCaptured':    return `CAPTURED: ${e.systemId ?? '?'}`;
      case 'BattleResolved':    return `BATTLE: ${e.systemId ?? '?'}`;
      case 'RouteEstablished':  return `ROUTE: ${p0}`;
      case 'RouteRaided':       return `RAIDED: ${e.systemId ?? '?'}`;
      case 'FactionEliminated': return `ELIM: ${p0}`;
      case 'VictoryAchieved':   return `VICTORY: ${p0}`;
      default:                  return e.type;
    }
  }

  evtTitle(e: PublicEvent): string {
    return `T${e.tick} ${e.type} — parties: ${e.parties.join(', ')}${e.systemId ? ` @ ${e.systemId}` : ''}`;
  }
}
