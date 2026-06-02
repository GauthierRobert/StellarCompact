# Always-Follow Rules — Core Principles

These apply to every change in this repo, regardless of language or module.

1. **Determinism is sacred.** The engine module is pure: no I/O, no Spring, no wall-clock, no unseeded randomness. Seed RNG from gameSeed ⊕ tick ⊕ localSalt. Same seed + same action log ⇒ identical resolution.
2. **Engine authority.** Frontend and agents are untrusted. All rules and authoritative state live server-side. Agents propose; the engine disposes.
3. **Scale discipline.** Never load or iterate the full star catalog on the client or in the simulation. Detail is always a function of zoom; the simulation touches only active systems.
4. **Provider neutrality.** All LLM calls go through Spring AI's ChatClient. No vendor/model/endpoint hardcoded; route by config.
5. **Closed agent I/O.** Agent output is validated against the sealed Action schema. Invalid ⇒ one re-prompt with reason ⇒ then Hold/drop.
6. **Numbers live in config.** Every gameplay constant is in a balance profile, never hardcoded.
7. **Specs before code.** If a contract is unclear or changing, edit `docs/specs/` first, then implement to match.
8. **Read the skill first.** Before writing code in an area, read the matching `.claude/skills/*` — it encodes constraints that are easy to get wrong.
