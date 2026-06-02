# Game Design 02 — Resources & Economy

The economy is half the game. It is what diplomacy negotiates over and what war is fought for.

## 1. The five resources

| Resource | Primary source | Primary use |
|---|---|---|
| **Energy** | Desert/gas worlds, solar slots | Powers buildings & fleets (upkeep); refining |
| **Minerals** | Arid/volcanic worlds, mines | Construction (buildings, ships) |
| **Food** | Oceanic/terran worlds, farms | Population growth; fleet & colony sustenance |
| **Tech** | Frozen worlds, labs | Research; advanced buildings & ship tiers |
| **Influence** | Capitals, diplomacy, trade volume, monuments | Treaties, claims, alliance votes, prestige victory |

Energy/Minerals/Food/Tech are **physical** (stored, traded, hauled along routes, can be raided). **Influence** is **political capital** — it is not hauled; it accrues and is spent on diplomatic and prestige actions. Keeping Influence un-tradeable as a commodity (you can only *grant* it via treaty terms, not sell it on the open market) prevents a pay-to-win spiral and keeps it tied to behaviour.

## 2. Production, upkeep, and deficits

- **Production**: each colonised planet yields resources per tick = base(biome) × buildings × tech multipliers × population factor.
- **Upkeep**: buildings and fleets consume resources per tick (mostly Energy and Food). 
- **Deficit handling**: if a faction cannot pay upkeep, it suffers **attrition** — fleets lose strength, buildings go idle, population shrinks — rather than going to impossible negative balances. This makes overextension a real, self-correcting risk and a lever opponents can exploit (cut a faction's trade and watch it starve).

## 3. Population

Each colonised planet has a **population** that grows with Food surplus and habitability (biome), up to a cap raised by buildings/tech. Population scales production and provides the soft-power base for Influence. War, blockade, and famine reduce it. Population is slow to rebuild — losing a developed world hurts for a long time.

## 4. The market — emergent prices via an order book

There is no fixed price list. Each **trade hub** (a system with a market building) runs an **order book** per physical resource:

- Sovereigns post **limit orders**: `BUY n Minerals @ ≤ p` or `SELL n Energy @ ≥ p`.
- Each tick the engine **matches** crossing orders by **price-time priority** and clears trades at the resting order's price.
- **Prices emerge** from supply and demand and differ between hubs, creating arbitrage and meaningful trade routes.

### Escrow & settlement

All market trades and treaty payments are **escrowed by the engine**. A Sovereign cannot offer resources it does not have, and cannot renege mid-transfer. Physical goods bought at a remote hub must still be **hauled home along routes** (a freighter task), so logistics and route security matter — an unescorted trade route is a target.

## 5. Trade routes as economic infrastructure

Activating a **trade route** between two systems:
- Establishes recurring throughput (configurable volume) of agreed resources.
- Generates **Influence** for both endpoints proportional to volume (commerce builds soft power).
- Is visible on the map and can be **raided** (steal a shipment) or **blockaded** (choke the throughput) by hostile fleets — a sub-war-threshold pressure tactic.
- Has a **kind** that the renderer colours: allied (intra-alliance, protected), commercial (cross-faction trade pact), contested (runs through disputed space).

## 6. Construction & the build economy

Buildings occupy planet **slots** and cost Minerals (+ Tech for advanced ones) and time (ticks):

| Building | Effect |
|---|---|
| Mine | +Minerals |
| Solar array | +Energy |
| Farm | +Food |
| Research lab | +Tech |
| Market hub | enables an order book at this system; +trade capacity |
| Shipyard | enables fleet construction here |
| Defense platform | system defensive bonus in combat |
| Monument | large one-time + ongoing Influence (prestige play) |
| Terraformer | slowly upgrades a hostile biome toward habitable |

Slots are limited per planet, so every colony is a small optimisation puzzle: specialise (all mines on a mineral world) or balance.

## 7. Economic victory pressure

Because Influence accrues from capitals, trade volume, monuments and honoured diplomacy, a Sovereign can pursue an **economic/prestige path**: out-trade and out-build rivals, lead the commercial web, and win on accumulated Influence without ever winning a major battle — provided it can keep its routes alive. That "provided" is where the military and diplomatic systems bite.

> All numbers above (yields, costs, upkeep, throughput) are **balance config**, externalised, never hardcoded.
