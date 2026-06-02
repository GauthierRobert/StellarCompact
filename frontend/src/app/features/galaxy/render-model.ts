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
}

/** A density impostor from an aggregate (coarse-zoom) tile. */
export interface RenderAggregate {
  readonly x: number;
  readonly y: number;
  /** Relative density weight, drives glow intensity. */
  readonly weight: number;
}

/** Region colour/density summary for an aggregate tile. */
export interface RenderColorStats {
  readonly avgDensity: number;
  readonly peakDensity: number;
  readonly sampleCount: number;
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
