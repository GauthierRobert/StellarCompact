---
name: agent-sovereign
description: Use when implementing the Sovereign agent contract, the tick orchestration (four phases), WorldView construction, fog-of-war filtering, the per-phase structured-concurrency fan-out with timeouts, or the scripted/bot agent used for testing and empty seats. Read BEFORE touching the orchestrator or the agent interface.
---

# Agent Sovereign & Tick Orchestration

A Sovereign is a function: `WorldView → {messages, actions}`. The default is LLM-backed (see spring-ai-agent skill); a scripted bot implements the same interface for tests and to fill empty seats.

## The tick (four phases)
Perception → Negotiation → Action → Resolution. Agent-calling phases fan out concurrently under a shared deadline.

```
try (var scope = new StructuredTaskScope<AgentReply>()) {
   var tasks = factions.stream().map(f -> scope.fork(() -> f.act(views.get(f.id())))).toList();
   scope.joinUntil(deadline);              // shared per-phase timeout
   // tasks not SUCCESS → treat as Hold; the galaxy never waits on one brain
}
```

## Rules
1. **One virtual thread per agent call.** Slow Ollama calls block only their own cheap VT.
2. **Per-phase timeout is mandatory.** Timed-out / errored agent → `Hold` for that phase. Log it; continue.
3. **WorldView is built server-side with fog-of-war filtering.** An agent can never receive another faction's hidden state — there is nothing to leak even under prompt-injection attempts.
4. **Keep WorldView compact** (token budget): top-of-book markets only, public reputation ledger, own state full, neighbours fog-limited. See `docs/specs/agent-io-schema.md`.
5. **Negotiation text is non-binding.** Only accepted structured proposals bind. The engine enforces only signed agreements.
6. **Resolution is pure & single-threaded** (see game-engine-determinism). Concurrency is only in gathering outputs.

## Failure/resilience
- Tick is transactional vs PostgreSQL: commit fully or roll back.
- Resume a paused/crashed galaxy from last committed tick + event log.

## References
- `docs/architecture/03-agent-runtime.md`
- `docs/game-design/00-overview.md` (the tick)
- Cross-skill: `spring-ai-agent`, `game-engine-determinism`, `realtime-websocket`.
