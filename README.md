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

## Run locally

**Prerequisites**
- **JDK 25** — the backend uses Java 25 *preview* features, so it must run with `--enable-preview`. Point `JAVA_HOME` at a JDK 25 install (the run scripts read it from the environment and refuse to start otherwise).
- **Maven 3.6.3+** on `PATH` (`mvn`).
- **Node + npm** (Angular 21 dev server).
- **Docker** with the `compose` plugin (for PostgreSQL).

**One command (Windows PowerShell)**
```powershell
docker compose up -d db      # PostgreSQL on host port 5433 (stellar/stellar/stellar)
./scripts/run-all.ps1        # builds the backend jar if missing, runs it, starts the UI
```
`run-all.ps1` actually brings up the DB itself, so the explicit `docker compose up -d db` is optional. On macOS/Linux use `scripts/run-all.sh`.

- UI:  http://localhost:4200
- API: http://localhost:8080  (`POST /api/games`, `/api/games/{id}/start`, `/state`, `/events`, `/leaderboard`)

The Angular dev server proxies `/api` and `/ws` to `:8080` via `frontend/proxy.conf.json` (already wired into `angular.json`).

**Run pieces individually**
- `scripts/run-backend.ps1` — starts Postgres via compose (if not up), builds `backend/app/target/app-0.1.0-SNAPSHOT.jar` if missing, then runs it with `--enable-preview` and the datasource args. Override the DB with `STELLAR_DB_HOST` / `STELLAR_DB_PORT` / `STELLAR_DB_NAME` / `STELLAR_DB_USER` / `STELLAR_DB_PASSWORD`.
- `scripts/run-frontend.ps1` — `npm install` (first run) then `npm start`.

**Notes / gotchas**
- PostgreSQL listens on host port **5433** (mapped to container 5432) to avoid colliding with a local Postgres on 5432. Credentials and DB name are all `stellar`. Flyway migrations live in `backend/persistence/src/main/resources/db/migration`.
- The backend **will not boot without `--enable-preview`** on a JDK 25 — the scripts handle this for you; if you run the jar by hand, include the flag.
- Build manually: `cd backend; mvn -DskipTests install`.

## Start here
1. Read `CLAUDE.md`.
2. Read `docs/game-design/00-overview.md` then the rest in order.
3. Read `docs/architecture/02-galaxy-scale.md` (the headline technical strategy).
4. Open `poc/galaxy-navigator.html`.
5. See `docs/ROADMAP.md` for build order.
