# Game Design 05 — Conflict & Military

Combat is **deterministic given the tick seed**, with **hidden information**, so the interesting decision is never "do the math" — it's "is this fight worth the diplomatic and economic price, given what I can't see?"

## 1. Fleets and ships

A **fleet** is a stack of ships under one faction, located at a system or travelling a lane. Ships have a **tier** (gated by tech) and contribute:
- **Attack** and **Defense** strength,
- **Speed** (affects travel and interception),
- **Upkeep** (Energy + Food per tick).

Ship specs are config-defined; a few archetypes (starting set): Scout (cheap, fast, weak, reveals), Corvette (cheap line ship), Cruiser (mainline), Capital (expensive, tech-gated, dominant), Freighter (no combat value; hauls trade — the thing raids target).

## 2. The combat model

When fleets engage (attacker vs defender, or fleet vs defended system):

```
attackerPower = Σ(ship.attack × tierMult) × techMult × stanceMod
defenderPower = Σ(ship.defense × tierMult) × techMult × terrainMod × defensePlatformBonus
roll          = seededRandom(gameSeed, tick, battleId)   // bounded variance band
result        = compare(attackerPower × roll, defenderPower × (1 - roll'))
```

- **Seeded variance** means a stronger fleet usually wins but not always — overwhelming force is safe, marginal force is a gamble. This rewards scouting (reduce uncertainty) and discourages reckless coin-flip attacks.
- **Losses are proportional**: the loser loses more ships, the winner takes attrition too. Pyrrhic victories are real — winning a war can leave you too weak to hold the peace, which a watching third party may exploit.
- **System assault**: defeating the garrison of a defended system lets the attacker **capture** it — ownership and surviving buildings transfer. Population may be reduced in the assault (and resents the conqueror — see unrest).

## 3. Hidden information in battle

A Sovereign sees its own fleets exactly but estimates enemy strength only as well as its intel allows (fog of war + last-known + espionage). So attack decisions are made under uncertainty: the defender may have a hidden capital fleet, a defense platform you didn't scout, or an ally who'll answer a defensive pact. This is the core tension that keeps combat strategic rather than arithmetic.

## 4. Interception and movement combat

Fleets moving along a lane can be **intercepted** by a hostile fleet positioned to contest that lane, forcing a battle mid-transit. This makes **lane control** strategic: holding a chokepoint lets you tax or block enemy movement, and makes routes defensible or vulnerable. Escorting freighters along a contested route is a real task.

## 5. Sub-war pressure (the grey zone)

Not all conflict is open war. Against a rival you're **contesting** (or at war with) you can:
- **Blockade** a route or system — throttle their economy without firing on planets.
- **Raid** a route — steal a shipment (seeded), harassing income.
- **Espionage/Sabotage** — damage buildings, steal tech, incite unrest.

These let a Sovereign apply pressure below the reputation cost of a full war declaration — a deniable, escalatory toolkit. Reading whether a rival is "just" raiding or building toward invasion is part of the diplomatic game.

## 6. Unrest and occupation cost

A freshly **captured** system has reduced loyalty: its population is unhappy, production is depressed, and it is vulnerable to `Espionage(IncitUnrest)` and to liberation by the former owner or its allies. Holding conquered territory therefore has an ongoing cost — conquest is not free real estate. This is a deliberate brake on snowballing: the more you grab by force, the more garrison and pacification you must spend, opening windows for coalitions.

## 7. War termination

Wars end by:
- **Ceasefire/Peace treaty** (negotiated; may include reparations or ceded systems),
- **Capitulation** (one side accepts vassalage or cedes its capital),
- **Exhaustion** (attrition makes continuing irrational — agents should recognise this).

The engine tracks **war exhaustion** (cumulative losses/upkeep strain) and surfaces it in the WorldView so Sovereigns can reason about when to sue for peace. A war with no end condition just bleeds both parties for a third to harvest.

## 8. Design intent recap

Every military system has a built-in cost or risk — variance, attrition, occupation unrest, reputation, exhaustion — so that **force is powerful but never strictly dominant**. The strongest fleet in the galaxy still has to worry about who it can trade with, who will gang up on it, and whether the war it's winning is bankrupting it. That is the 50/50 balance with diplomacy made concrete.
