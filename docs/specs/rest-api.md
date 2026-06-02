# Spec — REST API (config, CRUD, tiles)

Described contract only; no implementation. All under `/api`. Auth/session details deferred. JSON unless noted.

## Galaxy tiles (the scale-critical endpoint)

```
GET /api/galaxy/{gameSeed}/tile/{level}/{x}/{y}
    → 200 TilePayload
    TilePayload =
      | AggregateTile { level, x, y, densityImageRef|impostors[], colorStats }   // coarse zoom
      | StarListTile  { level, x, y, stars[ {localId, coords, spectral, brightness, size, activeSystemId?} ] }  // fine zoom
    Cache: immutable scenery → long TTL + CDN; ETag by (seed,level,x,y,schemaVersion)
```

Active-state overlay (separate, thin, dynamic — NOT baked into star tiles):
```
GET /api/galaxy/{gameId}/overlay?bbox=...&sinceTick=...
    → { systems[ {systemId, owner, tint, fleets[], routes[] } ], asOfTick }
```

## Match lifecycle

```
POST /api/games                      { size, factionCount, tickIntervalMs, victoryCondition, balanceProfile } → { gameId, gameSeed, status:CREATED }
GET  /api/games/{gameId}             → GameSummary
POST /api/games/{gameId}/start       → status:RUNNING
POST /api/games/{gameId}/pause       → status:PAUSED
POST /api/games/{gameId}/resume      → status:RUNNING
GET  /api/games/{gameId}/state       → spectator-visible snapshot (fog rules apply per requester)
GET  /api/games/{gameId}/events?fromTick= → public event log page (replay/spectate)
```

## Sovereign (agent) configuration

```
POST /api/games/{gameId}/factions    { personaPreset|customPersona, goals, hardConstraints, modelTier } → { factionId }
GET  /api/factions/{factionId}       → FactionConfig (owner-only sensitive fields redacted otherwise)
PATCH/api/factions/{factionId}       update standing directives (between matches only for persistent galaxies)
```

## Catalog / lookup

```
GET /api/galaxy/{gameSeed}/system/{systemId}  → procedural system detail (+ active state if promoted)
GET /api/games/{gameId}/leaderboard           → scores/ranking
```

## Conventions
- Pagination via `?cursor=`/`?fromTick=`.
- Fog of war enforced server-side on every read; never return hidden faction state to an unauthorized requester.
- Tiles are content-addressed & cacheable; game-state reads are not cached.
