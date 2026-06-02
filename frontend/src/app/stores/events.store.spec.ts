import { TestBed } from '@angular/core/testing';
import { provideZonelessChangeDetection } from '@angular/core';
import { EventsStore, PublicEvent, TickEvent } from './events.store';

function makeEvent(
  type: PublicEvent['type'],
  tick: number,
  parties: string[] = ['F1'],
  systemId?: string,
): PublicEvent {
  return { type, tick, parties, systemId };
}

describe('EventsStore', () => {
  let store: EventsStore;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideZonelessChangeDetection(), EventsStore],
    });
    store = TestBed.inject(EventsStore);
  });

  it('starts empty', () => {
    expect(store.events()).toEqual([]);
    expect(store.latestTick()).toBeNull();
  });

  it('appendEvents adds events', () => {
    store.appendEvents([makeEvent('WarDeclared', 1), makeEvent('TreatySigned', 2)]);
    expect(store.events().length).toBe(2);
  });

  it('appendEvents sorts by tick ascending', () => {
    store.appendEvents([makeEvent('WarDeclared', 5), makeEvent('BattleResolved', 2)]);
    expect(store.events()[0].tick).toBe(2);
    expect(store.events()[1].tick).toBe(5);
  });

  it('appendEvents deduplicates identical events', () => {
    const e = makeEvent('WarDeclared', 1);
    store.appendEvents([e]);
    store.appendEvents([e]);
    expect(store.events().length).toBe(1);
  });

  it('appendEvents handles empty array gracefully', () => {
    store.appendEvents([]);
    expect(store.events().length).toBe(0);
  });

  it('recentEvents returns newest first, max 20', () => {
    const events: PublicEvent[] = [];
    for (let i = 1; i <= 25; i++) {
      events.push(makeEvent('BattleResolved', i));
    }
    store.appendEvents(events);
    const recent = store.recentEvents();
    expect(recent.length).toBe(20);
    // Newest first
    expect(recent[0].tick).toBe(25);
    expect(recent[19].tick).toBe(6);
  });

  it('eventsForFaction filters by party', () => {
    store.appendEvents([
      makeEvent('WarDeclared', 1, ['F1', 'F2']),
      makeEvent('BattleResolved', 2, ['F2', 'F3']),
    ]);
    const forF1 = store.eventsForFaction('F1');
    expect(forF1.length).toBe(1);
    expect(forF1[0].type).toBe('WarDeclared');
  });

  it('eventsOfType filters by type', () => {
    store.appendEvents([
      makeEvent('WarDeclared', 1),
      makeEvent('TreatySigned', 2),
      makeEvent('WarDeclared', 3),
    ]);
    expect(store.eventsOfType('WarDeclared').length).toBe(2);
    expect(store.eventsOfType('TreatySigned').length).toBe(1);
  });

  it('eventCounts tracks counts per type', () => {
    store.appendEvents([
      makeEvent('WarDeclared', 1),
      makeEvent('WarDeclared', 2),
      makeEvent('TreatySigned', 3),
    ]);
    const counts = store.eventCounts();
    expect(counts['WarDeclared']).toBe(2);
    expect(counts['TreatySigned']).toBe(1);
  });

  it('applyTick updates latestTick', () => {
    const tick: TickEvent = { tick: 7, phase: 'RESOLUTION', startedAt: '2026-01-01T00:00:00Z' };
    store.applyTick(tick);
    expect(store.latestTick()!.tick).toBe(7);
  });

  it('reset clears all events and tick', () => {
    store.appendEvents([makeEvent('WarDeclared', 1)]);
    store.applyTick({ tick: 1, phase: 'RESOLUTION', startedAt: '' });
    store.reset();
    expect(store.events()).toEqual([]);
    expect(store.latestTick()).toBeNull();
  });

  it('eventCounts computed recomputes after new events', () => {
    store.appendEvents([makeEvent('WarDeclared', 1)]);
    expect(store.eventCounts()['WarDeclared']).toBe(1);
    store.appendEvents([makeEvent('WarDeclared', 2)]);
    expect(store.eventCounts()['WarDeclared']).toBe(2);
  });

  it('all ten public event types are accepted without error', () => {
    const types: PublicEvent['type'][] = [
      'WarDeclared', 'TreatySigned', 'TreatyBroken', 'AllianceFormed',
      'SystemCaptured', 'BattleResolved', 'RouteEstablished', 'RouteRaided',
      'FactionEliminated', 'VictoryAchieved',
    ];
    const events = types.map((t, i) => makeEvent(t, i + 1));
    store.appendEvents(events);
    expect(store.events().length).toBe(10);
  });
});
