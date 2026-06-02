# Stellar Compact — Board Index (quick scan)

One line per card. Full detail (read-first links, do, done-when, 🔒 flags) lives in **`BOARD.md`**.
Pick the lowest-id `☐` card whose deps are all `✅`. Build tool is **Maven** (multi-module reactor).

Legend: ☐ Todo · ▶ Doing · ⛔ Blocked · ✅ Done · 🔒 needs security-reviewer sign-off

## E0 — Scaffolding (Phase 0.5)
- ✅ **E0-01** Maven multi-module reactor (engine/galaxy/agent-runtime/orchestrator/api/persistence/app) — *deps: —* — game-engine-developer
- ✅ **E0-02** Angular 21 app skeleton (standalone, signals, zoneless) — *deps: —* — frontend-developer
- ✅ **E0-03** Balance config: schema, loader, `small-default` + `large-persistent` — *deps: E0-01* — game-balance-designer
- ✅ **E0-04** Test + golden-hash harness, 80% coverage gates — *deps: E0-01* — game-engine-developer

## E1 — Deterministic rules engine (Phase 1)
- ✅ **E1-01** Core state records — *deps: E0-01* — game-engine-developer
- ✅ **E1-02** 🔒 Sealed Action hierarchy (25 variants) + AgentResponse — *deps: E1-01* — game-engine-developer
- ✅ **E1-03** Seeded RNG (gameSeed ⊕ tick ⊕ localSalt) — *deps: E0-01* — game-engine-developer
- ✅ **E1-04** 🔒 Action validation (Valid|Rejected{reason}) — *deps: E1-01, E1-02* — game-engine-developer
- ✅ **E1-05** Resolver skeleton (fixed 11-step order) — *deps: E1-03, E1-04* — game-engine-developer
- ✅ **E1-06** Economy: production/upkeep/deficit attrition/population — *deps: E1-05, E0-03* — game-engine-developer
- ✅ **E1-07** Market order book + escrow (price-time priority) — *deps: E1-05* — game-engine-developer
- ✅ **E1-08** Construction + tech DAG + terraform — *deps: E1-05* — game-engine-developer
- ✅ **E1-09** Movement & interception — *deps: E1-05, E2-03* — game-engine-developer
- ✅ **E1-10** Combat & system assault/capture/unrest — *deps: E1-05, E1-09* — game-engine-developer
- ✅ **E1-11** Blockade & raid effects — *deps: E1-07, E1-09* — game-engine-developer
- ✅ **E1-12** 🔒 Diplomacy: treaties/reputation/war/tribute — *deps: E1-05* — game-engine-developer
- ✅ **E1-13** Espionage operations (seeded) — *deps: E1-05* — game-engine-developer
- ✅ **E1-14** Influence accrual & decay — *deps: E1-06, E1-12* — game-engine-developer
- ✅ **E1-15** Victory conditions, scoring, lifecycle — *deps: E1-14* — game-balance-designer
- ✅ **E1-16** Public event emission — *deps: E1-10, E1-12* — game-engine-developer
- ✅ **E1-17** Golden-hash & replay tests — *deps: E1-06…E1-16* — game-engine-developer

## E2 — Galaxy generation, small/fixed (Phase 1)
- ✅ **E2-01** Procedural star placement (seed-based, spiral density) — *deps: E0-01* — game-engine-developer
- ✅ **E2-02** System & planet roster generation (biomes) — *deps: E2-01* — game-engine-developer
- ✅ **E2-03** Lane graph (length = travel ticks) — *deps: E2-01* — game-engine-developer
- ✅ **E2-04** Home placement (min separation, balanced) — *deps: E2-02, E2-03* — game-balance-designer
- ✅ **E2-05** Promotion/demotion boundary (procedural ↔ active) — *deps: E2-02, E1-01* — game-engine-developer

## E3 — Sovereign contract, scripted bot, headless runner (Phase 1)
- ✅ **E3-01** Sovereign interface + scripted bot (no LLM) — *deps: E1-02* — agent-runtime-developer
- ✅ **E3-02** 🔒 WorldView builder + fog-of-war filtering — *deps: E1-01, E3-01* — agent-runtime-developer
- ✅ **E3-03** Headless match runner + determinism replay — *deps: E1-17, E3-01, E3-02* — game-engine-developer

## E4 — LLM agent runtime + orchestration (Phase 2)
- ✅ **E4-01** ChatClient integration (Ollama default, pluggable) — *deps: E3-01* — agent-runtime-developer
- ✅ **E4-02** Prompt assembly (persona+goals+rules+schema+example) — *deps: E4-01, E3-02* — agent-runtime-developer
- ✅ **E4-03** 🔒 Structured-output coercion + defensive parse — *deps: E4-02, E1-02* — agent-runtime-developer
- ✅ **E4-04** 🔒 Validate-after-parse + single re-prompt → Hold — *deps: E4-03, E1-04* — agent-runtime-developer
- ✅ **E4-05** Tick orchestrator (4 phases, virtual threads, timeouts) — *deps: E4-04, E1-05* — agent-runtime-developer
- ✅ **E4-06** Negotiation phase (rounds, messages, pending proposals) — *deps: E4-05* — agent-runtime-developer

## E5 — Persistence (Phase 2)
- ✅ **E5-01** PostgreSQL schema & migrations (Flyway) — *deps: E1-01* — agent-runtime-developer
- ✅ **E5-02** Active-state repositories (load/save bounded set) — *deps: E5-01* — agent-runtime-developer
- ✅ **E5-03** Transactional tick commit + resume from log — *deps: E5-02, E4-05* — agent-runtime-developer

## E6 — API: REST + STOMP (Phase 2)
- ✅ **E6-01** Match lifecycle REST — *deps: E1-15, E5-03* — agent-runtime-developer
- ✅ **E6-02** 🔒 Faction config REST (owner redaction) — *deps: E6-01* — agent-runtime-developer
- ✅ **E6-03** Tile + overlay endpoints (cacheable, ETag) — *deps: E2-01* — galaxy-renderer-engineer
- ✅ **E6-04** 🔒 STOMP live stream (public topics + owner-only view) — *deps: E1-16, E4-05* — agent-runtime-developer

## E7 — Frontend, canvas PoC port (Phase 2)
- ✅ **E7-01** Signal stores (camera + game state) — *deps: E0-02* — frontend-developer
- ✅ **E7-02** Canvas galaxy renderer (PoC port) — *deps: E7-01, E6-03* — galaxy-renderer-engineer
- ✅ **E7-03** STOMP client → signals — *deps: E7-01, E6-04* — frontend-developer
- ✅ **E7-04** HUD & panels — *deps: E7-01* — frontend-developer
- ☐ **E7-05** Sovereign config screens — *deps: E6-02* — frontend-developer
- ☐ **E7-06** Spectator view & public event feed — *deps: E7-02, E7-03* — frontend-developer

## E8 — Scale to billions (Phase 3)
- ✅ **E8-01** WebGL2 instanced renderer (swap canvas) — *deps: E7-02* — galaxy-renderer-engineer
- ✅ **E8-02** Bloom / diffraction spikes / nebulosity — *deps: E8-01* — galaxy-renderer-engineer
- ✅ **E8-03** Procedural catalog generator (server+client, same constants) — *deps: E2-01* — game-engine-developer
- ✅ **E8-04** Quadtree/Hilbert tile service + payloads — *deps: E8-03, E6-03* — galaxy-renderer-engineer
- ✅ **E8-05** Viewport tile fetch + cache + LOD cross-fade — *deps: E8-04, E8-01* — galaxy-renderer-engineer
- ☐ **E8-06** 🔒 Active overlay layer composited on tiles — *deps: E8-05, E6-04* — galaxy-renderer-engineer
- ☐ **E8-07** Floating origin + CDN / pre-bake — *deps: E8-05* — galaxy-renderer-engineer

## E9 — Progression & spectacle (Phase 4)
- ☐ **E9-01** Small→large progression + reputation carry-over — *deps: E1-15, E6-01* — game-balance-designer
- ☐ **E9-02** Replay / spectator from (seed, action log) — *deps: E1-17, E5-01, E7-06* — game-engine-developer
- ☐ **E9-03** Tournament / season scaffolding + leaderboard — *deps: E9-01, E9-02* — game-balance-designer

## Cross-cutting (recurring)
- ☐ **X-01** 🔒 Security review pass — security-reviewer
- ☐ **X-02** Balance coherence pass — game-balance-designer
- ☐ **X-03** Spec sync (edit `docs/specs/` before code) — card owner

## Milestones
- **M1** Deterministic core — E0+E1+E2+E3 (headless match replays tick-identically)
- **M2** Live AI match — E4+E5+E6+E7 (LLM Sovereigns, spectatable in browser)
- **M3** Billion-star client — E8 (60 fps pan/zoom, tile LOD, live overlay)
- **M4** Spectacle — E9 (progression, exact replay, seasons)
