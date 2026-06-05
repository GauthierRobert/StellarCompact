import { computed, Injectable, signal } from '@angular/core';
import type { EnergySource } from '../features/empire/kardashev';
import type {
  TechBranch,
  TechField,
  TechTier,
} from '../features/empire/tech-tree';

/**
 * Empire store — the rich, owner-private dashboard state for the player's own
 * Sovereign: resource ledger with per-tick deltas, construction queue, research
 * progress, open trade agreements, fleets and diplomatic relations.
 *
 * This mirrors the kind of detail a player's fog-filtered WorldView carries
 * (docs/game-design + websocket-protocol §WorldView). It is display-only — the
 * engine remains authoritative. The store is fed by the live owner WorldView
 * queue when a real match is connected, or by the offline demo simulation when
 * running the frontend standalone. The dashboard reads these signals directly.
 */

// ---------------------------------------------------------------------------
// Types
// ---------------------------------------------------------------------------

/** A single resource value plus its most-recent per-tick change. */
export interface ResourceLine {
  readonly value: number;
  /** Net change applied on the last tick (positive = income). */
  readonly delta: number;
}

/** The five tracked economy resources for the empire ledger. */
export interface EmpireLedger {
  readonly credits: ResourceLine;
  readonly minerals: ResourceLine;
  readonly energy: ResourceLine;
  readonly alloys: ResourceLine;
  readonly influence: ResourceLine;
}

/** A construction-queue entry on some owned system. */
export interface BuildOrder {
  readonly id: string;
  /** Display name, e.g. "Orbital Shipyard". */
  readonly name: string;
  /** System the structure is being built at (display name). */
  readonly location: string;
  /** Build progress in [0,1]. */
  readonly progress: number;
  /** Ticks remaining (display estimate). */
  readonly etaTicks: number;
  /** Glyph/category for the icon: 'industry'|'science'|'military'|'economy'|'defense'. */
  readonly category: BuildCategory;
}

export type BuildCategory =
  | 'industry'
  | 'science'
  | 'military'
  | 'economy'
  | 'defense';

/** Active or completed research project. */
export interface ResearchProject {
  readonly id: string;
  readonly name: string;
  /** Research field, drives the accent colour. */
  readonly field: ResearchField;
  readonly progress: number;
  readonly etaTicks: number;
  /** True once unlocked (shown in the completed list). */
  readonly done: boolean;
}

export type ResearchField = 'physics' | 'society' | 'engineering';

/** An open trade agreement / standing offer. */
export interface TradeAgreement {
  readonly id: string;
  /** Counterparty faction id (or 'MARKET' for the open galactic market). */
  readonly partnerFactionId: string;
  /** What we export each tick. */
  readonly gives: string;
  /** What we import each tick. */
  readonly gets: string;
  /** Net credit balance per tick (positive = in our favour). */
  readonly balancePerTick: number;
  readonly status: 'active' | 'pending' | 'strained';
}

/** A class of ship within a fleet (composition line). */
export interface ShipLine {
  /** Display class name, e.g. "Corvette", "Cruiser", "Dreadnought". */
  readonly className: string;
  /** Hull tier 1..4 (corvette → capital). */
  readonly hullTier: number;
  readonly count: number;
}

export type FleetStatus = 'idle' | 'moving' | 'engaged' | 'defending' | 'returning';
export type FleetMission =
  | 'patrol'
  | 'invade'
  | 'escort'
  | 'explore'
  | 'reinforce'
  | 'garrison';

/** A fleet the empire commands — composition plus where it is going. */
export interface FleetSummary {
  readonly id: string;
  readonly name: string;
  /** Combat strength (display units). */
  readonly strength: number;
  /** Where it is / what it is doing. */
  readonly status: FleetStatus;
  /** Current location (display name) — for in-transit fleets, the last system. */
  readonly location: string;
  /** What the fleet is tasked to do. */
  readonly mission?: FleetMission;
  /** Ship composition lines. */
  readonly ships?: readonly ShipLine[];
  /** Origin system display name (set while moving/returning). */
  readonly originName?: string;
  /** Destination system display name (set while moving/returning). */
  readonly destName?: string;
  /** Destination active-system id (links to the galaxy/overlay). */
  readonly destSystemId?: string;
  /** Origin active-system id. */
  readonly originSystemId?: string;
  /** Ticks remaining until arrival (set while moving). */
  readonly etaTicks?: number;
  /** Transit progress in [0,1] (set while moving). */
  readonly progress?: number;
}

/** A planet the empire controls, with its development detail. */
export interface PlanetDetail {
  readonly id: string;
  readonly name: string;
  readonly systemId: string;
  readonly systemName: string;
  /** Biome key (oceanic|terran|arid|desert|volcanic|frozen|toxic|gas). */
  readonly biome: string;
  /** Planet size class. */
  readonly size: 'dwarf' | 'small' | 'medium' | 'large' | 'giant';
  readonly population: number;
  readonly maxPopulation: number;
  readonly slotsUsed: number;
  readonly slotsTotal: number;
  readonly buildings: readonly PlanetBuilding[];
  /** Per-tick yields after buildings/tech. */
  readonly yields: { energy: number; minerals: number; food: number; tech: number };
  /** In-progress terraform, or null. */
  readonly terraform: { target: string; progress: number } | null;
  /** Short specialisation tag, e.g. "Forge World", "Capital", "Breadbasket". */
  readonly specialisation: string;
  /** Watts this planet contributes to the Kardashev capture (planetary grid + orbital). */
  readonly captureWatts: number;
  /** True for the faction capital. */
  readonly isCapital: boolean;
}

/** A building occupying a planet slot. */
export interface PlanetBuilding {
  /** Type key, e.g. "mine", "solarArray", "farm", "researchLab", "shipyard". */
  readonly type: string;
  /** Display name. */
  readonly name: string;
  /** Tier 1..3. */
  readonly tier: number;
}

/** An active war the empire is fighting. */
export interface WarSummary {
  readonly enemyFactionId: string;
  /** Tick the war began. */
  readonly sinceTick: number;
  /** War-score in [-1,1] (positive = we are winning). */
  readonly warScore: number;
  readonly battlesWon: number;
  readonly battlesLost: number;
  /** Contested fronts: systems and their intensity 0..2. */
  readonly fronts: readonly { systemId: string; systemName: string; intensity: number }[];
  /** Our fleets currently engaged on this war. */
  readonly fleetsEngaged: number;
}

/** A multi-stage megastructure — the Kardashev engines. */
export interface Megastructure {
  readonly id: string;
  /** Kind key, e.g. "orbitalLattice", "dysonSwarm", "stellarEngine". */
  readonly kind: string;
  readonly name: string;
  readonly systemName: string;
  /** Current completed stage (0 = under first stage). */
  readonly stage: number;
  readonly maxStage: number;
  /** Progress within the current stage [0,1]. */
  readonly progress: number;
  readonly etaTicks: number;
  /** Captured power this structure currently contributes, in watts. */
  readonly outputWatts: number;
  /** Kardashev tier this structure belongs to (1/2/3). */
  readonly tier: 1 | 2 | 3;
}

/** Per-faction runtime state of one technology node. */
export interface TechNodeState {
  readonly id: string;
  readonly status: 'locked' | 'available' | 'researching' | 'done';
  /** Progress within research [0,1]. */
  readonly progress: number;
  readonly etaTicks: number;
}

/** Joined tech definition + runtime state for display. */
export interface TechNodeView {
  readonly id: string;
  readonly name: string;
  readonly branch: TechBranch;
  readonly tier: TechTier;
  readonly field: TechField;
  readonly timeTicks: number;
  readonly costTech: number;
  readonly prereqs: readonly string[];
  readonly unlocks: string;
  readonly blurb: string;
  readonly kardashevGate?: 1 | 2 | 3;
  readonly status: TechNodeState['status'];
  readonly progress: number;
  readonly etaTicks: number;
}

/** The civilization's Kardashev standing. */
export interface KardashevState {
  /** Continuous K-value, e.g. 1.43. */
  readonly k: number;
  /** Band id (K0..K3) and label. */
  readonly tierId: string;
  readonly tierLabel: string;
  /** Total captured power, watts. */
  readonly totalWatts: number;
  /** Progress [0,1] toward the next whole tier. */
  readonly progressToNext: number;
  /** Watts still needed to reach the next whole tier. */
  readonly wattsToNext: number;
  /** Energy-capture breakdown (sums to totalWatts). */
  readonly sources: readonly EnergySource[];
}

/** A diplomatic relationship with another faction. */
export interface Relation {
  readonly factionId: string;
  readonly status: 'allied' | 'neutral' | 'war' | 'tribute' | 'truce';
  /** Opinion in [-1,1]. */
  readonly opinion: number;
}

/** The full owner-private empire snapshot. */
export interface EmpireState {
  readonly factionId: string;
  readonly ledger: EmpireLedger;
  readonly builds: readonly BuildOrder[];
  readonly research: readonly ResearchProject[];
  readonly trades: readonly TradeAgreement[];
  readonly fleets: readonly FleetSummary[];
  readonly relations: readonly Relation[];
  /** Empire-wide stats for the overview header. */
  readonly systemCount: number;
  readonly planetCount: number;
  readonly population: number;
  /** Owned planets with development detail (E13-03/04). */
  readonly planets: readonly PlanetDetail[];
  /** Active wars with fronts + battle history (E13-07). */
  readonly wars: readonly WarSummary[];
  /** Megastructures under construction or complete (E12-03). */
  readonly megastructures: readonly Megastructure[];
  /** The full tech-tree runtime state, by node id (E12-02). */
  readonly tech: readonly TechNodeState[];
  /** The civilization's Kardashev standing (E12-01) — the central metric. */
  readonly kardashev: KardashevState;
}

/** Default Kardashev standing before the first snapshot arrives. */
export const EMPTY_KARDASHEV: KardashevState = {
  k: 0,
  tierId: 'K0',
  tierLabel: 'Type 0',
  totalWatts: 0,
  progressToNext: 0,
  wattsToNext: 0,
  sources: [],
};

// ---------------------------------------------------------------------------
// Store
// ---------------------------------------------------------------------------

@Injectable({ providedIn: 'root' })
export class EmpireStore {
  private readonly _state = signal<EmpireState | null>(null);

  /** The player's own empire detail, or null until first received. */
  readonly state = this._state.asReadonly();

  readonly ledger = computed<EmpireLedger | null>(() => this._state()?.ledger ?? null);
  readonly builds = computed<readonly BuildOrder[]>(() => this._state()?.builds ?? []);
  readonly research = computed<readonly ResearchProject[]>(
    () => this._state()?.research ?? [],
  );
  /** Research still in progress (newest activity first is up to the feeder). */
  readonly activeResearch = computed<readonly ResearchProject[]>(() =>
    this.research().filter((r) => !r.done),
  );
  readonly completedResearch = computed<readonly ResearchProject[]>(() =>
    this.research().filter((r) => r.done),
  );
  readonly trades = computed<readonly TradeAgreement[]>(() => this._state()?.trades ?? []);
  readonly fleets = computed<readonly FleetSummary[]>(() => this._state()?.fleets ?? []);
  readonly relations = computed<readonly Relation[]>(() => this._state()?.relations ?? []);

  /** Owned planets with development detail. */
  readonly planets = computed<readonly PlanetDetail[]>(() => this._state()?.planets ?? []);
  /** Megastructures (under construction or complete). */
  readonly megastructures = computed<readonly Megastructure[]>(
    () => this._state()?.megastructures ?? [],
  );
  /** Tech-tree runtime state by node id. */
  readonly tech = computed<readonly TechNodeState[]>(() => this._state()?.tech ?? []);
  /** The civilization's Kardashev standing — the central metric. */
  readonly kardashev = computed<KardashevState>(
    () => this._state()?.kardashev ?? EMPTY_KARDASHEV,
  );

  /** Active wars (rich summaries with fronts + battle history). */
  readonly wars = computed<readonly WarSummary[]>(() => this._state()?.wars ?? []);
  /** Factions we are allied with. */
  readonly allies = computed<readonly Relation[]>(() =>
    this.relations().filter((r) => r.status === 'allied'),
  );

  /** Fleets currently in transit (have a destination + ETA). */
  readonly fleetsInTransit = computed<readonly FleetSummary[]>(() =>
    this.fleets().filter((f) => f.status === 'moving' || f.status === 'returning'),
  );

  /** Total fleet strength across the empire. */
  readonly totalFleetStrength = computed<number>(() =>
    this.fleets().reduce((sum, f) => sum + f.strength, 0),
  );

  // ---- mutators ----

  /** Replace the empire snapshot (called by the feeder each tick). */
  set(state: EmpireState): void {
    this._state.set(state);
  }

  reset(): void {
    this._state.set(null);
  }
}
