---
name: lod-tiling
description: Use when implementing the galaxy spatial index, the quadtree/Hilbert tile scheme, tile payloads (aggregate vs star-list), viewport-driven tile fetching, tile caching/CDN, or the LOD cross-fade. This is the machinery that makes billions of stars perform like Google Maps. Read BEFORE touching anything about how galaxy data is partitioned, requested, or cached.
---

# LOD Tiling (billion-star scale)

The requirement: support up to **billions of stars** with **Google-Maps performance**. The only way this works is the slippy-map model: load only the screenful at the current zoom, never the whole catalog.

## The model
- Impose a **quadtree** over galactic space. Tile addressed by `(level, x, y)` (quadkey), like slippy-map `z/x/y`.
- **Coarse tiles (zoomed out)** carry an **aggregate**: a density/brightness field (precomputed density image or a few hundred impostor stars) + colour stats. This renders the galaxy glow/arms — NOT individual stars.
- **Fine tiles (zoomed in)** carry the **actual star list** for a tiny region (hundreds–few thousand). Active systems include a pointer to mutable state; scenery stars are pure procedural.
- **Hilbert-curve** cell ordering for spatial locality (good cache/CDN behaviour; prefix-matching range queries).

## Client behaviour
- Compute visible tiles from camera bbox + zoom; fetch only those.
- Cross-fade tile levels by alpha across a zoom boundary (seamless LOD).
- Cache fetched tiles client-side (LRU by quadkey); evict off-screen.
- Per-frame data in flight ≈ constant regardless of total catalog size — that is the whole point.

## Server behaviour
- Tile endpoint: `GET /api/galaxy/{seed}/tile/{level}/{x}/{y}`.
- Generate on miss: coarse → evaluate density field over region; fine → enumerate procedural stars (seeded) and merge active-system state from PostgreSQL.
- Scenery tiles are **immutable** for a seed → long TTL + CDN + ETag by (seed,level,x,y,schemaVersion).
- **Active overlay is a SEPARATE thin layer** (ownership/fleets/routes), fetched by bbox or streamed over WS, composited client-side. Never bake it into star tiles (keeps star tiles cacheable while state changes every tick).

## Invariants
- Tiles are derivable from seed at any time; the cache is an optimisation, never the source of truth.
- The simulation never iterates tiles or the full catalog — it only touches active systems (see game-engine-determinism + procedural-galaxy skills).

## Phasing
1. Small galaxies: skip tiling, ship fixed dataset (PoC).
2. Add WebGL2 renderer (same data contract).
3. Add tile service + procedural generator → unlocks 10^9.
4. Floating origin + CDN + pre-bake → Google-Maps-everywhere.

## References
- `docs/architecture/02-galaxy-scale.md` (full strategy)
- `docs/specs/rest-api.md` (tile + overlay endpoints)
- Cross-skill: `galaxy-rendering`, `procedural-galaxy`.
