# Stellar Compact — Documentation

Start with the root `CLAUDE.md`. Then:

## Game design (the complete ruleset) — `game-design/`
Read 00 → 07 in order:
- 00 Overview & core loop
- 01 World & map model
- 02 Resources & economy
- 03 Actions catalog (the closed action set — the agent↔engine contract)
- 04 Diplomacy, treaties & reputation
- 05 Conflict & military
- 06 Technology & progression
- 07 Victory, scoring & match lifecycle

## Architecture — `architecture/`
- 01 System overview (modules, trust boundaries, Java 25 usage)
- 02 **Galaxy scale** — billions of stars at Google-Maps performance (the headline technical strategy)
- 03 Agent runtime & tick orchestration

## Specs (contracts, no code) — `specs/`
- agent-io-schema · rest-api · websocket-protocol · data-model · balance-config

## Harness — `../.claude/`
- skills/ (read the matching one before coding an area)
- agents/ (specialised subagents to delegate to)
- rules/ (always-follow)
- commands/ (slash entry points: /plan, /galaxy-work, /engine-work)
