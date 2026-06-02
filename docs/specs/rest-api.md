# Spec — REST API (config, CRUD, tiles)

Described contract only; no implementation. All under `/api`. Auth/session details deferred. JSON unless noted.

## Galaxy tiles (the scale-critical endpoint)

```
GET /api/galaxy/{gameSeed}/tile/{level}/{x}/{y}
    → 200 TilePayload | 304 Not Modified
    TilePayload =
      | AggregateTile { kind:"aggregate", level, x, y, bbox, impostors[ {x,y,weight} ], colorStats {avgDensity,peakDensity,sampleCount}, schemaVersion }   // coarse zoom
      | StarListTile  { kind:"starlist", level, x, y, bbox, stars[ {localId, x, y, spectral, brightness, size, activeSystemId?} ], schemaVersion }  // fine zoom
    Cache: immutable scenery → long TTL + CDN; ETag by (seed,level,x,y,schemaVersion)
```

Tile addressing & LOD split (small tier — E6-03; full quadtree/Hilbert is E8-04):
- A quadtree over the galaxy square `[-R_MAX, R_MAX]²` (galaxy units). Level `L` has `2^L`
  tiles per axis; tile `(x,y)` covers `[ -R_MAX + x·side , -R_MAX + (x+1)·side ]` on each axis
  where `side = 2·R_MAX / 2^L`. `x,y ∈ [0, 2^L)`.
- Levels `0..(STAR_LIST_MIN_LEVEL-1)` return an **AggregateTile** (density impostors + colour
  stats sampled over the region — the galaxy glow/arms, NOT individual stars).
- Levels `>= STAR_LIST_MIN_LEVEL` return a **StarListTile** (the procedural stars whose
  positions fall inside the tile bbox, derived from the seed; each star's spectral/brightness/
  size come from the same seed-derived system). `activeSystemId` is populated only when a
  procedural star has been promoted to a live, persisted system (E2-05/E6-01); procedural
  scenery leaves it null.
- Out-of-range `level` (negative or `> MAX_LEVEL`) or out-of-range `x/y` → `400 Bad Request`.

Caching / conditional-GET contract (MUST):
- Response carries a strong validator `ETag: "<hex>"` computed from `(gameSeed, level, x, y,
  schemaVersion)` only — independent of generated content but stable because content is a pure
  function of those same inputs. Same coordinates ⇒ identical ETag on every call and JVM.
- `Cache-Control: public, max-age=31536000, immutable`.
- A request whose `If-None-Match` matches the current ETag returns `304 Not Modified` with the
  ETag and Cache-Control headers and no body.
- Tiles are never persisted; the cache is a pure-CDN/client optimisation (lod-tiling invariant).

Active-state overlay (separate, thin, dynamic — NOT baked into star tiles):
```
GET /api/galaxy/{gameId}/overlay?bbox=minX,minY,maxX,maxY&sinceTick=N
    → 200 { gameId, asOfTick, sinceTick, bbox,
            systems[ {systemId, owner, tint, contested, fleets[], routes[ {toSystemId} ] } ],
            blockades[ {systemId} ] }
```
- `bbox` is required (`minX,minY,maxX,maxY` in galaxy units); the response contains only
  active state whose system position falls inside the bbox.
- `sinceTick` (optional, default 0) scopes the payload to systems whose state changed at a tick
  `> sinceTick` — a thin diff, not a full snapshot. `asOfTick` is the latest tick reflected.
- The overlay carries NO star/scenery data and is never routed over the websocket (E6-04 owns
  the live push of this same thin shape). Heavy star tiles MUST NOT be designed to flow over WS.

## Match lifecycle

```
POST /api/games                      { seed?, size?, factionCount?, tickIntervalMs?, victoryCondition?, balanceProfile? } → 201 GameSummary { gameId, gameSeed, status:CREATED, tick, balanceProfile, factions[] }
GET  /api/games/{gameId}             → GameSummary
POST /api/games/{gameId}/start       → GameSummary status:RUNNING
POST /api/games/{gameId}/pause       → GameSummary status:PAUSED
POST /api/games/{gameId}/resume      → GameSummary status:RUNNING
GET  /api/games/{gameId}/state[?requester=factionId] → fog-applied snapshot per requester
GET  /api/games/{gameId}/events?fromTick=&limit= → EventsPage (replay/spectate)
GET  /api/games/{gameId}/leaderboard → LeaderboardResponse { gameId, tick, entries[ {rank, factionId, score} ] }
```

Implemented contract (E6-01):
- **Create** (`POST /api/games`) seeds a small galaxy deterministically and returns `201` with the
  `GameSummary`. Every field is optional: `seed` (omitted ⇒ service-derived, never wall-clock),
  `factionCount` (default 2, clamped to `[2,8]`), `balanceProfile` (default `small-default`; an
  unknown profile is `404`). The match starts in `CREATED`. `tickIntervalMs`/`size`/`victoryCondition`
  are accepted but informational (the active victory condition and gameplay numbers live in the
  balance profile, rule 6; tick pacing is orchestration timing, never an engine input — principle 1).
- **Lifecycle transitions** are guarded by the engine `LifecycleTransitions` state machine (E1-15:
  `CREATED→LOBBY→RUNNING→(PAUSED↔RUNNING)→CONCLUDED→ARCHIVED`). `start` drives `CREATED→LOBBY→RUNNING`
  and kicks the tick loop; `pause` halts it (`RUNNING→PAUSED`); `resume` restarts it (`PAUSED→RUNNING`).
  An **illegal transition is `409 Conflict`** (a 4xx) — e.g. resuming a non-paused match, starting a
  paused/concluded match, pausing a non-running match. The orchestrator concludes the match itself
  (`RUNNING→CONCLUDED`) when a victory condition fires.
- **State read** (`GET …/state`) is fog-applied server-side. With `?requester=factionId` it returns
  that faction's fog-filtered `WorldView` (own state full; everyone else fog-limited to ownership +
  rough strength, default-deny) built by the authoritative `WorldViewBuilder` (E3-02). With no
  `requester` it returns a strictly-public `SpectatorView` `{ gameId, tick, status, reputations[] }` —
  no private faction state at all. An unknown match or a `requester` that is not a seat is `404`.
- **Events** (`GET …/events`) page the append-only public event log (E1-16 `PublicEvent`):
  `fromTick` is an inclusive lower bound (every returned event has `tick ≥ fromTick`), ordered by
  `(tick, seq)`; `limit` caps the page (default 200, max 1000). `nextFromTick` is the forward cursor
  (the tick after the last returned, or `-1` at the end of the log).
- **Leaderboard** (`GET …/leaderboard`) is the engine's config-weighted `Scoring.rank` (E1-15) at the
  last resolved snapshot: `entries[]` ranked rank-1-first (score desc, faction id asc on ties). Public
  by construction (no hidden state leaks through the weighted roll-up).
- All match-state reads carry `Cache-Control: no-store` (game state changes every tick). The live
  push of ticks/events/overlay over the websocket is **E6-04** (this card is REST-only).

## Sovereign (agent) configuration

```
POST /api/games/{gameId}/factions    { personaPreset|customPersona, goals, hardConstraints, modelTier } → { factionId }
GET  /api/factions/{factionId}       → FactionConfig (owner-only sensitive fields redacted otherwise)
PATCH/api/factions/{factionId}       update standing directives (between matches only for persistent galaxies)
```

## Catalog / lookup

```
GET /api/galaxy/{gameSeed}/system/{systemId}  → procedural system detail (+ active state if promoted)
GET /api/games/{gameId}/leaderboard           → scores/ranking   (contract under Match lifecycle, E6-01)
```

## Conventions
- Pagination via `?cursor=`/`?fromTick=`.
- Fog of war enforced server-side on every read; never return hidden faction state to an unauthorized requester.
- Tiles are content-addressed & cacheable; game-state reads are not cached.
