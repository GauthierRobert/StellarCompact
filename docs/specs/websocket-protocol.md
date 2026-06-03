# Spec — WebSocket / STOMP Protocol (live tick stream)

STOMP over WebSocket. The live spine of spectating and per-Sovereign perception.
Implemented by board card **E6-04** (`com.stellarcompact.api.ws`).

## Handshake & identity

The STOMP endpoint is `/ws` (SockJS fallback enabled). The session identity is pinned
**at the HTTP handshake**, never per-frame, and is trusted thereafter:

```
ws://…/ws?principal={name}&gameId={gameId}
```

- `principal` → the session `Principal` (server-held; used for owner-view routing/authorization).
- `gameId`    → pinned into the session attributes; scopes ownership so faction-1 in game A
                ≠ faction-1 in game B.

This is the intentionally thin authentication stand-in. A later auth card replaces the
query-param read with a verified session token / OAuth principal at the same handshake
seam; the authorization decision below does not change.

## Topics (server → client)

```
/topic/games/{gameId}/ticks          TickEvent     { tick, status, startedAt }
/topic/games/{gameId}/events          PublicEventMessage { type, parties[], systemId?, tick }
                                        type ∈ { WarDeclared, TreatySigned, TreatyBroken, AllianceFormed,
                                                 SystemCaptured, BattleResolved, RouteEstablished,
                                                 RouteRaided, FactionEliminated, VictoryAchieved,
                                                 ColonyFounded, FirstContact, TechUnlocked }
/topic/games/{gameId}/overlay         OverlayDelta  { asOfTick, changedSystems[] }   (thin pointer; resync detail via REST)
/user/queue/faction/{factionId}/view  WorldView     (OWNER-ONLY; the Sovereign's fog-filtered perception each tick)
```

`startedAt` is a server wall-clock millis stamp for client latency display only — it is
**never** an engine input (determinism, principle 1).

## Messages (client → server)

```
SUBSCRIBE /topic/games/{gameId}/events            spectate public stream (anyone)
SUBSCRIBE /topic/games/{gameId}/ticks             spectate tick heartbeats (anyone)
SUBSCRIBE /topic/games/{gameId}/overlay           spectate overlay-change pointers (anyone)
SUBSCRIBE /user/queue/faction/{factionId}/view    owner-only; gated server-side (see Security)
SEND      /app/games/{gameId}/spectate            { minX, minY, maxX, maxY }  (optional bbox to scope overlay deltas)
```

## Security — owner-only WorldView (the crux)

A non-owner MUST NOT subscribe to or receive another faction's view. Enforced
authoritatively server-side, defence-in-depth, with **default deny**:

1. **Subscription gate** — a `ChannelInterceptor` on the client-inbound channel
   (`OwnerViewAuthorizationInterceptor`) inspects every `SUBSCRIBE` to
   `/user/queue/faction/{id}/view` (or the resolved `/queue/...` form), extracts the
   faction id, and admits the frame ONLY if the session principal is the registered owner
   of `(gameId, factionId)` per the `FactionOwnershipRegistry`. Otherwise the frame is
   **dropped** (no subscription created). A `null` principal, unbound faction, or
   cross-game attempt is denied.
2. **User-destination routing** — the server publishes each view with
   `convertAndSendToUser(ownerPrincipal, /queue/faction/{id}/view, worldView)`; Spring
   enqueues a user-destination message only onto sessions whose principal matches, so a
   non-owner session is physically never sent another faction's view even absent (1).

The per-faction view is always built through the authoritative `WorldViewBuilder`
(own state in full, everyone else fog-limited). Raw `GameState` is never serialised to any
client. An unowned seat (e.g. a bot) has no registered owner → nothing is published.

## Public event kinds

Every event carries `{ type, parties[], systemId?, tick }`. Payload semantics per kind:

| `type` | `parties[]` | `systemId?` | emitted at (resolution step) |
| --- | --- | --- | --- |
| `WarDeclared` | `[declarer, target]` | — | DIPLOMATIC_STATE |
| `TreatySigned` | signatories | — | DIPLOMATIC_STATE |
| `TreatyBroken` | signatories | — | DIPLOMATIC_STATE |
| `AllianceFormed` | allied signatories | — | DIPLOMATIC_STATE |
| `SystemCaptured` | `[newOwner]` | captured system | COMBAT |
| `BattleResolved` | `[attacker, defender]` | system (assault/interception) or — | COMBAT |
| `RouteRaided` | `[raider, victim]` | — | INTERDICTION |
| `FirstContact` | `[discoverer, contacted]` | system where contact occurred | DEVELOPMENT (Explore reveal) |
| `TechUnlocked` | `[faction]` | — | DEVELOPMENT (research completes) |
| `ColonyFounded` | `[coloniser]` | colonised system | COLONISATION |
| `RouteEstablished` | `[owner]` | endpoint system | MARKET |
| `FactionEliminated` | `[eliminated]` | — | EVENTS |
| `VictoryAchieved` | `[winner]` | — | EVENTS |

The three exploration/development milestones (`FirstContact`, `TechUnlocked`, `ColonyFounded`)
were added by **E12-04** (P7d) so meaningful first-contact / tech / expansion milestones
surface to the spectator feed and galaxy overlay. They are emitted deterministically during
resolution (same seed + same action log ⇒ identical event stream, order included). The
engine `TechUnlocked` payload additionally carries the unlocked `techId` (public tech web);
`FirstContact` is recorded once per `(discoverer, system)` the first time a faction's Explore
reveals a system owned by a different faction (the pure-engine proxy for fog overlap).

## Notes
- The heavy star **tiles** come over HTTP/CDN, NOT this socket. The socket carries only
  small, live deltas (tick heartbeats + events + thin overlay pointers + per-faction
  views). This keeps the live channel light at scale.
- The overlay message is a **thin pointer** (`asOfTick` + optionally changed system ids),
  not the heavy per-system overlay. Clients fetch detail for their bbox over REST.
- **Reconnect / lag resync:** the client resubscribes and requests
  `GET /api/galaxy/{gameId}/overlay?bbox=&sinceTick={lastAsOfTick}` over REST (E6-03) to
  resync only what changed after the last tick it saw. Events are never dropped (public
  record); overlay deltas may be coalesced under backpressure.
- **Wiring:** `InMemoryMatchService` invokes a `TickListener` once per committed tick;
  `LiveStreamPublisher` (the listener) fans that tick out to the public topics and each
  owner's queue.
