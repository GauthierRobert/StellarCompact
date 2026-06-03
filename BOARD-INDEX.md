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
- ✅ **E7-05** Sovereign config screens — *deps: E6-02* — frontend-developer
- ✅ **E7-06** Spectator view & public event feed — *deps: E7-02, E7-03* — frontend-developer

## E8 — Scale to billions (Phase 3)
- ✅ **E8-01** WebGL2 instanced renderer (swap canvas) — *deps: E7-02* — galaxy-renderer-engineer
- ✅ **E8-02** Bloom / diffraction spikes / nebulosity — *deps: E8-01* — galaxy-renderer-engineer
- ✅ **E8-03** Procedural catalog generator (server+client, same constants) — *deps: E2-01* — game-engine-developer
- ✅ **E8-04** Quadtree/Hilbert tile service + payloads — *deps: E8-03, E6-03* — galaxy-renderer-engineer
- ✅ **E8-05** Viewport tile fetch + cache + LOD cross-fade — *deps: E8-04, E8-01* — galaxy-renderer-engineer
- ✅ **E8-06** 🔒 Active overlay layer composited on tiles — *deps: E8-05, E6-04* — galaxy-renderer-engineer
- ✅ **E8-07** Floating origin + CDN / pre-bake — *deps: E8-05* — galaxy-renderer-engineer

## E9 — Progression & spectacle (Phase 4)
- ✅ **E9-01** Small→large progression + reputation carry-over — *deps: E1-15, E6-01* — game-balance-designer
- ✅ **E9-02** Replay / spectator from (seed, action log) — *deps: E1-17, E5-01, E7-06* — game-engine-developer
- ✅ **E9-03** Tournament / season scaffolding + leaderboard — *deps: E9-01, E9-02* — game-balance-designer

## E10 — Post-simulation tuning & agent depth (from 3-agent 1h sim → `docs/game-design/09-three-agent-sim-findings.md`)
- ✅ **E10-01** Scripted bot: end Explore busy-loop, enable colonise (F1) — *deps: E3-01, E3-02* — agent-runtime-developer
- ✅ **E10-02** Scripted bot: balance the build ladder, fix energy starve (F2) — *deps: E10-01* — agent-runtime-developer
- ✅ **E10-03** Aggressive scripted-bot variant — exercise combat/diplomacy/victory (F5) — *deps: E3-01* — agent-runtime-developer
- ✅ **E10-04** Close economy loops: mineral sink + energy-deficit brownout (F2/F3) — *deps: E1-06* — game-balance-designer
- ✅ **E10-05** Fairness: starting-economy floor + reconcile economic-victory target (F4/F6) — *deps: E2-04, E1-15* — game-balance-designer
- ✅ **E10-06** 🔒 Validation: reject redundant Explore of a revealed system (F1) — *deps: E1-04, E3-02* — game-engine-developer (security sign-off via X-01)

## E11 — Make the live match a real game (from 4-agent live-server sim → `docs/game-design/10-four-agent-live-sim-findings.md`)
- ✅ **E11-01** Swap `MatchBootstrap` stub for galaxy-generated live bootstrap (connected region, planeted neutrals, fair homes) (L1/L2/L4) — *deps: E2-05, E6-01* — game-engine-developer
- ✅ **E11-02** Active timeout-victory + deterministic score tie-break so matches end (L3) — *deps: E1-15* — game-balance-designer
- ✅ **E11-03** Reconcile DOMINATION/economic victory thresholds against the generated map (L3) — *deps: E11-01, E1-15* — game-balance-designer
- ✅ **E11-04** 🔒 Per-seat agent-type selection in match API (SCRIPTED/AGGRESSIVE/LLM, whitelisted) (L6) — *deps: E6-01, E10-03, E4-05* — agent-runtime-developer (security sign-off via X-01 pass #3)
- ✅ **E11-05** Scripted bot: shipyard→ship→colonise economy, spend minerals (L5) — *deps: E11-01* — agent-runtime-developer
- ✅ **E11-06** Balance: planet-slot count vs upkeep so a home isn't permanently energy-negative (L5) — *deps: E10-04* — game-balance-designer
- ✅ **E11-07** Demo-match autostart profile + spectator defaults to it (P5) — *deps: E6-01, E7-06* — frontend-developer
- ✅ **E11-08** Match-picker + create controls in spectator HUD (P5) — *deps: E11-07* — frontend-developer
- ✅ **E11-09** One-command local run (compose Postgres + run scripts, `--enable-preview`, datasource env) (L7) — *deps: —* — game-engine-developer
- ✅ **E11-10** App-context `@SpringBootTest` smoke test in CI (catch wiring bugs like TileCache) (L7) — *deps: E11-09* — agent-runtime-developer

## E12 — Agent depth & spectacle (from 4-agent live-server sim → `docs/game-design/10-four-agent-live-sim-findings.md`)
- ✅ **E12-01** Scripted bot multi-tick memory/plans (scout→colonise→fortify) (P7a) — *deps: E11-05* — agent-runtime-developer
- ☐ **E12-02** Wire faction-config persona/goals/constraints into the live LLM prompt (P7b) — *deps: E7-05, E4-02* — agent-runtime-developer
- ☐ **E12-03** Opening-diplomacy phase on first contact (treaty/declaration, not silence) (P7c) — *deps: E11-01, E4-06* — agent-runtime-developer
- ✅ **E12-04** Richer public events (colony founded, first contact, tech unlocked) for feed + overlay (P7d) — *deps: E1-16* — game-engine-developer

## Cross-cutting (recurring)
- ▶ **X-01** 🔒 Security review pass (recurring) — pass #1: E4-03/E6-02/E8-06 PASS, E4-04/E6-04 PASS-with-notes (X01-1/X01-2 fixed; X01-3 tracked); pass #2: **E10-06 PASS** (no fog leak in `ALREADY_REVEALED`, pure reveal-tracking); pass #3: **E11-04 PASS** (closed `SeatType` whitelist + exhaustive switch, no reflection/arbitrary instantiation, `Locale.ROOT` case-fold, length-check before per-element work, LLM rejected not instantiated; one optional defense-in-depth note: request list-size bound) — security-reviewer
- ▶ **X-02** Balance coherence pass (recurring) — pass #1: 50/50 holds, no dominant strategy, 4 gaps noted; pass #2 (E10-05): home economy floor + economic-victory target reconciled (`08-balance-coherence-notes.md` §8); pass #3 (E11-03/06): victory thresholds verified reachable on the 8-system generated small map (DOMINATION 0.6 = 5/8, economic 700 within reach), oceanic-biome energy floor (0→1.0) fixes permanent home energy-deficit (`08-balance-coherence-notes.md` §9) — game-balance-designer
- ▶ **X-03** Spec sync (recurring) — maintained in step this session (rest-api, websocket-protocol, agent-io-schema, balance-config, data-model, procedural-catalog-algorithm, 02-galaxy-scale) — card owner

## Milestones
- **M1** Deterministic core — E0+E1+E2+E3 (headless match replays tick-identically)
- **M2** Live AI match — E4+E5+E6+E7 (LLM Sovereigns, spectatable in browser)
- **M3** Billion-star client — E8 (60 fps pan/zoom, tile LOD, live overlay)
- **M4** Spectacle — E9 (progression, exact replay, seasons)
