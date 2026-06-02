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

### 4a. Validation invariants (from E1-04 security review — some checks gated on later cards)

The validator never trusts an agent-supplied id/owner/count; everything is checked against authoritative `GameState`, and rejection messages reference only actor-knowable facts (own assets, the public reputation/treaty ledger, ids the actor itself supplied). The following invariants are specified now but their enforcement depends on records/systems landing in later cards — each is a **hard prerequisite** on the named resolver card, because the check only becomes exploitable once the resolver mutates state:

- **Kinetic war-state (E1-09).** A kinetic action (`Attack`/`Blockade`/`Raid`) requires a positive war-state OR a neutral (unowned) target. Absence of a forbidding treaty is *necessary but not sufficient* — an agent must not strike a faction it is at peace-but-not-treaty with. (E1-04 enforces the treaty-forbids half; the positive war-state gate awaits the war-state record in E1-09.)
- **Lane adjacency / reachability (E2-03/E1-09).** `Explore`, `Colonize`, `MoveFleet` (incl. origin = the fleet's current location and each hop being a real lane), `EstablishRoute`, and "fleet positioned at target" for kinetic actions require the lane graph. Until then movement/adjacency is only shape-checked; the resolver MUST NOT move/fight on an unvalidated path.
- **Directed trade offers (E1-07).** The `MarketOrder`/directed-offer record must carry an **addressee** and **`expiresTick`**. `AcceptTrade`/`DeclineTrade` are valid only for offers addressed to the actor and not expired (`OFFER_EXPIRED`). E1-04 checks existence/proposer-identity only; addressee+expiry await the E1-07 record.
- **Atomic escrow at resolution (E1-05).** Affordability is a per-action *snapshot* at validation time. The resolver must debit/escrow under a single authoritative pass so N validated spends in one tick cannot collectively overdraw one stockpile.

## 5. Structured-output strategy (Spring AI)

- Use Spring AI's structured-output converter to push the model toward emitting valid `AgentResponse` JSON directly.
- Always **validate after parse** — never trust the model's self-reported validity.
- Defensive parsing: strip markdown fences, tolerate trailing prose, but reject ambiguous output (→ re-prompt).

### 5a. Untrusted-boundary hardening (from E1-02 security review — implement in E1-03/E1-04 agent-runtime)

The `Action` sealed type closes the *shape* of agent input (E1-02). The following defenses live at the **mapper/transport/validation** layer the agent-runtime owns, not in the pure engine records, and must be applied before/around parse:

- **No polymorphic default typing.** The `ObjectMapper` used to deserialize `AgentResponse` MUST NOT enable polymorphic default typing and MUST NOT register a permissive `PolymorphicTypeValidator`. The engine relies on Jackson `@JsonTypeInfo(use = Id.NAME)` resolved only against the closed `@JsonSubTypes` permit list; default typing would reopen the arbitrary-class-instantiation (gadget) hole.
- **Per-action parse isolation.** An unknown top-level action `type` degrades to the inert `UnknownAction` sentinel (→ treated as Hold/drop). However a *malformed/unknown nested target `kind`* on `Attack`/`Blockade` fails fast and, with a single `readValue`, would abort parsing the **whole** `AgentResponse`. Decision: a malformed action must NOT kill sibling actions — the agent-runtime parses defensively so one throwing action degrades to Hold while the rest survive (and feeds the rejection reason into the single re-prompt).
- **Raw-size bounds (pre-validation DoS).** Apply Jackson `StreamReadConstraints` (max string length, max nesting depth, max number length) plus a max-document-size and a max `actions[]` / `messages[]` length at the transport/mapper layer, so an oversized response is rejected before it can exhaust memory. Semantic caps (e.g. `text ≤ cap`) are enforced in §4 validation; these are the raw resource-exhaustion guards.

## 6. Versioning

The schema is versioned (`schemaVersion`). Adding an action variant is a minor version; agents and validators must handle unknown future variants by ignoring/holding rather than crashing.
