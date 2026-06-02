# Game Design 07 — Victory, Scoring & Match Lifecycle

## 1. Victory conditions (one selected per match)

A match is configured with **one** primary victory condition. All are reachable by a mix of the systems above, but each rewards a different style, so the chosen condition shapes how Sovereigns should play.

| Condition | Win when… | Rewards style |
|---|---|---|
| **Domination** | you (or your alliance) control ≥ X% of habitable systems | military + expansion |
| **Economic / Prestige** | you reach Y total Influence (or hold the top Influence for N consecutive ticks) | trade + building + diplomacy |
| **Diplomatic** | an alliance you lead controls a qualifying majority of galaxy influence/territory | coalition-building |
| **Survival / Last Standing** | you are the last faction with a capital after the tick limit, or all rivals are vassalised/eliminated | endurance + opportunism |
| **Wonder** | you complete a galaxy Wonder (a very expensive multi-stage Monument project) and hold it for N ticks | long-game prestige + defence |

Shared (alliance) conditions split the win by the alliance's pre-agreed victory-sharing rule — which is itself a negotiated term and a classic late-game betrayal trigger.

## 2. Scoring (for ranking, tournaments, and non-win outcomes)

Even without a clean victory (e.g. tick-limit reached), every Sovereign gets a **score** so matches always produce a ranking. Score combines, with config weights: systems controlled, total Influence, economic output, tech depth, reputation, military strength, and diplomatic centrality (how many treaties/routes it anchors). This feeds tournament brackets and the small→large progression gating.

## 3. Elimination, vassalage, and staying in the game

- A faction that loses its **capital** and all colonies is **eliminated** (its Sovereign becomes a spectator for the rest of the match).
- A faction may instead **capitulate into vassalage** to avoid elimination — staying alive, paying dues, and waiting for a chance to break free. This keeps "losing" players meaningfully in the story rather than abruptly out.
- Eliminated-but-allied players still share in an alliance victory if their alliance wins — incentivising genuine teamwork over self-preservation.

## 4. Match lifecycle

```
CREATED → LOBBY → RUNNING → (PAUSED ↔ RUNNING) → CONCLUDED → ARCHIVED
```

- **CREATED**: a galaxy is seeded with parameters (size, faction count, tick interval, victory condition, balance config).
- **LOBBY**: Sovereigns join; humans attach configured agents (persona/goals/constraints). Home systems are placed (seeded, balanced).
- **RUNNING**: the tick loop drives the four phases in real time; the live event stream feeds spectators.
- **PAUSED**: the loop halts; state frozen; resumable. (Useful for spectating, debugging, or scheduled persistent galaxies.)
- **CONCLUDED**: a victory condition fired or the tick limit hit; final scores computed; result recorded (feeds rankings & progression).
- **ARCHIVED**: state + full event log retained for replay and analysis.

## 5. Replay & spectator value

Because the engine is **deterministic** and emits a complete **event log**, any concluded match can be **replayed exactly** from `gameSeed` + the recorded agent action stream. This is the backbone of:
- **Spectator mode / tournaments** (the "AI as sport" angle): watch alliances form and betrayals land.
- **Debugging & balancing**: reproduce any situation precisely.
- **Agent improvement**: study why a Sovereign made a call.

## 6. The progression loop (small → large)

1. New human configures a Sovereign; runs **small galaxies** (free, fast, ephemeral).
2. Completing small galaxies earns **standing / a seat** (and a score).
3. With a seat, the Sovereign enters a **large persistent galaxy** — the long campaign, carrying only identity/reputation, never material advantage.
4. Large galaxies feed seasonal **tournaments** and the public spectator experience.

This is both the gameplay arc and the monetisation/scale story (small = cheap funnel, large = the expensive, high-value, spectacle tier). See `docs/architecture` for how the largest galaxies are made technically possible.

> All thresholds (X%, Y influence, N ticks, score weights) are balance config, externalised and tunable per match.
