# Game Design 04 — Diplomacy, Treaties & Reputation

Diplomacy is the counterweight to force. This system is what makes a coalition able to defeat a stronger but friendless warlord, and what gives "talk" real stakes.

## 1. The negotiation channel

During each tick's **Negotiation phase**, Sovereigns exchange free-form `SendMessage` text and structured proposals. Free text has **no mechanical force** — it is persuasion, threat, flattery, deception. Only a structured `ProposeTrade`/`ProposeTreaty` that is **accepted** binds anyone. This separation is deliberate: it lets agents lie, bluff and posture (which is good drama and good strategy) while keeping the engine's enforcement strictly on signed agreements.

Messages are delivered into the recipient's next WorldView, so negotiation spans ticks naturally — offers, counteroffers, and ultimatums play out over the real-time stream.

## 2. Treaty types and what the engine enforces

| Treaty | Engine-enforced effect | Typical terms |
|---|---|---|
| **Ceasefire** | Suspends war; `Attack` between parties illegal for duration | duration, optional reparations |
| **NonAggression** | `Attack`/`DeclareWar` between parties illegal until broken | duration |
| **TradePact** | Enables cross-faction routes at preferential terms; price floors/ceilings between parties | resource scope, volume, pricing |
| **DefensivePact** | If a signatory is attacked, others *may* (and are reputationally expected to) join its defence | trigger conditions |
| **Alliance** | Full: NonAggression + shared vision in border systems + allied routes + pooled votes for diplomatic victory | membership, victory-sharing rule |
| **Vassalage** | Vassal pays recurring Tribute and cannot declare war independently; suzerain pledges protection | dues, duration, autonomy level |

Enforcement means the engine **refuses** illegal actions (you cannot `Attack` a NonAggression partner) and **applies** automatic effects (allied vision, route protection, vote pooling). To act against a treaty you must first `BreakTreaty` — a legal but punished, public act.

## 3. Reputation — the public ledger

Every faction has a **public Reputation** score, visible to all Sovereigns in their WorldView. It is the memory of behaviour:

**Raises reputation:** honouring treaties to term, completing trades as agreed, defending pact partners when triggered, sustained peaceful commerce, declining to exploit a weakened neighbour.

**Lowers reputation:** breaking treaties (penalty scales with treaty weight × remaining duration), unprovoked war declarations, reneging on accepted deals (the engine's escrow prevents outright theft, but bad-faith withdrawals and ultimatums still cost), detected espionage, abandoning a pact partner under attack.

### Why reputation has teeth

Reputation is not flavour — it has **mechanical consequences**:
- **Trade terms:** high-reputation factions are offered better prices and more route partners; low-reputation factions find the commercial web closing against them (other agents, reading the low score in their WorldView, rationally refuse to deal).
- **Treaty trust:** agents weight the credibility of a proposal by the proposer's reputation; a serial treaty-breaker struggles to form the alliances needed for late-game defence or a diplomatic victory.
- **Coalition formation:** a low-reputation aggressor becomes a natural Schelling point for everyone else to ally against.

This is how a warlord can win every battle and still lose the game: economic isolation and a hostile coalition. **Force and diplomacy are genuinely co-equal.**

## 4. Alliances and shared victory

An **Alliance** can pursue a **shared diplomatic victory** (see 07): if an alliance collectively controls a qualifying majority of the galaxy's influence/territory, its members win together, with spoils split by the alliance's victory-sharing rule agreed at formation. This makes alliance-building a path to winning, not merely surviving — and makes the *terms* of an alliance (who gets credit) a negotiation in itself, ripe for late betrayal.

## 5. Diplomacy under fog of war

Sovereigns negotiate with imperfect information. You may not know a rival's true strength, stockpiles, or secret treaties. `Espionage(StealIntel)` can pierce this; bluffing exploits it. Public events (wars, broken treaties, big battles) are the shared common knowledge that anchors everyone's reasoning, which is exactly why betrayal is always announced.

## 6. Agent guidance (non-binding, persona-shaped)

A human configures a Sovereign with constraints like "never break a treaty" or "honour all defensive pacts" or "expand by trade, not conquest". These shape but do not hard-limit behaviour beyond what the engine enforces — a "treacherous" persona *may* break treaties (and eat the reputation cost); a "principled" one will refuse to. This is where personality produces emergent politics.
