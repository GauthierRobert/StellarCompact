import { TestBed } from '@angular/core/testing';
import { provideZonelessChangeDetection } from '@angular/core';
import { FactionStore, FactionSnapshot, TickFactionPayload } from './faction.store';

function makeFaction(id: string, systemCount = 0, eliminated = false): FactionSnapshot {
  return {
    factionId: id,
    name: `Faction ${id}`,
    colour: '#ffffff',
    resources: { credits: 100, minerals: 50, influence: 10 },
    reputation: 0.5,
    systemCount,
    eliminated,
  };
}

describe('FactionStore', () => {
  let store: FactionStore;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideZonelessChangeDetection(), FactionStore],
    });
    store = TestBed.inject(FactionStore);
  });

  it('starts empty', () => {
    expect(store.factions()).toEqual([]);
    expect(store.tick()).toBe(0);
  });

  it('applyTick populates factions and updates tick', () => {
    const payload: TickFactionPayload = {
      tick: 5,
      factions: [makeFaction('A', 3), makeFaction('B', 7)],
    };
    store.applyTick(payload);
    expect(store.tick()).toBe(5);
    expect(store.factions().length).toBe(2);
  });

  it('factions() is sorted by systemCount descending', () => {
    store.applyTick({
      tick: 1,
      factions: [makeFaction('A', 2), makeFaction('B', 10), makeFaction('C', 5)],
    });
    const ids = store.factions().map((f) => f.factionId);
    expect(ids).toEqual(['B', 'C', 'A']);
  });

  it('activeFactions excludes eliminated factions', () => {
    store.applyTick({
      tick: 2,
      factions: [makeFaction('A', 3, false), makeFaction('B', 0, true)],
    });
    expect(store.activeFactions().length).toBe(1);
    expect(store.activeFactions()[0].factionId).toBe('A');
  });

  it('eliminatedFactions contains only eliminated factions', () => {
    store.applyTick({
      tick: 3,
      factions: [makeFaction('A', 3, false), makeFaction('B', 0, true)],
    });
    expect(store.eliminatedFactions().length).toBe(1);
    expect(store.eliminatedFactions()[0].factionId).toBe('B');
  });

  it('totalSystems sums active faction system counts', () => {
    store.applyTick({
      tick: 4,
      factions: [makeFaction('A', 3), makeFaction('B', 7), makeFaction('C', 0, true)],
    });
    expect(store.totalSystems()).toBe(10);
  });

  it('getById returns the correct snapshot', () => {
    store.applyTick({ tick: 1, factions: [makeFaction('X', 5)] });
    const f = store.getById('X');
    expect(f).toBeDefined();
    expect(f!.systemCount).toBe(5);
  });

  it('getById returns undefined for unknown id', () => {
    expect(store.getById('UNKNOWN')).toBeUndefined();
  });

  it('patchFaction merges partial update', () => {
    store.applyTick({ tick: 1, factions: [makeFaction('A', 5)] });
    store.patchFaction({ factionId: 'A', systemCount: 8 });
    expect(store.getById('A')!.systemCount).toBe(8);
    // Other fields should be preserved
    expect(store.getById('A')!.name).toBe('Faction A');
  });

  it('patchFaction creates a new entry if faction was unknown', () => {
    store.patchFaction({ factionId: 'NEW', systemCount: 1 });
    expect(store.getById('NEW')!.systemCount).toBe(1);
  });

  it('applyTick replaces all previous faction data', () => {
    store.applyTick({ tick: 1, factions: [makeFaction('A'), makeFaction('B')] });
    store.applyTick({ tick: 2, factions: [makeFaction('C')] });
    expect(store.factions().length).toBe(1);
    expect(store.factions()[0].factionId).toBe('C');
  });

  it('reset clears state', () => {
    store.applyTick({ tick: 5, factions: [makeFaction('A')] });
    store.reset();
    expect(store.factions()).toEqual([]);
    expect(store.tick()).toBe(0);
  });

  it('computed signals recompute after applyTick', () => {
    store.applyTick({ tick: 1, factions: [makeFaction('A', 5), makeFaction('B', 3)] });
    expect(store.totalSystems()).toBe(8);
    store.applyTick({ tick: 2, factions: [makeFaction('A', 10)] });
    expect(store.totalSystems()).toBe(10);
  });
});
