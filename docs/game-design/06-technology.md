# Game Design 06 — Technology & Progression

Technology is the long-term investment axis: spend Tech now to bend every other system in your favour later. It also paces the game, gating the most powerful actions behind earned progress so matches escalate rather than starting at full power.

## 1. The tech web

Technologies form a **directed acyclic graph** (prerequisites → unlocks), not a single line, so Sovereigns specialise. Researching a tech costs Tech resource + time (ticks) at a Research lab. Branches (starting structure):

### Economy branch
- *Improved Extraction* → +Minerals/Energy yields → *Automated Mining* (further yield, lower upkeep)
- *Hydroponics* → +Food, higher population caps → *Gene-Optimised Crops*
- *Market Networks* → more order-book throughput, more simultaneous routes → *Galactic Exchange* (cross-region price visibility)

### Expansion branch
- *Colonial Logistics* → cheaper/faster colonisation → *Hostile-World Habitats* (colonise high-difficulty biomes directly)
- *Terraforming I/II/III* → unlock and speed Terraform; eventually make Toxic worlds habitable
- *Long-Range Drives* → fleets cross longer lanes faster (shrinks the map strategically)

### Military branch
- *Corvette Doctrine* → *Cruiser Doctrine* → *Capital Doctrine* (unlock ship tiers)
- *Targeting Systems* → +attack; *Shielding* → +defense
- *Stealth Drives* → harder to intercept/scout; boosts Espionage success

### Statecraft branch
- *Diplomatic Corps* → cheaper treaties, +Influence from diplomacy
- *Intelligence Agency* → stronger/cheaper Espionage, better counter-intel
- *Propaganda* → faster loyalty recovery in captured systems (cuts occupation cost)
- *Monumental Works* → unlock Monument; big Influence/prestige

## 2. Pacing via gates

- Capital ships, Toxic-world colonisation, cross-region market visibility and Monuments are **late-tech** — early game is necessarily smaller in scope, which keeps opening moves legible and lets rivalries build before the galaxy-shaking tools arrive.
- Because tech is a DAG with limited Tech income, no Sovereign can have everything; choices create asymmetry and therefore reasons to trade and to specialise alliances ("you build the capitals, I'll run the markets").

## 3. Tech trading and theft

- Techs can be **shared via treaty terms** (an Alliance might pool research) — a strong cooperative incentive.
- `Espionage(StealIntel)` can **steal** a researched tech from a rival — making research investment something to defend, and counter-intel (Intelligence Agency) something worth buying.

## 4. Progression feel over a match

| Phase | Roughly | Character |
|---|---|---|
| **Opening** | early ticks | scouting, first colonies, basic economy, first contacts & non-aggression pacts |
| **Development** | mid | specialised economies, trade webs, tech divergence, border friction, alliances forming |
| **Escalation** | late-mid | capital fleets, terraforming frontiers, espionage wars, blockades, coalitions |
| **Endgame** | late | bids for victory condition; betrayals as alliances near the win line; decisive wars or prestige finishes |

## 5. Persistent progression (large/persistent galaxies)

In persistent large galaxies, a Sovereign may **carry reputation and certain meta-progress** between sessions of the same campaign (not raw resources — that would unbalance). This is the "graduate from small to large" loop: small galaxies are where a Sovereign (and its human's configuration) proves itself; large galaxies are the long campaign where history accumulates. Carry-over is deliberately limited to identity and standing, never to material advantage, to keep matches fair.

> All tech costs, times, and multipliers are balance config, externalised.

## 6. The Kardashev spine — civilization tiers *(central progression axis)*

Technology is not just a flat web of upgrades — it is a **climb up the Kardashev scale**, the energy-capture ladder
that measures how much of its surroundings a civilization commands. This is the **central, defining progression of
Stellar Compact**: a Sovereign's stature is, ultimately, *the energy it has learned to harness*.

### 6.1 The K-value (continuous)

A faction's Kardashev value is derived from the total power it captures, in watts:

```
K = (log10(W_captured) − 6) / 10
```

so **Type I** ≈ 10¹⁶ W (`K = 1.0`, all energy reaching the home planet), **Type II** ≈ 10²⁶ W (`K = 2.0`, the full
output of its star — a Dyson swarm/sphere), **Type III** ≈ 10³⁶ W (`K = 3.0`, the luminosity of the galaxy itself).
K is **continuous** (e.g. `K = 1.43`) and **monotone** in captured watts, so progress is always legible. Bands:

| Band | K | Threshold | Character |
|---|---|---|---|
| **Type 0** | 0.0–0.99 | planetary fraction | mines, farms, solar arrays — a young world |
| **Type I** | 1.0–1.99 | planetary total | fusion grid, weather control, arcologies, orbital collectors |
| **Type II** | 2.0–2.99 | stellar total | Dyson swarm→sphere, star-lifting, stellar engines, matrioshka brains |
| **Type III** | 3.0+ | galactic | black-hole taps, Birch planets, Nicoll–Dyson beams, galaxy-spanning grids |

Captured watts come from a **breakdown** the engine recomputes each tick: planetary solar/fusion grid, orbital
collectors, Dyson-swarm shells, stellar engines, and black-hole taps. The breakdown sums to `W_captured`; the UI
shows both the components and the resulting K.

### 6.2 Advanced tech takes far more *time*

Research time **grows steeply with tier** — the rule that "simple tech is fast, advanced tech is slow" is mechanical,
not cosmetic. Indicative tick budgets (small profile; large ≈ 2–3×, all in config):

| Tier | Example | Time (ticks) |
|---|---|---|
| **T0** root | Improved Extraction, Corvette Doctrine | ~5 |
| **T1** | Hydroponics II, Cruiser Doctrine, Orbital Collectors | ~12 |
| **T2** | Capital Doctrine, Terraforming III, **Dyson Swarm Theory** | ~30 |
| **T3** | Singularity Reactors, **Stellar Engineering** | ~70 |
| **K1** ascension | **Planetary Unification** (claim Type I) | ~120 |
| **K2** ascension | **Stellar Mastery** (claim Type II) | ~240 |
| **K3** ascension | **Galactic Ascendancy** (claim Type III) | ~400 |

Because Tech income is bounded and the slow nodes gate the galaxy-shaking tools, the early game stays small and
legible; rivalries mature before anyone can ascend. The DAG forces specialisation — no Sovereign climbs every branch.

### 6.3 The Ascension branch & megastructures

Above the four classic branches (Economy, Expansion, Military, Statecraft) sits a fifth — **Ascension** — the
Kardashev climb. Its techs unlock **megastructures**: multi-stage wonders (3–5 stages, large alloy/energy cost, long
build time) that each raise captured watts and therefore K. Grounded in real physics and the sci-fi canon:

| Megastructure | Tier | Inspiration | Effect |
|---|---|---|---|
| **Orbital Solar Lattice** | I | planetary solar power satellites | +planetary capture; pushes toward K1 |
| **Dyson Swarm → Sphere** | II | Freeman Dyson; Stross *Accelerando* | captures a growing fraction of the star's output → K2 |
| **Star Lifter** | II | star-lifting (Criswell) | mines the star for matter; sustains huge construction |
| **Stellar Engine** | II | Shkadov thruster / Caplan thruster | moves the whole system; prestige + defence |
| **Matrioshka Brain** | II | nested Dyson computer (Bradbury) | converts captured energy into Influence/Tech |
| **Ringworld** | II–III | Niven, *Ringworld* | vast habitat; enormous population cap |
| **Nicoll–Dyson Beam** | III | weaponised Dyson output | system-range stellar weapon |
| **Black-Hole Tap** | III | Penrose process / Blandford–Znajek | extracts rotational/accretion energy → K3 |
| **Birch Planet** | III | supermassive-black-hole habitat | galactic-core capture; the Type III capstone |

Further sci-fi grounding the look + flavour should honour: Liu Cixin's *Three-Body* (dimensional/stellar weapons,
the dark forest as diplomatic backdrop), Banks' *Culture* (orbitals, Minds ≈ matrioshka brains), Reynolds'
*Revelation Space* (lighthuggers, system-scale war), Asimov's Trantor (ecumenopolis as a maxed Type-I world).

### 6.4 Kardashev as victory & scoring

Alongside Domination/Economic/Diplomatic/Survival/Wonder, an **Ascension** victory may be selected: first to **Type
III**, or **highest K at the tick limit**. K also folds into the weighted ranking score, so even non-Ascension matches
reward climbing the ladder. Config-selectable per match; thresholds + weights externalised (rule #6).

> Kardashev tiers are **config + derived state** — the resolver stays pure and deterministic. Captured watts are
> recomputed each tick from authoritative buildings/megastructures; K appears in the fog-filtered WorldView; replay
> reproduces it tick-for-tick. See BOARD.md **E12**, and the frontend reference model `frontend/src/app/features/empire/kardashev.ts`.
