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
