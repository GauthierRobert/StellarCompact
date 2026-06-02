# AGENTS.md

Cross-harness entry point (Codex, Cursor, OpenCode, Gemini, etc.). The canonical project context lives in **`CLAUDE.md`** — read it first, then this file's quick rules.

## Quick rules for any agent

- **Stack is fixed:** Angular 21 (signals, zoneless, WebGL2) + Spring Boot 4 / Java 25 + Spring AI 2.0.0-M8 + Ollama-default-but-pluggable LLM. Do not substitute frameworks.
- **No code in this drop.** This repository is currently specs + skeleton only. Produce code only when explicitly asked, and only after reading the matching skill in `.claude/skills/`.
- **Determinism & engine authority are sacred.** See `CLAUDE.md` → "Non-negotiable principles".
- **Billion-star rule:** the client never loads the full catalog. Detail is a function of zoom. See `docs/architecture/02-galaxy-scale.md`.

## Where things are

- **Implementation board (start here to build): `BOARD.md`** — ordered cards with deps, the skill/spec to read first, the subagent to delegate to, and a Definition of Done. `BOARD-INDEX.md` is the quick-scan card list.
- Game rules: `docs/game-design/`
- Architecture (incl. scaling): `docs/architecture/`
- Specs, schemas, API contracts: `docs/specs/`
- Skills (deep procedural knowledge): `.claude/skills/`
- Subagents: `.claude/agents/`
- HTML proof of concept: `poc/`
