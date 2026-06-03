# 4-Agent Live-Server Match — Simulation Findings & New Possibilities

> Source run: the **live application stack**, not a unit test. Spring Boot app
> (`app-0.1.0-SNAPSHOT`, JDK 25 `--enable-preview`) on `:8080` backed by
> PostgreSQL 16 (Flyway-migrated), Angular 21 dev server on `:4200` (WebGL galaxy
> renderer confirmed drawing). Match created via `POST /api/games`
> `{factionCount:4, seed:48043857, profile:"small-default"}`, started via
> `/start`, observed live through `/state`, `/events`, `/leaderboard` from tick 0
> to **tick ~874** (background loop at 200 ms cadence ≈ 5 ticks/s).
>
> This run exercises the **live REST/STOMP path** (`InMemoryMatchService` +
> `MatchBootstrap`), which is a *different* code path from the orchestrator test
> harness that validated the E10 epic (`GalaxyMatchScenario` /
> `ThreeAgentHourMatchTest`). The E10 fixes are real and hold in the test harness;
> this document is about what the **shipped server actually does** when you drive
> it like a player would.

## What happened (raw result)

| metric | value at tick ~874 |
|--------|--------------------|
| status | `RUNNING` (no victory — never concludes) |
| public events all match | **0** (no wars, treaties, trades, captures, battles, colonisations) |
| leaderboard | **4-way exact tie every single tick** (identical scores to 12 sig-figs) |
| faction-1 energy | 5000 → **1513** (monotonic decline, heading to deficit) |
| faction-1 minerals | 5000 → **8829** (slow unbounded hoard) |
| faction-1 food | 5000 → **4126** (slow decline) |
| faction-1 tech | 5000 → 5000 (**no research, ever**) |
| faction-1 influence | 100 → **49.0** (asymptote, identical for all four) |
| faction-1 systems owned | **1** (`home-1`) start → end |
| faction-1 planet slots | 3/3 filled by ~tick 40, then **idle forever** |
| `neutral-1` colonisable | **false** — the neutral system has **zero planets** |

All four factions are byte-for-byte mirror images of each other. The match is, in
effect, **four identical games of single-system solitaire running side by side in
total isolation.**

## Findings (verified against `small-default.json`, `MatchBootstrap`, the live API)

### L1 — The live bootstrap galaxy is four disconnected 2-system pockets
`MatchBootstrap` wires `home-k ↔ neutral-k` and **nothing else** (`laneNetwork`
adds one lane per faction; `adjacency` mirrors it). Faction *k* can perceive and
reach only its own home and its own neutral. `faction-1`'s WorldView lists exactly
one neighbour (`neutral-1`) and **never sees another faction** all match. So every
inter-faction subsystem — diplomacy, war, trade, espionage, blockade, interception
— is **structurally unreachable on the live server.** The zero-events result isn't
a bot timidity problem; the factions physically cannot touch.

### L2 — Neutral systems have no planets, so colonisation is impossible
The E10-01 fix taught `ScriptedSovereign` to colonise a reachable neutral once the
frontier is explored. But `MatchBootstrap` builds each `neutral-k` with
`List.of()` planets. The live WorldView confirms it:
`"colonisablePlanets":[] → "colonisable":false`. There is **nothing to colonise**,
so the fixed bot correctly falls through to Hold. Expansion — the only route to a
second system, and therefore to any victory — is closed at the data layer.

### L3 — No victory condition can ever fire (the live match cannot end)
`victory.active = DOMINATION` at `systemPct 0.6`. The galaxy has 8 systems (4 home
+ 4 neutral); 60% = **5 systems**, but a faction can own at most **1** (it can't
colonise, can't conquer an unreachable rival). The fallback `economic` condition is
"reach `influenceTarget 700` **or** be top-rank for `40` ticks" — but influence
asymptotes at `perCapitalSystem/decayRate = 1.0/0.02 = 50` (we observed 49.0,
~14× short of 700), **and** the perfect 4-way tie means *nobody is ever uniquely
top-rank*, so the `orTopForTicks` clause can't latch either. `survival.tickLimit
300` is configured but inactive. **Every live match is an unkillable `RUNNING`
loop.** (This is L-version of the 3-agent doc's F5, but now baked into the shipped
server rather than a test scenario.)

### L4 — Perfect symmetry erases all competition
Because `MatchBootstrap` gives every faction an *identical* home (one 3-slot Terran
planet, identical `RICH` stockpiles of 5000/5000/5000/5000) in an *identical*
isolated pocket, and `ScriptedSovereign` is deterministic, all four factions make
identical decisions and hold identical state forever. The leaderboard is a 4-way
tie at every tick. There is no seed-driven variety (contrast the 3-agent test's
1-vs-7 home-planet swing), no asymmetry, no reason for any agent to behave
differently from any other. A spectator sees four copies of the same screen.

### L5 — The single-planet economy is in structural deficit (slow brownout)
Energy falls 5000 → 1513 over ~874 ticks and is still dropping ~3–4/tick net. The
home biome plus 3 building slots cannot cover upkeep: even with the E10-02 ladder
preferring `SOLAR_ARRAY` when energy is low, **3 slots is too few** to run mines
*and* keep energy/food positive. Minerals meanwhile climb unbounded (8829 and
rising) with nothing to spend them on (no colonisation, no ships, no shipyard —
`hasShipyard:false`). Tech never moves (no `RESEARCH_LAB` built, no research
actions). The E10-04 deficit brownout will eventually bite once energy hits 0, but
the run is so slow it never gets there in a watchable window. Net: the live economy
trends toward stagnation, not collapse and not growth.

### L6 — The live server only ever seats `ScriptedSovereign` (no variety, no LLM, no aggression)
`InMemoryMatchService.create()` hard-codes `seats.add(new ScriptedSovereign(fid))`
for every seat. The `AggressiveScriptedSovereign` built in E10-03 to exercise
combat/diplomacy/victory is **not reachable through the API**, and neither is the
Spring AI `LlmSovereign`. There is no request field to choose an agent type or mix.
So even if the galaxy *were* connected (L1), the default bots wouldn't start a war.

### L7 — Operational gaps that blocked first boot (now fixed locally)
The app had **never been booted as a whole** before this run; three things blocked
it, none caught by the module tests:
1. **`TileCache` had two constructors and neither was `@Autowired`** → Spring
   `BeanInstantiationException: No default constructor found`. *Fixed* by marking
   the 2-arg production constructor `@Autowired` (the 3-arg is test-only). This is a
   genuine wiring bug, invisible to api tests that call the constructor directly.
2. **No dev proxy** — the frontend calls relative `/api` and `/ws`, but `ng serve`
   had no `proxyConfig`, so every call would hit `:4200` and 404. *Fixed* by adding
   `frontend/proxy.conf.json` (→ `:8080`, with `ws:true`) and wiring it in
   `angular.json`.
3. **`@stomp/stompjs` declared but not installed**; `--enable-preview` required at
   runtime (Java 25 preview class files). Documented for the run scripts.

## New possibilities & decisions I would make (prioritised)

The findings split cleanly: **the engine and the agents are fine; the live
*scenario* is a placeholder that starves them.** `MatchBootstrap` even says so in
its own Javadoc ("A later card can swap this for the galaxy-generated promotion
path"). So the highest-leverage work is to make the live server instantiate the
*real* game the engine already knows how to resolve. I'd open an **E11 epic
("Make the live match a real game")**.

### P1 — Replace `MatchBootstrap` with the real galaxy generator + home placement
Wire the live `create()` path to `GalaxyGenerator` + `HomePlacementGenerator` +
the promotion boundary (E2-01..05) instead of the hand-built 8-system stub.
**Decision:** one connected active region, homes placed with the fairness guard
(`qualityToleranceFraction 0.35`), neutrals that actually *have planets*, and a
lane graph that connects rival territories within a few hops. This single change
unlocks L1 (factions can meet), L2 (colonisation has targets), L3 (domination
becomes reachable), and L4 (seed-driven asymmetry returns).
*Candidate card: **E11-01** — galaxy-generated live bootstrap behind the
`MatchService` seam.*

### P2 — Make matches end: an active, reachable victory + a hard tick cap
**Decision:** for the small profile, add a `survival`/timeout fallback that is
*active* (e.g. "highest score at `tickLimit` wins") so no match can run forever,
and break score ties deterministically (by faction-id, or by a tiny seeded jitter)
so `orTopForTicks` can latch. Re-check the DOMINATION threshold against the real
system count from P1.
*Candidate cards: **E11-02** timeout-victory + tie-break; **E11-03**
victory-threshold reconcile against the generated map.*

### P3 — Let the API choose who sits in each seat
Extend `CreateGameRequest` with a `seats` array: `SCRIPTED`, `AGGRESSIVE`, or `LLM`
(provider by config — Ollama default, principle 4). **Decision:** default a 4-agent
match to a *mix* (e.g. 2 scripted + 2 aggressive) so combat and diplomacy actually
fire out of the box, and expose the existing `AggressiveScriptedSovereign` and
`LlmSovereign` that are already built but unreachable (L6).
*Candidate card: **E11-04** — per-seat agent-type selection in the match API
(🔒 security sign-off: validate/whitelist the enum, never instantiate arbitrary
classes from request input).*

### P4 — Give minerals a sink and the economy a growth path
The unbounded mineral hoard (L5, and F3 from the 3-agent run) means production has
no purpose. **Decision:** make the default bot spend: build a `SHIPYARD` →
construct colony/military ships → colonise/contest. Pair with a slightly larger
starting planet-slot count (or a second home planet) so a single system isn't
permanently energy-negative. Add a mineral-denominated cost somewhere the bot will
actually reach (ships, monuments) to close the loop.
*Candidate cards: **E11-05** scripted-bot ship/colony economy; **E11-06**
balance pass on slot count vs. upkeep for the small profile.*

### P5 — Auto-start a demo match so the spectator UI is alive on first load
Today the frontend loads to "Awaiting faction data…" because no match is wired to
it. **Decision:** a `stellar-compact.demo.autostart=true` profile that creates and
starts one match on boot and the spectator view defaults to it — so `npm start` +
`java -jar` gives an immediately-watchable game. Also surface a match-picker /
"new match" control in the HUD.
*Candidate cards: **E11-07** demo-match autostart; **E11-08** match-picker + create
controls in the spectator HUD.*

### P6 — Close the operational gaps so "run the app" is one command
**Decision:** check in a `docker-compose.yml` (Postgres) + a `make run` / npm script
that sets `JAVA_HOME`, `--enable-preview`, the datasource env, and starts both
tiers; document it in the root README. Add a thin **full-context Spring smoke test**
(`@SpringBootTest` that boots the whole app context against Testcontainers Postgres)
so wiring bugs like the `TileCache` constructor (L7-1) fail in CI, not on a human's
first boot.
*Candidate cards: **E11-09** one-command local run + compose; **E11-10**
app-context smoke test in CI.*

### P7 — Deeper, more interesting agents (the project's whole point)
Once the live game is real, the differentiator is agent behaviour. **Decisions I'd
explore:** (a) give `ScriptedSovereign` a tiny memory so it pursues multi-tick
plans (scout → colonise → fortify) instead of re-deciding from scratch; (b) wire the
LLM Sovereign's persona/goals/constraints from the faction-config screens (E7-05)
into the live prompt so a human's configured Sovereign actually plays; (c) add an
opening-diplomacy phase so the first contact between factions produces a treaty or a
declaration, not silence; (d) emit richer public events (colony founded, first
contact, tech unlocked) so the spectator feed and the galaxy overlay have something
to show.
*Candidate epic: **E12 — agent depth & spectacle.***

## One-line summary

The full stack **runs** (engine, persistence, REST/STOMP, WebGL client all live),
but the **live match scenario is a symmetric, disconnected placeholder** that
starves every interesting subsystem: four identical isolated factions, zero events,
no possible victory. The engine is ready for a real game — the next move is to feed
it one (E11), then make the agents worth watching (E12).
