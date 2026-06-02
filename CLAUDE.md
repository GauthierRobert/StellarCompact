# Stellar Compact — Project Context (CLAUDE.md)

> This file orients any AI coding agent (Claude Code, Codex, Cursor…) working in this repository. Read it first. It is intentionally concise; deep detail lives in `docs/` and `.claude/skills/`.

## What this project is

**Stellar Compact** is a browser-based, real-time, multi-agent strategy game set in a procedurally seeded galaxy. The defining twist: **the players are AI agents**, not humans. Each human owns one autonomous *Sovereign* (an LLM-driven faction). Humans configure their Sovereign's persona, goals and constraints, then watch it explore, colonise, trade, negotiate, ally, betray and wage war against other Sovereigns.

Two things make this project unusual and must never be lost:

1. **The galaxy must scale to billions of stars while rendering like Google Maps** — instant pan/zoom, level-of-detail, no popping, no loading the whole dataset. See `docs/architecture/02-galaxy-scale.md`.
2. **The engine is the referee.** Agents never mutate game state directly. They submit validated, structured actions; a deterministic engine resolves them. This guarantees fairness, reproducibility and cheat-resistance. See `docs/game-design/`.

## Tech stack (fixed — do not substitute)

- **Frontend:** Angular 21 (standalone components, signals, zoneless change detection). Rendering via WebGL2 (regl or raw) for the galaxy; canvas/DOM for HUD.
- **Backend:** Spring Boot 4 (Java 25 — virtual threads, structured concurrency, records, sealed interfaces, pattern matching).
- **AI orchestration:** Spring AI 2.0.0-M8. Default model provider is **local Ollama**; provider is pluggable (OpenAI etc.) behind a Spring AI `ChatClient`.
- **Transport:** REST for config/CRUD; WebSocket (STOMP) for the live tick event stream.
- **Persistence:** PostgreSQL (game state, catalog metadata) + object storage / served tiles for the galaxy spatial index.

## Repository layout

```
stellar-compact/
├── CLAUDE.md                 ← you are here
├── AGENTS.md                 ← cross-harness pointer (Codex/Cursor)
├── .claude/                  ← agent harness: skills, subagents, rules, commands
│   ├── skills/               ← deep procedural knowledge (galaxy-rendering, lod-tiling, …)
│   ├── agents/               ← specialised subagents to delegate to
│   ├── rules/                ← always-follow guidelines
│   └── commands/             ← slash-command entry points
├── docs/
│   ├── game-design/          ← the complete game rules (deep)
│   ├── architecture/         ← system + billion-star scaling architecture
│   └── specs/                ← component specs, schemas, API contracts (NO code)
├── poc/                      ← the HTML galaxy-navigator proof of concept (v8)
├── frontend/                 ← Angular 21 app (to be built)
└── backend/                  ← Spring Boot 4 app (to be built)
```

## Current status

This is a **specification + skeleton** drop. There is **no application code yet** — by design. What exists:
- Complete game-design documents (rules, economy, diplomacy, combat, win conditions).
- Architecture documents, including the billion-star LOD/tiling strategy.
- Component specs and data-schema/API contracts (described, not implemented).
- The `.claude` harness (skills + agents + rules) to drive implementation.
- The HTML PoC under `poc/` demonstrating the rendering approach.

When implementation begins, **read the relevant skill in `.claude/skills/` before writing code** for that area. Each skill encodes constraints that are easy to get wrong.

## Non-negotiable principles

1. **Determinism.** Same seed + same agent outputs ⇒ identical resolution. Seed all RNG from `gameSeed + tickNumber`. Never use wall-clock or unseeded randomness in the engine.
2. **Engine authority.** The frontend and the agents are untrusted. All rules live server-side. Agents propose; the engine disposes.
3. **Scale discipline.** Never load or iterate the full star catalog on the client. Detail is always a function of zoom (see lod-tiling skill).
4. **Provider neutrality.** Never hardcode an LLM vendor. Always go through the Spring AI `ChatClient` abstraction so Ollama/OpenAI/etc. are swappable by config.
5. **Strict agent I/O.** Agent output is validated against a closed schema (sealed `Action` types). Invalid ⇒ one re-prompt with the rejection reason, then the faction idles that tick.

## How to work here

- Planning a feature → use the `planner` subagent / `/plan`.
- Touching rendering → read `.claude/skills/galaxy-rendering` and `lod-tiling` first.
- Touching the agent loop → read `.claude/skills/agent-sovereign` and `spring-ai-agent`.
- Touching the rules engine → read `.claude/skills/game-engine-determinism`.
- Always prefer editing specs in `docs/specs/` before implementing; keep them in sync.
