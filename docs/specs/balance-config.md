# Spec — Balance Config (externalised tunables)

Every number in the game-design docs lives here, never hardcoded. Loaded per match as a named "balance profile" so matches are tunable and reproducible.

## Shape (conceptual)

```
balanceProfile:
  resources:
    biomeYields: { oceanic:{food:..}, volcanic:{minerals:..,energy:..}, ... }
    upkeep: { buildingX:{energy:..}, shipTierY:{energy:..,food:..} }
    deficitAttritionRate: ..
  population:
    growthPerFoodSurplus: ..
    capByBuilding: { ... }
  market:
    matchPolicy: priceTimePriority
    routeInfluencePerVolume: ..
    currency: ENERGY                  # PhysicalResource a market order price is denominated in (E1-07)
    blockadeThroughputFactor: ..      # E1-11: fraction (0..1) of a route's throughput a blockade removes (1.0 = fully choked); INTERDICTION step flips the route to BLOCKADED, economy/Influence scale volume by this
    raidStealFraction: ..             # E1-11: MAX fraction (0..1) of a route's per-cycle cargo a raid steals; actual = fraction × seeded roll in [0,1) from gameSeed⊕tick⊕SaltDomain.RAID.salt(routeId); split across hauled resources, debited owner / credited raider via the ledger
  construction:
    buildTimes: { mine:.., shipyard:.., terraformerStep:.. }   # terraformerStep = ticks per terraform biome step (E1-08)
    costs: { ... }
    terraformChain: { toxic: arid, arid: terran, ... }          # biome -> next biome "toward habitable" (E1-08); a biome absent from the map is habitable / un-terraformable
  combat:                                  # combat power model + capture (E1-10; game-design 05 §2,§6)
    tierMultipliers: { scout:.., corvette:.., cruiser:.., capital:.. }  # per-spec tier/tech multiplier; absent spec = 1.0
    shipAttack:  { scout:.., corvette:.., cruiser:.., capital:.., freighter:0 }   # per-spec base attack; absent spec = 0
    shipDefense: { scout:.., corvette:.., cruiser:.., capital:.., freighter:.. }  # per-spec base defence; absent spec = 0
    stanceAttackMod:  { AGGRESSIVE:.., BALANCED:1.0, DEFENSIVE:.., EVASIVE:.. }    # per FleetStance attack mult; absent = 1.0
    stanceDefenseMod: { AGGRESSIVE:.., BALANCED:1.0, DEFENSIVE:.., EVASIVE:.. }    # per FleetStance defence mult; absent = 1.0
    terrainDefenseMod: ..                    # flat defender mult for a SYSTEM assault (home ground); 1.0 = none. Not applied fleet-vs-fleet
    varianceBand: [lo, hi]                   # seeded roll bounds; attacker×roll vs defender×(1-roll'), per (seed,tick,battleId)
    defensePlatformBonus: ..                 # defender mult when assaulted system has an active defensePlatform
    lossFractionWinner: ..                   # fraction (0..1) of the WINNER's ships destroyed (attrition — victory is not costless)
    lossFractionLoser: ..                    # fraction (0..1) of the LOSER's ships destroyed (loser loses more)
    occupationLoyaltyPenalty: ..             # loyalty lost (floored at 0) on a freshly captured system (unrest brake)
    warExhaustionPerLoss: ..                 # exhaustion accrued per ship lost
    # power model: attackerPower = Σ(shipAttack[spec] × tierMult[spec]) × stanceAttackMod[stance]
    #              defenderPower = Σ(shipDefense[spec] × tierMult[spec]) × stanceDefenseMod[stance] × terrainDefenseMod × (defensePlatformBonus if platform)  ← system assault only
    # battleId is derived purely from (participants, contested lane/system, tick); seed each fight from SaltDomain.COMBAT.salt(battleId)
    # interception battles (E1-09) resolve through the identical fleet-vs-fleet primitives, before the Attack slice
  movement:                       # fleet travel + lane interception tunables (E1-09)
    energyCostPerLaneTick: ..     # Energy charged per tick of lane travel; summed over the journey and escrowed at launch (0 = free)
    interceptionEnabled: ..       # master switch: a hostile at-war fleet holding a contested-lane endpoint forces a mid-transit battle (E1-09 trigger; E1-10 resolves)
                                  # NOTE: lane *lengths* (travel ticks) are map geometry, NOT here — they live on the lane graph (engine.map.LaneNetwork), derived from real distance at generation
  tech:
    costs: { ... }
    times: { ... }
    multipliers: { ... }
    prereqs: { techId: [prerequisiteTechId, ...], ... }   # tech DAG edges (E1-08); a node absent / empty is a root. Research is gated until every prereq is UNLOCKED (TECH_PREREQ_MISSING)
    unlocks: { techId: [capabilityKey, ...], ... }        # what a tech gates once UNLOCKED: ship-spec or building configKeys (E1-08). A capability not named by any tech is ungated
  diplomacy:
    reputation:
      gainHonourTreaty: ..                                 # awarded for honouring a treaty to term (on expiry)
      penaltyBreakTreaty: ..                               # E1-12: coefficient; BreakTreaty penalty = penaltyBreakTreaty × treatyEnforcement[type] × remainingDurationTicks
      penaltyUnprovokedWar: ..                             # E1-12: flat reputation hit on declaring a NEW war (idempotent re-declare is not re-penalised)
      espionageDetectedPenalty: ..
    treatyEnforcement: { ceasefire:.., nonAggression:.., tradePact:.., defensivePact:.., alliance:.., vassalage:.. }   # E1-12: per-treaty-type weight (camelCase keys = TreatyType.configKey()); used as the BreakTreaty penalty weight
  victory:                                  # E1-15 (game-design 07); ONE primary condition selected per match
    active: DOMINATION                       # the single primary condition checked each tick: DOMINATION|ECONOMIC|DIPLOMATIC|SURVIVAL|WONDER (default SURVIVAL when omitted)
    conditionEnabled: true                   # master switch for the active condition; false => no victory ever fires (sandbox). Default false (omitted block = inert, hash-stable)
    eliminationEnabled: true                 # master switch for capital-loss elimination & vassalage survival (game-design 07 §3); false => no faction eliminated. Default false
    domination: { systemPct: .. }            # win when faction/alliance controls >= this fraction (0,1] of habitable systems
    economic:   { influenceTarget: .., orTopForTicks: .. }   # win at >= influenceTarget Influence (orTopForTicks: the hold-top-N-ticks variant; the influence target is the snapshot-evaluable trigger). E10-05/F6: the target is the ceiling of a DOCUMENTED expansion+monument+trade build (Influence is an exponentially-decayed stock, so steady accrual A against decayRate d converges to A/d). It is deliberately UNREACHABLE for an idle 1-capital faction (whose A/d asymptotes at perCapitalSystem/decayRate) and reachable only by a committed prestige economy — see "Economic-victory reachability (E10-05/F6)" below for the per-profile A/d budget
    diplomatic: { allianceMajorityPct: .. }  # win when a multi-member ALLIANCE controls >= this fraction (0,1] of habitable systems (shared win)
    survival:   { tickLimit: .. }            # last faction with a capital wins; else the top-ranked survivor wins at tickLimit
    wonder:     { stages: .., holdTicks: .. } # win on completing >= stages ACTIVE Monuments (holdTicks: the hold-N-ticks variant; completing the stages is the snapshot-evaluable trigger)
    scoreWeights: { systems:.., influence:.., economy:.., tech:.., reputation:.., military:.., centrality:.. }   # E1-15 ranking weights (game-design 07 §2); every match produces a ranking even without a clean win
    # The active victory condition fires for an alliance group when it qualifies; an alliance win is SHARED by the whole group (each member emits VictoryAchieved). On a win the match transitions RUNNING -> CONCLUDED (LifecycleTransitions). Elimination emits FactionEliminated; a VASSALAGE junior party is shielded from elimination.
  tick:
    intervalMs: ..            # small galaxy ~ seconds; large ~ minutes
    negotiationRounds: ..
    phaseTimeoutMs: ..
  homePlacement:                          # seeded starting-position fairness (E2-04; game-design 01 §6)
    factionCount: ..                      # homes to place, one per faction (>= 1)
    minSeparationHops: ..                 # min lane hops between any two homes (>= 1); anti-cramping guarantee
    neighbourhoodHops: ..                 # hop radius K defining a home's local neighbourhood for the balance measure (>= 0)
    qualityToleranceFraction: ..          # max relative spread of neighbourhood quality across chosen homes, in [0,1]; smaller = stricter fairness
    homeBiome: oceanic                    # cradle biome a home system must carry (the colonised home world the faction starts on)
    minHomePlanetCount: ..                # E10-05 starting-economy floor (3-agent-sim F4): a candidate whose OWN system carries fewer than this many planets is rejected, so a starved 1-planet home can never be dealt. >= 1; 1 = inert (no floor). Layered ON TOP of the cradle + qualityToleranceFraction guards (a survivor still has to fall in the fairness band)
    minHomeBiomeYield: ..                 # E10-05 starting-economy floor (F4): a candidate whose OWN system's aggregate base biome yield (sum over its planets of the planet's base biome yields across all four resources) is below this is rejected, so a home that is multi-planet but near-barren (e.g. toxic rubble) is also excluded. >= 0; 0 = inert (no floor)
  influence:                                # E1-14: Influence accrual & decay (game-design 02 §1,§5,§7)
    perCapitalSystem: ..                     # flat Influence per owned (capital/home) system per tick (>= 0)
    perMonument: ..                          # flat Influence per active Monument building per tick (>= 0)
    perTradeVolume: ..                       # Influence per unit of an owned ACTIVE route's per-tick throughput (>= 0); a BLOCKADED route's volume is scaled by (1 - market.blockadeThroughputFactor); SUSPENDED earns nothing
    perActiveTreaty: ..                      # flat Influence per ACTIVE treaty the faction signs (honoured diplomacy) (>= 0)
    decayRate: ..                            # fraction [0,1] the post-accrual Influence stockpile bleeds each tick (0 = no decay)
    # Influence is political capital: NEVER hauled on a route nor matched on the market order book (it is granted via treaty terms only). The INFLUENCE step accrues/decays it directly on the faction stockpile, not via the SpendLedger.
  espionage:                                                   # E1-13: seeded covert ops (game-design 03 C, 06 §3)
    successBase: { scout:.., stealIntel:.., sabotage:.., inciteUnrest:.. }   # per-op base success probability [0,1]; an op absent = 0 (always fails)
    detectionBase: { scout:.., stealIntel:.., sabotage:.., inciteUnrest:.. } # per-op base detection probability [0,1]; an op absent = 0 (never detected)
    cost: { scout:{tech:..,influence:..}, ... }                # per-op cost (typically Tech/Influence), escrowed win-or-lose
    counterIntelTech: intelligenceAgency                       # TechId that, when UNLOCKED by the TARGET, confers counter-intel; blank disables the mechanic
    counterIntelSuccessPenalty: ..                             # subtracted from success odds when the target holds counterIntelTech (>= 0)
    counterIntelDetectionBonus: ..                             # added to detection odds when the target holds counterIntelTech (>= 0)
    stealResourceFraction: ..                                  # STEAL_INTEL fallback: fraction [0,1] of target stockpile transferred when no stealable tech exists
    unrestPopulationLoss: ..                                   # INCITE_UNREST: population removed from the struck colony (>= 0)
    unrestLoyaltyLoss: ..                                      # INCITE_UNREST: loyalty removed from the struck system [0,1], floored at 0
  progression:                              # E9-01 (game-design 07 §6, 06 §5): small->large gating + identity/reputation-ONLY carry-over
    sizeClass: SMALL                        # which tier this profile configures: SMALL (the free/fast funnel) | LARGE (the persistent gated campaign). Default SMALL when omitted
    seatThresholdScore: ..                  # min concluded-SMALL-match Scoring (E1-15) a faction must reach to be admitted a LARGE seat (>= 0); only gates entry INTO a LARGE galaxy. 0 = open seat
    winGrantsSeat: false                    # if true, WINNING (placing 1st) a small match earns a seat regardless of seatThresholdScore
    reputationCarryWeight: ..               # fraction [0,1] of prior-standing reputation that seeds the carried identity in the new match. 0 = clean (identity-only) start; the ONLY non-material quantity that crosses the boundary
    starterStockpile: { energy:.., minerals:.., food:.., tech:.., influence:.. }   # the fresh material loadout EVERY entrant of this tier starts with; carry-over RESETS material state to exactly this (no resources/tech/fleets ever carry)
  season:                                   # E9-03 (game-design 07 §2,§6): tournament/season aggregation -> leaderboard -> feeds the progression seat gate above
    matchScoreWeight: 1.0                    # weight on a Sovereign's SUMMED per-match Scoring (E1-15) across the season (>= 0). 1.0 = aggregate is the raw score sum
    winBonus: ..                             # flat bonus added per match WON (placed 1st) in the season (>= 0). 0 = scores-only
    participationBonus: ..                   # flat bonus per concluded match the Sovereign took part in (>= 0). 0 = no participation credit
    minMatchesForSeat: 1                     # min concluded matches a Sovereign must have played before its season aggregate may clear a LARGE seat (>= 1). raise to demand a campaign body of work
```

**Home placement (E2-04, + E10-05 starting-economy floor).** A pure function of `(gameSeed, lane graph, homePlacement config)` chooses one home system per faction such that (a) every pair of homes is at least `minSeparationHops` lane hops apart, and (b) the chosen homes' neighbourhood-quality scores — colonisable build capacity + habitable cradles + resource accessibility within `neighbourhoodHops` hops — all fall inside a band of width `qualityToleranceFraction × maxChosenQuality`, so no faction is gifted a runaway start. **E10-05 layers a starting-economy floor on the candidate set** (3-agent-sim F4: the sim dealt a 1-planet home against a 7-planet home — a 7× gap from the seed alone): before quality scoring, a cradle candidate is admitted only if its OWN system carries `>= minHomePlanetCount` planets AND its aggregate base biome yield (summed over its planets, across all four resources) is `>= minHomeBiomeYield`. A starved home is therefore filtered out of the pool, so it can be neither the high-quality anchor nor a band member — the floor bounds the *absolute* economic floor while `qualityToleranceFraction` continues to bound the *relative* spread. If the galaxy cannot satisfy the request (too few cradle candidates **clearing the floor**, or no separated+balanced set exists) placement fails deterministically rather than cramming factions together or dealing a starved start. The authoritative numbers live here; the framework-free `galaxy` module receives them via a mirror `HomePlacementConfig` (it cannot depend on the engine).

### Economic-victory reachability (E10-05 / 3-agent-sim F6)
The 3-agent sim observed all factions converging to Influence ≈ 49 against an `economic.influenceTarget` of 1000 — a ~20× gap, because a do-nothing 1-capital faction's Influence asymptotes at `perCapitalSystem / decayRate` (small-default: `1.0 / 0.02 = 50`). That is **by design**: ECONOMIC is the *prestige/specialist* win, not the idle baseline. The fix is to make the target the explicit **ceiling of a documented expansion+monument+trade build**, verified against the `A/d` identity (a steady per-tick accrual `A` against decay `d` converges to `A/d`).

- **small-default** retuned to `influenceTarget = 700` (was 1000), `orTopForTicks` unchanged (40). Documented path that reaches it: a committed prestige faction holding ~5 owned systems (`5 × 1.0 = 5`), 2 active Monuments (`2 × 3.0 = 6`), ~6 units of owned active-route trade volume (`6 × 0.5 = 3`) and ~2 active treaties (`2 × 0.5 = 1`) accrues `A ≈ 15/tick`, ceiling `15 / 0.02 = 750 ≥ 700`. So a single-capital faction cannot reach it (its ceiling is 50), but an expansion-plus-monuments-plus-trade specialist clears it with headroom. Note `active = DOMINATION` on small-default, so 700 is the informational/alternative line, not the primary win — but it is now internally coherent (a number a competent agent can actually walk).
- **large-persistent** keeps `influenceTarget = 10000` (`active = SURVIVAL`, so again the alternative path). With `decayRate = 0.01` the ceiling is `100 × A`; reaching 10000 needs sustained `A ≈ 100/tick` — a deep late-game empire (e.g. ~20+ systems, several Monuments at `perMonument 4.0`, a broad trade web). Deliberately a long-campaign prestige win, not a sprint; left ambitious-but-reachable per the documented build, unchanged to protect the large-profile golden baseline.

### Espionage resolution (E1-13)
- Each `Espionage(target, operationType)` runs in resolution step 2, drawing one seeded generator keyed by `gameSeed ⊕ tick ⊕ opId` (`opId` = pure fn of actor/target/operation/submission-order, in `SaltDomain.ESPIONAGE`). From it: a **success** roll then a **detection** roll — deterministic per `(seed, tick, opId)`.
- Effective success = `clamp(successBase[op] − counterIntelSuccessPenalty?, 0, 1)`; effective detection = `clamp(detectionBase[op] + counterIntelDetectionBonus?, 0, 1)`, where the `?` term applies only when the **target** has `counterIntelTech` UNLOCKED.
- On **success**: `SCOUT` records the actor in the target faction's `revealedIntel`; `STEAL_INTEL` copies one UNLOCKED tech the actor lacks (lowest tech-id), else transfers `stealResourceFraction` of the target stockpile; `SABOTAGE` flips the first ACTIVE building in the target's territory to IDLE; `INCITE_UNREST` drops the first owned colony's population/loyalty.
- On **detection** (independent of success): the actor's reputation drops by `diplomacy.reputation.espionageDetectedPenalty`.
- Cost is escrowed through the tick-wide ledger **win or lose**; nothing debits a stockpile directly.

### Small→large progression & carry-over (E9-01)
- The match tier is the profile's `progression.sizeClass`: a `small-default` profile is `SMALL` (the cheap/fast funnel, open entry); `large-persistent` is `LARGE` (the slow, persistent, gated campaign). Tick cadence already scales with the tier (`tick.intervalMs`: small ~seconds, large ~minutes); the progression block adds the gating and carry rules.
- **Standing.** A *concluded* match yields one `StandingRecord` per faction — a pure projection of the final snapshot + the `Scoring` ranking (E1-15): identity (faction id + name), final score, final reputation, 1-based placement, match size. It holds **no material state** by construction, so it physically cannot carry advantage forward.
- **Seat gating.** Completing a SMALL match earns standing. Entry into a LARGE galaxy is admitted iff the SMALL standing's `score >= progression.seatThresholdScore`, OR (`progression.winGrantsSeat` and the Sovereign placed 1st). A faction below the bar is denied a seat. SMALL (funnel) galaxies are always open entry — only the persistent campaign gates.
- **Identity/reputation-ONLY carry-over.** Crossing the boundary produces a `CarriedIdentity` = { faction id, name, `reputation × progression.reputationCarryWeight` }. Seeding it into the new match RESETS the faction's material state to the destination profile's `progression.starterStockpile` with an empty tech DAG (no resources, tech, fleets or territory carry). This is the enforcement of game-design 06 §5 / 07 §6: identity + standing persist, material advantage never does.
- All of this is pure engine logic in `engine.progression` (`ProgressionEvaluation`, `StandingRecord`, `CarriedIdentity`, `GalaxySizeClass`); persisting records between matches and constructing the next match's `GameState` from carried identities are orchestration concerns that call these pure functions.

### Tournament / season aggregation (E9-03)
- A **season** is a bracket/structure into which humans enter their Sovereigns: it aggregates the per-match `StandingRecord`s (the E9-01 standings, each carrying the E1-15 `Scoring`) of multiple **concluded** matches into a single season **leaderboard** — one `SeasonStanding` per Sovereign.
- **Aggregation.** For each Sovereign the season aggregate is `matchScoreWeight × Σ(matchScore) + winBonus × wins + participationBonus × matchesPlayed`; the fold also tracks `matchesPlayed`, `wins`, `bestPlacement` (lowest across the season) and `bestReputation` (max final reputation). The leaderboard is ordered by aggregate **score descending, then faction id ascending** — a total order, so ties break stably and the same standings (in any input order) always yield the same ranking (determinism).
- **Feeds the seat gate (no fork).** The season aggregate drives the SAME small→large gate as a single match: a `SeasonStanding` is projected back into a `StandingRecord` (aggregate→`score`, season rank→`placement`, best reputation→`reputation`) and handed to `ProgressionEvaluation.admits`. A Sovereign whose season aggregate clears `progression.seatThresholdScore` (or wins, when `progression.winGrantsSeat`) is admitted to a LARGE galaxy; one below is denied. An extra season guard requires `season.minMatchesForSeat` plays before the aggregate may clear a seat (a single lucky match cannot buy a campaign seat). Per-match scoring (E1-15) and seat gating (E9-01) are **reused, never re-implemented**.
- All of this is pure engine logic in `engine.season` (`Season`, `SeasonStanding`, `SeasonAggregation`); a `Season` is an immutable, append-only value object (`withMatch` returns a fresh season) and `leaderboard(profile)` derives the ranking on demand. Assembling a season as matches conclude, persisting it, and exposing a season-standings read are orchestration/api concerns that call these pure functions.

## Rules
- Two named profiles to ship: `small-default` and `large-persistent`.
- The engine reads only from the active profile; no literal gameplay constants in code.
- Profiles are versioned and stored with the match for reproducibility.
