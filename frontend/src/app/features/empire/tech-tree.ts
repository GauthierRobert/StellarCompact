/**
 * Tiered technology tree — the research DAG.
 *
 * The defining rule (user intent): **advanced tech takes far more time than
 * simple tech**. Research time grows steeply by tier — a T0 root resolves in a
 * handful of ticks, while a Kardashev-ascension tech takes hundreds. The tree is
 * a directed acyclic graph (prereqs → unlocks) so Sovereigns must specialise;
 * the fifth branch, **Ascension**, is the Kardashev climb.
 *
 * This module holds the *static* definitions (shape + base times/costs/prereqs).
 * Per-faction runtime state (status/progress/eta) lives in the empire store as
 * {@link TechNodeState} and is joined to a definition for display.
 *
 * Numbers here are the frontend reference; the engine mirrors them in balance
 * config (BOARD E12-07). See docs/game-design/06-technology.md §6.
 */

export type TechBranch =
  | 'economy'
  | 'expansion'
  | 'military'
  | 'statecraft'
  | 'ascension';

/** Tiers, low to high. Time grows steeply across them. */
export type TechTier = 'T0' | 'T1' | 'T2' | 'T3' | 'K1' | 'K2' | 'K3';

/** Research field — drives the accent colour, mirrors the engine fields. */
export type TechField = 'physics' | 'engineering' | 'society';

/**
 * Reference research time (ticks) per tier — the "simple is fast, advanced is
 * slow" curve made explicit. A node may override with its own `timeTicks`.
 */
export const TIER_TIME: Record<TechTier, number> = {
  T0: 5,
  T1: 12,
  T2: 30,
  T3: 70,
  K1: 120,
  K2: 240,
  K3: 400,
};

/** Reference Tech cost per tier. */
export const TIER_COST: Record<TechTier, number> = {
  T0: 40,
  T1: 90,
  T2: 200,
  T3: 480,
  K1: 800,
  K2: 1700,
  K3: 3200,
};

export const TIER_LABEL: Record<TechTier, string> = {
  T0: 'Tier 0',
  T1: 'Tier 1',
  T2: 'Tier 2',
  T3: 'Tier 3',
  K1: 'Ascension I',
  K2: 'Ascension II',
  K3: 'Ascension III',
};

export const BRANCH_LABEL: Record<TechBranch, string> = {
  economy: 'Economy',
  expansion: 'Expansion',
  military: 'Military',
  statecraft: 'Statecraft',
  ascension: 'Ascension',
};

/** A static technology definition. */
export interface TechDef {
  readonly id: string;
  readonly name: string;
  readonly branch: TechBranch;
  readonly tier: TechTier;
  readonly field: TechField;
  /** Research time in ticks (defaults from the tier curve if omitted). */
  readonly timeTicks: number;
  /** Tech-resource cost (defaults from the tier curve if omitted). */
  readonly costTech: number;
  /** Prerequisite tech ids (all must be done before this is available). */
  readonly prereqs: readonly string[];
  /** What unlocking this enables (free text for the UI). */
  readonly unlocks: string;
  /** If set, this tech claims a Kardashev tier (1/2/3) — the ascension gates. */
  readonly kardashevGate?: 1 | 2 | 3;
  readonly blurb: string;
}

function t(def: Omit<TechDef, 'timeTicks' | 'costTech'> & Partial<Pick<TechDef, 'timeTicks' | 'costTech'>>): TechDef {
  return {
    ...def,
    timeTicks: def.timeTicks ?? TIER_TIME[def.tier],
    costTech: def.costTech ?? TIER_COST[def.tier],
  };
}

/**
 * The technology tree. Roughly forty nodes across five branches, with the
 * Ascension branch carrying the three Kardashev gates. Prereq edges keep the
 * slow, galaxy-shaking tech behind earned progress.
 */
export const TECH_TREE: readonly TechDef[] = [
  // --- Economy ---------------------------------------------------------------
  t({ id: 'improvedExtraction', name: 'Improved Extraction', branch: 'economy', tier: 'T0', field: 'engineering', prereqs: [], unlocks: '+Mineral/Energy yields', blurb: 'Better drills and refineries lift raw extraction.' }),
  t({ id: 'hydroponics', name: 'Hydroponics', branch: 'economy', tier: 'T0', field: 'society', prereqs: [], unlocks: '+Food, higher pop caps', blurb: 'Controlled agriculture feeds growing colonies.' }),
  t({ id: 'marketNetworks', name: 'Market Networks', branch: 'economy', tier: 'T1', field: 'society', prereqs: ['improvedExtraction'], unlocks: 'More routes & order-book throughput', blurb: 'Standardised exchanges thicken interstellar trade.' }),
  t({ id: 'automatedMining', name: 'Automated Mining', branch: 'economy', tier: 'T1', field: 'engineering', prereqs: ['improvedExtraction'], unlocks: 'Yield up, upkeep down', blurb: 'Autonomous rigs work seams no crew could reach.' }),
  t({ id: 'geneCrops', name: 'Gene-Optimised Crops', branch: 'economy', tier: 'T1', field: 'society', prereqs: ['hydroponics'], unlocks: '++Food & population', blurb: 'Tailored biology turns hostile soil fertile.' }),
  t({ id: 'galacticExchange', name: 'Galactic Exchange', branch: 'economy', tier: 'T2', field: 'society', prereqs: ['marketNetworks'], unlocks: 'Cross-region price visibility', blurb: 'A unified ledger spans the whole galaxy.' }),
  t({ id: 'fusionEconomy', name: 'Fusion Economy', branch: 'economy', tier: 'T2', field: 'physics', prereqs: ['automatedMining'], unlocks: '++Energy across all worlds', blurb: 'Commercial fusion ends scarcity of power.' }),
  t({ id: 'singularityReactors', name: 'Singularity Reactors', branch: 'economy', tier: 'T3', field: 'physics', prereqs: ['fusionEconomy'], unlocks: 'Micro black-hole power plants', blurb: 'Bottled singularities pour out energy.' }),

  // --- Expansion -------------------------------------------------------------
  t({ id: 'colonialLogistics', name: 'Colonial Logistics', branch: 'expansion', tier: 'T0', field: 'engineering', prereqs: [], unlocks: 'Cheaper, faster colonisation', blurb: 'Prefabbed habitats cut the cost of settling.' }),
  t({ id: 'longRangeDrives', name: 'Long-Range Drives', branch: 'expansion', tier: 'T1', field: 'physics', prereqs: ['colonialLogistics'], unlocks: 'Fleets cross longer lanes faster', blurb: 'Higher-impulse drives shrink the map.' }),
  t({ id: 'terraformingI', name: 'Terraforming I', branch: 'expansion', tier: 'T1', field: 'engineering', prereqs: ['colonialLogistics'], unlocks: 'Begin terraforming', blurb: 'Atmospheric processors nudge worlds habitable.' }),
  t({ id: 'hostileHabitats', name: 'Hostile-World Habitats', branch: 'expansion', tier: 'T2', field: 'engineering', prereqs: ['terraformingI'], unlocks: 'Colonise high-difficulty biomes', blurb: 'Sealed arcologies thrive where nothing should.' }),
  t({ id: 'terraformingII', name: 'Terraforming II', branch: 'expansion', tier: 'T2', field: 'engineering', prereqs: ['terraformingI'], unlocks: 'Faster terraforming', blurb: 'Orbital mirrors and seeded ecologies accelerate the work.' }),
  t({ id: 'terraformingIII', name: 'Terraforming III', branch: 'expansion', tier: 'T3', field: 'engineering', prereqs: ['terraformingII'], unlocks: 'Make Toxic worlds habitable', blurb: 'Even poisoned worlds can be remade into gardens.' }),
  t({ id: 'ecumenopolis', name: 'Ecumenopolis', branch: 'expansion', tier: 'T3', field: 'society', prereqs: ['geneCrops', 'terraformingII'], unlocks: 'City-world population tiers', blurb: 'A planet paved pole-to-pole — Trantor realised.' }),

  // --- Military --------------------------------------------------------------
  t({ id: 'corvetteDoctrine', name: 'Corvette Doctrine', branch: 'military', tier: 'T0', field: 'engineering', prereqs: [], unlocks: 'Build corvettes', blurb: 'Light hulls for scouting and skirmishing.' }),
  t({ id: 'targetingSystems', name: 'Targeting Systems', branch: 'military', tier: 'T1', field: 'physics', prereqs: ['corvetteDoctrine'], unlocks: '+Attack', blurb: 'Predictive fire-control sharpens every salvo.' }),
  t({ id: 'cruiserDoctrine', name: 'Cruiser Doctrine', branch: 'military', tier: 'T1', field: 'engineering', prereqs: ['corvetteDoctrine'], unlocks: 'Build cruisers', blurb: 'Line warships to hold a contested lane.' }),
  t({ id: 'shielding', name: 'Deflector Shielding', branch: 'military', tier: 'T2', field: 'physics', prereqs: ['targetingSystems'], unlocks: '+Defense', blurb: 'Energy screens shrug off kinetic and beam fire.' }),
  t({ id: 'capitalDoctrine', name: 'Capital Doctrine', branch: 'military', tier: 'T2', field: 'engineering', prereqs: ['cruiserDoctrine'], unlocks: 'Build capital ships', blurb: 'Fleet flagships that decide whole campaigns.' }),
  t({ id: 'lighthuggers', name: 'Lighthugger Hulls', branch: 'military', tier: 'T3', field: 'physics', prereqs: ['capitalDoctrine', 'longRangeDrives'], unlocks: 'Relativistic capital fleets', blurb: 'Near-c warships — Reynolds-grade engines of war.' }),

  // --- Statecraft ------------------------------------------------------------
  t({ id: 'diplomaticCorps', name: 'Diplomatic Corps', branch: 'statecraft', tier: 'T0', field: 'society', prereqs: [], unlocks: 'Cheaper treaties, +Influence', blurb: 'Professional envoys grease every accord.' }),
  t({ id: 'intelligenceAgency', name: 'Intelligence Agency', branch: 'statecraft', tier: 'T1', field: 'society', prereqs: ['diplomaticCorps'], unlocks: 'Stronger espionage & counter-intel', blurb: 'Eyes everywhere; secrets become currency.' }),
  t({ id: 'propaganda', name: 'Propaganda Networks', branch: 'statecraft', tier: 'T1', field: 'society', prereqs: ['diplomaticCorps'], unlocks: 'Faster loyalty recovery', blurb: 'Win the captured world before the war ends.' }),
  t({ id: 'monumentalWorks', name: 'Monumental Works', branch: 'statecraft', tier: 'T2', field: 'society', prereqs: ['diplomaticCorps'], unlocks: 'Build Monuments (+prestige)', blurb: 'Wonders that broadcast a civilization’s reach.' }),

  // --- Ascension (the Kardashev climb) --------------------------------------
  t({ id: 'orbitalCollectors', name: 'Orbital Collectors', branch: 'ascension', tier: 'T1', field: 'engineering', prereqs: ['fusionEconomy'], unlocks: 'Orbital Solar Lattice megastructure', blurb: 'Power satellites beam a world’s sunlight down — the first step off-planet.' }),
  t({ id: 'planetaryUnification', name: 'Planetary Unification', branch: 'ascension', tier: 'K1', field: 'society', prereqs: ['orbitalCollectors', 'fusionEconomy'], unlocks: 'Claim Type I', kardashevGate: 1, blurb: 'Capture the full energy budget of a world — a Type I civilization.' }),
  t({ id: 'dysonTheory', name: 'Dyson Swarm Theory', branch: 'ascension', tier: 'T2', field: 'physics', prereqs: ['planetaryUnification'], unlocks: 'Dyson Swarm megastructure', blurb: 'A cloud of collectors that will one day shroud the star.' }),
  t({ id: 'starLifting', name: 'Star-Lifting', branch: 'ascension', tier: 'T3', field: 'physics', prereqs: ['dysonTheory', 'singularityReactors'], unlocks: 'Star Lifter & Stellar Engine', blurb: 'Mine the star itself; steer it with a Shkadov–Caplan thruster.' }),
  t({ id: 'matrioshkaMinds', name: 'Matrioshka Minds', branch: 'ascension', tier: 'T3', field: 'physics', prereqs: ['dysonTheory'], unlocks: 'Matrioshka Brain megastructure', blurb: 'Nested Dyson computers turn starlight into thought.' }),
  t({ id: 'stellarMastery', name: 'Stellar Mastery', branch: 'ascension', tier: 'K2', field: 'physics', prereqs: ['starLifting'], unlocks: 'Claim Type II', kardashevGate: 2, blurb: 'Command a star’s entire output — a Type II civilization.' }),
  t({ id: 'gravitonEngineering', name: 'Graviton Engineering', branch: 'ascension', tier: 'T3', field: 'physics', prereqs: ['stellarMastery'], unlocks: 'Ringworld & Nicoll–Dyson Beam', blurb: 'Hold matter against impossible forces — Niven’s Ringworld within reach.' }),
  t({ id: 'blackHoleForge', name: 'Black-Hole Forge', branch: 'ascension', tier: 'T3', field: 'physics', prereqs: ['stellarMastery'], unlocks: 'Black-Hole Tap megastructure', blurb: 'Draw power from a singularity’s spin via the Penrose process.' }),
  t({ id: 'galacticAscendancy', name: 'Galactic Ascendancy', branch: 'ascension', tier: 'K3', field: 'physics', prereqs: ['blackHoleForge', 'gravitonEngineering'], unlocks: 'Claim Type III', kardashevGate: 3, blurb: 'Harness the energy of a galaxy — a Type III civilization.' }),
];

/** Fast lookup by id. */
export const TECH_BY_ID: ReadonlyMap<string, TechDef> = new Map(
  TECH_TREE.map((d) => [d.id, d]),
);

/** Tiers in display order. */
export const TIER_ORDER: readonly TechTier[] = ['T0', 'T1', 'T2', 'T3', 'K1', 'K2', 'K3'];

/** Branches in display order (Ascension last — it sits above the classic four). */
export const BRANCH_ORDER: readonly TechBranch[] = [
  'economy',
  'expansion',
  'military',
  'statecraft',
  'ascension',
];
