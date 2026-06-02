# Architecture 02 — Galaxy Scale: Billions of Stars, Google-Maps Performance

> **The requirement:** the galaxy must support up to **billions of stars** and pan/zoom like Google Maps — instant, fluid, no popping, no loading the whole thing. This document is the strategy that makes that possible. It is the single most important architecture decision in the project; read it fully before touching rendering or galaxy data.

## 1. The core insight

Google Maps does **not** load the Earth. It loads a handful of **tiles** at the **one zoom level you're looking at**, for the **small rectangle on your screen**. Everything else is generated/stored elsewhere and fetched on demand. The amount of data in flight at any instant is roughly constant regardless of whether the planet has thousands of roads or billions — because you only ever see a screenful at one detail level.

We apply the same model to a galaxy. **You never load billions of stars. You load the screenful you're looking at, at the right detail.** The total catalog size becomes almost irrelevant to client performance.

This combines three ideas: **procedural generation** (so the catalog need not be stored), a **spatial tile index with level-of-detail** (so only the visible screenful at the current zoom is ever materialised), and **GPU instanced rendering** (so drawing that screenful is cheap even at high counts).

## 2. Procedural generation — the catalog doesn't have to exist until asked

The full star catalog is a pure function of `gameSeed` and position. A star at galactic coordinate (x, y[, z]) is derived by hashing its cell coordinate with the seed. Consequences:

- **Storage is O(active), not O(total).** Billions of *potential* stars cost nothing on disk; only the few thousand systems that become *active* (colonised, contested, route endpoints) are persisted in PostgreSQL with mutable game state. Everything else is regenerated deterministically on demand.
- **Server and client agree without shipping data.** Both derive the same star from the same seed+coords. The client can render scenery stars it was never explicitly sent.
- **Determinism for free.** Same seed ⇒ same galaxy, forever — the same property the engine needs.

The generator encodes the galaxy's *shape* (spiral arms via a log-spiral density function, bulge, halo, dust lanes) as a density field over position, so the procedural output *looks* like a real galaxy at every scale (this is exactly what the PoC's `spiralDensity` does, scaled up).

## 3. The spatial index — quadtree/Hilbert tiles with LOD

We impose a hierarchical spatial structure over galactic space (a **quadtree** in 2D; an octree if we go 2.5D/3D later). Each node is a **tile** at a **zoom level**:

```
Level 0  : the whole galaxy in 1 tile        (most zoomed out)
Level 1  : 4 tiles
Level 2  : 16 tiles
...
Level Z  : 4^Z tiles                          (most zoomed in)
```

A tile at level L covers a square region and is **pre-summarised for that zoom**:

- **Coarse levels (zoomed out):** a tile does **not** list its millions of stars. It stores an **aggregate**: a density/brightness field (effectively a small precomputed image or a few hundred representative "impostor" stars) plus colour. This is what renders the galaxy's glow and arm structure when you can't possibly resolve individual stars. *(Cf. the PoC's aggregate glow + spiral density — same idea, formalised into tiles.)*
- **Fine levels (zoomed in):** a tile lists the **actual stars** in its small region — at most a few hundred to a few thousand, because the region is tiny. Active systems carry a pointer to their mutable state in PostgreSQL; scenery stars are pure procedural.

**Detail is a function of zoom** (the lesson from real star maps / D3-celestial's magnitude limit): the client requests only the tiles covering its viewport at its current zoom level. Planets, orbits, routes, labels appear only in fine-level tiles — exactly the zoom-gated detail the PoC demonstrates.

### Tile addressing

A tile is addressed by `(level, x, y)` (a quadkey), identical in spirit to slippy-map `z/x/y`. A **Hilbert curve** ordering of cells gives good spatial locality for cache/CDN and for prefix-matching range queries (the technique in the SDSS panning paper). The endpoint looks like:

```
GET /galaxy/{gameSeed}/tile/{level}/{x}/{y}     → tile payload (aggregate or star list)
```

Because tiles for a given seed are immutable (scenery) or change rarely (active overlays), they are **CDN/HTTP-cacheable** like map tiles. Active-state overlays (ownership tint, fleet markers, live routes) are a **separate, thin, dynamic layer** fetched/streamed over WebSocket and composited on top — so the heavy star tiles stay cacheable while the game state stays live.

## 4. Client rendering — WebGL2 instancing, bounded per-frame work

Canvas2D (as in the PoC) is fine to ~10⁴ stars. For the production client we render with **WebGL2**:

- **Instanced point sprites:** one draw call renders all visible stars as GPU instances from a compact buffer (position, colour, size, brightness). Hundreds of thousands of on-screen points at 60 fps is routine; we never have more than a screenful's worth loaded anyway.
- **Additive blending + a bloom post-pass** for the luminous astrophotography look (glows, diffraction spikes on the brightest), all on the GPU.
- **Aggregate tiles render as a textured layer** (the precomputed density image), so the zoomed-out galaxy is a handful of textured quads, not millions of draws.
- **Continuous LOD via tile cross-fade:** when crossing a zoom boundary, the outgoing and incoming tile levels are blended by alpha for a frame or two — the seamless transition the PoC's octave/cross-fade work converged on, but now driven by tiles.
- **Floating origin:** at extreme zoom, world coordinates are re-centred on the camera periodically to avoid float32 precision breakdown (essential for "infinite" descent; deliberately omitted from the PoC for readability).

Per-frame work is **bounded by screen size and zoom**, never by catalog size. That is the whole game.

## 5. Tile generation & caching pipeline (server)

```
request tile (seed, level, x, y)
   │
   ├─ in CDN/cache?  → serve (immutable scenery tiles hit here almost always)
   │
   └─ miss → generate:
        ├─ coarse level → evaluate density field over the region → aggregate payload (impostors/density image)
        └─ fine level   → enumerate procedural stars in region (seeded) → merge active-system state from PostgreSQL → star-list payload
        → cache (immutable scenery long-TTL; active-overlay short-TTL or separate layer)
```

- **Pre-generation (optional):** for a launched galaxy, coarse tiles and the active region's fine tiles can be pre-baked so the common views are warm.
- **Active overlay** (ownership, fleets, live routes) is **not** baked into star tiles; it is a lightweight dynamic layer keyed by system id, joined client-side. This keeps the expensive star tiles cacheable while game state changes every tick.

## 6. Why this meets the requirement

| Concern | How it's solved |
|---|---|
| Billions of stars on disk | Procedural — not stored; only ~thousands of active systems persisted |
| Loading time | Only visible tiles at current zoom load; constant data-in-flight |
| Pan/zoom fluidity | WebGL2 instancing + tile LOD + floating origin; bounded per-frame cost |
| Popping when zooming | Tile cross-fade + procedural continuity (PoC-proven approach) |
| Live game state at scale | Thin dynamic overlay layer, separate from cacheable star tiles |
| Determinism | Seed-derived catalog shared by server, client, and engine |

## 7. Relationship to the simulation

The **simulation only ever touches active systems** — a tiny, bounded set living in PostgreSQL and in the engine's in-memory state. The billion-star catalog is *scenery and frontier*: it defines where expansion *can* go and what it looks like, but the engine never iterates it. When a Sovereign colonises a previously-procedural star, that star is **promoted** to an active, persisted system; if abandoned, it can be demoted back to procedural scenery. This promotion/demotion boundary is what keeps an astronomically large galaxy computationally cheap to simulate.

## 8. Phasing (so we don't over-build early)

1. **PoC → small galaxies:** the fixed-dataset / canvas approach (current PoC, v8) is enough for thousands of stars and the small-galaxy tier. Ship this first.
2. **WebGL2 renderer:** swap canvas for instanced WebGL2; same data contract. Handles 10⁵–10⁶ visible.
3. **Tile service + procedural catalog:** introduce the quadtree tile endpoint and procedural generator; client fetches tiles by viewport. This is the step that unlocks 10⁹.
4. **Floating origin + CDN + pre-bake:** the polish that makes the largest galaxies feel like Google Maps everywhere.

Build in this order; each phase ships value and the data contract (tiles of stars + a thin active overlay) is stable from phase 2 onward.
