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
  construction:
    buildTimes: { mine:.., shipyard:.., terraformerStep:.. }
    costs: { ... }
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
```

## Rules
- Two named profiles to ship: `small-default` and `large-persistent`.
- The engine reads only from the active profile; no literal gameplay constants in code.
- Profiles are versioned and stored with the match for reproducibility.
