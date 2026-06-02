# Stellar Compact — Implementation BOARD

> **Purpose.** This is the working board an AI coding agent follows to build Stellar Compact from the
> specs in `docs/` and the PoC in `poc/`. The high-level phasing lives in `docs/ROADMAP.md`; **this file
> breaks that into ordered, pick-up-able cards** with dependencies, the skill/spec to read first, the
> subagent to delegate to, and a concrete Definition of Done.
>
> **How to use it.**
> 1. Pick the lowest-id `☐ Todo` card whose dependencies are all `✅ Done`.
> 2. Read the card's **Read first** links and the matching `.claude/skills/*` — they encode constraints
>    that are easy to get wrong (this is rule #8 in `.claude/rules/00-principles.md`).
> 3. Move the card to `▶ Doing` (flip the checkbox), implement, satisfy **Done when**, then mark `✅ Done`.
> 4. Keep `docs/specs/` in sync — if a contract is unclear or must change, edit the spec **before** the code.
>
> One card = one focused unit of work. Don't batch unrelated cards; don't start a card whose deps are open.

---

## Working agreements (non-negotiable — from `.claude/rules/`)

1. **Determinism is sacred.** `engine` + `galaxy` are pure: no I/O, no Spring, no wall-clock, no unseeded
   randomness. Seed RNG from `gameSeed ⊕ tick ⊕ localSalt`. Same seed + same action log ⇒ identical state hash.
2. **Engine authority.** Frontend and agents are untrusted. All rules and authoritative state are server-side.
   Agents propose; the engine disposes.
3. **Scale discipline.** Never load or iterate the full star catalog on the client or in the sim. Detail is a
   function of zoom; the sim touches only active systems.
4. **Provider neutrality.** All LLM calls go through Spring AI's `ChatClient`. No vendor/model/endpoint hardcoded.
5. **Closed agent I/O.** Agent output is validated against the sealed `Action` schema. Invalid ⇒ one re-prompt
   with the reason ⇒ then `Hold`/drop.
6. **Numbers live in config.** Every gameplay constant is in a balance profile, never hardcoded.
7. **Specs before code.** Edit `docs/specs/` first when a contract is unclear or changing.
8. **Read the skill first.** Before coding an area, read the matching `.claude/skills/*`.

**Stack (fixed, do not substitute):** Angular 21 (standalone, signals, zoneless, WebGL2) · Spring Boot 4 / Java 25
(virtual threads, structured concurrency, records, sealed interfaces, exhaustive switches) · Spring AI 2.0.0-M8
(Ollama default, pluggable) · REST for config/CRUD/tiles · WebSocket/STOMP for live deltas · PostgreSQL +
object-store/CDN tiles.

---

## Status legend

| Mark | Meaning |
|---|---|
| ☐ Todo | not started |
| ▶ Doing | in progress (only a few at a time) |
| ⛔ Blocked | waiting on a dependency or decision |
| ✅ Done | complete and **Done when** verified |

## Subagents (delegate targets — see `.claude/agents/`)

- **game-engine-developer** — engine + galaxy-lib (pure, deterministic, framework-free).
- **agent-runtime-developer** — Spring AI integration, prompt assembly, WorldView, tick fan-out.
- **galaxy-renderer-engineer** — WebGL2 renderer + LOD/tiling client.
- **frontend-developer** — Angular app (HUD, config, spectator, signal stores, STOMP client) excl. renderer internals.
- **game-balance-designer** — balance profiles, victory/scoring tuning, rule-coherence reviews.
- **security-reviewer** — fog-of-war enforcement, agent-output validation, trust-boundary review.

---

## Board overview (epics → phases)

| Epic | Theme | Phase | Lead module(s) |
|---|---|---|---|
| **E0** | Scaffolding & foundations | 0.5 | build, config |
| **E1** | Deterministic rules engine | 1 | engine |
| **E2** | Galaxy generation (small/fixed) | 1 | galaxy |
| **E3** | Sovereign contract, scripted bot, headless runner | 1 | engine, orchestrator |
| **E4** | LLM agent runtime + tick orchestration | 2 | agent-runtime, orchestrator |
| **E5** | Persistence | 2 | persistence |
| **E6** | API (REST + STOMP) | 2 | api |
| **E7** | Frontend (Angular, canvas PoC port) | 2 | frontend |
| **E8** | Scale to billions (WebGL2 + tiles + procedural catalog) | 3 | galaxy, frontend, api |
| **E9** | Progression & spectacle | 4 | all |

**Critical path:** E0 → E1 → E3 → (E4 ∥ E5 ∥ E6) → E7 → E8 → E9. E2 runs alongside E1.
Cross-cutting **security-reviewer** pass is required on every card that touches WorldView, agent I/O, or any
client/agent-visible read (flagged 🔒 below).

---

# E0 — Scaffolding & foundations  *(Phase 0.5)*

### E0-01 · Backend multi-module build
- **Status:** ✅ Done · **Module:** build · **Depends on:** — · **Delegate to:** game-engine-developer
- **Read first:** `docs/architecture/01-system-overview.md` §2, `.claude/rules/01-stack.md`
- **Do:** Create the **Maven** multi-module reactor (parent POM + one module per directory) with Java 25 toolchain and
  module skeletons: `engine`, `galaxy` (framework-free libs), `agent-runtime`, `orchestrator`, `api`, `persistence`,
  `app` (Spring). Enforce that `engine`/`galaxy` declare **no Spring/IO dependency** (e.g. via the `maven-enforcer-plugin`
  banned-dependencies rule).
- **Done when:** `mvn verify` succeeds on empty modules; the enforcer/dependency-rule build fails if `engine`/`galaxy`
  import Spring or `java.io`/`java.net`; Java 25 release/features enabled in the parent POM.

### E0-02 · Frontend Angular 21 app skeleton
- **Status:** ✅ Done · **Module:** frontend · **Depends on:** — · **Delegate to:** frontend-developer
- **Read first:** `.claude/skills/angular21-signals`, `frontend/README.md`
- **Do:** Scaffold the Angular 21 app: standalone components only (no NgModules), signals, **zoneless** change
  detection, no Zone.js reliance. Blank shell + routing skeleton (galaxy / config / spectator routes).
- **Done when:** dev server runs a blank shell; build passes; `provideExperimentalZonelessChangeDetection`
  (or current zoneless provider) wired; lint clean.

### E0-03 · Balance config: schema, loader, two profiles
- **Status:** ✅ Done · **Module:** engine/config · **Depends on:** E0-01 · **Delegate to:** game-balance-designer
- **Read first:** `docs/specs/balance-config.md`, `docs/game-design/02-economy.md`
- **Do:** Model the balance profile as immutable records; ship `small-default` and `large-persistent` profiles
  as resources; write a loader. **No literal gameplay constant anywhere else in code.**
- **Done when:** both profiles load and validate; a test asserts every documented tunable (resources, population,
  market, construction, combat, tech, diplomacy, victory, tick) is present; profile is versioned and attachable to a match.

### E0-04 · Test & determinism harness
- **Status:** ✅ Done · **Module:** build · **Depends on:** E0-01 · **Delegate to:** game-engine-developer
- **Read first:** `.claude/skills/game-engine-determinism`, `.claude/rules/01-stack.md` §Testing
- **Do:** Set up the test harness with a **golden state-hash** utility (canonical serialization → stable hash)
  and coverage gates (80%+ on `engine` and `agent-runtime`).
- **Done when:** a sample golden-hash test runs in CI; coverage gate enforced and visible.

---

# E1 — Deterministic rules engine  *(Phase 1)* — read `.claude/skills/game-engine-determinism`

### E1-01 · Core state records
- **Status:** ✅ Done · **Module:** engine · **Depends on:** E0-01 · **Delegate to:** game-engine-developer
- **Read first:** `docs/specs/data-model.md`, `docs/game-design/01-world-and-map.md`
- **Do:** Immutable records for `GameState`, `Faction`, `ActiveSystem`, `Planet`, `Building`, `Fleet`, `Ship`,
  `Treaty`, `Route`, `MarketOrder`, `TechProgress`, plus `Biome`, resource bundle, coords. All immutable, copy-on-write.
- **Done when:** records compile; a snapshot round-trips to JSON and back; canonical hash is stable across runs.

### E1-02 · Sealed Action hierarchy + AgentResponse
- **Status:** ✅ Done · **Module:** engine · **Depends on:** E1-01 · **Delegate to:** game-engine-developer · 🔒
- **Security sign-off:** PASS (security-reviewer) — closed `@JsonTypeInfo(Id.NAME)` permit set, no gadget surface, unknown variants degrade to inert `UnknownAction`, no state smuggling. Deferred hardening recorded in `docs/specs/agent-io-schema.md` §5a for E1-03/E1-04 (no default typing; per-action parse isolation; raw-size bounds).
- **Read first:** `docs/specs/agent-io-schema.md`, `docs/game-design/03-actions.md`
- **Do:** `sealed interface Action permits …` with **all 25 variants** (Explore … Hold) as records; `AgentResponse`
  `{messages[], actions[]}`; `schemaVersion`. Unknown future variants must be ignorable (forward-compat per spec §6).
- **Done when:** sealed set complete; an exhaustive `switch` over `Action` compiles with no default; JSON schema can be
  generated from the types; round-trip parse test passes.

### E1-03 · Seeded RNG
- **Status:** ✅ Done · **Module:** engine · **Depends on:** E0-01 · **Delegate to:** game-engine-developer
- **Read first:** `.claude/skills/game-engine-determinism`
- **Do:** Deterministic RNG seeded from `gameSeed ⊕ tick ⊕ localSalt` (per-battle/per-event salt). No `Math.random`,
  no `System.currentTimeMillis`, no shared mutable RNG state.
- **Done when:** identical (seed, tick, salt) ⇒ identical stream; a test proves no wall-clock/global-state leakage.

### E1-04 · Action validation engine
- **Status:** ✅ Done · **Module:** engine · **Depends on:** E1-01, E1-02 · **Delegate to:** game-engine-developer · 🔒
- **Security sign-off:** PASS (security-reviewer) — every variant checks existence + ownership against authoritative `GameState`; no hidden-state leakage in rejection messages (verified `DemandTribute` runs no target-affordability probe, `TREATY_FORBIDS` names only public-ledger treaties the actor is party to); exhaustive switch, `UnknownAction` rejected, pure/deterministic. Deferred checks (F1 war-state, F2 lane adjacency, F3 offer addressee/expiry) recorded in `docs/specs/agent-io-schema.md` §4a as hard prerequisites on E1-09/E2-03/E1-07 before their resolvers mutate state.
- **Read first:** `docs/specs/agent-io-schema.md` §4, `docs/game-design/03-actions.md`
- **Do:** Per-action `Valid | Rejected{reason}` using the documented reason codes (`INSUFFICIENT_RESOURCES`,
  `NOT_OWNED`, `NOT_ADJACENT`, `NO_PATH`, `TREATY_FORBIDS`, `TECH_PREREQ_MISSING`, `NO_FREE_SLOT`, `NOT_AT_WAR`,
  `TARGET_UNKNOWN`, `OFFER_EXPIRED`, …). Checks: affordability, ownership, adjacency, treaty legality, tech prereqs.
- **Done when:** each action variant has validation tests for both accept and each rejection reason; reasons are
  human-readable (they feed the agent re-prompt).

### E1-05 · Resolver skeleton (fixed resolution order)
- **Status:** ✅ Done · **Module:** engine · **Depends on:** E1-03, E1-04 · **Delegate to:** game-engine-developer
- **Read first:** `docs/game-design/03-actions.md` §"Resolution order", `.claude/skills/game-engine-determinism`
- **Do:** `resolve(state, validatedActions, seed)` — **single-threaded, pure**. Orders actions by the fixed 11-step
  category order, then faction id, then submission order. Exhaustive `switch`; dispatches to per-step handlers (stubs ok).
- **Done when:** resolver wires all 11 steps in order; ordering is deterministic & tested; no concurrency inside resolve.
- **🔒 Security prereq (from E1-04 review, spec §4a):** resource debits MUST be atomic/escrowed in a single authoritative pass so N validated spends in one tick cannot collectively overdraw one stockpile (validation affordability is only a per-action snapshot).

### E1-06 · Economy resolution
- **Status:** ☐ Todo · **Module:** engine · **Depends on:** E1-05, E0-03 · **Delegate to:** game-engine-developer
- **Read first:** `docs/game-design/02-economy.md`
- **Do:** Steps 6/9/11-ish: production (base×buildings×tech×population), upkeep, **deficit ⇒ attrition** (no negative
  balances), population growth/decline. All numbers from balance profile.
- **Done when:** golden tests cover surplus growth, deficit attrition, and population dynamics; no hardcoded constants.

### E1-07 · Market order book + escrow settlement
- **Status:** ☐ Todo · **Module:** engine · **Depends on:** E1-05 · **Delegate to:** game-engine-developer
- **Read first:** `docs/game-design/02-economy.md` §4
- **Do:** Per-hub order book; **price-time-priority** matching each tick; clears at resting order's price; engine
  escrow prevents offering unowned/encumbered resources. Influence is **not** market-tradeable.
- **Done when:** matching is deterministic; escrow prevents over-offer; prices differ per hub from supply/demand;
  golden tests for crossing/non-crossing/partial fills.
- **🔒 Security prereq (from E1-04 review, spec §4a):** the directed-offer record must add an **addressee** + **`expiresTick`**; then extend `ActionValidator` so `AcceptTrade`/`DeclineTrade` reject offers not addressed to the actor and expired offers (`OFFER_EXPIRED`). E1-04 only checks existence/proposer-identity today.

### E1-08 · Construction, research (tech DAG), terraform
- **Status:** ☐ Todo · **Module:** engine · **Depends on:** E1-05 · **Delegate to:** game-engine-developer
- **Read first:** `docs/game-design/06-technology.md`, `docs/game-design/02-economy.md` §6
- **Do:** Build queues with build-time ticks; tech as a **DAG** with prereqs, costs, times, and applied
  multipliers/unlocks; terraform stepping biomes toward habitable with Energy upkeep. Slot limits enforced.
- **Done when:** construction completes after configured ticks; tech unlocks gate dependent actions/ship tiers;
  terraform advances biome one step over many ticks; all costs/times from config.

### E1-09 · Movement & interception
- **Status:** ☐ Todo · **Module:** engine · **Depends on:** E1-05, E2-03 · **Delegate to:** game-engine-developer
- **Read first:** `docs/game-design/05-conflict.md` §4, `docs/game-design/01-world-and-map.md` §3
- **Do:** Fleet travel over a lane path (arrival after summed lane lengths in ticks, Energy cost); mid-transit
  **interception** by a hostile fleet contesting a lane forces a battle.
- **Done when:** travel ETAs deterministic; interception triggers combat at the contested lane; chokepoint control tested.
- **🔒 Security prereq (from E1-04 review, spec §4a):** extend `ActionValidator` with the now-deferrable checks — F1 positive **war-state gate** for `Attack`/`Blockade`/`Raid` (peace-but-not-treaty targets must be rejected, not just treaty-bound ones), and F2 **lane adjacency/reachability** (`Explore`/`Colonize`/`MoveFleet` origin=fleet-location + real-lane hops/`EstablishRoute`/fleet-positioned-at-target). The resolver MUST NOT move/fight on an unvalidated path.

### E1-10 · Combat & system assault
- **Status:** ☐ Todo · **Module:** engine · **Depends on:** E1-05, E1-09 · **Delegate to:** game-engine-developer
- **Read first:** `docs/game-design/05-conflict.md` §§1-2,6
- **Do:** `attackerPower`/`defenderPower` with tier/tech/stance/terrain/defense-platform mods; **seeded variance band**;
  proportional losses (winner takes attrition too); system assault ⇒ capture (ownership + surviving buildings transfer)
  with **reduced loyalty/unrest** on captured systems.
- **Done when:** same (seed,tick,battleId) ⇒ identical outcome; stronger force usually-but-not-always wins; capture
  transfers ownership and applies occupation penalty; golden battle tests.

### E1-11 · Blockade & raid effects
- **Status:** ☐ Todo · **Module:** engine · **Depends on:** E1-07, E1-09 · **Delegate to:** game-engine-developer
- **Read first:** `docs/game-design/05-conflict.md` §5, `docs/game-design/02-economy.md` §5
- **Do:** Blockade chokes a route/market throughput; Raid intercepts a shipment and steals part of cargo (seeded),
  no territory capture. Both gated by war/contested status.
- **Done when:** blockade reduces throughput deterministically; raid steals a seeded cargo fraction; tests cover legality gating.

### E1-12 · Diplomacy: treaties, reputation, war, tribute
- **Status:** ☐ Todo · **Module:** engine · **Depends on:** E1-05 · **Delegate to:** game-engine-developer · 🔒
- **Read first:** `docs/game-design/04-diplomacy.md`, `docs/game-design/03-actions.md` §B
- **Do:** All treaty types enforced (engine **refuses** illegal actions, e.g. Attack vs NonAggression partner; **applies**
  auto-effects: allied vision, route protection, vote pooling). Reputation ledger (gains/penalties incl.
  break-penalty = weight×remaining-duration). War declaration state; tribute/demand-tribute transfers.
- **Done when:** illegal-under-treaty actions are rejected with `TREATY_FORBIDS{id}`; reputation moves per config;
  BreakTreaty emits a galaxy-wide event + penalty; war state gates kinetic actions.

### E1-13 · Espionage operations
- **Status:** ☐ Todo · **Module:** engine · **Depends on:** E1-05 · **Delegate to:** game-engine-developer
- **Read first:** `docs/game-design/03-actions.md` §C (Espionage), `docs/game-design/06-technology.md` §3
- **Do:** `{Scout, StealIntel, Sabotage, IncitUnrest}` with seeded success/detection; effects: reveal intel,
  steal a tech/resources, damage a building, reduce population/loyalty. Detection ⇒ reputation penalty.
- **Done when:** outcomes deterministic per seed; counter-intel tech affects odds; detected ops cost reputation.

### E1-14 · Influence accrual & decay
- **Status:** ☐ Todo · **Module:** engine · **Depends on:** E1-06, E1-12 · **Delegate to:** game-engine-developer
- **Read first:** `docs/game-design/02-economy.md` §1, §7
- **Do:** Influence from capitals, trade volume, monuments, honoured diplomacy; decay rules. Not haulable, not market-traded.
- **Done when:** influence accrues from the documented sources per config; decay applied; tested.

### E1-15 · Victory conditions, scoring, lifecycle
- **Status:** ☐ Todo · **Module:** engine · **Depends on:** E1-14 · **Delegate to:** game-balance-designer
- **Read first:** `docs/game-design/07-victory-and-lifecycle.md`
- **Do:** Evaluate the **one** configured victory condition (Domination/Economic/Diplomatic/Survival/Wonder);
  compute weighted score for ranking/non-win; elimination & vassalage; lifecycle states
  `CREATED→LOBBY→RUNNING→(PAUSED↔RUNNING)→CONCLUDED→ARCHIVED`.
- **Done when:** each condition fires at its configured threshold; scores computed with config weights; alliance
  shared-victory split honoured; lifecycle transitions guarded.

### E1-16 · Public event emission
- **Status:** ☐ Todo · **Module:** engine · **Depends on:** E1-10, E1-12 · **Delegate to:** game-engine-developer
- **Read first:** `docs/specs/websocket-protocol.md` (event types), `docs/game-design/03-actions.md` §"Resolution order" step 11
- **Do:** Emit the public event set (`WarDeclared, TreatySigned, TreatyBroken, AllianceFormed, SystemCaptured,
  BattleResolved, RouteEstablished, RouteRaided, FactionEliminated, VictoryAchieved`) as the final resolution step,
  ordered by tick. These are galaxy-wide common knowledge.
- **Done when:** events emitted deterministically in order; payloads match the WS spec; append-only.

### E1-17 · Golden-hash & replay tests
- **Status:** ☐ Todo · **Module:** engine · **Depends on:** E1-06…E1-16 · **Delegate to:** game-engine-developer
- **Read first:** `.claude/skills/game-engine-determinism`, `docs/game-design/07-victory-and-lifecycle.md` §5
- **Do:** End-to-end determinism: replay a recorded `(seed, action log)` and assert **identical** state hash at every
  tick. Cover a multi-faction scenario exercising economy/combat/diplomacy.
- **Done when:** replay reproduces state hash exactly across two independent runs; coverage gate met on `engine`.

---

# E2 — Galaxy generation (small/fixed scale)  *(Phase 1)* — read `.claude/skills/procedural-galaxy`

### E2-01 · Procedural star placement (seed-based)
- **Status:** ✅ Done · **Module:** galaxy · **Depends on:** E0-01 · **Delegate to:** game-engine-developer
- **Read first:** `.claude/skills/procedural-galaxy`, `docs/game-design/01-world-and-map.md` §1, `poc/galaxy-navigator.html` (`spiralDensity`)
- **Do:** Pure function `(seed, cell) → stars`: spiral-arm log-density field + bulge/halo, deterministic hashing.
  Fixed/small scale matching the PoC. **No storage** — regenerable on demand.
- **Done when:** same seed ⇒ identical star field; output visually matches PoC density character; framework-free.

### E2-02 · System & planet roster generation
- **Status:** ✅ Done · **Module:** galaxy · **Depends on:** E2-01 · **Delegate to:** game-engine-developer
- **Read first:** `docs/game-design/01-world-and-map.md` §2 (biomes), `.claude/skills/procedural-galaxy`
- **Do:** Per star: spectral class, brightness, size, and a seeded **planet roster** with biomes (Oceanic…Gas giant),
  slot counts by size, base yields. Deterministic from (seed, systemId).
- **Done when:** roster reproducible per seed; biome distribution plausible; slot counts respect planet size.

### E2-03 · Lane graph
- **Status:** ☐ Todo · **Module:** galaxy · **Depends on:** E2-01 · **Delegate to:** game-engine-developer
- **Read first:** `docs/game-design/01-world-and-map.md` §3
- **Do:** Build **natural lanes** (edges by proximity) with **length = travel cost in ticks** from real distance.
  Deterministic; supports pathfinding for E1-09.
- **Done when:** graph reproducible per seed; connectivity guaranteed for the playable region; lane lengths derived from distance.

### E2-04 · Home system placement (balanced, seeded)
- **Status:** ☐ Todo · **Module:** galaxy · **Depends on:** E2-02, E2-03 · **Delegate to:** game-balance-designer
- **Read first:** `docs/game-design/01-world-and-map.md` §6
- **Do:** Place N home systems with **minimum separation** and **balanced local resource potential**; assign starter
  loadout (one cradle world, small stockpile, one scout). Seeded & reproducible.
- **Done when:** no faction starts boxed-in or starved; separation/balance constraints tested; placement reproducible.

### E2-05 · Promotion / demotion boundary
- **Status:** ☐ Todo · **Module:** galaxy/engine · **Depends on:** E2-02, E1-01 · **Delegate to:** game-engine-developer
- **Read first:** `docs/architecture/02-galaxy-scale.md` §7, `docs/specs/data-model.md`
- **Do:** Colonising a procedural star **promotes** it to an `active_system` (materialise planet/building rows);
  abandonment **demotes** back to pure procedural scenery. The sim only ever touches active systems.
- **Done when:** promote materialises from seed deterministically; demote reverts cleanly; sim never iterates the catalog.

---

# E3 — Sovereign contract, scripted bot, headless runner  *(Phase 1)* — read `.claude/skills/agent-sovereign`

### E3-01 · Sovereign interface + scripted bot
- **Status:** ☐ Todo · **Module:** engine/orchestrator · **Depends on:** E1-02 · **Delegate to:** agent-runtime-developer
- **Read first:** `.claude/skills/agent-sovereign`, `docs/architecture/03-agent-runtime.md` §2
- **Do:** Define `Sovereign`: `WorldView → {messages[], actions[]}`. Implement a **scripted/bot** Sovereign (no LLM)
  for tests and empty seats — deterministic, simple heuristics.
- **Done when:** interface stable; scripted bot plays a full match headlessly with valid actions; deterministic per seed.

### E3-02 · WorldView builder + fog-of-war filtering
- **Status:** ☐ Todo · **Module:** orchestrator · **Depends on:** E1-01, E3-01 · **Delegate to:** agent-runtime-developer · 🔒
- **Read first:** `docs/architecture/03-agent-runtime.md` §3, `docs/specs/agent-io-schema.md` §1, `docs/game-design/01-world-and-map.md` §4
- **Do:** Build the compact `WorldView` per faction with **authoritative server-side fog filtering**: own assets full;
  neighbours fog-limited (ownership, rough strength, last-seen); top-of-book markets only; treaties/reputation/offers/
  events/inbox/victory-progress. **No hidden enemy state ever included.**
- **Done when:** a faction's view contains zero hidden state of others (security-reviewer sign-off); token-compact;
  fog rules tested against the visibility spec.

### E3-03 · Headless match runner + determinism replay
- **Status:** ☐ Todo · **Module:** orchestrator · **Depends on:** E1-17, E3-01, E3-02 · **Delegate to:** game-engine-developer
- **Read first:** `docs/ROADMAP.md` Phase 1, `.claude/skills/game-engine-determinism`
- **Do:** A headless runner that drives ticks with scripted bots (no Spring needed), records the action log, and can
  **replay** to verify identical state hashes. The proof that the engine + galaxy are reproducible end-to-end.
- **Done when:** a small galaxy runs to a victory/limit; replay from `(seed, action log)` reproduces every tick's hash.

---

# E4 — LLM agent runtime + tick orchestration  *(Phase 2)* — read `.claude/skills/spring-ai-agent`, `agent-sovereign`

### E4-01 · ChatClient integration (provider-pluggable)
- **Status:** ☐ Todo · **Module:** agent-runtime · **Depends on:** E3-01 · **Delegate to:** agent-runtime-developer
- **Read first:** `.claude/skills/spring-ai-agent`, `docs/architecture/01-system-overview.md` §5
- **Do:** Wire Spring AI 2.0.0-M8 `ChatClient`; **Ollama default**, OpenAI/others by Spring profile/properties.
  Model **tier** routing by config. No vendor/model/endpoint hardcoded.
- **Done when:** switching provider/model is config-only; an integration test runs against a local Ollama (or a stub).

### E4-02 · Prompt assembly
- **Status:** ☐ Todo · **Module:** agent-runtime · **Depends on:** E4-01, E3-02 · **Delegate to:** agent-runtime-developer
- **Read first:** `docs/architecture/03-agent-runtime.md` §2, `.claude/skills/spring-ai-agent`
- **Do:** System prompt = persona + goals + hard constraints + compact rules summary + strict output JSON schema
  (closed Action set) + one worked example. User message = serialized compact WorldView.
- **Done when:** prompt is assembled from config + WorldView; token budget respected; persona/constraints injected; golden prompt test.

### E4-03 · Structured-output coercion + defensive parse
- **Status:** ☐ Todo · **Module:** agent-runtime · **Depends on:** E4-02, E1-02 · **Delegate to:** agent-runtime-developer · 🔒
- **Read first:** `docs/specs/agent-io-schema.md` §5, `.claude/skills/spring-ai-agent`
- **Do:** Use Spring AI structured-output/converter to coerce to `AgentResponse`. Defensive parsing: strip markdown
  fences, tolerate trailing prose, **reject ambiguous output**. Never trust model self-reported validity.
- **Done when:** well-formed output parses to typed `Action[]`; malformed/ambiguous output is rejected (→ re-prompt path).

### E4-04 · Validate-after-parse + single re-prompt
- **Status:** ☐ Todo · **Module:** agent-runtime · **Depends on:** E4-03, E1-04 · **Delegate to:** agent-runtime-developer · 🔒
- **Read first:** `docs/architecture/03-agent-runtime.md` §2 (Output handling), `.claude/rules/00-principles.md` #5
- **Do:** Validate each parsed action against engine rules; on rejection, **one** re-prompt that includes the specific
  rejection reason; still invalid/timed-out ⇒ drop that action (faction may `Hold`).
- **Done when:** exactly one re-prompt on failure; rejection reason fed back verbatim; post-retry failure ⇒ Hold; tested.

### E4-05 · Tick orchestrator (4 phases, structured concurrency, timeouts)
- **Status:** ☐ Todo · **Module:** orchestrator · **Depends on:** E4-04, E1-05 · **Delegate to:** agent-runtime-developer
- **Read first:** `docs/architecture/03-agent-runtime.md` §1, `.claude/skills/agent-sovereign`
- **Do:** Real-time tick loop: Perception → Negotiation → Action → Resolution. Agent-calling phases fan out with **one
  virtual thread per Sovereign** under a **shared deadline** (`StructuredTaskScope`/`joinUntil`); stragglers contribute
  `Hold` and are cancelled. **Resolution is single-threaded & pure.** Tick interval from config.
- **Done when:** a slow agent never stalls the tick; per-phase timeout enforced; resolution stays deterministic; load test with N slow agents.

### E4-06 · Negotiation phase
- **Status:** ☐ Todo · **Module:** orchestrator · **Depends on:** E4-05 · **Delegate to:** agent-runtime-developer
- **Read first:** `docs/game-design/04-diplomacy.md` §1, `docs/architecture/03-agent-runtime.md` §1
- **Do:** Run 1–2 configurable negotiation rounds: collect `SendMessage` (free text, no mechanical effect) + structured
  `Propose*`; deliver messages/offers into the next WorldView; nothing binds until accepted.
- **Done when:** messages reach recipients' next view; pending proposals persist across ticks; rounds count from config.

---

# E5 — Persistence  *(Phase 2)*

### E5-01 · PostgreSQL schema & migrations
- **Status:** ☐ Todo · **Module:** persistence · **Depends on:** E1-01 · **Delegate to:** agent-runtime-developer
- **Read first:** `docs/specs/data-model.md`
- **Do:** Flyway migrations for `game, faction, active_system, planet, building, fleet, ship, treaty, route,
  market_order, tech_progress, event_log`. **Only active systems** persisted (catalog is procedural, not stored).
- **Done when:** migrations apply cleanly; schema matches the spec; event_log is append-only & tick-ordered.

### E5-02 · Active-state repositories
- **Status:** ☐ Todo · **Module:** persistence · **Depends on:** E5-01 · **Delegate to:** agent-runtime-developer
- **Read first:** `docs/specs/data-model.md`, `docs/architecture/02-galaxy-scale.md` §7
- **Do:** Repositories to load/save the bounded active set into/out of the engine's in-memory `GameState`. Promotion
  inserts active rows; demotion deletes them.
- **Done when:** a tick's state round-trips DB↔engine; only active systems touched; promote/demote persisted.

### E5-03 · Transactional tick commit + resume
- **Status:** ☐ Todo · **Module:** persistence · **Depends on:** E5-02, E4-05 · **Delegate to:** agent-runtime-developer
- **Read first:** `docs/architecture/03-agent-runtime.md` §6
- **Do:** A tick **fully commits or rolls back**; event log appended in order; a paused/crashed galaxy **resumes from the
  last committed tick + event log**.
- **Done when:** kill-and-resume reproduces the next tick identically; partial-tick failure rolls back atomically.

---

# E6 — API: REST + STOMP  *(Phase 2)* — read `.claude/skills/realtime-websocket`

### E6-01 · Match lifecycle REST
- **Status:** ☐ Todo · **Module:** api · **Depends on:** E1-15, E5-03 · **Delegate to:** agent-runtime-developer
- **Read first:** `docs/specs/rest-api.md` §Match lifecycle
- **Do:** `POST /api/games`, `GET /api/games/{id}`, `start/pause/resume`, `GET …/state` (fog-applied per requester),
  `GET …/events?fromTick=`, `GET …/leaderboard`.
- **Done when:** lifecycle endpoints drive the orchestrator; state read is fog-correct per requester; events paginate by tick.

### E6-02 · Faction (Sovereign) config REST
- **Status:** ☐ Todo · **Module:** api · **Depends on:** E6-01 · **Delegate to:** agent-runtime-developer · 🔒
- **Read first:** `docs/specs/rest-api.md` §Sovereign configuration
- **Do:** `POST /api/games/{id}/factions` (persona/goals/hardConstraints/modelTier), `GET /api/factions/{id}`
  (owner-only sensitive fields redacted otherwise), `PATCH` standing directives (persistent galaxies, between matches).
- **Done when:** config persists & feeds prompt assembly; non-owners get redacted views; directive edits gated to between-matches.

### E6-03 · Tile + overlay endpoints
- **Status:** ☐ Todo · **Module:** api · **Depends on:** E2-01 · **Delegate to:** galaxy-renderer-engineer
- **Read first:** `docs/specs/rest-api.md` §Galaxy tiles, `docs/architecture/02-galaxy-scale.md` §3,§5
- **Do:** `GET /api/galaxy/{seed}/tile/{level}/{x}/{y}` → `AggregateTile|StarListTile`, **immutable, long-TTL, ETag by
  (seed,level,x,y,schemaVersion)**. Separate thin dynamic `GET /api/galaxy/{gameId}/overlay?bbox=&sinceTick=`.
  *(Small-scale: a single/few levels are enough until E8.)*
- **Done when:** tile endpoint serves cacheable payloads with correct ETag; overlay is a thin diff; heavy data never on the socket.

### E6-04 · STOMP live stream
- **Status:** ☐ Todo · **Module:** api · **Depends on:** E1-16, E4-05 · **Delegate to:** agent-runtime-developer · 🔒
- **Read first:** `docs/specs/websocket-protocol.md`, `.claude/skills/realtime-websocket`
- **Do:** Topics `…/ticks`, `…/events`, `…/overlay` (public) + `/user/queue/faction/{id}/view` (**owner-only** WorldView).
  Overlay deltas scoped to spectator bbox; reconnect resyncs via REST `overlay?sinceTick=`. Keep the channel light.
- **Done when:** spectators get public events/overlay; owners get only their own view; a non-owner cannot subscribe to another's view (security-reviewer sign-off).

---

# E7 — Frontend (Angular 21, canvas PoC port)  *(Phase 2)* — read `angular21-signals`, `galaxy-rendering`, `lod-tiling`

### E7-01 · Signal stores (camera + game state)
- **Status:** ☐ Todo · **Module:** frontend · **Depends on:** E0-02 · **Delegate to:** frontend-developer
- **Read first:** `.claude/skills/angular21-signals`, `poc/galaxy-navigator.html` (camera model)
- **Do:** Signal-based **camera store** (pan/zoom state) ported from the PoC, plus signal stores for game state
  (factions, events, overlay). Zoneless-friendly, computed-derived view models.
- **Done when:** camera store mirrors PoC behaviour; state flows through signals; no Zone.js dependency.

### E7-02 · Canvas galaxy renderer (PoC port)
- **Status:** ☐ Todo · **Module:** frontend · **Depends on:** E7-01, E6-03 · **Delegate to:** galaxy-renderer-engineer
- **Read first:** `.claude/skills/galaxy-rendering`, `poc/galaxy-navigator.html`
- **Do:** Port the canvas PoC into an Angular standalone component for the **small-galaxy** tier: stars, routes,
  zoom-gated detail (planets/orbits/labels). Same data contract that WebGL2 will later satisfy (E8).
- **Done when:** small galaxy renders & pans/zooms like the PoC; reads tiles via E6-03; detail gated by zoom.

### E7-03 · STOMP client → signals
- **Status:** ☐ Todo · **Module:** frontend · **Depends on:** E7-01, E6-04 · **Delegate to:** frontend-developer
- **Read first:** `.claude/skills/realtime-websocket`, `docs/specs/websocket-protocol.md`
- **Do:** STOMP/WebSocket client that subscribes to ticks/events/overlay (and owner view) and feeds **signals**.
  Reconnect → resubscribe + REST `overlay?sinceTick=` resync.
- **Done when:** live events/overlay update the UI reactively; reconnect resyncs without a full reload.

### E7-04 · HUD & panels
- **Status:** ☐ Todo · **Module:** frontend · **Depends on:** E7-01 · **Delegate to:** frontend-developer
- **Read first:** `.claude/skills/angular21-signals`, `docs/game-design/01-world-and-map.md` §5
- **Do:** Signal-driven HUD: resource/influence/reputation readouts, selected-system/planet panels, scale-tier-aware
  action surfacing (display only — humans don't micromanage).
- **Done when:** HUD reflects live state via signals; panels update on selection; no direct state mutation from UI.

### E7-05 · Sovereign config screens
- **Status:** ☐ Todo · **Module:** frontend · **Depends on:** E6-02 · **Delegate to:** frontend-developer
- **Read first:** `docs/specs/rest-api.md` §Sovereign configuration, `docs/game-design/04-diplomacy.md` §6
- **Do:** Create/edit a Sovereign: persona preset or custom, goals, hard constraints, model tier. Calls the faction config REST.
- **Done when:** a human can configure & attach a Sovereign to a game; validation matches the REST contract.

### E7-06 · Spectator view & public event feed
- **Status:** ☐ Todo · **Module:** frontend · **Depends on:** E7-02, E7-03 · **Delegate to:** frontend-developer
- **Read first:** `docs/game-design/07-victory-and-lifecycle.md` §5, `docs/specs/websocket-protocol.md`
- **Do:** Spectator mode: watch the galaxy + a live public event feed (wars, treaties, betrayals, battles, captures),
  leaderboard, victory-progress. The "AI as sport" view.
- **Done when:** a spectator can watch a live match with a readable event timeline and standings.

---

# E8 — Scale to billions  *(Phase 3)* — read `galaxy-rendering`, `lod-tiling`, `procedural-galaxy`

### E8-01 · WebGL2 instanced renderer (swap canvas)
- **Status:** ☐ Todo · **Module:** frontend · **Depends on:** E7-02 · **Delegate to:** galaxy-renderer-engineer
- **Read first:** `.claude/skills/galaxy-rendering`, `docs/architecture/02-galaxy-scale.md` §4
- **Do:** Replace the canvas renderer with **WebGL2 instanced point sprites** behind the **same data contract**; one
  draw call for all visible stars from a compact buffer (pos/color/size/brightness).
- **Done when:** 10⁵–10⁶ visible stars at 60 fps; identical data contract to E7-02; canvas path retired or fallback.

### E8-02 · Astrophotography look (bloom, spikes, nebulosity)
- **Status:** ☐ Todo · **Module:** frontend · **Depends on:** E8-01 · **Delegate to:** galaxy-renderer-engineer
- **Read first:** `.claude/skills/galaxy-rendering`, `poc/galaxy-navigator.html`
- **Do:** Additive blending + GPU bloom post-pass, diffraction spikes on the brightest, nebulosity/dust; planet shading
  at system scale.
- **Done when:** visual quality matches/exceeds the PoC's luminous look; all GPU-side; frame budget held.

### E8-03 · Procedural catalog generator (server + client, identical constants)
- **Status:** ☐ Todo · **Module:** galaxy/frontend · **Depends on:** E2-01 · **Delegate to:** game-engine-developer
- **Read first:** `.claude/skills/procedural-galaxy`, `docs/architecture/02-galaxy-scale.md` §2
- **Do:** Scale the E2 generator to billions: server and client derive the **same star from the same seed+coords** with
  shared constants, so the client renders scenery it was never sent.
- **Done when:** server and client agree star-for-star for sampled cells; generation cost is O(visible), not O(catalog).

### E8-04 · Quadtree/Hilbert tile service + payloads
- **Status:** ☐ Todo · **Module:** galaxy/api · **Depends on:** E8-03, E6-03 · **Delegate to:** galaxy-renderer-engineer
- **Read first:** `.claude/skills/lod-tiling`, `docs/architecture/02-galaxy-scale.md` §3,§5
- **Do:** Full quadtree LOD: coarse levels → `AggregateTile` (density image/impostors); fine levels → `StarListTile`
  (actual stars + active-system pointers). Hilbert ordering for cache locality; generate-on-miss + cache.
- **Done when:** any (level,x,y) yields the correct aggregate-or-star-list payload; tiles cacheable; merges active state at fine levels.

### E8-05 · Viewport tile fetching, caching, LOD cross-fade
- **Status:** ☐ Todo · **Module:** frontend · **Depends on:** E8-04, E8-01 · **Delegate to:** galaxy-renderer-engineer
- **Read first:** `.claude/skills/lod-tiling`, `docs/architecture/02-galaxy-scale.md` §4
- **Do:** Client requests **only the tiles covering the viewport at the current zoom**; client-side tile cache;
  **cross-fade** outgoing/incoming levels at zoom boundaries (no popping). Per-frame work bounded by screen+zoom.
- **Done when:** panning/zooming a billion-star galaxy stays fluid; constant data-in-flight; no visible popping at LOD changes.

### E8-06 · Active overlay layer composited on tiles
- **Status:** ☐ Todo · **Module:** frontend · **Depends on:** E8-05, E6-04 · **Delegate to:** galaxy-renderer-engineer · 🔒
- **Read first:** `docs/architecture/02-galaxy-scale.md` §3,§5, `docs/specs/websocket-protocol.md`
- **Do:** Composite the **thin dynamic overlay** (ownership tint, fleet markers, live routes) — fetched/streamed
  separately — on top of cacheable star tiles, joined client-side by system id.
- **Done when:** overlay updates live without re-fetching star tiles; heavy tiles stay CDN-cacheable; fog-correct.

### E8-07 · Floating origin + CDN / pre-bake
- **Status:** ☐ Todo · **Module:** frontend/api · **Depends on:** E8-05 · **Delegate to:** galaxy-renderer-engineer
- **Read first:** `docs/architecture/02-galaxy-scale.md` §4,§5
- **Do:** Re-centre world coords on the camera periodically (avoid float32 breakdown at extreme zoom). Pre-bake coarse
  tiles + the active region's fine tiles; serve via CDN.
- **Done when:** infinite descent holds precision; common views are warm; largest galaxies feel like Google Maps.

---

# E9 — Progression & spectacle  *(Phase 4)*

### E9-01 · Small→large progression + reputation carry-over
- **Status:** ☐ Todo · **Module:** all · **Depends on:** E1-15, E6-01 · **Delegate to:** game-balance-designer
- **Read first:** `docs/game-design/07-victory-and-lifecycle.md` §6, `docs/game-design/06-technology.md` §5
- **Do:** Earn standing/a seat from small galaxies; enter large persistent galaxies carrying **only identity/reputation**
  (never material advantage). Tick interval scales with size.
- **Done when:** completing small galaxies gates entry to large ones; only identity/reputation carries; no resource carry-over.

### E9-02 · Replay / spectator from (seed, action log)
- **Status:** ☐ Todo · **Module:** all · **Depends on:** E1-17, E5-01, E7-06 · **Delegate to:** game-engine-developer
- **Read first:** `docs/game-design/07-victory-and-lifecycle.md` §5, `docs/architecture/03-agent-runtime.md` §4
- **Do:** Reconstruct any archived match exactly from `gameSeed` + recorded action stream; drive the spectator UI from
  replay. Backbone of tournaments, debugging, agent analysis.
- **Done when:** an archived match replays tick-identically and is watchable in the spectator view with scrub/seek.

### E9-03 · Tournament / season scaffolding + leaderboard
- **Status:** ☐ Todo · **Module:** all · **Depends on:** E9-01, E9-02 · **Delegate to:** game-balance-designer
- **Read first:** `docs/game-design/07-victory-and-lifecycle.md` §2,§6
- **Do:** Bracket/season structure where humans enter agents; scoring feeds rankings and the small→large gating;
  public spectator experience.
- **Done when:** a season runs multiple matches, aggregates scores into a ranking, and feeds progression gates.

---

## Cross-cutting cards (run continuously)

### X-01 · Security review pass (recurring) 🔒
- **Status:** ☐ Todo · **Delegate to:** security-reviewer
- **Read first:** `.claude/agents/security-reviewer.md`, `docs/architecture/01-system-overview.md` §3
- **Do:** Review every 🔒-flagged card before it's marked Done: fog-of-war enforcement, agent-output validation,
  prompt-injection resistance, owner-only WorldView delivery, no hidden state leaking to client/agent.
- **Done when:** each 🔒 card has a recorded sign-off; untrusted-client/untrusted-agent boundaries hold.

### X-02 · Balance coherence pass (recurring)
- **Status:** ☐ Todo · **Delegate to:** game-balance-designer
- **Read first:** `docs/specs/balance-config.md`, all `docs/game-design/`
- **Do:** Keep the 50/50 force↔diplomacy balance coherent as systems land; tune `small-default`/`large-persistent`;
  verify no dominant strategy; numbers stay in config.
- **Done when:** profiles produce balanced matches in the headless runner; emergent-behaviour notes recorded.

### X-03 · Spec sync (recurring)
- **Status:** ☐ Todo · **Delegate to:** (card owner)
- **Read first:** `docs/specs/`
- **Do:** Whenever a contract changes during implementation, update the matching spec **first**, then the code (rule #7).
- **Done when:** specs and code never diverge; schemaVersion bumped on any agent-I/O change.

---

## Milestones

- **M1 — Deterministic core (Phase 1):** E0 + E1 + E2 + E3 done. *Proof:* headless multi-faction match runs and replays
  tick-identically with scripted bots; golden hashes stable.
- **M2 — Live AI match (Phase 2):** E4 + E5 + E6 + E7 done. *Proof:* a small galaxy runs in real time with LLM Sovereigns,
  spectatable in the browser; agents negotiate, act, and the engine resolves under per-phase timeouts.
- **M3 — Billion-star client (Phase 3):** E8 done. *Proof:* pan/zoom a billion-star galaxy at 60 fps with tile LOD,
  cross-fade, and a live overlay — Google-Maps feel.
- **M4 — Spectacle (Phase 4):** E9 done. *Proof:* progression small→large, exact replay/spectator, and a season with rankings.
```
