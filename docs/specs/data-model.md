# Spec — Data Model (persistence)

Described, not implemented. Split per Architecture 01 §6: small mutable **game state** in PostgreSQL; huge immutable **catalog** is procedural (not stored) with only active systems persisted.

## PostgreSQL (mutable game state — bounded, ~thousands of rows per active galaxy)

```
game(id, seed, status, params_json, balance_profile, created_at)
faction(id, game_id, name, owner_user_id, persona_json, goals_json, constraints_json,
        reputation, influence, energy, minerals, food, tech, model_tier, status)
active_system(id, game_id, seed_coords, name, owner_faction_id?, biome_summary, population, loyalty)
planet(id, active_system_id, biome, slots_total, population)
building(id, planet_id, slot_index, type, status, progress)
fleet(id, game_id, faction_id, location_system_id?, enroute_path_json?, stance)
ship(id, fleet_id, spec, count)
treaty(id, game_id, type, parties_json, terms_json, signed_tick, expires_tick, status)
route(id, game_id, system_a, system_b, kind, resources_json, volume, status)
market_order(id, game_id, hub_system_id, side, resource, qty, price, faction_id, placed_tick, status)
tech_progress(id, faction_id, tech_id, status, progress)
event_log(id, game_id, tick, type, payload_json)            -- append-only; powers replay
```

## Procedural catalog (NOT stored)
- A star at (seed, coords) is derived by hash; only `active_system` rows exist in the DB.
- Promotion: colonising a procedural star inserts an `active_system` (+ planet/building rows).
- Demotion: an abandoned active system can be deleted, reverting to pure procedural scenery.

### Natural lane graph (procedural, NOT stored) — E2-03

The **natural lanes** of game-design 01 §3 (the static travel skeleton) are part of
the procedural catalog: a pure function of `(seed, region)`, regenerated on demand,
never persisted. This is distinct from the `route` table above, which is the
faction-*activated* trade/military layer (game-design 01 §3 "established routes").

- **Input:** the set of stars in a bounded **playable region** (E2-01 cells), plus the
  game seed. The galaxy module builds the graph; it does not iterate the whole catalog
  (principle 3).
- **Edges (lanes):** undirected, built by **proximity** — each star links to its
  nearest neighbours (k-nearest within a max radius). Determinism: neighbour selection
  is by distance then a `SeedHash`-derived deterministic tiebreak, so equal-distance
  ties resolve identically per seed.
- **Connectivity guarantee:** proximity edges alone can leave isolated stars/clusters.
  After building them we add **MST-style bridging lanes** (a deterministic minimum
  spanning forest over the components, shortest cross-component link first, ties broken
  by the same seeded order) so the region's graph is **connected** — pathfinding
  (E1-09) always has a path between any two region systems.
- **Lane length = travel ticks:** derived from the Euclidean distance between the two
  star coords via a configured `LANE_TICKS_PER_UNIT` factor, rounded deterministically,
  **minimum 1 tick**. Length is monotonic with distance.
- **Consumer (E1-09):** the graph exposes immutable adjacency lookup (`star id ->
  lanes`) and a separated, deterministic shortest-path helper (Dijkstra by summed lane
  ticks) so fleet travel ETAs and interception are reproducible.
- **Tunables** (`GalaxyConstants`, part of the determinism contract — not the balance
  profile, since they shape the fixed map skeleton like `CELL_SIZE`): max neighbours
  per star, max lane radius, ticks-per-distance-unit.

## Object store / CDN (tiles)
- Precomputed/cached tile payloads keyed by (seed, level, x, y, schemaVersion).
- Immutable for scenery; regenerable from seed at any time (cache is an optimisation, not a source of truth).

## Determinism note
The DB holds only the *divergence* from the procedural baseline (active systems + game state). Seed + DB + event_log fully reconstruct any tick.
