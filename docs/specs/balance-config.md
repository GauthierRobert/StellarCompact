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
    currency: ENERGY            # PhysicalResource a market order price is denominated in (E1-07)
  construction:
    buildTimes: { mine:.., shipyard:.., terraformerStep:.. }   # terraformerStep = ticks per terraform biome step (E1-08)
    costs: { ... }
    terraformChain: { toxic: arid, arid: terran, ... }          # biome -> next biome "toward habitable" (E1-08); a biome absent from the map is habitable / un-terraformable
  combat:
    tierMultipliers: { scout:.., corvette:.., cruiser:.., capital:.. }
    varianceBand: [lo, hi]
    defensePlatformBonus: ..
    occupationLoyaltyPenalty: ..
    warExhaustionPerLoss: ..
  tech:
    costs: { ... }
    times: { ... }
    multipliers: { ... }
    prereqs: { techId: [prerequisiteTechId, ...], ... }   # tech DAG edges (E1-08); a node absent / empty is a root. Research is gated until every prereq is UNLOCKED (TECH_PREREQ_MISSING)
    unlocks: { techId: [capabilityKey, ...], ... }        # what a tech gates once UNLOCKED: ship-spec or building configKeys (E1-08). A capability not named by any tech is ungated
  diplomacy:
    reputation:
      gainHonourTreaty: ..
      penaltyBreakTreaty: (weight × remainingDuration)
      penaltyUnprovokedWar: ..
      espionageDetectedPenalty: ..
    treatyEnforcement: { ... }
  victory:
    domination: { systemPct: .. }
    economic:   { influenceTarget: .., orTopForTicks: .. }
    diplomatic: { allianceMajorityPct: .. }
    survival:   { tickLimit: .. }
    wonder:     { stages: .., holdTicks: .. }
    scoreWeights: { systems:.., influence:.., economy:.., tech:.., reputation:.., military:.., centrality:.. }
  tick:
    intervalMs: ..            # small galaxy ~ seconds; large ~ minutes
    negotiationRounds: ..
    phaseTimeoutMs: ..
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
```

### Espionage resolution (E1-13)
- Each `Espionage(target, operationType)` runs in resolution step 2, drawing one seeded generator keyed by `gameSeed ⊕ tick ⊕ opId` (`opId` = pure fn of actor/target/operation/submission-order, in `SaltDomain.ESPIONAGE`). From it: a **success** roll then a **detection** roll — deterministic per `(seed, tick, opId)`.
- Effective success = `clamp(successBase[op] − counterIntelSuccessPenalty?, 0, 1)`; effective detection = `clamp(detectionBase[op] + counterIntelDetectionBonus?, 0, 1)`, where the `?` term applies only when the **target** has `counterIntelTech` UNLOCKED.
- On **success**: `SCOUT` records the actor in the target faction's `revealedIntel`; `STEAL_INTEL` copies one UNLOCKED tech the actor lacks (lowest tech-id), else transfers `stealResourceFraction` of the target stockpile; `SABOTAGE` flips the first ACTIVE building in the target's territory to IDLE; `INCITE_UNREST` drops the first owned colony's population/loyalty.
- On **detection** (independent of success): the actor's reputation drops by `diplomacy.reputation.espionageDetectedPenalty`.
- Cost is escrowed through the tick-wide ledger **win or lose**; nothing debits a stockpile directly.

## Rules
- Two named profiles to ship: `small-default` and `large-persistent`.
- The engine reads only from the active profile; no literal gameplay constants in code.
- Profiles are versioned and stored with the match for reproducibility.
