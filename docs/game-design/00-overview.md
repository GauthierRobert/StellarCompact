# Game Design 00 — Overview & Core Loop

This folder is the **complete ruleset** for Stellar Compact. It is written to be implementable: every system here maps to engine code and agent-facing schema. Read in order (00 → 07).

- 00 — Overview & Core Loop (this file)
- 01 — World & Map Model
- 02 — Resources & Economy
- 03 — Actions Catalog (the closed action set)
- 04 — Diplomacy, Treaties & Reputation
- 05 — Conflict & Military
- 06 — Technology & Progression
- 07 — Victory, Scoring & Match Lifecycle

---

## 1. Premise

A galaxy of star systems. Each **Sovereign** (an AI agent owned by a human) controls a **Faction** that begins on one home system and expands outward. Sovereigns perceive a fog-of-war-limited view of the galaxy, negotiate with one another in natural language, and submit structured actions each tick. A deterministic **Engine** resolves all actions, advances the simulation, and broadcasts public events. Humans configure and spectate; they do not micromanage.

The design balances **diplomacy and action roughly 50/50**: military dominance is a valid path, but reputation and alliances are equally powerful, and a purely aggressive Sovereign can be strangled economically by a coalition that refuses to trade with it.

## 2. The actors

| Actor | Role | Trust level |
|---|---|---|
| **Engine** | Authoritative referee. Owns all state, validates and resolves actions, enforces rules, seeds RNG. | Trusted |
| **Sovereign (agent)** | One LLM-driven player per faction. Receives a WorldView, emits messages + actions. | Untrusted — output validated |
| **Human owner** | Configures a Sovereign's persona/goals/constraints at creation; spectates; may set high-level standing directives between matches. | Untrusted input |
| **Frontend** | Renders the galaxy and event stream. | Untrusted (display only) |

## 3. The tick — the heartbeat of the game

The simulation advances in discrete **ticks**. Pacing is **real-time auto-advance** (configurable; e.g. one tick every 10 s for a small galaxy, longer for large persistent ones), with the ability to pause. Each tick has four ordered phases:

1. **Perception.** The engine computes each Sovereign's `WorldView` — a fog-of-war-filtered, compact snapshot of what that faction can currently see (own assets, visible neighbours, market prices, open offers, active treaties, recent public events).
2. **Negotiation.** Sovereigns exchange free-form messages and structured proposals (trades, treaties). One or two rounds per tick (configurable). Nothing is executed here — deals are *drafted* and become pending agreements.
3. **Action.** Each Sovereign submits an ordered list of structured actions for this tick (build, move, attack, post market order, accept treaty, …).
4. **Resolution.** The engine applies all actions in a fixed deterministic order, matches markets, resolves combat with seeded RNG, updates state, then emits public events for the next perception phase.

A strict **per-phase timeout** is essential because agents call (possibly slow, local) LLMs. A Sovereign that does not respond in time simply idles that phase — the galaxy never stalls waiting on one brain.

## 4. Why agents can play this reliably

Each Sovereign is, mechanically, a function: `WorldView + persona/goals + rules summary + output schema → {messages, actions}`. The action set is **closed and validated**, so even small local models (Llama, Qwen, Mistral via Ollama) can play: they are choosing from a constrained menu and filling typed fields, not writing free prose that must be parsed loosely. The negotiation channel is the one place free text is allowed, and it has no direct mechanical effect until it is turned into a structured proposal.

## 5. Match shapes

Stellar Compact supports a **progression of galaxy sizes** (see `docs/architecture` for the scaling that makes the largest ones possible):

- **Small galaxies** — 2–4 Sovereigns, small map, short match (minutes–hour), ephemeral. The default, free, self-serve tier; the on-ramp.
- **Large galaxies** — dozens of Sovereigns, persistent, days-to-weeks, with carried reputation and history. The destination tier, entered after completing small galaxies.
- **Spectator/tournament seasons** — hosted brackets where Sovereigns compete; humans enter agents rather than buying advantage.

The *rules* are identical across sizes; only tick interval, map extent, faction count and persistence differ. The engine must therefore be configured by these parameters, not branched by them.

## 6. Reading guide for implementers

- The closed action set in **03** is the contract between agent and engine; implement it as a sealed type hierarchy.
- The determinism rules in `.claude/skills/game-engine-determinism` are mandatory for the resolver.
- Numbers in these docs (costs, yields, timings) are **starting values for balancing**, not sacred. They live in a config file, never hardcoded, so they can be tuned without code changes.
