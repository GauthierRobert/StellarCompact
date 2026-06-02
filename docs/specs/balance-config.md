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
      gainHonourTreaty: ..                                 # awarded for honouring a treaty to term (on expiry)
      penaltyBreakTreaty: ..                               # E1-12: coefficient; BreakTreaty penalty = penaltyBreakTreaty × treatyEnforcement[type] × remainingDurationTicks
      penaltyUnprovokedWar: ..                             # E1-12: flat reputation hit on declaring a NEW war (idempotent re-declare is not re-penalised)
      espionageDetectedPenalty: ..
    treatyEnforcement: { ceasefire:.., nonAggression:.., tradePact:.., defensivePact:.., alliance:.., vassalage:.. }   # E1-12: per-treaty-type weight (camelCase keys = TreatyType.configKey()); used as the BreakTreaty penalty weight
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
```

## Rules
- Two named profiles to ship: `small-default` and `large-persistent`.
- The engine reads only from the active profile; no literal gameplay constants in code.
- Profiles are versioned and stored with the match for reproducibility.
