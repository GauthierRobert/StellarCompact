/**
 * Galaxy render-input model — the decoupled draw contract.
 *
 * This module defines the *shape* of everything the renderer draws, independent
 * of HOW it is drawn. The Canvas2D draw layer (E7-02) and the future WebGL2 draw
 * layer (E8) both consume exactly these arrays, so swapping the draw backend
 * never touches the data-mapping, tile-fetch, or camera-wiring code.
 *
 * The tile service produces `RenderStar`/`RenderAggregate` from the E6-03 tile
 * payloads; the overlay store produces `RenderRoute`; the component assembles a
 * `RenderScene` each frame and hands it to a `GalaxyDrawLayer`.
 *
 * Coordinates here are always *world* (galaxy units). The draw layer is given a
 * world to screen transform so it never assumes a coordinate system.
 */

// ---------------------------------------------------------------------------
// Spectral palette — mirrored from the catalog generator (SpectralClass.java).
// ---------------------------------------------------------------------------

/**
 * Morgan-Keenan classes, hottest to coolest, matching the backend enum order
 * (O,B,A,F,G,K,M) and the PoC palette indices. Index = colour-class index.
 */
export const SPECTRAL_ORDER = ['O', 'B', 'A', 'F', 'G', 'K', 'M'] as const;
export type SpectralClassName = (typeof SPECTRAL_ORDER)[number];

/**
 * RGB triplets per spectral class — byte-identical to the PoC `GALAXY.classes`
 * palette so the Angular renderer reproduces the PoC look exactly.
 */
export const SPECTRAL_PALETTE: readonly (readonly [number, number, number])[] = [
  [155, 176, 255], // O blue
  [170, 191, 255], // B blue-white
  [202, 215, 255], // A white
  [248, 247, 255], // F yellow-white
  [255, 244, 234], // G yellow (Sol)
  [255, 210, 161], // K orange
  [255, 170, 120], // M red
];

/** Map a backend spectral class name to a palette index (defaults to G). */
export function spectralIndex(spectral: string): number {
  const i = SPECTRAL_ORDER.indexOf(spectral as SpectralClassName);
  return i < 0 ? 4 : i;
}

// ---------------------------------------------------------------------------
// Brightness / size normalisation
// ---------------------------------------------------------------------------

/**
 * The backend StarDto carries raw luminosity (Sol G ~ 1, O up to ~30) and raw
 * radius (Sol G ~ 1, O up to ~10). The PoC's renderer worked in a perceptual
 * 0..1 `b` and a ~0.3..1.1 `sz`. These map raw to perceptual so the draw math
 * (which is ported verbatim from the PoC) stays valid.
 */
export function normalizeBrightness(rawLuminosity: number): number {
  // log compression: dim M dwarfs ~0.1, Sol ~0.45, bright O ~1.0
  const v = Math.log10(Math.max(rawLuminosity, 0.01) + 1) / Math.log10(31);
  return v < 0 ? 0 : v > 1 ? 1 : v;
}

export function normalizeSize(rawRadius: number): number {
  // compress radius into the PoC's ~0.3..1.4 band
  const v =
    0.3 + 0.9 * (Math.log10(Math.max(rawRadius, 0.05) + 1) / Math.log10(11));
  return v;
}

// ---------------------------------------------------------------------------
// Render-input records
// ---------------------------------------------------------------------------

/** A single star to draw (world coords + perceptual draw attributes). */
export interface RenderStar {
  readonly id: number;
  readonly x: number;
  readonly y: number;
  /** Spectral palette index (0=O .. 6=M). */
  readonly k: number;
  /** Perceptual brightness 0..1 (drives glow alpha + spike threshold). */
  readonly b: number;
  /** Perceptual size ~0.3..1.4 (drives point radius). */
  readonly sz: number;
  /** Giant flag: 1 if a luminous giant (longer diffraction spikes), else 0. */
  readonly g: number;
  /** Live system id if promoted (activeSystemId), else null (procedural scenery). */
  readonly activeSystemId: number | null;
  /**
   * LOD cross-fade weight in [0,1]. 1 = fully shown; <1 during a zoom-boundary
   * transition while this star's tile-level fades in/out. Defaults to 1 (no
   * fade) so all pre-E8-05 callers are unaffected. The draw layer multiplies
   * brightness/alpha by this so the two blended levels never pop.
   */
  readonly a?: number;
}

/** A density impostor from an aggregate (coarse-zoom) tile. */
export interface RenderAggregate {
  readonly x: number;
  readonly y: number;
  /** Relative density weight, drives glow intensity. */
  readonly weight: number;
  /**
   * LOD cross-fade weight in [0,1] (see RenderStar.a). Defaults to 1. Lets the
   * coarse aggregate layer fade out as the fine star layer fades in across a
   * zoom boundary, so the galaxy glow dissolves into resolved stars seamlessly.
   */
  readonly a?: number;
}

/** Region colour/density summary for an aggregate tile. */
export interface RenderColorStats {
  readonly avgDensity: number;
  readonly peakDensity: number;
  readonly sampleCount: number;
}

/**
 * A single active-overlay mark to composite ON TOP of a rendered star (E8-06).
 *
 * Built by joining an `OverlayStore` `SystemOverlay` to the rendered star that
 * carries the matching `activeSystemId` (join-by-system-id), so the mark always
 * sits at the star's *world* position. The renderer draws these in a separate
 * compositing pass over the instanced star field — the heavy star tiles never
 * carry this live state (they stay CDN-cacheable; see lod-tiling skill).
 *
 * Fog-correctness: a mark only exists when the overlay store (already
 * server-authoritative + fog-filtered) contains the system. The client never
 * infers ownership/fleets for systems the server did not disclose.
 */
export interface RenderOverlayMark {
  /** Joined active system id (string form, matches SystemOverlay.systemId). */
  readonly systemId: string;
  /** World position (copied from the joined star) — never recomputed. */
  readonly x: number;
  readonly y: number;
  /** Ownership tint RGB 0..1 from the controlling faction, or null if unclaimed. */
  readonly tint: readonly [number, number, number] | null;
  /** Activity level 0..2 (scales the marker glow). */
  readonly activity: number;
  /** Live fleet/battle marker present at this system. */
  readonly battle: boolean;
  /** Under blockade (drawn as a ring marker). */
  readonly blockaded: boolean;
}

/**
 * An empire influence field (E-immersive). One per faction with visible owned
 * systems: a tint plus the world positions of that faction's controlled systems.
 * The renderer sums soft additive gaussians at each node to paint a glowing
 * territory region — the "this space belongs to X" read at galaxy/region zoom,
 * without per-pixel borders. Bounded by the visible owned systems, never the
 * catalog. Fog-correct: built only from systems present in the overlay store.
 */
export interface RenderTerritory {
  readonly factionId: string;
  /** Ownership tint RGB 0..1. */
  readonly tint: readonly [number, number, number];
  /** World positions of this faction's controlled, in-view systems. */
  readonly nodes: readonly { readonly x: number; readonly y: number }[];
}

/** A single vision-reveal disk for the fog-of-war veil (world units). */
export interface RenderFogReveal {
  readonly x: number;
  readonly y: number;
  /** Vision radius in world units. */
  readonly r: number;
}

/**
 * Fog-of-war veil description. When `enabled`, the renderer darkens unexplored
 * space and clears it only inside the union of `reveals` (a soft disk around
 * every explored/monitored system, plus the camera's local surroundings). When
 * disabled (debug toggle), the whole galaxy is shown. The reveal set is bounded
 * by the visible known systems; nothing hidden is implied — the fog is a visual
 * veil over data the server already fog-filtered.
 */
export interface RenderFog {
  readonly enabled: boolean;
  readonly reveals: readonly RenderFogReveal[];
}

/**
 * The closed set of discrete deep-space objects the renderer can draw. These are
 * the "other interstellar objects" beyond stars: luminous gas clouds, compact
 * remnants and exotic phenomena. Each kind has a bespoke procedural look in the
 * draw layers (NASA-photo inspired) and a bespoke detail panel in the HUD.
 */
export type InterstellarKind =
  | 'nebula' // emission/reflection gas cloud (Orion/Carina look)
  | 'blackhole' // accretion disk + gravitational-lensing photon ring
  | 'pulsar' // neutron star with sweeping twin beams
  | 'wormhole' // swirling spacetime aperture (traversable anomaly)
  | 'asteroidField' // scattered rocky belt
  | 'roguePlanet' // unbound sunless world drifting between stars
  | 'supernovaRemnant'; // expanding shock shell (Veil/Crab look)

/**
 * A discrete interstellar object to draw at a world position, independent of the
 * star field. Unlike the procedural nebula *shader* (a full-screen backdrop),
 * these are addressable, hit-testable entities: the player can click one to open
 * its detail panel, and each carries a stable id so selection/labels are stable
 * across frames. Bounded by the visible set, never the catalog.
 */
export interface RenderObject {
  /** Stable id (matches SelectedObjectInfo.objectId). */
  readonly id: string;
  readonly kind: InterstellarKind;
  /** World position of the object centre. */
  readonly x: number;
  readonly y: number;
  /** Visual extent in world units (drives the drawn radius + hit radius). */
  readonly r: number;
  /** Primary tint RGB 0..1 (gas glow / disk / beam colour). */
  readonly tint: readonly [number, number, number];
  /** Optional secondary tint RGB 0..1 (2nd cloud lobe / inner disk / halo). */
  readonly tint2?: readonly [number, number, number];
  /** Stable phase 0..2π so rotation/sweep animation is deterministic per object. */
  readonly phase: number;
  /** True when this object is the current selection (drawn with a focus ring). */
  readonly selected?: boolean;
  /** LOD cross-fade weight in [0,1] (see RenderStar.a). Defaults to 1. */
  readonly a?: number;
}

/**
 * A single addressable sector of the galaxy — one cell of a coarse square grid
 * laid over world space. Sectors give the map a navigable "Google-Maps gridded"
 * structure: each is named, hit-testable (click to open the sector summary), and
 * tinted by its dominant controlling faction. Drawn as a soft boundary + label
 * overlay, gated to region/galaxy zoom so it never clutters the system view.
 */
export interface RenderSector {
  /** Stable id "S{col}.{row}" (matches SelectedSectorInfo.sectorId). */
  readonly id: string;
  /** Evocative display name, e.g. "Orion Reach". */
  readonly name: string;
  /** Sector centre in world units. */
  readonly cx: number;
  readonly cy: number;
  /** Half-extent (world units) of the square sector from its centre. */
  readonly half: number;
  /** Dominant-owner tint RGB 0..1, or null when contested/empty. */
  readonly tint: readonly [number, number, number] | null;
  /** True when this sector is hovered (brighter boundary). */
  readonly hovered?: boolean;
  /** True when this sector is the current selection (filled highlight). */
  readonly selected?: boolean;
}

/**
 * Sector-grid overlay description. When `enabled`, the renderer draws the cell
 * boundaries + labels (zoom-gated) so the galaxy reads as named, clickable
 * sectors. Empty/disabled → no grid. Bounded by the in-view cells.
 */
export interface RenderSectors {
  readonly enabled: boolean;
  readonly cells: readonly RenderSector[];
}

/** A trade-lane overlay segment (world endpoints already resolved). */
export interface RenderRoute {
  readonly id: string;
  readonly ax: number;
  readonly ay: number;
  readonly bx: number;
  readonly by: number;
  readonly kind: 'allied' | 'trade' | 'contested';
  /** Lane length in world units (used for zoom-gated culling, PoC `len`). */
  readonly len: number;
}

/**
 * The complete frame input. The draw layer reads this plus a transform; it never
 * fetches data or reads stores. `stars` is empty at coarse zoom (use aggregates);
 * `aggregates` is empty at fine zoom (use stars). Either tier may be present
 * during a cross-fade.
 */
export interface RenderScene {
  readonly stars: readonly RenderStar[];
  readonly aggregates: readonly RenderAggregate[];
  readonly routes: readonly RenderRoute[];
  /**
   * Active-overlay marks (ownership tint / fleet+battle / blockade), one per
   * visible active system present in the overlay store, joined by system id and
   * composited ON TOP of the star field (E8-06). Empty when there is no live
   * overlay state in view. Defaults to `[]` for pre-E8-06 scene builders.
   */
  readonly overlayMarks?: readonly RenderOverlayMark[];
  /**
   * Empire influence fields, one per faction with visible owned systems. Drawn
   * UNDER the star field as soft additive territory glows so factions read as
   * coloured regions, not just per-star dots. Empty/undefined → no territories.
   */
  readonly territories?: readonly RenderTerritory[];
  /**
   * Discrete interstellar objects (nebulae, black holes, pulsars, wormholes,
   * asteroid fields, rogue planets, supernova remnants) to draw with/under the
   * star field but BEFORE the fog veil, so distant landmarks read against black
   * space and get veiled like scenery. Bounded by the visible set, never the
   * catalog. Empty/undefined → none. See {@link RenderObject}.
   */
  readonly objects?: readonly RenderObject[];
  /**
   * Named sector-grid overlay (navigational UI). When enabled, the renderer
   * draws the in-view cell boundaries + tinted fills as the LAST overlay (over
   * the fog veil) so the grid stays readable everywhere. Empty/undefined → no
   * grid. See {@link RenderSectors}.
   */
  readonly sectors?: RenderSectors;
  /**
   * Fog-of-war veil (E-immersive). When present and enabled, the renderer
   * darkens space outside the explored/monitored reveal disks. Undefined →
   * legacy behaviour (no veil). See {@link RenderFog}.
   */
  readonly fog?: RenderFog;
  /** Galaxy radius (world units) for the nebula glow + dust arms. */
  readonly rMax: number;
}

/** World to screen transform handed to the draw layer (device-pixel space). */
export interface ViewTransform {
  /** CSS pixels per world unit (the camera scale, NOT multiplied by dpr). */
  readonly scale: number;
  /** Device-pixel ratio (the draw layer multiplies as needed). */
  readonly dpr: number;
  /** Canvas backing-store size in device pixels. */
  readonly widthPx: number;
  readonly heightPx: number;
  /** World to screen device-pixel projection. */
  readonly w2s: (wx: number, wy: number) => { x: number; y: number };
  /**
   * Floating-origin world anchor (E8-07). The camera periodically re-bases this
   * to near the rendered camera position so that, at extreme zoom / large world
   * coordinates, the renderer can subtract it from world coords (in float64 on
   * the CPU) BEFORE they are uploaded to float32 GPU buffers — keeping rendered
   * magnitudes small and preserving float32 precision during "infinite" descent.
   *
   * It is mathematically transparent: the draw layer subtracts it from BOTH the
   * star positions and the recovered camera world, so `(world - origin) -
   * (camWorld - origin)` equals the original `world - camWorld`. The on-screen
   * result before and after a re-base is identical (the re-base is invisible).
   *
   * Defaults to 0 (no re-base) so all pre-E8-07 callers/tests are unaffected.
   */
  readonly originX?: number;
  readonly originY?: number;
}

/**
 * The draw-backend contract. Canvas2D implements it now; WebGL2 (E8) implements
 * the same interface and is swapped in without touching the component.
 */
export interface GalaxyDrawLayer {
  /** (Re)bind the backing canvas + size. Called on init and resize. */
  resize(widthPx: number, heightPx: number, dpr: number): void;
  /** Draw one frame from the scene + transform. Must be cheap + side-effect-free. */
  draw(scene: RenderScene, view: ViewTransform, timeSeconds: number): void;
  /** Release GPU/canvas resources. */
  dispose(): void;
}
