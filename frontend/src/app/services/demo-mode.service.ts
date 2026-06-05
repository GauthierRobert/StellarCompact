import { Injectable, inject, signal } from '@angular/core';
import { FactionStore, type FactionSnapshot } from '../stores/faction.store';
import {
  OverlayStore,
  type RouteOverlay,
  type SystemOverlay,
} from '../stores/overlay.store';
import { EventsStore, type PublicEvent } from '../stores/events.store';
import {
  EmpireStore,
  type BuildOrder,
  type EmpireState,
  type FleetSummary,
  type KardashevState,
  type Megastructure,
  type PlanetBuilding,
  type PlanetDetail,
  type Relation,
  type ResearchProject,
  type ShipLine,
  type TechNodeState,
  type TradeAgreement,
  type WarSummary,
} from '../stores/empire.store';
import {
  buildDemoWorld,
  type DemoSystem,
  type DemoWorld,
} from '../features/galaxy/demo-world';
import {
  buildInterstellarObjects,
  type DemoObject,
} from '../features/galaxy/interstellar-objects';
import type { TileAddress, TilePayloadDto } from '../features/galaxy/tile.service';
import {
  bandForK,
  kFromWatts,
  progressToNextTier,
  wattsToNextTier,
  ENERGY_SOURCE_LABELS,
  type EnergySource,
  type EnergySourceKind,
} from '../features/empire/kardashev';
import {
  TECH_TREE,
  TECH_BY_ID,
  type TechDef,
  type TechField,
} from '../features/empire/tech-tree';

/**
 * Demo-mode service — drives a living, self-consistent galaxy WITHOUT a backend
 * so the immersive map + command dashboard are fully demonstrable standalone.
 *
 * It owns a {@link DemoWorld} (active systems elevated from the byte-parity
 * catalog generator) and runs a lightweight tick loop that simulates empire
 * expansion, wars, blockades, trade routes, diplomacy and economy, pushing the
 * results into exactly the same signal stores the live STOMP path feeds
 * (faction / overlay / events / empire). The galaxy renderer and dashboard are
 * therefore identical whether the data is live or demo.
 *
 * This is a DISPLAY/DEV affordance only — it never touches authoritative game
 * state and is swapped out the moment a real match is connected.
 */

interface SimFaction {
  readonly id: string;
  readonly name: string;
  readonly colour: string;
  homeX: number;
  homeY: number;
  reputation: number;
  resources: { credits: number; minerals: number; energy: number; alloys: number; influence: number };
  income: { credits: number; minerals: number; energy: number; alloys: number; influence: number };
  eliminated: boolean;
}

interface SimSystem {
  owner: string | null;
  battle: number; // ticks remaining of active battle
  blockade: number; // ticks remaining of blockade
  activity: number; // 0..2
}

const FACTION_DEFS: readonly { id: string; name: string; colour: string }[] = [
  { id: 'AUR', name: 'Aurelian Concord', colour: '#36e0c8' },
  { id: 'CRM', name: 'Crimson Hegemony', colour: '#ff5468' },
  { id: 'VRD', name: 'Verdant Compact', colour: '#76e05a' },
  { id: 'OBS', name: 'Obsidian Syndicate', colour: '#b06bff' },
  { id: 'SOL', name: 'Solar Dominion', colour: '#ffc24d' },
];

const BUILD_NAMES: readonly { name: string; cat: BuildOrder['category'] }[] = [
  { name: 'Orbital Shipyard', cat: 'military' },
  { name: 'Mineral Refinery', cat: 'industry' },
  { name: 'Research Campus', cat: 'science' },
  { name: 'Trade Exchange', cat: 'economy' },
  { name: 'Planetary Shield', cat: 'defense' },
  { name: 'Fusion Array', cat: 'industry' },
  { name: 'Deep-Space Array', cat: 'science' },
  { name: 'Alloy Foundry', cat: 'industry' },
];

// ---------------------------------------------------------------------------
// Kardashev-era demo model constants
// ---------------------------------------------------------------------------

/** Biome keys with base per-tick yields + watt weight for planetary capture. */
interface BiomeDef {
  readonly key: string;
  readonly energy: number;
  readonly minerals: number;
  readonly food: number;
  readonly tech: number;
  /** Specialisation tag suggested by the dominant yield. */
  readonly tag: string;
}
const BIOMES: readonly BiomeDef[] = [
  { key: 'oceanic', energy: 1, minerals: 0, food: 6, tech: 0, tag: 'Breadbasket' },
  { key: 'terran', energy: 1, minerals: 1, food: 4, tech: 0, tag: 'Garden World' },
  { key: 'arid', energy: 1, minerals: 4, food: 1, tech: 0, tag: 'Mining World' },
  { key: 'desert', energy: 5, minerals: 1, food: 0, tech: 0, tag: 'Solar Farm' },
  { key: 'volcanic', energy: 3, minerals: 5, food: 0, tech: 0, tag: 'Forge World' },
  { key: 'frozen', energy: 0, minerals: 1, food: 0, tech: 4, tag: 'Research World' },
  { key: 'toxic', energy: 1, minerals: 2, food: 0, tech: 1, tag: 'Frontier World' },
  { key: 'gas', energy: 6, minerals: 0, food: 0, tech: 0, tag: 'Gas Refinery' },
];
const BIOME_BY_KEY = new Map(BIOMES.map((b) => [b.key, b]));

const PLANET_SIZES: readonly PlanetDetail['size'][] = ['dwarf', 'small', 'medium', 'large', 'giant'];
/** Building slots by size index. */
const SLOTS_BY_SIZE = [2, 3, 4, 5, 6];
/** Population cap (thousands) by size index. */
const POPCAP_BY_SIZE = [1800, 3600, 6400, 10400, 16000];

const BUILDING_CATALOG: readonly { type: string; name: string }[] = [
  { type: 'mine', name: 'Deep Mine' },
  { type: 'solarArray', name: 'Solar Array' },
  { type: 'farm', name: 'Hydroponics Farm' },
  { type: 'researchLab', name: 'Research Lab' },
  { type: 'fusionPlant', name: 'Fusion Plant' },
  { type: 'shipyard', name: 'Orbital Shipyard' },
  { type: 'marketHub', name: 'Market Hub' },
  { type: 'defensePlatform', name: 'Defense Platform' },
];

/** Ship classes by hull tier (1=corvette … 4=capital). */
const SHIP_CLASSES: readonly { className: string; hullTier: number; gateTech: string | null; str: number }[] = [
  { className: 'Corvette', hullTier: 1, gateTech: null, str: 8 },
  { className: 'Frigate', hullTier: 1, gateTech: 'corvetteDoctrine', str: 12 },
  { className: 'Cruiser', hullTier: 2, gateTech: 'cruiserDoctrine', str: 26 },
  { className: 'Battleship', hullTier: 3, gateTech: 'capitalDoctrine', str: 48 },
  { className: 'Dreadnought', hullTier: 4, gateTech: 'lighthuggers', str: 90 },
];

/**
 * Megastructure definitions — the Kardashev engines. `stageWatts[i]` is the
 * power (watts) the i-th completed stage adds; `source` maps the structure to a
 * Kardashev energy-capture source.
 */
interface MegaDef {
  readonly kind: string;
  readonly name: string;
  readonly tier: 1 | 2 | 3;
  readonly reqTech: string;
  readonly source: EnergySourceKind;
  readonly stageWatts: readonly number[];
}
const MEGA_DEFS: readonly MegaDef[] = [
  {
    kind: 'orbitalLattice',
    name: 'Orbital Solar Lattice',
    tier: 1,
    reqTech: 'orbitalCollectors',
    source: 'orbitalCollectors',
    stageWatts: [3e15, 4e15, 6e15],
  },
  {
    kind: 'dysonSwarm',
    name: 'Dyson Swarm',
    tier: 2,
    reqTech: 'dysonTheory',
    source: 'dysonSwarm',
    stageWatts: [6e23, 3e24, 1.4e25, 5e25, 1.6e26],
  },
  {
    kind: 'matrioshkaBrain',
    name: 'Matrioshka Brain',
    tier: 2,
    reqTech: 'matrioshkaMinds',
    source: 'dysonSwarm',
    stageWatts: [4e23, 1.2e24, 4e24],
  },
  {
    kind: 'stellarEngine',
    name: 'Shkadov Stellar Engine',
    tier: 2,
    reqTech: 'starLifting',
    source: 'stellarEngine',
    stageWatts: [8e23, 2.4e24, 6e24],
  },
  {
    kind: 'blackHoleTap',
    name: 'Black-Hole Tap',
    tier: 3,
    reqTech: 'blackHoleForge',
    source: 'blackHoleTap',
    stageWatts: [2e30, 8e31, 2e33, 6e34],
  },
];

// --- mutable demo empire state --------------------------------------------

interface MutablePlanet {
  id: string;
  systemId: number;
  name: string;
  biome: string;
  sizeIdx: number;
  population: number;
  buildings: PlanetBuilding[];
  slotsTotal: number;
  terraform: { target: string; progress: number } | null;
  isCapital: boolean;
}

interface RichFleet {
  id: string;
  name: string;
  homeSystemId: number;
  ships: ShipLine[];
  status: FleetSummary['status'];
  mission: NonNullable<FleetSummary['mission']>;
  curSystemId: number;
  originSystemId: number | null;
  destSystemId: number | null;
  etaTicks: number;
  totalEta: number;
  holdTicks: number;
}

interface MegaState {
  id: string;
  def: MegaDef;
  systemId: number;
  stage: number; // completed stages
  progress: number; // within current stage [0,1]
}

/** Terraform chain (toward more habitable). */
const TERRAFORM_CHAIN: Record<string, string> = {
  toxic: 'arid',
  arid: 'terran',
  volcanic: 'arid',
  frozen: 'terran',
  terran: 'oceanic',
};

@Injectable({ providedIn: 'root' })
export class DemoModeService {
  private readonly factionStore = inject(FactionStore);
  private readonly overlayStore = inject(OverlayStore);
  private readonly eventsStore = inject(EventsStore);
  private readonly empireStore = inject(EmpireStore);

  /** True while the demo simulation is driving the stores. */
  readonly enabled = signal<boolean>(false);
  /** The player's own faction id (drives the resource bar + dashboard). */
  readonly playerFactionId = signal<string>('AUR');
  /** Current sim tick. */
  readonly tick = signal<number>(0);
  /** Playback speed multiplier (1 = normal). */
  readonly speed = signal<number>(1);
  private readonly _paused = signal<boolean>(false);
  readonly paused = this._paused.asReadonly();

  private world: DemoWorld | null = null;
  private demoObjects: readonly DemoObject[] = [];
  private factions: SimFaction[] = [];
  private systems = new Map<number, SimSystem>();
  private routes = new Map<string, RouteOverlay>();
  private relations = new Map<string, Relation['status']>(); // key "A|B" sorted
  private opinions = new Map<string, number>();
  private builds: BuildOrder[] = [];
  private trades: TradeAgreement[] = [];
  private rngState = 0;
  private timer: ReturnType<typeof setInterval> | null = null;
  private nextRouteId = 1;
  private nextBuildId = 1;

  // --- player Kardashev-era empire model ---
  /** Per-tech runtime state by id. */
  private playerTech = new Map<string, { status: TechNodeState['status']; progress: number }>();
  /** The tech currently being researched (id), or null. */
  private researchingId: string | null = null;
  /** Owned-planet detail by planet id (deterministic per system). */
  private playerPlanets = new Map<string, MutablePlanet>();
  private playerFleets: RichFleet[] = [];
  private playerMega: MegaState[] = [];
  private nextFleetId = 1;
  private nextMegaId = 1;
  /** Per-enemy war bookkeeping (since-tick + tally). */
  private warStats = new Map<string, { since: number; won: number; lost: number }>();
  /** Demo research-rate multiplier (keeps relative tier timing, watchable wall-clock). */
  private readonly researchRate = 5;

  // ---- public API ----

  /** Whether the demo can serve a tile for the renderer right now. */
  isActive(): boolean {
    return this.enabled() && this.world !== null;
  }

  /** Synthesise an LOD tile from the demo world (offline galaxy). */
  localTile(seed: string, addr: TileAddress): TilePayloadDto | null {
    return this.world?.tile(seed, addr) ?? null;
  }

  /** Look up a demo system by overlay id (string). */
  systemById(systemId: string): DemoSystem | undefined {
    const id = Number(systemId);
    return this.world?.systems.find((s) => s.id === id);
  }

  /** The deterministic interstellar-object roster for the current world. */
  objects(): readonly DemoObject[] {
    return this.demoObjects;
  }

  /** Look up an interstellar object by id. */
  objectById(objectId: string): DemoObject | undefined {
    return this.demoObjects.find((o) => o.id === objectId);
  }

  /** Active systems with world positions (for sector tinting/summaries). */
  worldSystems(): readonly DemoSystem[] {
    return this.world?.systems ?? [];
  }

  /** Start the demo simulation for a numeric seed. Idempotent. */
  start(seedNum = 1): void {
    if (this.enabled()) {
      return;
    }
    this.world = buildDemoWorld(seedNum);
    this.demoObjects = buildInterstellarObjects(seedNum, this.world.rMax);
    this.rngState = (seedNum * 2654435761) >>> 0 || 1;
    this.tick.set(0);
    this.eventsStore.reset();
    this.routes.clear();
    this.initFactions();
    this.initEmpire();
    this.enabled.set(true);
    this.publishAll();
    this.scheduleTimer();
  }

  /** Stop the demo and clear the timer (does not wipe stores). */
  stop(): void {
    if (this.timer) {
      clearInterval(this.timer);
      this.timer = null;
    }
    this.enabled.set(false);
  }

  togglePause(): void {
    this._paused.update((p) => !p);
  }

  setSpeed(mult: number): void {
    this.speed.set(mult);
    if (this.enabled()) {
      this.scheduleTimer();
    }
  }

  // ---- RNG ----

  private rnd(): number {
    // mulberry32
    let t = (this.rngState += 0x6d2b79f5);
    t = Math.imul(t ^ (t >>> 15), t | 1);
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  }
  private pick<T>(arr: readonly T[]): T {
    return arr[Math.floor(this.rnd() * arr.length)];
  }
  private chance(p: number): boolean {
    return this.rnd() < p;
  }

  // ---- init ----

  private initFactions(): void {
    const w = this.world!;
    this.factions = FACTION_DEFS.map((d) => ({
      id: d.id,
      name: d.name,
      colour: d.colour,
      homeX: 0,
      homeY: 0,
      reputation: 0,
      resources: {
        credits: 800 + Math.floor(this.rnd() * 600),
        minerals: 400 + Math.floor(this.rnd() * 300),
        energy: 300 + Math.floor(this.rnd() * 200),
        alloys: 150 + Math.floor(this.rnd() * 120),
        influence: 60 + Math.floor(this.rnd() * 60),
      },
      income: { credits: 0, minerals: 0, energy: 0, alloys: 0, influence: 0 },
      eliminated: false,
    }));

    // Distribute faction homes evenly around the core, then seed each with a
    // cluster of the nearest systems. Some systems stay unclaimed (frontier).
    this.systems.clear();
    for (const s of w.systems) {
      this.systems.set(s.id, { owner: null, battle: 0, blockade: 0, activity: 0 });
    }
    const n = this.factions.length;
    const radius = 360;
    this.factions.forEach((f, i) => {
      const ang = (i / n) * Math.PI * 2 + 0.4;
      f.homeX = Math.cos(ang) * radius;
      f.homeY = Math.sin(ang) * radius;
    });
    // Greedy assign each faction its nearest free systems.
    const claimCount = Math.max(3, Math.floor((w.systems.length * 0.7) / n));
    for (const f of this.factions) {
      const free = w.systems
        .filter((s) => this.systems.get(s.id)!.owner === null)
        .sort(
          (a, b) =>
            (a.x - f.homeX) ** 2 + (a.y - f.homeY) ** 2 -
            ((b.x - f.homeX) ** 2 + (b.y - f.homeY) ** 2),
        );
      for (let k = 0; k < claimCount && k < free.length; k++) {
        this.systems.get(free[k].id)!.owner = f.id;
      }
    }

    // Diplomacy seed: neutral all round, one rivalry (war) + one pact (ally).
    this.relations.clear();
    this.opinions.clear();
    this.setRelation('AUR', 'CRM', 'war', -0.6);
    this.setRelation('AUR', 'VRD', 'allied', 0.7);
    this.setRelation('CRM', 'OBS', 'allied', 0.5);
    this.setRelation('VRD', 'SOL', 'neutral', 0.1);

    // A few initial trade routes between same-owner neighbours.
    for (let i = 0; i < 6; i++) {
      this.tryCreateRoute(true);
    }
    this.recomputeIncome();
  }

  private relKey(a: string, b: string): string {
    return a < b ? a + '|' + b : b + '|' + a;
  }
  private setRelation(a: string, b: string, status: Relation['status'], opinion: number): void {
    this.relations.set(this.relKey(a, b), status);
    this.opinions.set(this.relKey(a, b), opinion);
  }
  private getRelation(a: string, b: string): Relation['status'] {
    return this.relations.get(this.relKey(a, b)) ?? 'neutral';
  }

  private initEmpire(): void {
    this.builds = [
      this.makeBuild(0.45),
      this.makeBuild(0.18),
      this.makeBuild(0.7),
    ];
    this.trades = [
      this.makeTrade('VRD', 'active'),
      this.makeTrade('MARKET', 'active'),
      this.makeTrade('SOL', 'pending'),
    ];
    this.initPlayerEmpire();
  }

  /** Seed the Kardashev-era player model: tech, planets, fleets, megastructures. */
  private initPlayerEmpire(): void {
    // --- Tech: a starting wedge unlocked, the rest locked/available. ---
    this.playerTech.clear();
    const seeded = new Set([
      'improvedExtraction',
      'hydroponics',
      'colonialLogistics',
      'corvetteDoctrine',
      'diplomaticCorps',
      'automatedMining',
      'cruiserDoctrine',
      'fusionEconomy',
    ]);
    for (const def of TECH_TREE) {
      this.playerTech.set(def.id, {
        status: seeded.has(def.id) ? 'done' : 'locked',
        progress: seeded.has(def.id) ? 1 : 0,
      });
    }
    this.refreshTechAvailability();
    this.researchingId = null;
    this.pickNextResearch();

    // --- Planets: generated deterministically from the player's owned systems. ---
    this.playerPlanets.clear();
    this.nextFleetId = 1;
    this.nextMegaId = 1;
    this.playerFleets = [];
    this.playerMega = [];
    this.syncPlanetsToOwnership(/*capitalFirst*/ true);

    // --- Fleets: a starting home guard + a vanguard. ---
    const owned = this.ownedSystems(this.playerFactionId());
    const home = owned[0]?.id ?? 0;
    this.playerFleets = [
      this.makeFleet('Home Guard', home, 'garrison'),
      this.makeFleet('1st Vanguard', home, 'patrol'),
      this.makeFleet('Frontier Wing', owned[1]?.id ?? home, 'explore'),
    ];
  }

  private makeBuild(progress: number): BuildOrder {
    const def = this.pick(BUILD_NAMES);
    const loc = this.pick(this.ownedSystems(this.playerFactionId()));
    const eta = Math.max(1, Math.round((1 - progress) * (8 + Math.floor(this.rnd() * 14))));
    return {
      id: 'b' + this.nextBuildId++,
      name: def.name,
      location: loc ? loc.name : 'Capital',
      progress,
      etaTicks: eta,
      category: def.cat,
    };
  }
  // ---- tech ladder ----

  /** Mark every locked tech whose prereqs are all done as 'available'. */
  private refreshTechAvailability(): void {
    for (const def of TECH_TREE) {
      const st = this.playerTech.get(def.id)!;
      if (st.status !== 'locked') continue;
      if (def.prereqs.every((p) => this.playerTech.get(p)?.status === 'done')) {
        this.playerTech.set(def.id, { ...st, status: 'available' });
      }
    }
  }

  /**
   * Choose the next tech to research from the available frontier. Biases toward
   * the Ascension branch (to drive the Kardashev climb), then cheapest-by-time so
   * quick wins land first; the steep ascension nodes are the long pursuits.
   */
  private pickNextResearch(): void {
    if (this.researchingId) return;
    const avail = TECH_TREE.filter((d) => this.playerTech.get(d.id)?.status === 'available');
    if (avail.length === 0) return;
    avail.sort((a, b) => {
      const aw = a.branch === 'ascension' ? 0 : 1;
      const bw = b.branch === 'ascension' ? 0 : 1;
      if (aw !== bw) return aw - bw;
      return a.timeTicks - b.timeTicks;
    });
    const chosen = avail[0];
    this.researchingId = chosen.id;
    const st = this.playerTech.get(chosen.id)!;
    this.playerTech.set(chosen.id, { ...st, status: 'researching' });
  }

  /** Advance the active research; complete + chain to the next when done. */
  private advanceResearch(): void {
    if (!this.researchingId) {
      this.pickNextResearch();
      return;
    }
    const def = TECH_BY_ID.get(this.researchingId)!;
    const st = this.playerTech.get(def.id)!;
    const step = this.researchRate / def.timeTicks;
    const np = st.progress + step;
    if (np >= 1) {
      this.playerTech.set(def.id, { status: 'done', progress: 1 });
      this.researchingId = null;
      this.refreshTechAvailability();
      this.pickNextResearch();
    } else {
      this.playerTech.set(def.id, { ...st, progress: np });
    }
  }

  private techDone(id: string): boolean {
    return this.playerTech.get(id)?.status === 'done';
  }

  // ---- planets ----

  /** Ensure player-owned systems have generated planets; drop lost systems. */
  private syncPlanetsToOwnership(capitalFirst = false): void {
    const owned = this.ownedSystems(this.playerFactionId());
    const ownedIds = new Set(owned.map((s) => s.id));
    // Drop planets whose system is no longer owned.
    for (const [pid, p] of [...this.playerPlanets]) {
      if (!ownedIds.has(p.systemId)) this.playerPlanets.delete(pid);
    }
    // Generate planets for newly owned systems.
    let capitalAssigned = [...this.playerPlanets.values()].some((p) => p.isCapital);
    owned.forEach((sys, idx) => {
      const already = [...this.playerPlanets.values()].some((p) => p.systemId === sys.id);
      if (already) return;
      const isCapitalSystem = capitalFirst && idx === 0 && !capitalAssigned;
      const planets = this.generatePlanets(sys, isCapitalSystem);
      if (isCapitalSystem) capitalAssigned = true;
      for (const pl of planets) this.playerPlanets.set(pl.id, pl);
    });
  }

  /** Deterministically generate 1–4 planets for a system (seeded by system id). */
  private generatePlanets(sys: DemoSystem, capitalSystem: boolean): MutablePlanet[] {
    let seed = (sys.id * 2654435761) >>> 0 || 1;
    const r = () => {
      seed ^= seed << 13; seed >>>= 0;
      seed ^= seed >> 17;
      seed ^= seed << 5; seed >>>= 0;
      return (seed >>> 0) / 4294967296;
    };
    const count = 1 + Math.floor(r() * 4);
    const out: MutablePlanet[] = [];
    for (let i = 0; i < count; i++) {
      const biome = BIOMES[Math.floor(r() * BIOMES.length)];
      const sizeIdx = Math.floor(r() * PLANET_SIZES.length);
      const isCap = capitalSystem && i === 0;
      const slots = SLOTS_BY_SIZE[sizeIdx] + (isCap ? 2 : 0);
      const used = isCap ? slots : 1 + Math.floor(r() * Math.max(1, slots - 1));
      const buildings = this.seedBuildings(biome, used, isCap, r);
      out.push({
        id: 'p' + sys.id + '_' + i,
        systemId: sys.id,
        name: this.planetName(sys.name, i),
        biome: biome.key,
        sizeIdx,
        population: Math.round(POPCAP_BY_SIZE[sizeIdx] * (isCap ? 0.85 : 0.25 + r() * 0.4)),
        buildings,
        slotsTotal: slots,
        terraform: !isCap && (biome.key === 'toxic' || biome.key === 'volcanic') && r() < 0.5
          ? { target: TERRAFORM_CHAIN[biome.key] ?? biome.key, progress: r() * 0.6 }
          : null,
        isCapital: isCap,
      });
    }
    return out;
  }

  private seedBuildings(biome: BiomeDef, used: number, isCap: boolean, r: () => number): PlanetBuilding[] {
    const out: PlanetBuilding[] = [];
    if (isCap) {
      out.push({ type: 'capital', name: 'Planetary Capital', tier: 3 });
    }
    // Pick buildings biased to the biome's strength.
    const prefer = biome.energy >= 4 ? 'solarArray' : biome.minerals >= 4 ? 'mine' : biome.food >= 4 ? 'farm' : biome.tech >= 4 ? 'researchLab' : 'fusionPlant';
    while (out.length < used) {
      const pick = r() < 0.5
        ? BUILDING_CATALOG.find((b) => b.type === prefer)!
        : BUILDING_CATALOG[Math.floor(r() * BUILDING_CATALOG.length)];
      out.push({ type: pick.type, name: pick.name, tier: 1 + Math.floor(r() * 3) });
    }
    return out.slice(0, used);
  }

  private planetName(systemName: string, idx: number): string {
    const roman = ['I', 'II', 'III', 'IV', 'V', 'VI'];
    return systemName + ' ' + (roman[idx] ?? String(idx + 1));
  }

  // ---- fleets ----

  private makeFleet(name: string, systemId: number, mission: RichFleet['mission']): RichFleet {
    return {
      id: 'fl' + this.nextFleetId++,
      name,
      homeSystemId: systemId,
      ships: this.composeFleet(),
      status: mission === 'garrison' ? 'defending' : 'idle',
      mission,
      curSystemId: systemId,
      originSystemId: null,
      destSystemId: null,
      etaTicks: 0,
      totalEta: 0,
      holdTicks: 0,
    };
  }

  /** Ship composition reflecting the player's unlocked hull tech. */
  private composeFleet(): ShipLine[] {
    const unlocked = SHIP_CLASSES.filter((s) => s.gateTech === null || this.techDone(s.gateTech));
    const lines: ShipLine[] = [];
    for (const s of unlocked) {
      if (this.chance(0.65) || s.hullTier === 1) {
        lines.push({ className: s.className, hullTier: s.hullTier, count: 1 + Math.floor(this.rnd() * 6) });
      }
    }
    if (lines.length === 0) lines.push({ className: 'Corvette', hullTier: 1, count: 3 });
    return lines;
  }

  private fleetStrength(f: RichFleet): number {
    let s = 0;
    for (const line of f.ships) {
      const def = SHIP_CLASSES.find((c) => c.className === line.className);
      s += (def?.str ?? 8) * line.count;
    }
    return s;
  }

  private makeTrade(partner: string, status: TradeAgreement['status']): TradeAgreement {
    const goods = ['Minerals', 'Energy', 'Alloys', 'Credits', 'Influence'];
    const gives = this.pick(goods);
    let gets = this.pick(goods);
    if (gets === gives) {
      gets = goods[(goods.indexOf(gives) + 1) % goods.length];
    }
    return {
      id: 't' + (this.trades.length + 1) + '_' + Math.floor(this.rnd() * 1000),
      partnerFactionId: partner,
      gives,
      gets,
      balancePerTick: Math.round((this.rnd() * 2 - 0.4) * 12),
      status,
    };
  }

  // ---- system helpers ----

  private ownedSystems(factionId: string): DemoSystem[] {
    const w = this.world!;
    return w.systems.filter((s) => this.systems.get(s.id)!.owner === factionId);
  }
  private systemCount(factionId: string): number {
    let c = 0;
    for (const st of this.systems.values()) {
      if (st.owner === factionId) c++;
    }
    return c;
  }

  // ---- tick loop ----

  private scheduleTimer(): void {
    if (this.timer) {
      clearInterval(this.timer);
    }
    const period = Math.max(220, 1150 / Math.max(0.25, this.speed()));
    this.timer = setInterval(() => this.step(), period);
  }

  private step(): void {
    if (this._paused()) {
      return;
    }
    const t = this.tick() + 1;
    this.tick.set(t);
    const events: PublicEvent[] = [];

    // 1) Economy: income each tick + small variance.
    this.recomputeIncome();
    for (const f of this.factions) {
      if (f.eliminated) continue;
      (['credits', 'minerals', 'energy', 'alloys', 'influence'] as const).forEach((r) => {
        const inc = f.income[r] * (0.85 + this.rnd() * 0.3);
        f.resources[r] = Math.max(0, f.resources[r] + inc);
      });
    }

    // 2) Decay battle/blockade timers; resolve battles.
    for (const [sysId, st] of this.systems) {
      if (st.battle > 0) {
        st.battle--;
        if (st.battle === 0) {
          events.push(this.evt('BattleResolved', [st.owner ?? '?'], String(sysId), t));
          st.activity = 1;
        }
      }
      if (st.blockade > 0) {
        st.blockade--;
      }
      if (st.activity > 0 && this.chance(0.15)) {
        st.activity = Math.max(0, st.activity - 1);
      }
    }

    // 3) Expansion: a faction claims its nearest frontier system.
    if (this.chance(0.55)) {
      this.doExpansion(events, t);
    }

    // 4) Conflict: ignite a battle on a contested border.
    if (this.chance(0.4)) {
      this.doConflict(events, t);
    }

    // 5) Blockade a border system occasionally.
    if (this.chance(0.18)) {
      this.doBlockade(events, t);
    }

    // 6) Trade route churn.
    if (this.chance(0.3)) {
      this.tryCreateRoute(false, events, t);
    }
    if (this.chance(0.16)) {
      this.raidRoute(events, t);
    }

    // 7) Diplomacy shifts.
    if (this.chance(0.12)) {
      this.doDiplomacy(events, t);
    }

    // 8) Player dashboard progression.
    this.advancePlayerEmpire(t);

    // 9) Victory check (display proxy).
    this.maybeVictory(events, t);

    this.publishAll();
    if (events.length > 0) {
      this.eventsStore.appendEvents(events);
    }
  }

  private evt(type: PublicEvent['type'], parties: string[], systemId: string | undefined, tick: number): PublicEvent {
    return { type, parties, systemId, tick };
  }

  private doExpansion(events: PublicEvent[], t: number): void {
    const w = this.world!;
    const f = this.pick(this.factions.filter((x) => !x.eliminated));
    const owned = this.ownedSystems(f.id);
    if (owned.length === 0) return;
    // Nearest unclaimed system to any of this faction's systems.
    let best: DemoSystem | null = null;
    let bestD = Infinity;
    for (const s of w.systems) {
      const st = this.systems.get(s.id)!;
      if (st.owner === f.id) continue;
      if (st.owner !== null && this.getRelation(f.id, st.owner) === 'allied') continue;
      for (const o of owned) {
        const d = (o.x - s.x) ** 2 + (o.y - s.y) ** 2;
        if (d < bestD) {
          bestD = d;
          best = s;
        }
      }
    }
    if (!best || bestD > 260 * 260) return;
    const st = this.systems.get(best.id)!;
    const wasOwner = st.owner;
    if (wasOwner && wasOwner !== f.id) {
      // Contested takeover → brief battle, then flip.
      st.battle = 2 + Math.floor(this.rnd() * 3);
      st.activity = 2;
      events.push(this.evt('BattleResolved', [f.id, wasOwner], String(best.id), t));
    }
    st.owner = f.id;
    st.activity = Math.max(st.activity, 1);
    events.push(this.evt('SystemCaptured', [f.id, wasOwner ?? ''].filter(Boolean), String(best.id), t));
  }

  private doConflict(events: PublicEvent[], t: number): void {
    const w = this.world!;
    // Find a system adjacent to a rival's territory and ignite a battle.
    const candidates = w.systems.filter((s) => {
      const st = this.systems.get(s.id)!;
      return st.owner !== null && st.battle === 0;
    });
    if (candidates.length === 0) return;
    const target = this.pick(candidates);
    const st = this.systems.get(target.id)!;
    // Is there a rival nearby?
    const rival = this.nearestRivalOwner(target, st.owner!);
    if (!rival) return;
    st.battle = 3 + Math.floor(this.rnd() * 4);
    st.activity = 2;
    events.push(this.evt('WarDeclared', [rival, st.owner!], String(target.id), t));
    if (this.getRelation(rival, st.owner!) !== 'war') {
      this.setRelation(rival, st.owner!, 'war', -0.7);
    }
  }

  private nearestRivalOwner(sys: DemoSystem, owner: string): string | null {
    const w = this.world!;
    let best: string | null = null;
    let bestD = 200 * 200;
    for (const s of w.systems) {
      const st = this.systems.get(s.id)!;
      if (st.owner === null || st.owner === owner) continue;
      const d = (s.x - sys.x) ** 2 + (s.y - sys.y) ** 2;
      if (d < bestD) {
        bestD = d;
        best = st.owner;
      }
    }
    return best;
  }

  private doBlockade(events: PublicEvent[], t: number): void {
    const w = this.world!;
    const owned = w.systems.filter((s) => this.systems.get(s.id)!.owner !== null);
    if (owned.length === 0) return;
    const target = this.pick(owned);
    const st = this.systems.get(target.id)!;
    st.blockade = 4 + Math.floor(this.rnd() * 5);
    st.activity = Math.max(st.activity, 1);
    events.push(this.evt('RouteRaided', [st.owner ?? '?'], String(target.id), t));
  }

  private tryCreateRoute(initial: boolean, events?: PublicEvent[], t = 0): void {
    const f = this.pick(this.factions.filter((x) => !x.eliminated));
    const owned = this.ownedSystems(f.id);
    if (owned.length < 2) return;
    const a = this.pick(owned);
    // Nearest other owned (same faction) system not already linked.
    let b: DemoSystem | null = null;
    let bestD = Infinity;
    for (const o of owned) {
      if (o.id === a.id) continue;
      const d = (o.x - a.x) ** 2 + (o.y - a.y) ** 2;
      const key = this.routeKey(a.id, o.id);
      if (d < bestD && !this.routes.has(key)) {
        bestD = d;
        b = o;
      }
    }
    if (!b) return;
    const id = this.routeKey(a.id, b.id);
    this.routes.set(id, {
      routeId: id,
      fromSystemId: String(a.id),
      toSystemId: String(b.id),
      ownerFactionId: f.id,
      kind: 'trade',
      active: true,
      asOfTick: t,
    });
    if (!initial && events) {
      events.push(this.evt('RouteEstablished', [f.id], String(a.id), t));
    }
  }

  private routeKey(a: number, b: number): string {
    return a < b ? 'rt' + a + '_' + b : 'rt' + b + '_' + a;
  }

  private raidRoute(events: PublicEvent[], t: number): void {
    const active = [...this.routes.values()].filter((r) => r.active);
    if (active.length === 0) return;
    const r = this.pick(active);
    // Mark contested for a while, then it heals back to trade.
    this.routes.set(r.routeId, { ...r, kind: 'contested', asOfTick: t });
    events.push(this.evt('RouteRaided', [r.ownerFactionId], r.fromSystemId, t));
    // Heal scheduling handled lazily: a later create pass flips kind back.
    if (this.chance(0.5)) {
      this.routes.set(r.routeId, { ...r, kind: 'trade', asOfTick: t });
    }
  }

  private doDiplomacy(events: PublicEvent[], t: number): void {
    const a = this.pick(this.factions);
    const b = this.pick(this.factions);
    if (a.id === b.id) return;
    const cur = this.getRelation(a.id, b.id);
    const roll = this.rnd();
    if (cur === 'war') {
      if (roll < 0.5) {
        this.setRelation(a.id, b.id, 'truce', 0.0);
        events.push(this.evt('TreatySigned', [a.id, b.id], undefined, t));
      }
    } else if (cur === 'allied') {
      if (roll < 0.2) {
        this.setRelation(a.id, b.id, 'neutral', -0.1);
        events.push(this.evt('TreatyBroken', [a.id, b.id], undefined, t));
      }
    } else {
      if (roll < 0.3) {
        this.setRelation(a.id, b.id, 'war', -0.6);
        events.push(this.evt('WarDeclared', [a.id, b.id], undefined, t));
      } else if (roll < 0.55) {
        this.setRelation(a.id, b.id, 'allied', 0.6);
        events.push(this.evt('AllianceFormed', [a.id, b.id], undefined, t));
      } else if (roll < 0.7) {
        this.setRelation(a.id, b.id, 'tribute', 0.3);
        events.push(this.evt('TreatySigned', [a.id, b.id], undefined, t));
      }
    }
    // Nudge reputations toward the relation tone.
    a.reputation = clampRep(a.reputation + (this.rnd() - 0.5) * 0.1);
    b.reputation = clampRep(b.reputation + (this.rnd() - 0.5) * 0.1);
  }

  private recomputeIncome(): void {
    for (const f of this.factions) {
      const n = this.systemCount(f.id);
      f.income = {
        credits: 4 + n * 2.4,
        minerals: 3 + n * 1.8,
        energy: 2 + n * 1.4,
        alloys: 1 + n * 0.7,
        influence: 0.6 + n * 0.25,
      };
    }
  }

  private advancePlayerEmpire(t: number): void {
    // Builds progress; completed ones are replaced with a fresh order.
    this.builds = this.builds.map((b) => {
      const np = Math.min(1, b.progress + 0.06 + this.rnd() * 0.05);
      return { ...b, progress: np, etaTicks: Math.max(0, Math.round((1 - np) * 14)) };
    });
    if (this.builds.some((b) => b.progress >= 1)) {
      this.builds = this.builds.filter((b) => b.progress < 1);
      this.builds.push(this.makeBuild(0.02));
    }
    while (this.builds.length < 3) {
      this.builds.push(this.makeBuild(0.02));
    }

    // Tech ladder advances (advanced nodes take far longer — see TIER_TIME).
    this.advanceResearch();

    // Planets: keep in sync with ownership, grow population, complete buildings.
    this.syncPlanetsToOwnership(true);
    for (const p of this.playerPlanets.values()) {
      const cap = POPCAP_BY_SIZE[p.sizeIdx];
      if (p.population < cap) {
        p.population = Math.min(cap, p.population + cap * 0.004 * (0.6 + this.rnd()));
      }
      if (p.terraform) {
        p.terraform = { ...p.terraform, progress: Math.min(1, p.terraform.progress + 0.01) };
        if (p.terraform.progress >= 1) {
          p.biome = p.terraform.target;
          p.terraform = TERRAFORM_CHAIN[p.biome]
            ? { target: TERRAFORM_CHAIN[p.biome], progress: 0 }
            : null;
        }
      }
      // Occasionally finish a queued building into an empty slot.
      if (p.buildings.length < p.slotsTotal && this.chance(0.02)) {
        const pick = BUILDING_CATALOG[Math.floor(this.rnd() * BUILDING_CATALOG.length)];
        p.buildings.push({ type: pick.type, name: pick.name, tier: 1 });
      }
    }

    // Megastructures: start newly-unlocked ones, advance the rest.
    this.advanceMegastructures();

    // Fleets: dispatch idle ones, advance transits.
    this.advanceFleets(t);

    // Trades drift; pending ones can activate.
    this.trades = this.trades.map((tr) => {
      let status = tr.status;
      if (status === 'pending' && this.chance(0.25)) status = 'active';
      const bal = tr.balancePerTick + Math.round((this.rnd() - 0.5) * 4);
      return { ...tr, balancePerTick: bal, status };
    });
  }

  /** Begin any megastructure whose tech is unlocked and not yet started; advance all. */
  private advanceMegastructures(): void {
    const owned = this.ownedSystems(this.playerFactionId());
    if (owned.length === 0) return;
    for (const def of MEGA_DEFS) {
      const exists = this.playerMega.some((m) => m.def.kind === def.kind);
      if (!exists && this.techDone(def.reqTech)) {
        // Anchor it at the capital system (or the first owned).
        const cap = [...this.playerPlanets.values()].find((p) => p.isCapital);
        const sysId = cap?.systemId ?? owned[0].id;
        this.playerMega.push({
          id: 'm' + this.nextMegaId++,
          def,
          systemId: sysId,
          stage: 0,
          progress: 0,
        });
      }
    }
    for (const m of this.playerMega) {
      if (m.stage >= m.def.stageWatts.length) continue;
      // Higher tiers build more slowly (spectacle of scale).
      const rate = m.def.tier === 1 ? 0.018 : m.def.tier === 2 ? 0.009 : 0.005;
      m.progress += rate * (0.7 + this.rnd() * 0.6);
      if (m.progress >= 1) {
        m.stage = Math.min(m.def.stageWatts.length, m.stage + 1);
        m.progress = m.stage >= m.def.stageWatts.length ? 1 : 0;
      }
    }
  }

  /** Dispatch idle fleets to a destination; advance those in transit. */
  private advanceFleets(t: number): void {
    const owned = this.ownedSystems(this.playerFactionId());
    if (owned.length === 0) return;
    // Refresh composition as new hulls unlock.
    for (const f of this.playerFleets) {
      if (f.status === 'idle' || f.status === 'defending') {
        // Occasionally send an idle fleet somewhere.
        if (this.chance(0.12)) {
          const dest = this.pick(owned);
          if (dest.id !== f.curSystemId) {
            const dist = this.systemDistance(f.curSystemId, dest.id);
            const eta = Math.max(2, Math.round(dist / 80) + 2 + Math.floor(this.rnd() * 4));
            f.originSystemId = f.curSystemId;
            f.destSystemId = dest.id;
            f.etaTicks = eta;
            f.totalEta = eta;
            f.status = 'moving';
            const atWar = this.empireAtWar();
            f.mission = atWar && this.chance(0.5) ? 'invade' : this.pick(['patrol', 'reinforce', 'escort', 'explore'] as const);
          }
        }
      } else if (f.status === 'moving' || f.status === 'returning') {
        f.etaTicks = Math.max(0, f.etaTicks - 1);
        if (f.etaTicks === 0 && f.destSystemId !== null) {
          f.curSystemId = f.destSystemId;
          f.originSystemId = null;
          f.destSystemId = null;
          f.holdTicks = 3 + Math.floor(this.rnd() * 6);
          // Engaged if arriving into a contested/at-war border, else defending.
          f.status = this.empireAtWar() && this.chance(0.4) ? 'engaged' : 'defending';
        }
      } else if (f.status === 'engaged') {
        if (this.chance(0.25)) f.status = 'defending';
      }
      if ((f.status === 'defending') && f.holdTicks > 0) {
        f.holdTicks--;
      }
    }
    void t;
  }

  private empireAtWar(): boolean {
    const pid = this.playerFactionId();
    return this.factions.some(
      (x) => x.id !== pid && this.getRelation(pid, x.id) === 'war',
    );
  }

  private systemDistance(a: number, b: number): number {
    const sa = this.world!.systems.find((s) => s.id === a);
    const sb = this.world!.systems.find((s) => s.id === b);
    if (!sa || !sb) return 200;
    return Math.hypot(sa.x - sb.x, sa.y - sb.y);
  }

  private systemName(id: number): string {
    return this.world!.systems.find((s) => s.id === id)?.name ?? 'Deep Space';
  }

  private maybeVictory(events: PublicEvent[], t: number): void {
    const total = this.world!.systems.length;
    for (const f of this.factions) {
      if (this.systemCount(f.id) / total > 0.6) {
        events.push(this.evt('VictoryAchieved', [f.id], undefined, t));
      }
    }
  }

  // ---- publish to stores ----

  private publishAll(): void {
    const t = this.tick();
    const w = this.world!;

    // Faction snapshots (public).
    const snaps: FactionSnapshot[] = this.factions.map((f) => ({
      factionId: f.id,
      name: f.name,
      colour: f.colour,
      resources: {
        credits: Math.round(f.resources.credits),
        minerals: Math.round(f.resources.minerals),
        influence: Math.round(f.resources.influence),
      },
      reputation: f.reputation,
      systemCount: this.systemCount(f.id),
      eliminated: f.eliminated,
    }));
    this.factionStore.applyTick({ tick: t, factions: snaps });

    // Overlay systems + routes.
    const systems: SystemOverlay[] = w.systems.map((s) => {
      const st = this.systems.get(s.id)!;
      return {
        systemId: String(s.id),
        ownerFactionId: st.owner,
        activityLevel: st.battle > 0 ? 2 : st.activity,
        blockaded: st.blockade > 0,
        battle: st.battle > 0,
        asOfTick: t,
      };
    });
    this.overlayStore.replaceAll(systems, [...this.routes.values()], t);

    // Tick heartbeat for the event feed badge.
    this.eventsStore.applyTick({ tick: t, phase: 'resolve', startedAt: '' });

    // Owner-private empire detail (dashboard).
    this.empireStore.set(this.buildEmpireState());
  }

  private buildEmpireState(): EmpireState {
    const pid = this.playerFactionId();
    const f = this.factions.find((x) => x.id === pid)!;
    const owned = this.ownedSystems(pid);
    const relations: Relation[] = this.factions
      .filter((x) => x.id !== pid)
      .map((x) => ({
        factionId: x.id,
        status: this.getRelation(pid, x.id),
        opinion: this.opinions.get(this.relKey(pid, x.id)) ?? 0,
      }));

    const planets = [...this.playerPlanets.values()].map((p) => this.planetToDetail(p));
    const kardashev = this.computeKardashev(planets);
    const fleets: FleetSummary[] = this.playerFleets.map((fl) => this.fleetToSummary(fl));
    const wars = this.buildWars(pid);
    const megastructures = this.megaToSummaries();
    const tech = this.techStates();
    const research = this.deriveResearch();
    const totalPop = planets.reduce((s, p) => s + p.population, 0);

    return {
      factionId: pid,
      ledger: {
        credits: { value: Math.round(f.resources.credits), delta: round1(f.income.credits) },
        minerals: { value: Math.round(f.resources.minerals), delta: round1(f.income.minerals) },
        energy: { value: Math.round(f.resources.energy), delta: round1(f.income.energy) },
        alloys: { value: Math.round(f.resources.alloys), delta: round1(f.income.alloys) },
        influence: { value: Math.round(f.resources.influence), delta: round1(f.income.influence) },
      },
      builds: this.builds,
      research,
      trades: this.trades,
      fleets,
      relations,
      systemCount: owned.length,
      planetCount: planets.length,
      population: Math.round(totalPop),
      planets,
      wars,
      megastructures,
      tech,
      kardashev,
    };
  }

  // ---- empire-state assembly helpers ----

  /** Per-tick yields for a planet after buildings + tech multipliers. */
  private planetYields(p: MutablePlanet): PlanetDetail['yields'] {
    const biome = BIOME_BY_KEY.get(p.biome) ?? BIOMES[1];
    const y = { energy: biome.energy, minerals: biome.minerals, food: biome.food, tech: biome.tech };
    for (const b of p.buildings) {
      switch (b.type) {
        case 'mine': y.minerals += b.tier * 2; break;
        case 'solarArray': y.energy += b.tier * 2; break;
        case 'fusionPlant': y.energy += b.tier * 3; break;
        case 'farm': y.food += b.tier * 2; break;
        case 'researchLab': y.tech += b.tier * 2; break;
        case 'capital': y.energy += 1; y.minerals += 1; y.food += 1; y.tech += 1; break;
        default: break;
      }
    }
    if (this.techDone('improvedExtraction')) { y.minerals *= 1.25; y.energy *= 1.15; }
    if (this.techDone('hydroponics')) y.food *= 1.25;
    if (this.techDone('fusionEconomy')) y.energy *= 1.3;
    if (this.techDone('singularityReactors')) y.energy *= 1.4;
    return {
      energy: round1(y.energy), minerals: round1(y.minerals),
      food: round1(y.food), tech: round1(y.tech),
    };
  }

  /** Watts a planet contributes to the planetary-grid capture. */
  private planetCaptureWatts(p: MutablePlanet): number {
    const energyBld = p.buildings.filter(
      (b) => b.type === 'solarArray' || b.type === 'fusionPlant' || b.type === 'capital',
    ).length;
    let w = 8e13 + p.population * 4e10 + energyBld * 2e14;
    if (this.techDone('fusionEconomy')) w *= 1.5;
    if (this.techDone('singularityReactors')) w *= 1.5;
    if (this.techDone('planetaryUnification')) w *= 1.6;
    if (p.isCapital) w *= 1.6;
    return w;
  }

  private planetToDetail(p: MutablePlanet): PlanetDetail {
    const biome = BIOME_BY_KEY.get(p.biome) ?? BIOMES[1];
    const spec = p.isCapital ? 'Capital' : biome.tag;
    return {
      id: p.id,
      name: p.name,
      systemId: String(p.systemId),
      systemName: this.systemName(p.systemId),
      biome: p.biome,
      size: PLANET_SIZES[p.sizeIdx],
      population: Math.round(p.population),
      maxPopulation: POPCAP_BY_SIZE[p.sizeIdx],
      slotsUsed: p.buildings.length,
      slotsTotal: p.slotsTotal,
      buildings: p.buildings,
      yields: this.planetYields(p),
      terraform: p.terraform,
      specialisation: spec,
      captureWatts: this.planetCaptureWatts(p),
      isCapital: p.isCapital,
    };
  }

  /** Total captured power broken down by source → continuous Kardashev value. */
  private computeKardashev(planets: readonly PlanetDetail[]): KardashevState {
    const buckets: Record<EnergySourceKind, number> = {
      planetaryGrid: 0,
      orbitalCollectors: 0,
      dysonSwarm: 0,
      stellarEngine: 0,
      blackHoleTap: 0,
    };
    for (const p of planets) buckets.planetaryGrid += p.captureWatts;
    for (const m of this.playerMega) {
      let w = 0;
      for (let i = 0; i < m.stage; i++) w += m.def.stageWatts[i];
      if (m.stage < m.def.stageWatts.length) w += m.def.stageWatts[m.stage] * m.progress;
      buckets[m.def.source] += w;
    }
    const sources: EnergySource[] = (Object.keys(buckets) as EnergySourceKind[])
      .map((kind) => ({ kind, label: ENERGY_SOURCE_LABELS[kind], watts: buckets[kind] }))
      .filter((s) => s.watts > 1)
      .sort((a, b) => b.watts - a.watts);
    const totalWatts = sources.reduce((s, x) => s + x.watts, 0);
    const k = kFromWatts(totalWatts);
    const band = bandForK(k);
    return {
      k,
      tierId: band.id,
      tierLabel: band.label,
      totalWatts,
      progressToNext: progressToNextTier(k),
      wattsToNext: wattsToNextTier(k),
      sources,
    };
  }

  private fleetToSummary(fl: RichFleet): FleetSummary {
    const moving = fl.status === 'moving' || fl.status === 'returning';
    return {
      id: fl.id,
      name: fl.name,
      strength: this.fleetStrength(fl),
      status: fl.status,
      location: this.systemName(fl.curSystemId),
      mission: fl.mission,
      ships: fl.ships,
      originName: moving && fl.originSystemId !== null ? this.systemName(fl.originSystemId) : undefined,
      destName: moving && fl.destSystemId !== null ? this.systemName(fl.destSystemId) : undefined,
      destSystemId: moving && fl.destSystemId !== null ? String(fl.destSystemId) : undefined,
      originSystemId: moving && fl.originSystemId !== null ? String(fl.originSystemId) : undefined,
      etaTicks: moving ? fl.etaTicks : undefined,
      progress: moving && fl.totalEta > 0 ? 1 - fl.etaTicks / fl.totalEta : undefined,
    };
  }

  private buildWars(pid: string): WarSummary[] {
    const t = this.tick();
    const out: WarSummary[] = [];
    const enemies = this.factions.filter(
      (x) => x.id !== pid && this.getRelation(pid, x.id) === 'war',
    );
    // Drop stats for ended wars.
    for (const key of [...this.warStats.keys()]) {
      if (!enemies.some((e) => e.id === key)) this.warStats.delete(key);
    }
    const myCount = this.systemCount(pid);
    for (const e of enemies) {
      let stat = this.warStats.get(e.id);
      if (!stat) {
        stat = { since: t, won: 0, lost: 0 };
        this.warStats.set(e.id, stat);
      }
      // Slowly tally battles for flavour.
      if (this.chance(0.06)) { if (this.chance(0.55)) stat.won++; else stat.lost++; }
      const enemyCount = this.systemCount(e.id);
      const warScore = clampRep((myCount - enemyCount) / Math.max(1, myCount + enemyCount) + (stat.won - stat.lost) * 0.04);
      out.push({
        enemyFactionId: e.id,
        sinceTick: stat.since,
        warScore,
        battlesWon: stat.won,
        battlesLost: stat.lost,
        fronts: this.warFronts(pid, e.id),
        fleetsEngaged: this.playerFleets.filter((fl) => fl.status === 'engaged').length,
      });
    }
    return out;
  }

  /** Up to 3 contested systems on the border between us and an enemy. */
  private warFronts(pid: string, enemyId: string): WarSummary['fronts'] {
    const w = this.world!;
    const mine = this.ownedSystems(pid);
    const fronts: { systemId: string; systemName: string; intensity: number }[] = [];
    for (const s of mine) {
      // nearest enemy system
      let nearD = Infinity;
      for (const o of w.systems) {
        if (this.systems.get(o.id)!.owner !== enemyId) continue;
        const d = (o.x - s.x) ** 2 + (o.y - s.y) ** 2;
        if (d < nearD) nearD = d;
      }
      if (nearD < 240 * 240) {
        const st = this.systems.get(s.id)!;
        fronts.push({ systemId: String(s.id), systemName: s.name, intensity: st.battle > 0 ? 2 : Math.max(st.activity, 1) });
      }
    }
    fronts.sort((a, b) => b.intensity - a.intensity);
    return fronts.slice(0, 3);
  }

  private megaToSummaries(): Megastructure[] {
    return this.playerMega.map((m) => {
      let outputWatts = 0;
      for (let i = 0; i < m.stage; i++) outputWatts += m.def.stageWatts[i];
      if (m.stage < m.def.stageWatts.length) outputWatts += m.def.stageWatts[m.stage] * m.progress;
      const etaTicks = m.stage >= m.def.stageWatts.length
        ? 0
        : Math.max(1, Math.round((1 - m.progress) * (m.def.tier === 1 ? 55 : m.def.tier === 2 ? 110 : 200)));
      return {
        id: m.id,
        kind: m.def.kind,
        name: m.def.name,
        systemName: this.systemName(m.systemId),
        stage: m.stage,
        maxStage: m.def.stageWatts.length,
        progress: m.stage >= m.def.stageWatts.length ? 1 : m.progress,
        etaTicks,
        outputWatts,
        tier: m.def.tier,
      };
    });
  }

  private techStates(): TechNodeState[] {
    return TECH_TREE.map((def) => {
      const st = this.playerTech.get(def.id)!;
      const etaTicks = st.status === 'done'
        ? 0
        : Math.max(0, Math.ceil((1 - st.progress) * def.timeTicks));
      return { id: def.id, status: st.status, progress: st.progress, etaTicks };
    });
  }

  /** Derive the empire-rail research list (active + recently completed) from tech. */
  private deriveResearch(): ResearchProject[] {
    const out: ResearchProject[] = [];
    const toField = (def: TechDef): TechField => def.field;
    if (this.researchingId) {
      const def = TECH_BY_ID.get(this.researchingId)!;
      const st = this.playerTech.get(def.id)!;
      out.push({
        id: def.id, name: def.name, field: toField(def),
        progress: st.progress, etaTicks: Math.max(1, Math.ceil((1 - st.progress) * def.timeTicks)),
        done: false,
      });
    }
    const done = TECH_TREE.filter((d) => this.playerTech.get(d.id)?.status === 'done').slice(-6);
    for (const def of done) {
      out.push({ id: def.id, name: def.name, field: toField(def), progress: 1, etaTicks: 0, done: true });
    }
    return out;
  }
}

function clampRep(v: number): number {
  return v < -1 ? -1 : v > 1 ? 1 : v;
}
function round1(v: number): number {
  return Math.round(v * 10) / 10;
}
