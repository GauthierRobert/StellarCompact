import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { EventsStore, type PublicEvent } from '../../stores';
import { FactionStore } from '../../stores';

@Component({
  selector: 'app-event-ticker',
  changeDetection: ChangeDetectionStrategy.OnPush,
  standalone: true,
  imports: [CommonModule],
  template: `
    <div class="ticker-bar">
      <div class="ticker-label">
        @if (latestTick()) { <span class="sc-live-dot"></span> }
        <span class="label-text">Events</span>
      </div>
      <div class="ticker-scroll sc-scroll">
        @for (evt of events(); track evtKey(evt)) {
          <span class="event-chip" [class]="'ec-' + evtClass(evt)" [title]="evtTitle(evt)">
            <span class="chip-type">{{ evtTypeLabel(evt) }}</span>
            <span class="chip-detail">{{ evtDetail(evt) }}</span>
            <span class="chip-tick">T{{ evt.tick }}</span>
          </span>
        }
        @empty {
          <span class="no-events">No events yet</span>
        }
      </div>
      @if (latestTick(); as t) {
        <span class="tick-badge">T{{ t.tick }}</span>
      }
    </div>
  `,
  styles: [`
    :host { display: block; }
    .ticker-bar {
      display: flex; align-items: center; gap: 8px;
      padding: 5px 14px; height: 36px;
      background: var(--sc-panel);
      border-top: 1px solid var(--sc-border);
      overflow: hidden;
    }
    .ticker-label { display: flex; align-items: center; gap: 5px; flex-shrink: 0; }
    .label-text { font: 400 9px/1 var(--din-body); letter-spacing: 1.6px; text-transform: uppercase; color: var(--sc-text-faint); }
    .ticker-scroll { display: flex; align-items: center; gap: 6px; overflow-x: auto; flex: 1; scrollbar-width: none; }
    .ticker-scroll::-webkit-scrollbar { display: none; }
    .event-chip {
      display: inline-flex; align-items: center; gap: 4px;
      white-space: nowrap; padding: 2px 8px; border-radius: var(--rounded-pill);
      font: 600 10px/1 var(--sc-mono); border: 1px solid transparent;
      flex-shrink: 0; cursor: default;
    }
    .chip-type { font-size: 9px; letter-spacing: 1px; text-transform: uppercase; opacity: 0.8; }
    .chip-detail { font-size: 10px; }
    .chip-tick { font-size: 8.5px; opacity: 0.55; }
    .ec-war { color: var(--sc-bad); border-color: rgba(255,84,104,0.35); background: rgba(255,84,104,0.07); }
    .ec-treaty { color: var(--sc-good); border-color: rgba(95,214,164,0.35); background: rgba(95,214,164,0.07); }
    .ec-battle { color: var(--sc-warn); border-color: rgba(232,192,97,0.35); background: rgba(232,192,97,0.07); }
    .ec-capture { color: var(--sc-energy); border-color: rgba(127,216,239,0.35); background: rgba(127,216,239,0.07); }
    .ec-route { color: var(--sc-text-dim); border-color: var(--sc-border); }
    .ec-victory { color: var(--sc-warn); border-color: rgba(232,192,97,0.4); background: rgba(232,192,97,0.08); }
    .ec-elim { color: var(--sc-influence); border-color: rgba(232,154,196,0.35); background: rgba(232,154,196,0.07); }
    .ec-default { color: var(--sc-text-dim); border-color: var(--sc-border); }
    .tick-badge { font: 600 10px/1 var(--sc-mono); font-variant-numeric: tabular-nums; color: var(--sc-text-faint); flex-shrink: 0; }
    .no-events { font: italic 10px var(--din-body); color: var(--sc-text-faint); }
  `],
})
export class EventTickerComponent {
  private readonly eventsStore = inject(EventsStore);
  private readonly factionStore = inject(FactionStore);

  protected readonly events = this.eventsStore.recentEvents;
  protected readonly latestTick = this.eventsStore.latestTick;

  protected evtKey(e: PublicEvent): string {
    // Must match EventsStore's dedup key (incl. systemId) so two distinct events
    // at the same tick/faction (e.g. battles at different systems) get unique
    // @for keys and don't trip NG0955.
    return e.tick + ':' + e.type + ':' + (e.parties[0] ?? '') + ':' + (e.systemId ?? '');
  }

  protected evtClass(e: PublicEvent): string {
    switch (e.type) {
      case 'WarDeclared':                     return 'war';
      case 'TreatySigned':
      case 'AllianceFormed':
      case 'TreatyBroken':                    return 'treaty';
      case 'BattleResolved':                  return 'battle';
      case 'SystemCaptured':                  return 'capture';
      case 'RouteEstablished':
      case 'RouteRaided':                     return 'route';
      case 'VictoryAchieved':                 return 'victory';
      case 'FactionEliminated':               return 'elim';
      default:                                return 'default';
    }
  }

  protected evtTypeLabel(e: PublicEvent): string {
    switch (e.type) {
      case 'WarDeclared':       return 'WAR';
      case 'TreatySigned':      return 'TREATY';
      case 'TreatyBroken':      return 'BROKEN';
      case 'AllianceFormed':    return 'ALLIANCE';
      case 'SystemCaptured':    return 'CAPTURED';
      case 'BattleResolved':    return 'BATTLE';
      case 'RouteEstablished':  return 'ROUTE';
      case 'RouteRaided':       return 'RAIDED';
      case 'FactionEliminated': return 'ELIMINATED';
      case 'VictoryAchieved':   return 'VICTORY';
      default:                  return (e.type as string).toUpperCase();
    }
  }

  protected evtDetail(e: PublicEvent): string {
    const name0 = this.resolveName(e.parties[0]);
    const name1 = e.parties[1] ? this.resolveName(e.parties[1]) : null;
    switch (e.type) {
      case 'WarDeclared':       return name0 + (name1 ? ' vs ' + name1 : '');
      case 'TreatySigned':
      case 'AllianceFormed':    return name0 + (name1 ? ' + ' + name1 : '');
      case 'TreatyBroken':      return name0;
      case 'SystemCaptured':    return (e.systemId ?? '?') + ' by ' + name0;
      case 'BattleResolved':    return (e.systemId ?? '?');
      case 'RouteEstablished':  return name0;
      case 'RouteRaided':       return (e.systemId ?? '?');
      case 'FactionEliminated': return name0;
      case 'VictoryAchieved':   return name0;
      default:                  return name0;
    }
  }

  protected evtTitle(e: PublicEvent): string {
    return 'T' + e.tick + ' ' + e.type + ' — ' + e.parties.join(', ') + (e.systemId ? ' @ ' + e.systemId : '');
  }

  private resolveName(id: string | undefined): string {
    if (!id) return '?';
    return this.factionStore.getById(id)?.name ?? id;
  }
}
