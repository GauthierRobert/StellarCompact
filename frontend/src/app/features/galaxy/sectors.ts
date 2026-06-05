/**
 * Sector grid — a coarse, named square partition laid over galaxy world space,
 * giving the map the "gridded, navigable" structure of a star atlas. Each cell
 * is addressable (stable id), evocatively named, and hit-testable so the player
 * can click a sector to open its summary.
 *
 * Pure + dependency-free (no Angular, no I/O). The world spans [-rMax, rMax] on
 * both axes; it is divided into {@link SECTOR_DIVISIONS}² cells. Column/row are
 * indexed from the bottom-left of the world box. Shared by the draw layers
 * (boundary + label overlay) and the galaxy component (hit-testing + summary).
 */

import type { RenderSector } from './render-model';

/** Number of sector cells across one galaxy axis (so DIVISIONS² total). */
export const SECTOR_DIVISIONS = 8;

/** Greek-letter column designators (stable, atlas-style). */
const COL_GLYPHS = ['Alpha', 'Beta', 'Gamma', 'Delta', 'Epsilon', 'Zeta', 'Eta', 'Theta'];
/** Evocative row stems. */
const ROW_STEMS = ['Reach', 'Expanse', 'Verge', 'Span', 'Marches', 'Drift', 'Frontier', 'Deep'];
/** Regional flavour words mixed into names by a stable hash of (col,row). */
const REGION_WORDS = [
  'Orion', 'Cygnus', 'Perseus', 'Lyra', 'Carina', 'Sagittarius', 'Vela', 'Norma',
  'Scutum', 'Centaurus', 'Crux', 'Aquila', 'Draco', 'Hydra', 'Phoenix', 'Corvus',
];

/** Cell side length in world units. */
export function sectorSize(rMax: number): number {
  return (2 * rMax) / SECTOR_DIVISIONS;
}

/** Clamp a world coordinate to a valid column/row index, or null if outside. */
export function sectorColRow(
  x: number,
  y: number,
  rMax: number,
): { col: number; row: number } | null {
  const size = sectorSize(rMax);
  const col = Math.floor((x + rMax) / size);
  const row = Math.floor((y + rMax) / size);
  if (col < 0 || col >= SECTOR_DIVISIONS || row < 0 || row >= SECTOR_DIVISIONS) {
    return null;
  }
  return { col, row };
}

/** Stable sector id, e.g. "S3.5". */
export function sectorId(col: number, row: number): string {
  return `S${col}.${row}`;
}

/** Sector centre + half-extent in world units. */
export function sectorBounds(
  col: number,
  row: number,
  rMax: number,
): { cx: number; cy: number; half: number } {
  const size = sectorSize(rMax);
  const half = size / 2;
  const cx = -rMax + col * size + half;
  const cy = -rMax + row * size + half;
  return { cx, cy, half };
}

/** Deterministic, evocative sector name from its grid position. */
export function sectorName(col: number, row: number): string {
  const region = REGION_WORDS[(col * 7 + row * 13) % REGION_WORDS.length];
  const stem = ROW_STEMS[row % ROW_STEMS.length];
  return `${region} ${stem}`;
}

/** Short atlas designation, e.g. "Gamma·5". */
export function sectorDesignation(col: number, row: number): string {
  return `${COL_GLYPHS[col % COL_GLYPHS.length]}·${row + 1}`;
}

/** A world-positioned node tagged with its controlling faction (for tinting). */
export interface SectorNode {
  readonly x: number;
  readonly y: number;
  /** Owner faction id, or null if unclaimed. */
  readonly ownerFactionId: string | null;
  /** Owner tint RGB 0..1, or null. */
  readonly tint: readonly [number, number, number] | null;
}

/**
 * Build the in-view sector cells. Cells overlapping the (padded) world bbox are
 * emitted; each cell's dominant-owner tint is the most-common owner among the
 * supplied nodes that fall inside it. Bounded by the visible cells + nodes.
 */
export function buildVisibleSectors(
  bbox: { minX: number; minY: number; maxX: number; maxY: number },
  rMax: number,
  nodes: readonly SectorNode[],
  selectedId: string | null,
  hoveredId: string | null,
): RenderSector[] {
  const size = sectorSize(rMax);
  // Column/row span covering the bbox, clamped to the grid.
  const c0 = Math.max(0, Math.floor((bbox.minX + rMax) / size));
  const c1 = Math.min(SECTOR_DIVISIONS - 1, Math.floor((bbox.maxX + rMax) / size));
  const r0 = Math.max(0, Math.floor((bbox.minY + rMax) / size));
  const r1 = Math.min(SECTOR_DIVISIONS - 1, Math.floor((bbox.maxY + rMax) / size));
  if (c1 < c0 || r1 < r0) {
    return [];
  }

  // Tally owners per cell from the nodes (one pass).
  const ownerTally = new Map<string, Map<string, number>>();
  const tintByOwner = new Map<string, readonly [number, number, number]>();
  for (const n of nodes) {
    if (n.ownerFactionId === null) {
      continue;
    }
    const cr = sectorColRow(n.x, n.y, rMax);
    if (!cr) {
      continue;
    }
    const key = sectorId(cr.col, cr.row);
    let tally = ownerTally.get(key);
    if (!tally) {
      tally = new Map();
      ownerTally.set(key, tally);
    }
    tally.set(n.ownerFactionId, (tally.get(n.ownerFactionId) ?? 0) + 1);
    if (n.tint) {
      tintByOwner.set(n.ownerFactionId, n.tint);
    }
  }

  const out: RenderSector[] = [];
  for (let row = r0; row <= r1; row++) {
    for (let col = c0; col <= c1; col++) {
      const id = sectorId(col, row);
      const { cx, cy, half } = sectorBounds(col, row, rMax);
      const tally = ownerTally.get(id);
      let tint: readonly [number, number, number] | null = null;
      if (tally) {
        let bestOwner: string | null = null;
        let best = 0;
        for (const [owner, count] of tally) {
          if (count > best) {
            best = count;
            bestOwner = owner;
          }
        }
        tint = bestOwner ? tintByOwner.get(bestOwner) ?? null : null;
      }
      out.push({
        id,
        name: sectorName(col, row),
        cx,
        cy,
        half,
        tint,
        hovered: id === hoveredId,
        selected: id === selectedId,
      });
    }
  }
  return out;
}
