# Game Design 03 — Actions Catalog (the closed action set)

This is **the contract between Sovereign and Engine**. Every move a Sovereign can make is one of these typed actions. The set is **closed** (a sealed hierarchy in code) so the resolver handles every case exhaustively and the agent's output schema is fully constrained. Anything not in this list cannot happen.

Each action lists: who may issue it, required fields, validation rules, and resolution effect. Invalid actions are rejected with a reason; the Sovereign gets **one** re-prompt, then idles for that action.

> Notation: fields are conceptual, not Java. The concrete JSON schema lives in `docs/specs/agent-io-schema.md`.

## A. Environment actions (agent ↔ world)

### Explore(targetSystem)
- **Requires:** targetSystem adjacent to a system the faction owns or has a fleet in; a scout available.
- **Resolves:** reveals targetSystem (planets, biomes, any present fleets) and its onward lanes. Adds it to the faction's known map.

### Colonize(planet, viaFleet)
- **Requires:** planet in a known, unowned (or owned-by-self) system; a colony ship that can reach it; resources for colonisation cost (scales with biome difficulty); planet not Toxic unless terraformed.
- **Resolves:** establishes a colony with starter population; system becomes owned if previously neutral. Hostile biomes carry a seeded failure/attrition risk.

### Build(planet, slot, buildingType)
- **Requires:** owned planet with a free slot; Minerals (+Tech) cost; for Shipyard/Market, tech prerequisites.
- **Resolves:** queues construction; building becomes active after its build time (ticks).

### Research(techId)
- **Requires:** a Research lab; Tech cost; prerequisite techs satisfied.
- **Resolves:** unlocks the tech after research time, applying its multipliers/unlocks (see 06).

### Terraform(planet)
- **Requires:** owned planet with a Terraformer; sustained Energy upkeep.
- **Resolves:** advances the planet's biome one step toward habitable over many ticks (Toxic→Arid→Terran…), raising yields and population cap.

### BuildFleet(system, shipSpec)
- **Requires:** owned system with a Shipyard; Minerals + Tech per ship; population to crew.
- **Resolves:** adds ships to a fleet at that system after build time. Ship tiers gated by tech.

### MoveFleet(fleet, viaRoute|viaLane, destination)
- **Requires:** fleet under faction control; a path of lanes/routes to destination; Energy for the move.
- **Resolves:** fleet travels over the path, arriving after total lane length in ticks; may be intercepted en route (see 05).

### EstablishRoute(systemA, systemB, kind, resources, volume)
- **Requires:** both systems reachable via lanes the faction can use; market/logistics capacity; for `allied` kind, an alliance with the other endpoint's owner.
- **Resolves:** creates a recurring trade route (see economy 05). Visible on map; raidable/blockadable.

## B. Inter-agent actions (agent ↔ agent)

### SendMessage(toFaction, text)
- **Requires:** nothing (diplomacy is always open). Free-form natural language, length-capped.
- **Resolves:** delivered to the target Sovereign's next WorldView. **No mechanical effect** on its own — talk is free and non-binding until turned into a structured proposal.

### ProposeTrade(toFaction, give, receive, [expiresIn])
- **Requires:** the `give` resources/assets are owned and currently unencumbered.
- **Resolves:** creates a **pending offer**. If the target issues `AcceptTrade` before expiry, the engine escrows and executes atomically. Either side may `Withdraw`/decline.

### AcceptTrade(offerId) / DeclineTrade(offerId) / WithdrawTrade(offerId)
- **Resolves:** completes, rejects, or cancels a pending trade. Acceptance triggers escrowed settlement.

### ProposeTreaty(toFaction, treatyType, terms, [duration])
- **treatyType ∈ { NonAggression, DefensivePact, Alliance, TradePact, Vassalage, Ceasefire }**
- **Requires:** terms are well-formed; proposer can meet any upfront terms.
- **Resolves:** pending treaty; on `AcceptTreaty`, becomes **engine-enforced** (see 04) and is announced publicly.

### AcceptTreaty(treatyId) / DeclineTreaty(treatyId)
- **Resolves:** activates or rejects. Active treaties constrain future actions (e.g. a NonAggression pact makes `Attack` against the partner illegal until broken).

### BreakTreaty(treatyId)
- **Requires:** an active treaty.
- **Resolves:** terminates it immediately, **announced galaxy-wide**, with a **reputation penalty** scaled to treaty type and remaining duration. This is allowed — betrayal is a legal move — but it is costly and visible.

### Tribute(toFaction, resources) / DemandTribute(fromFaction, resources, [orElse])
- **Resolves:** Tribute transfers resources (goodwill, appeasement, vassal dues). DemandTribute issues an ultimatum delivered to the target; compliance or refusal is the target's choice and may trigger pre-declared consequences.

## C. Military actions (agent ↔ agent, kinetic)

### DeclareWar(targetFaction)
- **Requires:** no binding NonAggression/Alliance with target (or `BreakTreaty` first).
- **Resolves:** sets a state of war (announced); enables `Attack`, `Blockade`, `Raid` against the target. Unprovoked declaration carries a reputation cost.

### Attack(fleet, targetSystem|targetFleet)
- **Requires:** state of war (or target is neutral/pirate); fleet in range.
- **Resolves:** combat (see 05). Outcomes: damage/destroy fleets; on winning a system assault, capture it (transfers ownership + surviving buildings).

### Blockade(fleet, route|system)
- **Requires:** war or contested status; fleet positioned on the route/system.
- **Resolves:** chokes throughput of that route or the system's market — economic strangulation short of assault.

### Raid(fleet, route)
- **Requires:** war or contested; fleet on the route.
- **Resolves:** intercepts a shipment, stealing part of the cargo (seeded), without capturing territory. A pirate-style harassment tool.

### Espionage(targetFaction, operationType)
- **operationType ∈ { Scout, StealIntel, Sabotage, IncitUnrest }**
- **Requires:** Influence/Tech cost; sometimes an asset near the target.
- **Resolves:** probabilistic (seeded) — reveal hidden details, steal a tech or resources, damage a building, or reduce a colony's population/loyalty. Failure can be detected and damage reputation.

## D. Meta / passive

### Hold()
- The explicit no-op. A Sovereign that submits nothing, or whose call times out, is treated as `Hold` for that slot.

---

## Resolution order (deterministic)

Within a tick the engine resolves actions in a **fixed category order**, then by faction id, then by submission order:

1. Treaty break/accept & war declarations (state changes first)
2. Espionage
3. Fleet movement & interception
4. Combat (attacks, assaults)
5. Blockade/raid effects on routes
6. Construction, research, terraform progress
7. Colonisation
8. Market matching & escrow settlement
9. Production, upkeep, population, attrition
10. Influence accrual & decay
11. Public event emission

This fixed order is part of the determinism contract — see `.claude/skills/game-engine-determinism`.
