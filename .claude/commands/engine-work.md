# /engine-work — Start work on the rules engine

Before any engine change:
1. Read `.claude/skills/game-engine-determinism` and the relevant `docs/game-design/*`.
2. Confirm: pure module, seeded RNG, fixed resolution order, sealed Action exhaustiveness, immutable records, validate-before-resolve, numbers from config.
3. Write/adjust golden tests first.
4. Delegate to `game-engine-developer`; route balance questions to `game-balance-designer`.
