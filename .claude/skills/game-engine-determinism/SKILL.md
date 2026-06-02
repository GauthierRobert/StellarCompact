---
name: game-engine-determinism
description: Use when implementing or modifying the game rules engine — the deterministic resolver, the sealed Action hierarchy, seeded RNG, fixed resolution order, market matching, combat, economy, or anything in the engine module. Read BEFORE writing any engine code. Violating determinism breaks replay, testing, and fairness.
---

# Game Engine Determinism

The engine is the authoritative referee. It must be **pure and deterministic**: same seed + same agent action stream ⇒ identical resolution, byte for byte. This underpins replay, reproducible tests, and cheat-resistance.

## Hard rules
1. **No I/O, no Spring, no wall-clock, no unseeded randomness in the engine module.** It is plain Java 25.
2. **Seed all RNG** from `gameSeed ⊕ tick ⊕ localSalt` (e.g. battleId). Use a `RandomGenerator` constructed per use-site from that seed. Never `Math.random` / `new Random()` with no seed.
3. **Fixed resolution order** (see game-design 03 §"Resolution order"): treaty/war state → espionage → movement/interception → combat → blockade/raid → construction/research/terraform → colonisation → market match/escrow → production/upkeep/population/attrition → influence → events. Within a category: by faction id, then submission order. Never iterate hash maps in undefined order.
4. **Closed action set as a sealed interface.** `sealed interface Action permits Explore, Colonize, …`. Resolver uses an exhaustive `switch` — a new variant is a compile error until handled.
5. **Immutable state via records.** State transitions produce new snapshots; no in-place mutation that could reorder effects.
6. **Validate before resolve.** Every action is `Valid` or `Rejected{reason}`; rejected actions never touch state.

## Concurrency boundary
- All concurrency lives in the *orchestrator* (gathering agent outputs via virtual threads + structured concurrency).
- `engine.resolve(state, validatedActions, seed)` is **single-threaded and pure**. Do not parallelise resolution.

## Replay contract
- Persist `(gameSeed, ordered action log per tick)`. Re-running resolution over that log reproduces every tick exactly.
- The event log is append-only, ordered by tick. Seed + DB divergence + event log fully reconstruct any state.

## Testing
- Unit-test the resolver with hand-written action lists and asserted post-states — no LLM needed.
- Property test: shuffle submission order within a faction's allowed slot and assert resolution order normalises it.
- Golden tests: a fixed seed + fixed action log must produce a fixed state hash.

## References
- `docs/game-design/03-actions.md` (the action set + resolution order)
- `docs/specs/agent-io-schema.md`, `docs/specs/balance-config.md`
- Cross-skill: `agent-sovereign`, `spring-ai-agent`.
