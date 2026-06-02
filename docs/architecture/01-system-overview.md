# Architecture 01 — System Overview

## 1. High-level shape

```
┌────────────────────────────────────────────────────────────────┐
│                         BROWSER (Angular 21)                     │
│  ┌───────────────┐   ┌──────────────┐   ┌────────────────────┐  │
│  │ Galaxy WebGL2 │   │  HUD / panels│   │ Sovereign config + │  │
│  │  renderer     │   │ (signals)    │   │ spectator views    │  │
│  └──────┬────────┘   └──────┬───────┘   └─────────┬──────────┘  │
│         │ tiles + viewport state    REST          │ WS (STOMP)  │
└─────────┼──────────────────────────┬──────────────┼────────────┘
          │ tile fetch (HTTP/CDN)     │ config/CRUD  │ live ticks
┌─────────▼──────────────────────────▼──────────────▼────────────┐
│                       SPRING BOOT 4 (Java 25)                    │
│  ┌────────────┐  ┌──────────────┐  ┌──────────────┐  ┌────────┐ │
│  │ Tile/Catalog│ │ Game API     │  │ Tick          │ │ WS hub │ │
│  │ service     │ │ (REST)       │  │ Orchestrator  │ │(STOMP) │ │
│  └─────┬──────┘  └──────┬───────┘  └──────┬───────┘  └───┬────┘ │
│        │                │                 │              │      │
│  ┌─────▼────────────────▼─────────────────▼──────────────▼────┐ │
│  │                    Game Engine (pure, deterministic)        │ │
│  │   records · sealed Action · seeded RNG · resolver           │ │
│  └─────┬───────────────────────────────────────────┬──────────┘ │
│        │                                            │            │
│  ┌─────▼─────────┐                          ┌───────▼─────────┐  │
│  │ Spatial index │                          │ Agent Runtime   │  │
│  │ (galaxy data) │                          │ (Spring AI)     │  │
│  └─────┬─────────┘                          └───────┬─────────┘  │
└────────┼────────────────────────────────────────────┼───────────┘
         │                                             │
   ┌─────▼──────┐   ┌──────────────┐            ┌──────▼───────┐
   │ PostgreSQL │   │ Object store │            │ Ollama /     │
   │ (state)    │   │ (tiles)      │            │ OpenAI / …   │
   └────────────┘   └──────────────┘            └──────────────┘
```

## 2. Modules (Maven multi-module reactor)

| Module | Responsibility | Spring? |
|---|---|---|
| **engine** | Pure domain: state records, sealed `Action`, deterministic resolver, market matching, combat, economy. No I/O, no Spring. Fully unit-testable. | No |
| **galaxy** | Procedural galaxy generation + the spatial index/tile model (the billion-star machinery). | No (lib) |
| **agent-runtime** | Spring AI `ChatClient` integration: build WorldView prompt, parse/validate agent JSON, one re-prompt, provider-pluggable (Ollama default). | Yes |
| **orchestrator** | The tick scheduler; runs the four phases with virtual threads + structured concurrency and per-phase timeouts. | Yes |
| **api** | REST (config/CRUD/tiles) + WebSocket/STOMP (live tick stream). | Yes |
| **persistence** | PostgreSQL repositories for game state + catalog metadata; object-store adapter for tiles. | Yes |
| **app** | Spring Boot entry point wiring it together. | Yes |

The **engine** and **galaxy** modules are intentionally framework-free so they can be tested in isolation and reused (e.g. a headless tournament runner) without booting Spring.

## 3. Trust boundaries

- **Browser** and **agents** are untrusted. All rules and all authoritative state live in **engine**/**persistence**.
- The frontend receives only what a spectator/owner is allowed to see; it never receives another faction's hidden state.
- Agent output crosses the boundary as data, validated against the closed schema before it can affect anything.

## 4. Java 25 features that carry their weight

- **Virtual threads** — one per agent call; hundreds of agents each blocked on a slow Ollama HTTP call cost almost nothing.
- **Structured concurrency (`StructuredTaskScope`)** — each tick phase forks all agent calls under one shared deadline; the phase ends when all finish or the timeout fires; stragglers are cleanly cancelled and idle.
- **Records** — immutable state snapshots, trivially serialisable to the `WorldView` JSON.
- **Sealed interfaces + pattern matching** — `sealed interface Action permits …`; the resolver's exhaustive `switch` makes a forgotten action type a compile error.

## 5. Spring AI 2.0.0-M8 placement

`agent-runtime` depends on Spring AI's `ChatClient`. Model provider (Ollama, OpenAI, …) is **configuration**, not code — selected by Spring profile/properties. The agent prompt is assembled from the WorldView + persona + rules summary + the strict output schema; the response is parsed defensively and validated. Spring AI's structured-output/converter support is used to coerce the model toward the closed `Action` schema, with a validation+re-prompt fallback. See `.claude/skills/spring-ai-agent`.

## 6. Data split: state vs catalog

Two very different data problems share the backend:
- **Game state** (factions, fleets, treaties, routes, orders, the ~hundreds–thousands of *active* systems): small, mutable, transactional → **PostgreSQL**.
- **The galaxy catalog** (up to billions of stars): huge, mostly immutable, read-mostly, spatially queried → **procedural generation + a tiled spatial index served like map tiles** (`docs/architecture/02-galaxy-scale.md`).

Keeping these separate is the central architectural decision that makes the billion-star requirement tractable: only the tiny *active* subset is ever loaded into the simulation or written to the relational store.
