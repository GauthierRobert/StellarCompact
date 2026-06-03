# Game Design 08 — Balance-Coherence Notes (X-02 recurring pass)

> **Status:** review + emergent-behaviour notes. This is the recurring X-02 coherence
> pass over the full E1–E9 ruleset. It is a living document: re-run and amend it whenever
> a balance constant or a resolver changes.
>
> **Scope of authority.** This pass guards the 50/50 force↔diplomacy balance and overall
> coherence. It proposes config/spec edits only; it does **not** touch engine code.
> Implementation gaps it finds are handed to `game-engine-developer`.
>
> **Config changes applied this pass: NONE.** The two shipped profiles
> (`backend/engine/src/main/resources/balance/small-default.json`,
> `large-persistent.json`) are internally coherent. Every concrete concern below is
> either (a) an *engine implementation gap* — a config knob that exists and validates but
> is not yet read by any resolver, which is a code task not a tuning task — or (b) a
> *debatable* tuning point flagged for a future pass rather than applied, to avoid
> perturbing the golden state-hash determinism tests. See §6 for the explicit list.

---

## 1. Method

Read all of `docs/game-design/00–07`, `docs/specs/balance-config.md`, both shipped
profiles, and every resolver that consumes a constant (economy, combat, interdiction,
diplomacy, espionage, influence, market, scoring, victory, progression, season). Each
verdict below is grounded in how the constant is *actually used* in code, not only how
the spec describes it.

Key arithmetic identity used repeatedly: Influence is an exponentially-decayed stock, so
a faction holding a steady per-tick accrual `A` against `decayRate d` converges to a
ceiling of **`A / d`** (geometric series). This is the single most important lever for
judging economic/prestige victory feasibility.

---

## 2. Per-pillar viability verdict

| Pillar | Path to win / influence on outcome | Verdict | Notes |
|---|---|---|---|
| **Economy** | Out-produce, out-trade; bankroll military or prestige; feeds `economy`+`influence` score weights | **Viable** | Production is `base(biome)×popFactor×techMult` per active building; upkeep + deficit attrition (0.10/0.07) make overextension self-correcting. Cutting a rival's routes is a real strangulation lever (blockade scales Influence + market throughput). |
| **Diplomacy** | DIPLOMATIC victory (alliance ≥ majority %); ECONOMIC via influence; alliance vote-pooling; coalition vs. warlord | **Viable but soft** | Alliance grouping (transitive closure of ACTIVE alliances) is correctly aggregated for DOMINATION/DIPLOMATIC. **Gap:** reputation has no *mechanical* market teeth in-engine (see §3.1) — diplomacy's bite currently depends on agent reasoning, not an engine penalty. |
| **Force** | DOMINATION (≥60%/65% systems); SURVIVAL; capture transfers territory | **Viable, correctly costed** | Every military edge carries a built-in cost: seeded variance band, two-sided attrition (`lossFractionWinner` 0.25/0.30), occupation loyalty drop, reputation hit on unprovoked war, deficit attrition from fleet upkeep. Force is strong but not strictly dominant. ✔ matches 05 §8 intent. |
| **Espionage** | Sub-war pressure: steal tech/resources, sabotage, incite unrest; counter-intel defence | **Viable as support, not a solo win** | Correct: espionage is a harassment/equaliser layer, no victory condition keys off it. Costs escrowed win-or-lose; detection costs reputation; counter-intel tech meaningfully shifts odds. |
| **Influence/Prestige** | ECONOMIC (influence target) and WONDER conditions; large score weight (2.0) | **Viable; gated correctly** | Accrual sources (capitals, monuments, trade volume, treaties) all wired. Decay forces *sustained* behaviour, not a one-time spike. Targets are reachable only by a committed economy/diplomacy build (see §4). |

**Bottom line: no single pillar dominates.** The force↔diplomacy 50/50 holds *in the
config*. The one place it is weaker than the design doc promises is that reputation's
"trade terms close against a warlord" effect (04 §3) is, in the current engine, an
*agent-behavioural* expectation rather than an engine-enforced one. That is an engine
gap, not a number to tune — flagged in §3.1 and §6.

---

## 3. Coherence findings

### 3.1 Reputation teeth are partly behavioural, not mechanical (ENGINE GAP — not config)

`docs/game-design/04 §3` promises reputation has **mechanical** consequences: better
trade prices, more route partners, coalition formation. In the engine today:

- Reputation is **written** (break-treaty penalty, unprovoked-war penalty,
  espionage-detection penalty) and **read** only by `Scoring` (weight 1.0) and the
  SURVIVAL tie-break leader selection.
- `MarketResolution` contains **no reference to reputation** (confirmed by grep). A
  serial treaty-breaker is *not* mechanically offered worse market fills.

This is internally consistent (nothing is contradictory), but it means reputation's
"teeth" currently bite only through (a) the score weight and (b) whatever the LLM agents
choose to do with the public score. For an all-AI game that is *partially* fine — agents
can read reputation in their WorldView and refuse to deal — but the design intent is that
the engine itself applies a floor of consequence so that even a careless agent pays.

- **Verdict:** acceptable for now; reputation is not *toothless* (war/break penalties are
  large and visible, and feed score). But it is the weakest link in the 50/50 promise.
- **Recommendation (engine, not config):** hand `game-engine-developer` a future card to
  let reputation modulate market matching (e.g. a price-improvement/penalty band keyed off
  the counterparty's reputation) and/or treaty-acceptance gating. Until then, the
  agent-prompt layer must surface reputation prominently so agents enforce it socially.
- **No config change** — there is no existing knob for this; inventing one would require
  engine support first (spec-before-code, rule 7).

### 3.2 `gainHonourTreaty` validated but never awarded (ENGINE GAP — not config)

`diplomacy.reputation.gainHonourTreaty` (5.0 in both profiles) is required `> 0` by the
loader and documented as "awarded for honouring a treaty **to term** (on expiry)". No
resolver applies it: `DiplomacyResolution` only ever flips treaties to `EXPIRED` on a
**decline** (no reward — correct) and there is no treaty-expiry sweep that detects an
ACTIVE treaty reaching `expiresTick` and credits the honour reward.

- **Consequence for balance:** the *only* reputation movements that actually fire are
  **negative** (break, war, espionage). Reputation can therefore only ratchet downward in
  the current engine, which undercuts the "sustained peaceful commerce raises reputation"
  half of 04 §3 and makes a recovered reputation impossible. This is a real coherence hole
  in the reputation economy, but it is a **missing resolver**, not a wrong number.
- **Recommendation (engine):** add a treaty-expiry honour sweep in the EVENTS or
  DIPLOMATIC step that, when an ACTIVE treaty's `expiresTick <= tick`, flips it to EXPIRED
  and credits each signatory `gainHonourTreaty` (optionally ×`treatyEnforcement[type]` for
  symmetry with the break penalty). Hand to `game-engine-developer`.
- **No config change.**

### 3.3 `warExhaustionPerLoss` validated but not accrued (KNOWN-DEFERRED — not config)

`combat.warExhaustionPerLoss` (1.0) is validated `> 0` and the `BalanceProfile.Combat`
doc explicitly says "later cards surface it for war termination". `CombatResolution`
applies losses but does not accumulate exhaustion onto any faction field (no exhaustion
field exists on `Faction` yet). This is a **planned deferral**, not an imbalance: the
WorldView-surfaced war-exhaustion signal of 05 §7 is a future card. Recorded here so the
next pass re-checks it once the field lands. **No config change.**

### 3.4 Break-treaty penalty is unbounded by remaining duration (COHERENT, but watch)

`breakTreaty` penalty = `penaltyBreakTreaty (0.5) × treatyEnforcement[type] × remainingDurationTicks`.
For an open-ended treaty `expiresTick = Long.MAX_VALUE`, so `remaining` would be
astronomically large — **but** in practice treaties are minted with a finite `duration`
or this term explodes. With an explicit duration this is well-behaved and intentionally
makes breaking a *long, important* treaty (alliance weight 3.0, long remaining term) very
costly — exactly the 04 §3 "scales with treaty weight × remaining duration" design.

- **Coherence note:** if an agent signs an **open-ended** Alliance (`duration` omitted →
  `Long.MAX_VALUE`), breaking it produces a numerically enormous (effectively infinite)
  reputation loss. That is arguably *correct* (betraying a permanent alliance should be
  ruinous) but it is a sharp cliff with no config cap. There is no `maxBreakPenalty` knob.
- **Verdict:** internally consistent; flag for design discussion only. A future
  refinement could cap remaining-duration at a configurable horizon so open-ended and
  very-long treaties don't produce unbounded penalties. **No config change this pass**
  (would require a new knob + loader support = engine work).

### 3.5 Tech gating is reachable and the DAG is acyclic ✔

Verified the `prereqs` DAG in both profiles: roots are the four economy/statecraft/
corvette/diplo nodes; the longest military chain is `corvetteDoctrine → cruiserDoctrine →
capitalDoctrine` (small-default cost 40+80+160 = 280 Tech, times 5+8+14 = 27 ticks;
large 60+120+240 = 420, times 12+20+36 = 68). With frozen-world labs yielding 4–5 Tech
× popFactor × research-lab count, capital ships are a genuine mid/late investment, not an
opening move. `terraformingI` correctly gates behind `improvedExtraction`;
`monumentalWorks` behind `marketNetworks`; `intelligenceAgency` behind `diplomaticCorps`.
No orphan unlock (every `unlocks` key is a real building/ship spec). **Coherent.**

### 3.6 Economy does not run away or stall ✔

- **Runaway check:** production scales with population (`1 + pop×productionPerPop`), and
  population is capped (`baseCap + Σ capByBuilding`), so per-planet output has a ceiling.
  Tech multipliers are multiplicative but bounded (max ~1.5 per resource line). No
  unbounded compounding loop exists. ✔
- **Stall check:** starter stockpile (120/120/80/20/0) covers several early buildings
  (mine 30, farm 25 minerals). Oceanic home (food 6.0 × pop) plus a farm seeds positive
  food, so population grows and bootstraps production. A faction that over-builds upkeep
  hits deficit attrition (self-correcting), not a hard stall. ✔
- **Upkeep vs. yield:** e.g. a mine costs 1 energy upkeep and yields `4–5 minerals ×
  popFactor`; a research lab costs 2–3 energy for `4–5 tech × popFactor`. Energy must be
  generated (solar/desert/gas) to sustain the build economy — a deliberate, coherent
  tension that makes desert/gas worlds strategically valuable. ✔

### 3.7 Influence accrual is neither trivial nor unreachable ✔ (see §4 for the math)

### 3.8 Progression carry & season weights are sane ✔

- `small-default`: SMALL, `seatThresholdScore 0` (open funnel), `reputationCarryWeight 0`
  (clean start). Correct for the free on-ramp.
- `large-persistent`: LARGE, `seatThresholdScore 60`, `winGrantsSeat true`,
  `reputationCarryWeight 0.5`, `minMatchesForSeat 3`. A seat demands either a win or a
  ≥60-score body of work across ≥3 matches — a real but clearable bar. Carry is
  identity+half-reputation only; `starterStockpile` is identical to small, so **no
  material advantage crosses the boundary** (07 §6 / 06 §5 honoured). ✔
- Season aggregate `1.0×Σscore + 25×wins + 2×matchesPlayed`: winBonus (25) is meaningful
  relative to a typical single-match score in the tens, without swamping the score sum
  over a multi-match season. Participation (2) is a small anti-zero floor. Sane. ✔

---

## 4. Quantitative feasibility of the influence/prestige targets

Using the `A/d` ceiling identity:

**small-default** (`decayRate 0.02`, so ceiling = 50×A; `perCapitalSystem 1.0`,
`perMonument 3.0`, `perActiveTreaty 0.5`, `perTradeVolume 0.5`):
- A mid-game faction with ~5 systems, 1 monument, 2 treaties, ~10 units of route volume:
  `A ≈ 5 + 3 + 1 + 5 = 14/tick` → ceiling ≈ **700**.
- ECONOMIC target is **1000** → requires a *dedicated* prestige build (multiple monuments,
  large trade web, more treaties). Reachable but not incidental. **Note:** small-default's
  *active* condition is DOMINATION, so 1000 is the informational/alternative target, not
  the win line — appropriate.
- DOMINATION at 60% of systems on a 4-faction small map is the primary, and is achievable
  by a committed military/expansion run within the 300-tick survival fallback. Coherent.

**large-persistent** (`decayRate 0.01`, ceiling = 100×A; `perMonument 4.0`):
- ECONOMIC target **10000** → needs sustained `A ≈ 100/tick`: a deep empire (e.g. ~20+
  systems, several monuments, a broad trade web). Deliberately a long-campaign prestige
  win, not a sprint. The *active* condition is SURVIVAL (`tickLimit 5000`), so 10000 is
  again the alternative path. **Coherent — appropriately hard.**
- WONDER: 5 active monuments. At 250 minerals + 80 tech and 30 ticks each, that is a
  major, defensible late-game project — correct prestige pacing.

**Verdict:** influence targets are *reachable by the intended specialist build and not by
accident*. The decay rates correctly prevent a one-tick spike from clinching a win.

---

## 5. Emergent-behaviour predictions

1. **Coalition-vs-warlord is the intended Schelling dynamic, but relies on agents.**
   With reputation lacking engine-side market teeth (§3.1), whether a low-reputation
   aggressor actually gets economically isolated depends on the agents *choosing* to
   refuse trade. Strong agents will; weak local models may not. **Risk:** against weak
   models, pure-rush may over-perform because the diplomatic punishment is not engine-
   enforced. *This is the single biggest threat to the 50/50 balance in practice* and the
   reason §3.1 is the top engine recommendation.

2. **Reputation can only fall (until §3.2 is fixed).** Until the honour-on-expiry reward
   lands, every match's reputations drift downward as wars/breaks accumulate. Over a
   *persistent* large campaign with 0.5 carry weight, this means carried reputation
   trends negative for everyone — a mild but real long-run distortion. Fixing §3.2 closes
   this.

3. **Turtle/defensive play is mildly favoured in fleet-vs-fleet** because defensive
   stance modifiers (DEFENSIVE def 1.3/1.35) plus terrain (1.25/1.3) plus defense platform
   (1.5/1.6) stack on the defender in a *system assault*, while attacker only gets stance
   (AGGRESSIVE 1.3/1.35). This is **intended** (assaulting a fortified system should be
   expensive) and is balanced by the attacker choosing *when/where* and by occupation
   cost on the defender's lost systems. Watch that it doesn't make all conquest
   uneconomical on the large profile, where the multipliers are slightly higher.

4. **Blockade is a strong, cheap economic weapon.** `blockadeThroughputFactor` 0.75/0.85
   removes most of a route's throughput *and* the matching Influence, with no resource
   cost to the blockader beyond fleet upkeep and positioning. Expect agents to favour
   blockade over open assault as the efficient sub-war pressure tool — which is exactly
   the 05 §5 grey-zone design. Coherent; no change.

5. **Espionage `inciteUnrest` synergises with conquest.** Incite unrest (pop/loyalty
   loss) softens a target before assault, and a freshly captured low-loyalty system is
   itself extra-vulnerable to incite unrest (occupation brake). Expect espionage+force
   combos. Intended and self-limiting (detection costs reputation). Coherent.

6. **Capital ships are a tempo gamble.** 280–420 Tech + ~27–68 ticks to reach
   `capitalDoctrine`, plus high upkeep (5–6 energy, 4–5 food per capital). A faction that
   over-invests in capitals while neglecting energy/food generation will hit deficit
   attrition and *lose* those expensive ships without a fight — a clean, coherent
   self-correction that punishes naive rush. Good.

---

## 6. Prioritised action list

### Hand to `game-engine-developer` (code, not config):

1. **[High] Reputation market teeth (§3.1).** Spec then implement reputation modulating
   market matching and/or treaty-acceptance, so the 50/50 force↔diplomacy promise is
   engine-enforced rather than agent-dependent. *Spec-before-code: amend
   `docs/specs/balance-config.md` market block with the new knob before coding.*
2. **[High] Treaty-honour-on-expiry reward (§3.2).** Add the expiry sweep that credits
   `gainHonourTreaty` so reputation can recover and peaceful play is rewarded; otherwise
   reputation is a one-way ratchet down.
3. **[Medium] War-exhaustion accrual (§3.3).** Already planned ("later cards"); accrue
   `warExhaustionPerLoss` onto a `Faction` exhaustion field and surface it in WorldView
   for war-termination reasoning (05 §7).

### Config / spec (future passes — FLAGGED, NOT applied this pass):

4. **[Low] Break-penalty cap for open-ended treaties (§3.4).** Consider a configurable
   `maxBreakPenaltyHorizonTicks` (or capping `remaining` at the treaty's nominal max) so
   an omitted-duration alliance does not yield an unbounded reputation loss. Requires a
   new knob + loader support → engine work; **flagged only**, not applied.
5. **[Low / watch] Large-profile defender stacking (§5.3).** If large-match play shows
   conquest is uneconomical, a small reduction to `terrainDefenseMod` (1.3) or
   `defensePlatformBonus` (1.6) on `large-persistent` would rebalance. **Not applied** —
   this would change shipped combat constants and **risk the golden state-hash tests**;
   defer until empirical match data justifies it.

### Config changes applied this pass: **NONE.**

No shipped constant in `small-default.json` or `large-persistent.json` was modified.
Every concrete issue is either an engine implementation gap (items 1–3) or a debatable
tuning point deliberately flagged rather than applied to protect the golden-hash
determinism baseline (items 4–5). The profiles are coherent as shipped.

---

## 7. Re-run checklist for the next X-02 pass

- [ ] Did §3.1 (reputation market teeth) land? If so, re-verify the 50/50 balance under
      weak-model agents.
- [ ] Did §3.2 (honour-on-expiry) land? If so, re-balance `gainHonourTreaty` magnitude
      against accumulated negative penalties so net reputation drift is ~neutral for
      peaceful play.
- [ ] Did §3.3 (war exhaustion) land? Tune `warExhaustionPerLoss` against the
      surfacing threshold once a `Faction` exhaustion field exists.
- [ ] Re-confirm no new resolver introduced a hardcoded constant (rule 6).
- [ ] Re-run the `A/d` influence-ceiling check if any `influence.*`, `decayRate`, or
      `economic.influenceTarget` changed.
- [ ] Re-confirm the E10-05 starting-economy floor (§8) still leaves the bulge region able
      to seat `factionCount` homes after any change to biome weights, planet sizing, or
      `homePlacement.*`.
- [ ] (E11, §9) If `MatchBootstrap` ever changes how many neutrals it materialises (currently
      up to `factionCount`), re-derive the small-default DOMINATION `systemPct` against the new
      active-system count so 0.6 stays reachable-but-not-trivial (and still fires the golden
      replay only post-conquest).
- [ ] (E11, §9) If `oceanic.energy` or `homePlacement.homeBiome` changes, re-check that a
      starting home stays Energy-sustainable (a cradle solar array must out-produce marginal
      producer upkeep) without becoming an Energy runaway.

---

## 8. E10-05 — Fairness floor (F4) + economic-victory reconciliation (F6)

Applied in card E10-05 (config + galaxy generator + spec), closing the two fairness/
reachability findings of `docs/game-design/09-three-agent-sim-findings.md`. Unlike the
NONE-applied passes above, this pass **does** change shipped numbers — but only additive,
fairness-restoring ones (the home-placement floor is inert by default; the small-default
economic retune lowers an unreachable target to a documented one). The galaxy home
placement is procedural and is **not** part of the engine's golden tick-hash baseline, so
the floor does not perturb the determinism fixtures.

### 8.1 F4 — Starting-economy floor on home placement

**Finding.** The sim dealt one faction a 1-planet home and another a 7-planet home — a 7×
economic gap from the seed alone. `HomePlacementGenerator` guarded only the *relative*
spread (`qualityToleranceFraction = 0.35`, homes within 35% neighbourhood quality); it had
no *absolute* floor, so an entire band of low-but-similar-quality homes could be dealt, and
the scenario that bypassed the generator showed how decisive a starved home is.

**Design.** Two new, config-driven, deterministic floors are layered on the candidate set
*before* quality scoring (so a starved home is neither a band anchor nor a band member):

- `homePlacement.minHomePlanetCount` — the home system's own planet count must be ≥ this.
  Directly kills the F4 1-planet home. Set **2** (small-default) / **3** (large-persistent).
- `homePlacement.minHomeBiomeYield` — the home system's *aggregate base biome yield* (sum
  over its planets of base yields across all four physical resources) must be ≥ this. Catches
  the multi-planet-but-near-barren home (e.g. an oceanic cradle ringed by toxic rubble that
  yields almost nothing). Set **6.0** (small-default) / **9.0** (large-persistent).

**Why these numbers.** An oceanic cradle alone yields FOOD 3 (procedural baseline). A 2-planet
small-default home clearing `minHomeBiomeYield = 6.0` must carry the cradle *plus* a second
body contributing ≥ 3 more yield — i.e. a genuinely workable starting economy, not a lone
food world. Large-persistent is a bigger region with far more cradle candidates, so it can
afford a stricter 3-planet / 9.0-yield floor without starving the 8-home draw. Both floors
are **inert at their defaults** (`minHomePlanetCount = 1`, `minHomeBiomeYield = 0.0`): a
profile that omits them, and the pre-E10-05 5-arg `HomePlacementConfig`/`HomePlacement`
constructors, select exactly as before. The relative guard (`qualityToleranceFraction`) is
unchanged and still applies to the survivors — the floor bounds the *absolute* floor, the
tolerance bounds the *spread*. Both work together; neither replaces the other.

**Coherence check.** The floor can only *shrink* the candidate pool, so it cannot relax
separation or tolerance. If too few cradles clear the floor to seat `factionCount` separated,
balanced homes, placement **fails deterministically** (same clear, repeatable message) rather
than dealing a starved start — the same "fail, don't cram" contract E2-04 already honoured,
now extended to "fail, don't starve". Determinism is preserved: every floor check is a pure
function of `(gameSeed, systemId)` via `SystemGenerator`.

### 8.2 F6 — Economic-victory target reconciled to a documented build

**Finding.** `economic.influenceTarget = 1000` (small-default) against a single-capital
asymptote of `perCapitalSystem / decayRate = 1.0 / 0.02 = 50` — ~20× out of reach for a
do-nothing 1-capital faction; the sim saw all three converge to ~49.

**Decision: retune small-default to a documented build's ceiling (option a), keep large
ambitious-but-documented (option b).** Using the `A/d` ceiling identity from §4:

- **small-default `influenceTarget`: 1000 → 700.** Documented reachable path: a committed
  prestige faction with ~5 owned systems (`5×1.0`), 2 active Monuments (`2×3.0`), ~6 units of
  owned active-route trade volume (`6×0.5`) and ~2 active treaties (`2×0.5`) accrues
  `A ≈ 15/tick`, ceiling `15/0.02 = 750 ≥ 700`. A 1-capital faction (ceiling 50) still cannot
  reach it — ECONOMIC stays the *specialist/expansion* win, not the idle baseline — but a
  competent expansion+monument+trade agent now has a target it can actually walk. small-default's
  *active* condition is DOMINATION, so 700 is the informational/alternative line; the retune
  makes it internally coherent rather than decorative.
- **large-persistent `influenceTarget`: 10000 (unchanged).** With `decayRate = 0.01` the ceiling
  is `100×A`; 10000 needs sustained `A ≈ 100/tick` — a deep late empire (~20+ systems, several
  Monuments at `perMonument 4.0`, a broad trade web). Deliberately a long-campaign prestige win;
  the *active* condition is SURVIVAL, so 10000 is again the alternative path. Left unchanged to
  avoid perturbing the large-profile baseline and because it is already documented-reachable.

**Net effect on the 50/50 balance.** Neither change advantages force over diplomacy: the floor
makes *every* faction's economic start fairer (helping the diplomacy/economy pillars that a
starved faction could never pursue), and the F6 retune makes the prestige/economic win line
honest without making it a sprint. Force still pays its built-in costs (variance, attrition,
occupation unrest, reputation) documented in §2.

---

## 9. E11 — Victory reachability (L3) + home sustainability (L5) on the generated live map

Applied in cards **E11-03** (victory-threshold reconcile) and **E11-06** (planet-slot/upkeep
sustainability), closing findings L3/L5 of `docs/game-design/10-four-agent-live-sim-findings.md`.
Both findings were artefacts of the **pre-E11-01 placeholder bootstrap** (disconnected 2-system
pockets, planet-less neutrals, a single 3-slot home), not of the constants. E11-01 replaced that
stub with the real galaxy generator; this pass re-derives the affected numbers against the
*generated* map and changes exactly one shipped constant (`oceanic.energy`).

### 9.1 L3 — Victory thresholds reconciled (E11-03): **no number changed; reachability documented**

**Finding.** On the old 8-system stub a faction could own **at most 1** system (planet-less
neutrals = nothing to colonise; disconnected pockets = nothing to conquer), so DOMINATION
`systemPct 0.6` (needs ⌈0.6 × 8⌉ = 5) and `economic.influenceTarget 700` (1-capital `A/d`
ceiling = `1.0/0.02 = 50`) were unreachable and every match ran forever.

**Re-derivation against the generated small map.** `small-default` `factionCount 4` ⇒
`MatchBootstrap.build` materialises **4 homes + up to 4 neutrals = 8 active systems**, one
*connected* region, neutrals carrying real planet rosters. A faction can now own **1..8** systems
via colonisation (4 colonisable neutrals) and conquest (rivals reachable). So:

- **DOMINATION `systemPct 0.6` → KEPT.** Needs **5 of 8** (62.5%): reachable (sweep the neutrals
  / conquer a rival / pool an alliance) but demanding real dominance over three rivals — the
  intended "reachable-but-not-trivial". **0.5 was rejected:** ⌈0.5 × 8⌉ = 4 is double the fair
  share of 2 ("biggest of four", not domination), and — decisively — a two-faction alliance owns
  4 systems *at alliance-formation, before any conquest*, so 0.5 would let a coalition win on the
  opening tick. 0.6 is also what the golden replay and `AggressiveMatchTest` are tuned against:
  alpha+gamma reach 4 on alliance-formation but only clear 5 *after* capturing the beta systems,
  so the scenario still exercises combat/capture/elimination before the win fires (lowering the
  threshold would conclude those tests prematurely and break them).
- **`economic.influenceTarget 700` → KEPT.** The E10-05 documented build (≈5 systems + 2
  Monuments + trade + treaties → `A ≈ 15`, ceiling `750 ≥ 700`) is now *attainable* because "≈5
  owned systems" is a real colonisation/conquest target on the generated map rather than the old
  hard cap of 1. Alternative line (`active = DOMINATION`); still unreachable for an idle 1-capital
  faction (ceiling 50) — correct.
- **`large-persistent` → KEPT.** 16 systems; DOMINATION 0.65 ⇒ ⌈10.4⌉ = 11 of 16 (long-campaign,
  and the *alternative* path under `active = SURVIVAL`); `economic 10000` is the E10-05 deep-empire
  build. Coherent on the bigger generated map; unchanged.

**Why no number moved.** The L3 unreachability was a *map* defect (L1/L2), fixed by E11-01. The
guaranteed terminator is the **E11-02 active hard timeout** (`hardTickLimit 500 / 8000`): default
scripted bots colonise but never war, so four scripted seats each end ~2 systems and nobody hits
5 — the timeout then concludes with the deterministic `Scoring.rank` leader. DOMINATION/ECONOMIC
are reachable *aspirations* for aggressive/LLM seats; the timeout is the floor that makes every
match end. This is internally coherent and needs no threshold change — only the documentation that
the generator (not a new constant) closed L3.

### 9.2 L5 — Home sustainability (E11-06): **`oceanic.energy` 0.0 → 1.0 (both profiles)**

**Finding.** A starting home bled Energy monotonically (5000 → 1513 over ~874 ticks, trending to
deficit). Root cause: the mandated cradle biome `oceanic` had `energy = 0.0`, and a `SOLAR_ARRAY`
produces `biome.energy × popFactor`, so a solar array on the *guaranteed* (oceanic) home planet
produced **nothing**. The bot's E10-02 "SOLAR_ARRAY-when-low" heuristic therefore could not fix
the deficit — every mine/farm/lab (1–2 Energy/tick upkeep) drained Energy with no in-home source.

**Fix (least-invasive, config-only).** `oceanic.energy: 0.0 → 1.0` in **both** profiles (parallel;
oceanic food stays 6.0 small / 8.0 large). Because `homePlacement.homeBiome = oceanic`, *every*
home now has a dependable Energy source: a cradle solar array yields `1.0 × popFactor` (≈1.5 at the
food-rich cradle's typical population), covering several producers' upkeep. The energy-floor
heuristic now genuinely self-corrects, so the home stabilises around the floor with a balanced
mine/solar mix instead of bleeding out.

- **Sustainable, not runaway.** Per-planet Energy is bounded by `popFactor × slot count`; solar
  arrays compete with mines/farms for finite slots; Energy is not an Influence/victory resource —
  no snowball. The economy still pressures expansion (minerals hoard until the E11-05 shipyard
  sink; more systems = more slots) and the **E10-04 brownout intent is preserved** (over-mine and
  neglect solar ⇒ still brown out; the home just now has a viable *in-home* recovery path).
- **Coherence.** Mine taper (F3) and brownout (F2) untouched. The E10-05 `minHomeBiomeYield` is the
  *aggregate* base yield across all four resources, so the oceanic cradle's contribution rises
  6.0 → 7.0: the floor gets marginally *easier* to clear (more homes qualify), never harder, and
  the relative `qualityToleranceFraction` guard is unchanged.

### 9.3 Test/golden-hash impact (for the orchestrator's suite run)

- **Engine tests that load the shipped `small-default.json` use no `OCEANIC` planet for a
  production assertion** (`EconomyResolutionTest` exercises TERRAN/ARID/VOLCANIC/FROZEN/DESERT;
  `ReplayScenario` uses ARID/TERRAN/TOXIC). So the `oceanic.energy` change perturbs **no** golden
  economy value or replay hash. `BalanceProfileTest`/`ProgressionConfigTest` assert ranges and the
  `large > small` influence/tick relations only — all still hold.
- **No victory threshold moved**, so `GoldenReplayDeterminismTest` (DOMINATION fires post-conquest)
  and `AggressiveMatchTest` (DOMINATION at 60% after total capture) are unaffected.
- `VictoryEvaluationTest` builds its own per-test profiles (not the shipped JSON); a new
  `dominationReachableOnEightSystemGeneratedSmallMap` case documents the 5-of-8 reconciliation. It
  asserts at-and-below the 0.6 boundary on an 8-system snapshot — no existing case changed.

### 9.4 Net effect on the 50/50 balance

Neither change favours force over diplomacy. The Energy fix makes *every* faction's home
sustainable (it helps the economy/diplomacy pillars a starved home could never pursue, exactly as
the E10-05 floor did), and the victory reconciliation leaves all win lines exactly where E10-05
documented them — now genuinely reachable because the map, not the number, was the blocker. Force
still pays its built-in costs (variance, two-sided attrition, occupation unrest, reputation,
exhaustion) per §2.
