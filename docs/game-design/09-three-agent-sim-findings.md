# 3-Agent 1-Hour Match — Simulation Findings & Recommended Changes

> Source run: `backend/orchestrator` test `ThreeAgentHourMatchTest`
> (`target/three-agent-hour-report.txt`). Three `ScriptedSovereign` bots, seed
> `0x3a9e27c0ffee3`, profile `small-default`, **720 ticks = 1 game-hour** at the
> small-galaxy 5 s cadence (`TickProperties.DEFAULT_INTERVAL`). Determinism verified:
> replaying the recorded `(seed, action log)` reproduced all 720 tick hashes.

## What happened (raw result)

| faction | home planets | buildings | minerals (start→end) | energy (start→end) | influence (end) | owned systems |
|---------|-------------|-----------|----------------------|--------------------|-----------------|---------------|
| alpha   | 5           | 19        | 5000 → 19 310        | 5000 → **0**       | 49              | 1 → 1 |
| beta    | 1           | 4         | 5000 → 7 746         | 5000 → 1 414       | 49              | 1 → 1 |
| gamma   | 7           | 20        | 5000 → **49 670**    | 5000 → **0**       | 49              | 1 → 1 |

- **Outcome: `TICK_LIMIT`** (status still `RUNNING` after a full hour). No victory can fire.
- **Action mix: `Build=43`, `Explore=2117`, `Hold=0` submitted.** Of 2160 decision slots
  (720 ticks × 3 agents), 43 were builds and **2117 were Explore** — the bots explored
  every tick for the rest of the match.
- **Public events: none.** Zero wars, treaties, trades, captures, battles all hour.
- **Still-neutral systems: 3 of 6** — no expansion happened at all.

## Findings (verified against `small-default.json` + the resolver)

### F1 — The scripted bot degenerates into an Explore busy-loop (agent bug)
`43` builds exactly fills every owned planet slot (alpha 19 + beta 4 + gamma 20 = 43;
"free slots remaining: 0" for all three). Once slots are full, `ScriptedSovereign`'s
ladder (`chooseBuild → chooseExplore → Hold`) falls through to **Explore the lowest-id
neutral neighbour** — but `Explore` reveals a system, it does **not** claim it or consume
the idle fleet, and the bot keeps **no memory**. So all three re-explore the same already-
revealed neutral **every tick for ~700 ticks** (2117 no-op Explores). Each is "valid" and
wastes a resolver slot. *The bot never colonises, never builds ships, never trades, never
negotiates* — whole engine subsystems go unexercised.

### F2 — Single-resource economy: only `MINE` is ever built, so energy starves
`ScriptedSovereign.BUILD_PREFERENCE` lists MINE/SOLAR_ARRAY/FARM/RESEARCH_LAB but the
ladder only uses `get(0)` = **MINE**. Mines cost 30 minerals (not energy) but carry
**1.0 energy/tick upkeep**. Home biome is `oceanic` (energy yield **0**). Result: alpha
and gamma drain energy to **0** and sit in permanent energy deficit (`deficitAttritionRate
0.10`). They never build a `SOLAR_ARRAY` (the energy fix that's already in the preference
list), so energy never recovers. Energy is also the **market currency** — at 0 they
couldn't trade even if they tried.

### F3 — Minerals have no sink → unbounded hoarding
Gamma ends with **49 670 minerals** doing nothing. Mines pour out minerals (arid 4 /
volcanic 5 per tick) but the bot stops building at slot-cap and never spends minerals on
ships, colonisation, or further structures. Minerals grow without bound and without value.

### F4 — Starting-position variance is decisive (1 vs 7 home planets = 7×)
Gamma drew a 7-planet home, beta a 1-planet home — a **7× economic gap from the seed
alone**, before any decision. Gamma out-produced beta ~6× on minerals. *Caveat:* this
scenario promotes the lowest-id separated stars directly and **bypasses
`HomePlacementGenerator`**, so it skips the production fairness guard
(`homePlacement.qualityToleranceFraction = 0.35`, homes within 35% quality). The real
funnel is fairer — but the run shows how much home quality dominates, and that the
fairness guard is load-bearing and worth a planet-count/economy floor, not just a
"quality" tolerance.

### F5 — A peaceful match can never conclude (victory unexercised)
`victory.active = DOMINATION` at `systemPct 0.6`. With 3 factions × 1 system + 3 neutrals
(6 total), nobody reaches 60% without colonising or conquering — which the bots never do.
So DOMINATION can't fire and every scripted match is `TICK_LIMIT`. Note `survival.tickLimit
= 300` exists but isn't the active condition; at 720 ticks a survival match would already
have ended. **No headless match currently exercises any victory path.**

### F6 — Influence asymptotes at ~50, far below the economic-victory target of 1000
`influence.perCapitalSystem = 1.0`, `decayRate = 0.02` → equilibrium `1.0/0.02 = 50`. All
three converged to **49** from a start of 100. The `economic` victory target is
**influenceTarget 1000** — unreachable for a 1-capital faction by ~20×. Reaching it needs
many monuments (`perMonument 3.0`) and/or trade volume, i.e. real expansion + building.

## Recommended changes (prioritised)

**P1 — Fix the agent so a match is non-degenerate** (`orchestrator/…/ScriptedSovereign.java`)
1. **Stop the Explore busy-loop:** only Explore systems not already revealed/explored; once
   the frontier is exhausted, prefer **Colonise** a reachable neutral, else **Hold**. (The
   colonise branch was deferred in E3-01; fog (E3-02) + lanes (E2-03) now exist to support it.)
2. **Balance the build ladder:** build `SOLAR_ARRAY` when energy upkeep ≥ production (or
   energy < floor), `FARM` when food trends negative, else `MINE`. Use the whole
   `BUILD_PREFERENCE`, not just `get(0)`.
3. Add a second **aggressive** scripted variant (build shipyard → corvettes → DeclareWar →
   Attack) so combat/diplomacy/victory paths are exercised headlessly.

**P2 — Close economy loops** (`engine/.../resources/balance/small-default.json` + resolver)
4. **Mineral sink / production taper:** diminishing mine yield past N per planet, or a
   storage cap, so minerals can't hoard to 50k with no use.
5. Re-check the **energy floor / deficit consequence:** confirm an energy-0 faction's mines
   actually stop producing (energy should be a real constraint, not cosmetic). If not, add
   brownout to mineral output under energy deficit.

**P3 — Fairness & victory reachability** (config + `HomePlacementGenerator`)
6. Add a **starting-economy floor** (min home planet count or min aggregate biome yield),
   not just `qualityToleranceFraction`, so a 1-planet home can't be dealt.
7. Reconsider `economic.influenceTarget 1000` vs the ~50 single-capital asymptote, or make
   monuments/trade the clear path and document it.

**P4 — Validation hygiene** (`engine/.../validation/ActionValidator.java`)
8. Consider rejecting a **redundant Explore** of an already-revealed system (a "valid no-op"
   today) to cut 2000+ wasted resolver slots/hour and keep the action log meaningful.

## Determinism note
The run is fully reproducible: 720/720 tick hashes matched on replay from `(seed, action
log)`. None of the above changes should touch that contract — agent-heuristic and
balance-number changes alter *outcomes* but the engine stays a pure function of
`(seed, validated action log, profile)`.
