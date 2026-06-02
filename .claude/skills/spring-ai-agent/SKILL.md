---
name: spring-ai-agent
description: Use when integrating Spring AI 2.0.0-M8 to drive Sovereign agents — building the ChatClient, assembling the system prompt (persona+goals+constraints+rules+schema), structured output coercion to the closed Action set, validation, the single re-prompt on rejection, and provider-pluggable model routing (Ollama default, OpenAI/others by config). Read BEFORE writing agent-runtime code.
---

# Spring AI Agent Runtime

`agent-runtime` turns a WorldView into a validated `AgentResponse` using Spring AI 2.0.0-M8. Provider is configuration, never code.

## Provider neutrality (non-negotiable)
- All model calls go through a Spring AI `ChatClient`. Default provider is **local Ollama**; OpenAI/others are swappable by Spring profile/properties.
- Never hardcode a vendor, model name, or endpoint in logic. Route by `modelTier` (e.g. free→Ollama, premium→hosted) via config.

## Prompt assembly
```
system = persona + goals + hardConstraints
       + compact rules summary
       + strict output JSON schema (closed Action set)
       + ONE worked example
user   = serialized WorldView (compact, fog-filtered)
```

## Output handling
1. Call model via ChatClient.
2. Use Spring AI **structured output / converters** to coerce to `AgentResponse` (messages[] + actions[]).
3. **Always validate after parse** against engine rules — never trust the model's self-report.
4. On rejection: **one** re-prompt including the specific reason (e.g. `TREATY_FORBIDS`, `INSUFFICIENT_RESOURCES`).
5. Still invalid / timed out → drop that action (faction may `Hold`).

## Robustness
- Defensive parse: strip markdown fences, tolerate trailing prose, reject ambiguous output → re-prompt.
- Cap message length; cap action count per tick.
- Keep prompts small so local models stay fast and on-task.

## Cost/scale
- Inference is the cost centre. Tier routing (Ollama vs hosted) is purely config. Virtual threads make many concurrent calls cheap.

## References
- `docs/specs/agent-io-schema.md` (schema + rejection reasons)
- `docs/architecture/03-agent-runtime.md`
- Cross-skill: `agent-sovereign`, `game-engine-determinism`.
