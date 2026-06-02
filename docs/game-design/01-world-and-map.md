# Game Design 01 — World & Map Model

## 1. The galaxy is procedurally seeded, deterministic, and mostly empty

The galaxy is generated from a single `gameSeed`. Given the seed, the entire star catalog — positions, spectral classes, brightness, planet rosters — is reproducible. This is the same determinism the engine relies on, and it lets the renderer and the simulation share one source of truth without storing or shipping the whole catalog. (How that catalog scales to billions of stars is the subject of `docs/architecture/02-galaxy-scale.md`; this document is about the *game* meaning of the map.)

Crucially, **the galaxy is mostly empty space**. Only a tiny fraction of stars are colonisable/relevant at any time; the rest are scenery and future frontier. This is both realistic and the key to performance and to making expansion feel meaningful.

## 2. Hierarchy of places

```
Galaxy
└── Region (a broad sector; coarse strategic zone)
    └── System (a star + its planets; the atomic unit of ownership)
        └── Planet (colonisable body with a biome and yields)
            └── Slot (a build location on a planet: mine, lab, shipyard, defense…)
```

- **System** is the unit you *own*, *contest*, and *route between*. Ownership of a system implies control of its planets unless individually contested.
- **Planet** carries a **biome** that determines base resource yields and colonisation difficulty.
- **Slot** is where buildings go; planets have a limited number of slots (by size), creating meaningful build choices.

### Biomes (starting set)

| Biome | Favoured yield | Colonise difficulty | Notes |
|---|---|---|---|
| Oceanic | Food | Low | Comfortable cradle worlds |
| Terran | Balanced | Low | Generalist |
| Arid | Minerals | Medium | |
| Desert | Energy (solar) | Medium | |
| Volcanic | Minerals + Energy | High | Hostile; high reward |
| Frozen | Tech (research stations) | High | Sparse but valuable |
| Toxic | — | Very high | Requires terraforming first |
| Gas giant | Energy (skimming) | Special | No ground slots; orbital only; often ringed/moons |

## 3. Adjacency and travel — the lane graph

Systems are connected by **lanes** (edges in a graph). A fleet or colony ship moves along lanes; you cannot teleport across open space. Lanes have a **length** (travel cost in ticks) derived from real distance between systems.

Two lane categories:
- **Natural lanes** — exist from generation, based on proximity; the static skeleton of the map.
- **Established routes** — lanes a faction has activated for **trade** or **military** use. These are the coloured, animated lines in the PoC. A route is a first-class game object: it can be allied, commercial, or contested, it carries throughput, and it can be blockaded or raided.

This is why the PoC shows routes between stars: routes are the circulatory system of the economy and the axis of most conflict.

## 4. Fog of war & visibility

A Sovereign sees:
- **Owned systems** in full detail (planets, slots, buildings, garrisons, stockpiles).
- **Adjacent systems** (one lane away from anything it owns or has a presence in) at reduced detail — presence of fleets, ownership, rough strength.
- **Explored-but-left** systems as a *last-known* snapshot (may be stale).
- **Public events** galaxy-wide (a declared war, a broken treaty, a new alliance, a major battle) — these are announced to everyone, because reputation only works if betrayal is visible.

Everything else is dark. Exploration (`Explore`) and scouting reveal new systems; espionage can reveal hidden details of a known one.

## 5. Map scale tiers (what the player sees as they zoom)

The renderer presents the same fixed data at zoom-dependent detail (see lod-tiling skill). The *game* recognises these conceptual scales for UI and for what actions are offered:

| Scale | What's shown | Typical actions surfaced |
|---|---|---|
| Galaxy | Star points, density, owned-territory tint, major routes | strategic overview, jump-to |
| Region | Clusters of systems, route web, frontiers | fleet routing, claim planning |
| System | The star, planets, orbits, garrisons | colonise, build, defend, attack |
| Planet | Surface, slots, buildings | build/upgrade/terraform a slot |

## 6. Starting positions & fairness

- Each Sovereign starts on one **home system** with a fixed starter loadout (one colonised cradle world, a small stockpile, one scout).
- Home systems are placed by the engine with a **minimum separation** and **balanced local resource potential**, so no Sovereign begins boxed-in or starved. Placement is seeded and reproducible.
- Neutral systems between factions form the contested frontier where most early expansion races happen.
