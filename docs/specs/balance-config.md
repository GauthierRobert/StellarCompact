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
```

## Rules
- Two named profiles to ship: `small-default` and `large-persistent`.
- The engine reads only from the active profile; no literal gameplay constants in code.
- Profiles are versioned and stored with the match for reproducibility.
