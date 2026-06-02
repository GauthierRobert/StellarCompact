import { TestBed } from '@angular/core/testing';
import { provideZonelessChangeDetection } from '@angular/core';
import { OverlayStore, OverlayDelta, SystemOverlay, RouteOverlay } from './overlay.store';

function makeSystem(id: string, owner: string | null = null, tick = 1): SystemOverlay {
  return {
    systemId: id,
    ownerFactionId: owner,
    activityLevel: 0,
    blockaded: false,
    battle: false,
    asOfTick: tick,
  };
}

function makeRoute(id: string, owner: string, kind: RouteOverlay['kind'] = 'trade', tick = 1): RouteOverlay {
  return {
    routeId: id,
    fromSystemId: 'S1',
    toSystemId: 'S2',
    ownerFactionId: owner,
    kind,
    active: true,
    asOfTick: tick,
  };
}

describe('OverlayStore', () => {
  let store: OverlayStore;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideZonelessChangeDetection(), OverlayStore],
    });
    store = TestBed.inject(OverlayStore);
  });

  it('starts empty', () => {
    expect(store.allSystems()).toEqual([]);
    expect(store.allRoutes()).toEqual([]);
    expect(store.asOfTick()).toBe(0);
  });

  it('applyOverlayDelta adds new systems', () => {
    const delta: OverlayDelta = {
      changedSystems: [makeSystem('SYS-1', 'F1')],
      changedRoutes: [],
      asOfTick: 1,
    };
    store.applyOverlayDelta(delta);
    expect(store.allSystems().length).toBe(1);
    expect(store.allSystems()[0].systemId).toBe('SYS-1');
  });

  it('applyOverlayDelta updates asOfTick', () => {
    store.applyOverlayDelta({ changedSystems: [], changedRoutes: [], asOfTick: 5 });
    expect(store.asOfTick()).toBe(5);
  });

  it('applyOverlayDelta does not decrease asOfTick', () => {
    store.applyOverlayDelta({ changedSystems: [], changedRoutes: [], asOfTick: 10 });
    store.applyOverlayDelta({ changedSystems: [], changedRoutes: [], asOfTick: 3 });
    expect(store.asOfTick()).toBe(10);
  });

  it('applyOverlayDelta merges (does not replace) existing systems', () => {
    store.applyOverlayDelta({
      changedSystems: [makeSystem('A', 'F1'), makeSystem('B', 'F2')],
      changedRoutes: [],
      asOfTick: 1,
    });
    store.applyOverlayDelta({
      changedSystems: [makeSystem('A', 'F3', 2)],
      changedRoutes: [],
      asOfTick: 2,
    });
    expect(store.allSystems().length).toBe(2);
    expect(store.getSystem('A')!.ownerFactionId).toBe('F3');
    expect(store.getSystem('B')!.ownerFactionId).toBe('F2');
  });

  it('applyOverlayDelta adds and updates routes', () => {
    store.applyOverlayDelta({
      changedSystems: [],
      changedRoutes: [makeRoute('R1', 'F1')],
      asOfTick: 1,
    });
    expect(store.allRoutes().length).toBe(1);
    store.applyOverlayDelta({
      changedSystems: [],
      changedRoutes: [{ ...makeRoute('R1', 'F1', 'contested', 2), active: false }],
      asOfTick: 2,
    });
    expect(store.allRoutes().length).toBe(1);
    expect(store.getRoute('R1')!.active).toBe(false);
  });

  it('activeRoutes filters out inactive routes', () => {
    store.applyOverlayDelta({
      changedSystems: [],
      changedRoutes: [
        makeRoute('R1', 'F1'),
        { ...makeRoute('R2', 'F2'), active: false },
      ],
      asOfTick: 1,
    });
    expect(store.activeRoutes().length).toBe(1);
    expect(store.activeRoutes()[0].routeId).toBe('R1');
  });

  it('battleSystems returns systems with battle=true', () => {
    store.applyOverlayDelta({
      changedSystems: [
        { ...makeSystem('A'), battle: true },
        makeSystem('B'),
      ],
      changedRoutes: [],
      asOfTick: 1,
    });
    expect(store.battleSystems().length).toBe(1);
    expect(store.battleSystems()[0].systemId).toBe('A');
  });

  it('blockadedSystems returns systems with blockaded=true', () => {
    store.applyOverlayDelta({
      changedSystems: [
        { ...makeSystem('X'), blockaded: true },
        makeSystem('Y'),
      ],
      changedRoutes: [],
      asOfTick: 1,
    });
    expect(store.blockadedSystems().length).toBe(1);
  });

  it('systemsOwnedBy filters by owner', () => {
    store.applyOverlayDelta({
      changedSystems: [makeSystem('A', 'F1'), makeSystem('B', 'F2'), makeSystem('C', 'F1')],
      changedRoutes: [],
      asOfTick: 1,
    });
    expect(store.systemsOwnedBy('F1').length).toBe(2);
    expect(store.systemsOwnedBy('F2').length).toBe(1);
  });

  it('getSystem returns undefined for unknown id', () => {
    expect(store.getSystem('NOPE')).toBeUndefined();
  });

  it('replaceAll replaces entire overlay', () => {
    store.applyOverlayDelta({
      changedSystems: [makeSystem('OLD', 'F1')],
      changedRoutes: [],
      asOfTick: 1,
    });
    store.replaceAll([makeSystem('NEW', 'F2')], [], 5);
    expect(store.allSystems().length).toBe(1);
    expect(store.getSystem('OLD')).toBeUndefined();
    expect(store.getSystem('NEW')!.ownerFactionId).toBe('F2');
    expect(store.asOfTick()).toBe(5);
  });

  it('reset clears all state', () => {
    store.applyOverlayDelta({
      changedSystems: [makeSystem('A')],
      changedRoutes: [makeRoute('R1', 'F1')],
      asOfTick: 3,
    });
    store.reset();
    expect(store.allSystems()).toEqual([]);
    expect(store.allRoutes()).toEqual([]);
    expect(store.asOfTick()).toBe(0);
  });

  it('computed signals recompute after applyOverlayDelta', () => {
    store.applyOverlayDelta({
      changedSystems: [makeSystem('A', 'F1'), makeSystem('B', 'F1')],
      changedRoutes: [],
      asOfTick: 1,
    });
    expect(store.systemsOwnedBy('F1').length).toBe(2);
    store.applyOverlayDelta({
      changedSystems: [makeSystem('A', 'F2', 2)],
      changedRoutes: [],
      asOfTick: 2,
    });
    expect(store.systemsOwnedBy('F1').length).toBe(1);
    expect(store.systemsOwnedBy('F2').length).toBe(1);
  });
});
