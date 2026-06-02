# Spec — WebSocket / STOMP Protocol (live tick stream)

Described contract only. STOMP over WebSocket. The live spine of spectating and (optionally) external agent feeds.

## Topics (server → client)

```
/topic/games/{gameId}/ticks          TickEvent     { tick, phase, startedAt }
/topic/games/{gameId}/events          PublicEvent   { type, parties[], systemId?, tick }
                                        type ∈ { WarDeclared, TreatySigned, TreatyBroken, AllianceFormed,
                                                 SystemCaptured, BattleResolved, RouteEstablished,
                                                 RouteRaided, FactionEliminated, VictoryAchieved }
/topic/games/{gameId}/overlay         OverlayDelta  { changedSystems[], changedRoutes[], asOfTick }
/user/queue/faction/{factionId}/view  WorldView     (owner-only; the agent's perception each tick)
```

## Messages (client → server)

```
SUBSCRIBE /topic/games/{gameId}/events            spectate public stream
SUBSCRIBE /user/queue/faction/{factionId}/view    owner subscribes to its Sovereign's view
SEND      /app/games/{gameId}/spectate            { cameraBbox } (optional: server tailors overlay deltas to view)
```

## Notes
- The heavy star **tiles** come over HTTP/CDN, NOT this socket. The socket carries only small, live deltas (events + overlay changes + per-faction views). This keeps the live channel light at scale.
- Overlay deltas are diffs since the last tick, scoped to the spectator's bbox when provided.
- Reconnect: client resubscribes and requests `overlay?sinceTick=` over REST to resync.
