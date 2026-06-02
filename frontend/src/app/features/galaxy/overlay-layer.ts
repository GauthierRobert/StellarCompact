import type { RouteOverlay, SystemOverlay } from '../../stores';
import type { RenderOverlayMark, RenderRoute, RenderStar } from './render-model';

/**
 * Active-overlay compositing layer (E8-06) — the CLIENT-SIDE join between the
 * thin dynamic overlay (ownership tint, fleet/battle markers, live routes) and
 * the cacheable star tiles, keyed by system id.
 *
 * Architecture (docs/architecture/02-galaxy-scale.md §3,§5 + lod-tiling skill):
 *   - The heavy star tiles are immutable scenery, fetched/cached over HTTP/CDN.
 *     They are NEVER mutated or refetched when only the live overlay changes.
 *   - The overlay is a SEPARATE thin layer streamed over STOMP (E7-03) into the
 *     OverlayStore (E7-01) and resynced over REST (E6-03). It changes every tick.
 *   - This module joins the two: for each overlay system whose `systemId` matches
 *     a rendered star's `activeSystemId`, it emits a RenderOverlayMark anchored at
 *     that star's already-resolved WORLD position. The renderer then draws these
 *     in a dedicated compositing pass ON TOP of the instanced star field.
 *
 * Fog-correctness (the security crux, X-01): every mark/route is derived ONLY
 * from entries the OverlayStore already holds. The store is fed exclusively by
 * the server-authoritative, fog-filtered overlay stream/REST, so a system the
 * server did not disclose has no entry here and therefore no mark. We never
 * infer ownership, fleets, routes, or any hidden state on the client.
 *
 * Everything here is pure (no Angular, no I/O), so it is trivially unit-testable
 * and shared by both the WebGL2 and Canvas2D draw backends.
 */

/**
 * Index the rendered stars by their active system id (string form). Only
 * promoted (active) stars carry an id; pure scenery stars are skipped. Bounded
 * by the visible star count, never the catalog.
 */
export function indexStarsBySystemId(
  stars: readonly RenderStar[],
): Map<string, RenderStar> {
  const byId = new Map<string, RenderStar>();
  for (const s of stars) {
    if (s.activeSystemId !== null) {
      byId.set(String(s.activeSystemId), s);
    }
  }
  return byId;
}

/**
 * Resolve a faction colour hex string (e.g. "#4ad6a0") to a 0..1 RGB triplet.
 * Returns null for an unknown/blank colour so callers can fall back gracefully.
 */
export function hexToRgb01(
  hex: string | undefined | null,
): readonly [number, number, number] | null {
  if (!hex) {
    return null;
  }
  let h = hex.trim();
  if (h.startsWith('#')) {
    h = h.slice(1);
  }
  if (h.length === 3) {
    h = h[0] + h[0] + h[1] + h[1] + h[2] + h[2];
  }
  if (h.length !== 6 || /[^0-9a-fA-F]/.test(h)) {
    return null;
  }
  const r = parseInt(h.slice(0, 2), 16) / 255;
  const g = parseInt(h.slice(2, 4), 16) / 255;
  const b = parseInt(h.slice(4, 6), 16) / 255;
  return [r, g, b];
}

/** A lookup from faction id to its display colour hex (from the FactionStore). */
export type FactionColourLookup = (factionId: string) => string | undefined;

/**
 * Build the overlay marks by joining the (fog-filtered, server-authoritative)
 * overlay systems to the visible rendered stars by system id.
 *
 * - `systems`   : the OverlayStore's accumulated SystemOverlay entries.
 * - `starsById` : rendered stars indexed by `String(activeSystemId)`.
 * - `colourOf`  : resolves a faction id to its display colour (ownership tint).
 *
 * An overlay system with no matching rendered star in view is skipped (its star
 * is off-screen or its tile is not loaded yet) — bounded work, no invention.
 */
export function buildOverlayMarks(
  systems: readonly SystemOverlay[],
  starsById: ReadonlyMap<string, RenderStar>,
  colourOf: FactionColourLookup,
): RenderOverlayMark[] {
  if (systems.length === 0 || starsById.size === 0) {
    return [];
  }
  const out: RenderOverlayMark[] = [];
  for (const sys of systems) {
    const star = starsById.get(sys.systemId);
    if (!star) {
      // Star not in the loaded/visible tile set → nothing to composite onto.
      continue;
    }
    const tint =
      sys.ownerFactionId === null
        ? null
        : hexToRgb01(colourOf(sys.ownerFactionId));
    out.push({
      systemId: sys.systemId,
      x: star.x,
      y: star.y,
      tint,
      activity: sys.activityLevel,
      battle: sys.battle,
      blockaded: sys.blockaded,
    });
  }
  return out;
}

/**
 * Build the route segments by joining each active RouteOverlay's endpoints to
 * the rendered stars by system id. A route whose endpoints are not both in the
 * visible set is skipped (bounded work). Mirrors the prior in-component join so
 * the route + mark joins now share one fog-correct code path.
 */
export function buildOverlayRoutes(
  routes: readonly RouteOverlay[],
  starsById: ReadonlyMap<string, RenderStar>,
): RenderRoute[] {
  if (routes.length === 0 || starsById.size === 0) {
    return [];
  }
  const out: RenderRoute[] = [];
  for (const r of routes) {
    const a = starsById.get(r.fromSystemId);
    const b = starsById.get(r.toSystemId);
    if (!a || !b) {
      continue;
    }
    out.push({
      id: r.routeId,
      ax: a.x,
      ay: a.y,
      bx: b.x,
      by: b.y,
      kind: r.kind,
      len: Math.hypot(a.x - b.x, a.y - b.y),
    });
  }
  return out;
}
