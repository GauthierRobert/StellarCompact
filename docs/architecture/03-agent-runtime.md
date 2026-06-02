# Architecture 03 — Agent Runtime & Tick Orchestration

## 1. The tick loop, concretely

The orchestrator drives ticks in real time. Each tick runs the four phases (Perception → Negotiation → Action → Resolution). Phases that call agents fan out to all Sovereigns concurrently under a **shared deadline** using Java 25 structured concurrency.

```
loop每tick:
  perception:  for each faction → build WorldView (from engine state, fog-filtered)
  negotiation: StructuredTaskScope → each Sovereign.negotiate(view) with timeout
               (collect messages + pending proposals; feed into next views)
  action:      StructuredTaskScope → each Sovereign.act(view) with timeout
               (collect + validate action lists)
  resolution:  engine.resolve(state, allValidatedActions, seed=gameSeed⊕tick)
               emit public events → push to WS subscribers
```

- **One virtual thread per agent call.** A slow Ollama call blocks only its own cheap virtual thread.
- **Per-phase timeout.** `scope.joinUntil(deadline)`; any Sovereign not done by the deadline contributes `Hold` for that phase. The galaxy never waits on one brain.
- **Resolution is single-threaded and pure.** All concurrency is in the *gathering* of agent outputs; the actual state mutation is one deterministic call. This is what preserves reproducibility.

## 2. The agent contract

A Sovereign is, to the orchestrator, an implementation of a small interface: given a `WorldView`, return `{messages[], actions[]}`. The default implementation is LLM-backed via `agent-runtime`; a scripted/bot implementation (for testing and for filling empty seats) implements the same interface.

### Prompt assembly (agent-runtime)

```
system prompt = persona + goals + hard constraints
              + rules summary (compact)
              + the strict output JSON schema (closed Action set)
              + one worked example
user message  = serialized WorldView (compact, fog-filtered)
```

### Output handling

1. Call the model through Spring AI `ChatClient` (provider from config).
2. Coerce to the `Action[]` schema (Spring AI structured output / converter).
3. **Validate** each action against engine rules (affordability, adjacency, ownership, treaty legality).
4. On validation failure: **one** re-prompt including the specific rejection reason.
5. Still invalid / timed out → that action is dropped (faction may idle).

The negotiation phase is the same machinery but the schema permits free-text `SendMessage` plus structured proposals.

## 3. WorldView design (keep it small)

The WorldView is the agent's entire perception, so it must be **compact** (token budget) yet sufficient:

- own factions's systems/fleets/stockpiles/tech (full)
- visible neighbours (fog-limited: ownership, rough strength, no hidden state)
- market snapshot at reachable hubs (best bids/asks)
- active treaties involving this faction + public reputation of all factions
- pending offers addressed to this faction
- recent public events (since last tick)
- current victory-condition progress

Fog-of-war filtering happens here, server-side — an agent literally cannot receive another faction's hidden state, so even a prompt-injection-style attempt to "ask" for it has nothing to leak.

## 4. Determinism boundary

- **Inside the engine:** fully deterministic. RNG seeded `gameSeed ⊕ tick ⊕ localSalt`. Fixed resolution order (game-design 03 §"Resolution order").
- **Agent outputs:** non-deterministic (LLMs). But the *recorded* action stream is replayable: given the same seed and the same recorded actions, resolution is identical. So **matches are reproducible from (seed, action log)** even though live agent behaviour is not. This is the basis of replay/spectator/debug (game-design 07 §5).

## 5. Scaling agents across many factions / many galaxies

- Per-galaxy orchestrators are independent; a host runs many concurrently. Virtual threads make thousands of in-flight agent calls cheap.
- Tick interval scales with galaxy size: small galaxies tick every few seconds; large persistent galaxies tick on the order of minutes, so a days-long campaign is feasible and agent-call volume stays bounded.
- Agent inference is the real cost centre. The Spring AI abstraction lets us route by tier: local Ollama for free/small play; hosted models for premium/large/tournament play — purely a config/routing choice, no engine impact.

## 6. Failure & resilience

- Agent call error/timeout → treated as `Hold`; logged; faction continues next tick.
- Engine resolution is transactional against PostgreSQL: a tick either fully commits or rolls back; the event log is append-only and ordered by tick.
- A paused/crashed galaxy resumes from the last committed tick + event log.
