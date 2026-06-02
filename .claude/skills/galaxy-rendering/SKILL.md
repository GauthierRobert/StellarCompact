---
name: galaxy-rendering
description: Use when building or modifying the galaxy visual renderer — WebGL2 star fields, instanced point sprites, additive bloom, diffraction spikes, nebulosity, planet shading, or porting the canvas PoC to production. Triggers on tasks mentioning the galaxy map, star rendering, zoom/pan, LOD cross-fade, or anything visual in the frontend galaxy view. Read BEFORE writing any rendering code.
---

# Galaxy Rendering

The galaxy view is the signature of this game. It must look like real astrophotography and pan/zoom like Google Maps. This skill encodes the hard-won rendering approach proven in the PoC (`poc/`) and the production target.

## Mandatory principles
1. **Detail is a function of zoom.** Never draw planets, orbits, routes, or labels at galaxy scale. They fade in only when a system is large on screen. (PoC `detailA`/`systemA` ramps.) This is the #1 cause of the "crowded petri dish" failure mode — avoid it.
2. **Black space, not grey.** Stars are light on a near-black void; depth comes from additive blending of glows, not from a flat background.
3. **Stars stay point-like.** Size grows only gently with zoom. Stars are distant suns, not balls filling space.
4. **Bounded per-frame work.** Cost scales with screen+zoom, never with catalog size. You only ever render a screenful.
5. **No popping.** Cross-fade adjacent LOD tiles/levels by alpha; rely on procedural continuity. Hard band-switches are forbidden.

## Production rendering (WebGL2)
- Render visible stars as **instanced point sprites**: one draw call, compact per-instance buffer (x,y, color, size, brightness).
- **Additive blending** + a **bloom post-pass** for the luminous look; diffraction spikes only on the brightest/largest stars.
- **Aggregate (coarse) tiles** render as a textured density quad — the zoomed-out galaxy is a few textured quads, not millions of points.
- **Floating origin:** periodically re-centre world coords on the camera to avoid float32 breakdown at deep zoom.
- HUD/labels/panels are Angular DOM/canvas overlaid on the WebGL canvas, driven by signals.

## Camera & motion (carry over from PoC, proven good)
- Exponential zoom: `scale = base^z`. Each zoom step multiplies scale.
- **Target vs rendered** camera state; ease rendered → target each frame with frame-rate-independent damping (`exp(-rate*dt)`).
- Momentum/fling on drag release; cursor-anchored zoom.

## Spectral colour
Approximate blackbody by class O→M (blue-white → orange-red); weight the population toward cool dwarfs for realism. Palette lives in shared config, mirrored from the catalog generator.

## What NOT to do
- Don't use Canvas2D for the production renderer beyond ~10^4 stars (fine for small-galaxy PoC only).
- Don't load the whole catalog. Pull tiles by viewport (see lod-tiling skill).
- Don't bake live game state (ownership/fleets) into star tiles; composite the thin overlay on top.

## References
- `poc/galaxy-navigator.html` — the canvas reference (v8): zoom-gated detail, black space, point stars, de-scribbled routes.
- `docs/architecture/02-galaxy-scale.md` — the tile/LOD/scale strategy this renders.
- Cross-skill: `lod-tiling`, `procedural-galaxy`.
