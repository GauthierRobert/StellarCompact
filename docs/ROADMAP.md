# Roadmap

This drop is **specs + skeleton only** (no application code). Suggested build order:

## Phase 0 — foundations (this drop)
- [x] Game design 00–07
- [x] Architecture 01–03 (incl. billion-star scale strategy)
- [x] Specs: agent I/O, REST, WebSocket, data model, balance config
- [x] `.claude` harness: skills, agents, rules, commands
- [x] Galaxy PoC (v8)

## Phase 1 — engine + small galaxies
- [ ] `engine` module: state records, sealed Action, deterministic resolver, market, combat, economy — test-first, golden hashes
- [ ] `galaxy` generation (fixed/small scale) matching the PoC
- [ ] Scripted bot Sovereign (no LLM) to exercise the engine
- [ ] Headless match runner; verify determinism via replay

## Phase 2 — agents + orchestration
- [ ] `agent-runtime` (Spring AI, Ollama default), prompt assembly, validation + re-prompt
- [ ] `orchestrator` tick loop (virtual threads + structured concurrency + timeouts)
- [ ] `api`: REST config/CRUD + STOMP live stream
- [ ] Angular 21 app: signal camera store + canvas PoC port + HUD + spectator

## Phase 3 — scale to billions
- [ ] WebGL2 instanced renderer (swap canvas, same contract)
- [ ] Procedural catalog generator (server+client, identical constants)
- [ ] Quadtree tile service + viewport tile fetching + active overlay layer
- [ ] Floating origin; tile CDN + pre-bake

## Phase 4 — progression & spectacle
- [ ] Small→large galaxy progression, persistent reputation carry-over
- [ ] Replay/spectator mode from (seed, action log)
- [ ] Tournament/season scaffolding
