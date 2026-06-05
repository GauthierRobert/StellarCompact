import { describe, it, expect, beforeEach } from 'vitest';
import { ViewSettingsStore } from './view-settings.store';

describe('ViewSettingsStore', () => {
  let store: ViewSettingsStore;
  beforeEach(() => {
    store = new ViewSettingsStore();
  });

  it('defaults to fog-of-war on with all layers visible', () => {
    expect(store.fogOfWar()).toBe(true);
    expect(store.showRoutes()).toBe(true);
    expect(store.showTerritories()).toBe(true);
    expect(store.showConflict()).toBe(true);
    expect(store.showLabels()).toBe(true);
  });

  it('toggles fog-of-war (the debug visibility switch)', () => {
    store.toggleFogOfWar();
    expect(store.fogOfWar()).toBe(false);
    store.setFogOfWar(true);
    expect(store.fogOfWar()).toBe(true);
  });

  it('toggles each layer independently', () => {
    store.toggleRoutes();
    store.toggleTerritories();
    expect(store.showRoutes()).toBe(false);
    expect(store.showTerritories()).toBe(false);
    expect(store.showConflict()).toBe(true);
  });
});
