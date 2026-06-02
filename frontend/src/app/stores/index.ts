/**
 * Public API for the signal stores.
 * Import from here rather than individual files so paths stay stable.
 */
export {
  CameraStore,
  expEase,
  CAMERA_BASE,
  CAMERA_ZMIN,
  CAMERA_ZMAX,
  GALAXY_R_MAX_DEFAULT,
} from './camera.store';
export type {
  CameraState,
  Vec2,
  WorldBbox,
  ViewportSize,
  ZoomAnchor,
} from './camera.store';

export { FactionStore } from './faction.store';
export type {
  FactionSnapshot,
  FactionResources,
  TickFactionPayload,
} from './faction.store';

export { EventsStore } from './events.store';
export type {
  PublicEvent,
  PublicEventType,
  TickEvent,
} from './events.store';

export { OverlayStore } from './overlay.store';
export type {
  SystemOverlay,
  RouteOverlay,
  OverlayDelta,
} from './overlay.store';

export { SelectionStore } from './selection.store';
export type {
  ScaleTier,
  SelectedSystemInfo,
  SelectedPlanetInfo,
} from './selection.store';
