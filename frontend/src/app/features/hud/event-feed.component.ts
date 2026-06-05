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
      background: var(--sc-panel);
      border-top: 1px solid var(--sc-border);
      font-family: var(--din-body);
      font-size: 11px;
      color: var(--sc-text);
      overflow: hidden;
    }
    .feed-label {
      font: 400 9px/1 var(--din-body);
      text-transform: uppercase;
      letter-spacing: 1.6px;
      color: var(--sc-text-dim);
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
      padding: 2px 7px;
      border-radius: var(--rounded-xs);
      font: 700 9px/1 var(--din-body);
      letter-spacing: 0.8px;
      text-transform: uppercase;
      border: 1px solid transparent;
    }
    .event-chip--war      { color: var(--sc-bad);      border-color: rgba(255, 84, 104, 0.35); }
    .event-chip--treaty   { color: var(--sc-good);     border-color: rgba(95, 214, 164, 0.35); }
    .event-chip--battle   { color: var(--sc-warn);     border-color: rgba(232, 192, 97, 0.35); }
    .event-chip--capture  { color: var(--sc-minerals); border-color: rgba(183, 196, 210, 0.35); }
    .event-chip--route    { color: var(--sc-energy);   border-color: rgba(127, 216, 239, 0.35); }
    .event-chip--victory  { color: var(--sc-credits);  border-color: rgba(232, 192, 97, 0.45); }
    .event-chip--elim     { color: var(--sc-influence); border-color: rgba(232, 154, 196, 0.35); }
    .event-chip--default  { color: var(--sc-text-dim); border-color: var(--sc-border); }
    .tick-badge {
      font: 400 10px/1 var(--sc-mono);
      color: var(--sc-text-faint);
      flex-shrink: 0;
      font-variant-numeric: tabular-nums;
    }
    .feed-empty {
      font: 400 11px/1 var(--din-body);
      font-style: italic;
      color: var(--sc-text-faint);
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
