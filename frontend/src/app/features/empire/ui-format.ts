/**
 * Shared presentation helpers for the empire command pages — number/biome/mission
 * formatting and the small metadata tables the pages render. Pure, no Angular.
 */

import type { TechField } from './tech-tree';

/** Compact number formatting, e.g. 12400 → "12.4k", 3_400_000 → "3.4M". */
export function fmtN(n: number): string {
  const abs = Math.abs(n);
  if (abs >= 1_000_000_000) return (n / 1_000_000_000).toFixed(1) + 'B';
  if (abs >= 1_000_000) return (n / 1_000_000).toFixed(1) + 'M';
  if (abs >= 1_000) return (n / 1_000).toFixed(1) + 'k';
  return String(Math.round(n));
}

/** Biome display metadata — label + accent colour for chips/cards. */
export interface BiomeMeta {
  readonly label: string;
  readonly colour: string;
  readonly glyph: string;
}
export const BIOME_META: Record<string, BiomeMeta> = {
  oceanic: { label: 'Oceanic', colour: '#4aa8e0', glyph: '🌊' },
  terran: { label: 'Terran', colour: '#5fd66f', glyph: '🌍' },
  arid: { label: 'Arid', colour: '#c9a45a', glyph: '🏜' },
  desert: { label: 'Desert', colour: '#e0c46a', glyph: '☀' },
  volcanic: { label: 'Volcanic', colour: '#e0683a', glyph: '🌋' },
  frozen: { label: 'Frozen', colour: '#9fd6e8', glyph: '❄' },
  toxic: { label: 'Toxic', colour: '#a0d040', glyph: '☣' },
  gas: { label: 'Gas Giant', colour: '#d2a0e0', glyph: '🪐' },
};
export function biomeMeta(key: string): BiomeMeta {
  return BIOME_META[key] ?? { label: key, colour: '#9fb2c8', glyph: '●' };
}

const SIZE_LABEL: Record<string, string> = {
  dwarf: 'Dwarf',
  small: 'Small',
  medium: 'Medium',
  large: 'Large',
  giant: 'Giant',
};
export function sizeLabel(size: string): string {
  return SIZE_LABEL[size] ?? size;
}

const MISSION_LABEL: Record<string, string> = {
  patrol: 'Patrol',
  invade: 'Invade',
  escort: 'Escort',
  explore: 'Explore',
  reinforce: 'Reinforce',
  garrison: 'Garrison',
};
export function missionLabel(m: string | undefined): string {
  return m ? (MISSION_LABEL[m] ?? m) : '—';
}

/** Research-field accent (matches the empire-rail field dots / CSS vars). */
export const FIELD_COLOUR: Record<TechField, string> = {
  physics: 'var(--sc-energy)',
  engineering: 'var(--sc-warn)',
  society: 'var(--sc-influence)',
};

/** Building-type glyphs for the planet slot grid. */
export const BUILDING_GLYPH: Record<string, string> = {
  capital: '★',
  mine: '⛏',
  solarArray: '☀',
  fusionPlant: '⚛',
  farm: '🌾',
  researchLab: '⚗',
  shipyard: '⚓',
  marketHub: '◈',
  defensePlatform: '🛡',
};
export function buildingGlyph(type: string): string {
  return BUILDING_GLYPH[type] ?? '▪';
}
