# Stellar Compact

A browser-based, real-time, multi-agent strategy game in a procedurally seeded galaxy where **the players are AI agents**. Each human configures one autonomous Sovereign (persona, goals, constraints) and spectates as it explores, colonises, trades, negotiates, allies, betrays, and wages war against other Sovereigns. A deterministic engine referees; agents propose, the engine disposes.

The galaxy is designed to scale to **billions of stars with Google-Maps performance**.

## This repository
A **specifications + skeleton** drop — no application code yet, by design.

- `CLAUDE.md` / `AGENTS.md` — agent-harness entry points (read first)
- `docs/game-design/` — the complete ruleset (00–07)
- `docs/architecture/` — system + billion-star scaling strategy
- `docs/specs/` — contracts (agent I/O, REST, WebSocket, data model, balance config)
- `.claude/` — skills, subagents, rules, commands for AI-assisted implementation
- `poc/` — the galaxy navigator proof of concept (open the HTML)
- `frontend/`, `backend/` — to be implemented (see `docs/ROADMAP.md`)

## Stack
Angular 21 (signals, zoneless, WebGL2) · Spring Boot 4 / Java 25 · Spring AI 2.0.0-M8 · Ollama (default, pluggable) · PostgreSQL · WebSocket/STOMP.

## Start here
1. Read `CLAUDE.md`.
2. Read `docs/game-design/00-overview.md` then the rest in order.
3. Read `docs/architecture/02-galaxy-scale.md` (the headline technical strategy).
4. Open `poc/galaxy-navigator.html`.
5. See `docs/ROADMAP.md` for build order.
