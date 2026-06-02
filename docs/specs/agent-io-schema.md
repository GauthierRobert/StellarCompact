# Spec — Agent I/O Schema

The contract for what a Sovereign receives (WorldView) and returns (messages + actions). Described here as field semantics; the canonical machine schema will be JSON Schema generated from the engine's records/sealed types. **No implementation code in this drop.**

## 1. WorldView (engine → agent)

A compact, fog-filtered snapshot. Conceptual shape:

```
WorldView
  tick                : integer
  faction             : { id, name, reputation, influence, stockpiles{energy,minerals,food,tech}, techKnown[] }
  victoryProgress     : { condition, value, threshold }
  systems[]           : owned systems (full): { id, name, coords, planets[], garrison, buildings[], population }
  visibleNeighbours[] : fog-limited: { systemId, ownerFaction?, roughStrength, lastSeenTick }
  fleets[]            : own fleets: { id, location|enRoute, ships[], stance }
  markets[]           : reachable hubs: { systemId, bestBids[], bestAsks[] }   // top-of-book only
  treaties[]          : active: { id, type, parties[], terms, expiresTick }
  reputations[]       : { factionId, reputation }                              // public ledger
  pendingOffers[]     : addressed to me: { id, from, give, receive, expiresTick }
  events[]            : public events since last tick: { type, parties[], systemId?, tick }
  inbox[]             : messages received: { from, text, tick }
```

Design rules:
- **Top-of-book only** for markets (not the full order book) to bound size.
- **No hidden enemy state** ever included — fog filtering is authoritative server-side.
- Keep total tokens modest so small local models stay focused.

## 2. AgentResponse (agent → engine)

```
AgentResponse
  messages[]  : { to: factionId, text: string(≤cap) }          // negotiation phase
  actions[]   : Action[]                                       // action phase (ordered)
```

## 3. Action (closed set — sealed in code)

Each action is a tagged variant. The discriminator field is `type`. Variants and their payloads mirror game-design 03:

```
Action.type ∈ {
  Explore        { targetSystem }
  Colonize       { planet, viaFleet }
  Build          { planet, slot, buildingType }
  Research       { techId }
  Terraform      { planet }
  BuildFleet     { system, shipSpec }
  MoveFleet      { fleet, path[], destination }
  EstablishRoute { systemA, systemB, kind, resources[], volume }
  SendMessage    { to, text }
  ProposeTrade   { to, give, receive, expiresIn? }
  AcceptTrade    { offerId }
  DeclineTrade   { offerId }
  WithdrawTrade  { offerId }
  ProposeTreaty  { to, treatyType, terms, duration? }
  AcceptTreaty   { treatyId }
  DeclineTreaty  { treatyId }
  BreakTreaty    { treatyId }
  Tribute        { to, resources }
  DemandTribute  { from, resources, orElse? }
  DeclareWar     { target }
  Attack         { fleet, target }            // target = systemId | fleetId
  Blockade       { fleet, target }            // target = routeId | systemId
  Raid           { fleet, routeId }
  Espionage      { target, operationType }
  Hold           { }                          // explicit no-op
}
```

## 4. Validation contract

For each action the engine answers `Valid` or `Rejected{reason}`. Rejection reasons are human-readable and fed back on the single re-prompt, e.g.:
- `INSUFFICIENT_RESOURCES`, `NOT_OWNED`, `NOT_ADJACENT`, `NO_PATH`, `TREATY_FORBIDS` (with treaty id), `TECH_PREREQ_MISSING`, `NO_FREE_SLOT`, `NOT_AT_WAR`, `TARGET_UNKNOWN`, `OFFER_EXPIRED`.

## 5. Structured-output strategy (Spring AI)

- Use Spring AI's structured-output converter to push the model toward emitting valid `AgentResponse` JSON directly.
- Always **validate after parse** — never trust the model's self-reported validity.
- Defensive parsing: strip markdown fences, tolerate trailing prose, but reject ambiguous output (→ re-prompt).

## 6. Versioning

The schema is versioned (`schemaVersion`). Adding an action variant is a minor version; agents and validators must handle unknown future variants by ignoring/holding rather than crashing.
