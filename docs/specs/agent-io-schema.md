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
  pendingOffers[]     : addressed to me: { id, from, give, receive, expiresTick } // directed offers whose addressee == me
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
  Espionage      { target, operationType }    // operationType ∈ { SCOUT, STEAL_INTEL, SABOTAGE, INCITE_UNREST }; seeded success + detection (E1-13)
  Hold           { }                          // explicit no-op
}
```

### 3a. Diplomacy enforcement (E1-12)

The diplomatic-state actions resolve **first** (resolution-order step 1, before any kinetic step), so a war declared or treaty broken this tick is in force for the actions that follow:

- `ProposeTreaty` mints a **PROPOSED** treaty (engine-assigned deterministic id from proposer/addressee/type/tick/submission-order); `AcceptTreaty` flips it to **ACTIVE** (now engine-enforced), `DeclineTreaty` closes it (**EXPIRED**, no penalty — declining an offer is not betrayal).
- `BreakTreaty` terminates an ACTIVE treaty (**BROKEN**) and docks the breaker's reputation by `penaltyBreakTreaty × treatyEnforcement[type] × remainingDurationTicks`. It is "announced galaxy-wide" — the state (BROKEN treaty) is recorded; the public-event emission lands with the EVENTS step.
- `DeclareWar` records a `WarState` (the positive gate kinetic actions require) and docks `penaltyUnprovokedWar` for a **new** war; re-declaring an existing war is idempotent and not re-penalised.
- `Tribute` is engine-enforced (not soft chatter): it **transfers** `resources` from payer to recipient through the tick's escrow ledger (atomic debit + credit). `DemandTribute` remains a non-binding ultimatum delivered to the target.
- Active treaties are enforced by the validator refusing illegal kinetic actions: `Attack`/`Blockade`/`Raid`/`DeclareWar` against a NonAggression/Alliance/Ceasefire partner are `Rejected{TREATY_FORBIDS, treatyId}` until the treaty is broken first.

**Espionage intel (E1-13).** A successful `Espionage(SCOUT)` records the acting faction in the **target faction's `revealedIntel`** set (engine state, held on the spied-upon `Faction`, not a new `GameState` component). The fog-of-war filter (E3-02) reads `revealedIntel` to widen the actor's `visibleNeighbours` view of that faction beyond the default ownership+rough-strength fog (e.g. exposing hidden stockpile/tech detail) for as long as the reveal stands — it is monotone within a match. A detected operation costs the actor public reputation, which surfaces to everyone via the existing `reputations[]` ledger.

## 4. Validation contract

For each action the engine answers `Valid` or `Rejected{reason}`. Rejection reasons are human-readable and fed back on the single re-prompt, e.g.:
- `INSUFFICIENT_RESOURCES`, `NOT_OWNED`, `NOT_ADJACENT`, `NO_PATH`, `TREATY_FORBIDS` (with treaty id), `TECH_PREREQ_MISSING`, `NO_FREE_SLOT`, `NOT_AT_WAR`, `TARGET_UNKNOWN`, `OFFER_EXPIRED`.

### 4a. Validation invariants (from E1-04 security review — some checks gated on later cards)

The validator never trusts an agent-supplied id/owner/count; everything is checked against authoritative `GameState`, and rejection messages reference only actor-knowable facts (own assets, the public reputation/treaty ledger, ids the actor itself supplied). The following invariants are specified now but their enforcement depends on records/systems landing in later cards — each is a **hard prerequisite** on the named resolver card, because the check only becomes exploitable once the resolver mutates state:

- **Kinetic war-state (E1-09 — IMPLEMENTED).** A kinetic action (`Attack`/`Blockade`/`Raid`) requires a positive war-state OR a neutral (unowned) target. Absence of a forbidding treaty is *necessary but not sufficient* — an agent must not strike a faction it is at peace-but-not-treaty with. War is now a first-class state value: `GameState.wars` is a set of symmetric `WarState{a,b,sinceTick}` (the unordered pair is canonical, so a war is one value regardless of who declared it). `DeclareWar` records one in the DIPLOMATIC_STATE resolution step (idempotent); `GameState.atWar(x,y)` is the gate. The validator rejects a kinetic action against an **owned** target that the actor is not at war with as `NOT_AT_WAR`; a **neutral (unowned)** target is exempt. `Raid` always targets an owned route, so it always requires war. (E1-04 enforced the treaty-forbids half; E1-09 added the positive war-state gate. The unprovoked-war reputation penalty lands with diplomacy card E1-12 — see §3.)
- **Lane adjacency / reachability (E2-03/E1-09 — IMPLEMENTED).** The lane graph is supplied to the engine as `engine.map.LaneNetwork` — an immutable, undirected, tick-weighted graph keyed by `SystemId`, passed to `ActionValidator.validate(...)` and `Resolver.resolve(...)` as a separate static-per-match input alongside the `BalanceProfile` (never embedded in `GameState`; the orchestrator projects the galaxy `LaneGraph` into it). When a network is supplied: `Explore` requires the target be one lane hop from a system the actor owns or has a fleet in (`NOT_ADJACENT`); `MoveFleet` requires the fleet be stationary, its path start from the fleet's current location, and every hop be a real lane (`NO_PATH`); `Colonize` requires the delivering fleet be stationed at the target system (`NOT_ADJACENT`). The four-arg `validate`/`resolve` overloads pass `LaneNetwork.EMPTY`, under which these checks degrade to shape-only (the pre-E1-09 behaviour) — but the resolver still MUST NOT move/fight on an unvalidated path: with an empty network `MoveFleet` is a no-op (the fleet does not move) and interception is not triggered. ("Fleet positioned at target" range gating for the kinetic actions themselves lands with combat/interdiction in E1-10/E1-11.)
- **Directed trade offers (E1-07 — IMPLEMENTED).** The `MarketOrder`/directed-offer record carries an **`addressee`** (`Optional<FactionId>`) and an **`expiresTick`** (`long`). An *open* order-book limit order has `addressee = empty` (anyone may match it through the book); a *directed* peer-to-peer offer sets `addressee` to the single faction allowed to accept/decline it. `AcceptTrade`/`DeclineTrade` are valid only for offers whose `addressee` is the actor (a present addressee not equal to the actor, or an empty addressee on an Accept/Decline, is rejected `NOT_OWNED`/`TARGET_UNKNOWN`) **and** that have not expired — an offer is expired when `state.tick > expiresTick`, rejected `OFFER_EXPIRED`. `WithdrawTrade` still checks proposer-identity (the order's `faction`), not addressee. E1-04 checked existence/proposer-identity only; addressee + expiry are enforced from E1-07.
- **Atomic escrow at resolution (E1-05).** Affordability is a per-action *snapshot* at validation time. The resolver must debit/escrow under a single authoritative pass so N validated spends in one tick cannot collectively overdraw one stockpile.

## 5. Structured-output strategy (Spring AI)

- Use Spring AI's structured-output converter to push the model toward emitting valid `AgentResponse` JSON directly.
- Always **validate after parse** — never trust the model's self-reported validity.
- Defensive parsing: strip markdown fences, tolerate trailing prose, but reject ambiguous output (→ re-prompt).

**Validate-after-parse + single re-prompt → Hold (E4-04).** `agent-runtime`, package `com.stellarcompact.agentruntime.validate`: `ActionValidationLoop.decide(ChatClient, AssembledPrompt, ValidationContext) → ValidatedDecision` is the orchestration that turns one assembled prompt (E4-02) into a clean, engine-validated `Action[]`. Flow: issue the prompt through the provider-neutral `ChatClient` (E4-01) → parse via `AgentResponseParser` (E4-03) → validate **every** parsed action against authoritative `GameState` through the pure `ActionValidator` (E1-04, the four-arg-plus-`LaneNetwork` overload) → on **any** rejection (a parse-stage `Rejected`, or one-or-more action validation rejections) perform **exactly one** re-prompt appending the specific reason(s) → after that single re-prompt, keep only the actions that now validate; anything still rejected (or a still-bad parse, or a model error/timeout) is dropped. An empty surviving set is a **Hold** (`ValidatedDecision.holds()`), modelled as an empty `validActions` list (no `Hold` action need be materialised). **At-most-one-retry invariant:** the `ChatClient` is invoked at most twice total (initial + at most one re-prompt); the loop never iterates again even if the second turn still has rejections. `ValidatedDecision.rePrompted()` records whether the second call happened. **Re-prompt injection safety (security boundary):** the corrective feedback (`RejectionFeedback`) is built ONLY from the closed `ParseRejectionCode` (each mapped to a fixed, hand-authored sentence — the parser's untrusted `Rejected.detail` is never echoed) and from the engine's `ValidationResult.Rejected` (`code` + `message`, which the engine contract guarantees references only actor-knowable facts and is intended to be fed back verbatim). The model's own free text is never an input to the next prompt. A provider error/timeout degrades to a drop/Hold rather than propagating.

**Implemented in E4-03** (`agent-runtime`, package `com.stellarcompact.agentruntime.parse`): `AgentResponseParser.parse(String) → ParseResult` is the untrusted-text boundary. It strips markdown code fences, tolerates leading/trailing prose, locates exactly **one** top-level JSON object by string-aware brace scanning (zero ⇒ `NO_JSON`, more than one / a top-level array ⇒ `AMBIGUOUS`), then tree-parses with a hardened mapper. The result is a sealed `ParseResult` = `Parsed(AgentResponse)` | `Rejected(ParseRejectionCode, detail)` — it **never throws** on hostile input and **never reads a model self-reported validity field** (an unknown top-level key like `"valid": true` is dropped; shape alone decides). The `Rejected.code` (closed `ParseRejectionCode`: `OVERSIZED`, `NO_JSON`, `MALFORMED_JSON`, `AMBIGUOUS`, `SHAPE_INVALID`, `TOO_MANY_ELEMENTS`) is what feeds the single re-prompt path (E4-04); `Rejected.detail` is server-log-only and is never echoed into a re-prompt verbatim.

### 5a. Untrusted-boundary hardening (from E1-02 security review — implemented in E4-03 agent-runtime)

The `Action` sealed type closes the *shape* of agent input (E1-02). The following defenses live at the **mapper/transport/validation** layer the agent-runtime owns, not in the pure engine records, and must be applied before/around parse:

- **No polymorphic default typing.** The `ObjectMapper` used to deserialize `AgentResponse` MUST NOT enable polymorphic default typing and MUST NOT register a permissive `PolymorphicTypeValidator`. The engine relies on Jackson `@JsonTypeInfo(use = Id.NAME)` resolved only against the closed `@JsonSubTypes` permit list; default typing would reopen the arbitrary-class-instantiation (gadget) hole. *(E4-03: `AgentResponseParser.hardenedMapper` never calls `activateDefaultTyping` and registers no PTV.)*
- **Per-action parse isolation.** An unknown top-level action `type` degrades to the inert `UnknownAction` sentinel (→ treated as Hold/drop). However a *malformed/unknown nested target `kind`* on `Attack`/`Blockade` fails fast and, with a single `readValue`, would abort parsing the **whole** `AgentResponse`. Decision: a malformed action must NOT kill sibling actions — the agent-runtime parses defensively so one throwing action degrades to Hold while the rest survive (and feeds the rejection reason into the single re-prompt). *(E4-03: the parser binds `actions[]` element-by-element; a throwing element becomes `UnknownAction` carrying the offending `type` label, siblings survive. A malformed **top-level** shape — `actions` not an array, a malformed `messages` entry with no recipient, a negative `schemaVersion` — is still rejected `SHAPE_INVALID`.)*
- **Raw-size bounds (pre-validation DoS).** Apply Jackson `StreamReadConstraints` (max string length, max nesting depth, max number length) plus a max-document-size and a max `actions[]` / `messages[]` length at the transport/mapper layer, so an oversized response is rejected before it can exhaust memory. Semantic caps (e.g. `text ≤ cap`) are enforced in §4 validation; these are the raw resource-exhaustion guards. *(E4-03: `StreamReadConstraints` on the `JsonFactory` plus a raw-char document cap → `OVERSIZED`, and `actions`/`messages` length caps → `TOO_MANY_ELEMENTS`. Defaults: 64 KiB doc, 32 KiB string, depth 32, 64 actions, 64 messages; all overridable via the parser's full constructor.)*

## 6. Versioning

The schema is versioned (`schemaVersion`). Adding an action variant is a minor version; agents and validators must handle unknown future variants by ignoring/holding rather than crashing.

## 7. The `Sovereign` contract + `WorldView` boundary type (E3-01)

A **Sovereign** is the orchestrator's view of any agent — LLM-backed (E4) or scripted (E3-01 `ScriptedSovereign`). It is a single, provider-neutral function:

```
Sovereign : WorldView -> AgentResponse        // {messages[], actions[]}
```

The Java contract is a plain interface (no Spring / Spring AI types — provider neutrality, principle 4):

```
interface Sovereign {
    FactionId factionId();                     // which seat this brain plays
    AgentResponse decide(WorldView view);      // perception -> messages + actions
}
```

`decide` maps one tick's `WorldView` to an `AgentResponse`. It must never throw on a well-formed `WorldView`; an agent with nothing useful to do returns a single `Hold` action and no messages. The LLM-backed Sovereign (E4) and the `ScriptedSovereign` bot (E3-01, for tests + empty seats) implement the same interface; the orchestrator calls them identically inside the per-phase `StructuredTaskScope`.

### 7a. `WorldView` shape (minimal, stable, forward-compatible)

`WorldView` is the per-faction perception the Sovereign sees. **This card (E3-01) fixes the interface-boundary shape only.** The authoritative server-side **fog-of-war filter that builds a `WorldView` from `GameState` is card E3-02** and is out of scope here; E3-01 ships only a clearly-marked test-only "full-state projection for one faction" stand-in so the bot can be exercised.

The shape mirrors §1, kept token-compact and additive (new fields append; consumers ignore unknown fields):

```
WorldView
  tick               : long
  self               : SelfView { id, name, reputation, stockpiles{energy,minerals,food,tech,influence}, techKnown[] }
  ownSystems[]       : SystemView { id, name, owned:true, ownedPlanets[]{ id, slotsTotal, freeSlots, hasShipyard }, hasShipyard }
  ownFleets[]        : FleetView  { id, location?, enRoute:boolean, stance, totalShips }
  neighbours[]       : NeighbourView { systemId, owner? (fog: ownership only, present iff revealed), roughStrength, lastSeenTick }
  treaties[]         : TreatyView { id, type, parties[], status }
  reputations[]      : RepEntry  { factionId, reputation }          // public ledger
  pendingOffers[]    : OfferView { id, from, expiresTick }          // offers addressed to me
  events[]           : EventView { type, tick }                     // public events since last tick
  inbox[]            : InboxMessage { from, text, tick }            // messages received
```

Design rules carried from §1: own state full; neighbours **fog-limited to ownership + rough strength only** (no hidden enemy state); compact (small lists, top-of-book elsewhere). The type is a deeply-immutable record graph so a `WorldView` handed to a (possibly slow, possibly remote) Sovereign cannot be mutated under the orchestrator.

### 7b. Scripted bot (`ScriptedSovereign`) behaviour

Deterministic, legible heuristics for tests and empty seats — **no LLM, no Spring, no randomness, no wall-clock**. Same `WorldView` ⇒ same `AgentResponse`, every run. To avoid map/set iteration-order nondeterminism it sorts candidate ids before choosing. Priority ladder (first applicable wins; all emitted actions are shape-valid and target only actor-knowable, owned assets so they pass `ActionValidator`):

1. **Build economy** — on an owned planet with a free slot, queue a building it can afford (lowest planet id, lowest free slot, first affordable building type in a fixed order).
2. **Colonize** — if it has a fleet and a reachable neutral (unowned) neighbour planet, colonize it. *(Gated until E3-02 surfaces neutral-planet detail; conservative/off by default this card.)*
3. **Explore** — if it has an idle fleet and an unexplored/neutral neighbour system, explore it.
4. **Hold** — otherwise emit the explicit no-op.

The bot emits at most one primary action per tick (plus `Hold` when idle), keeping it simple and its output trivially replayable.
