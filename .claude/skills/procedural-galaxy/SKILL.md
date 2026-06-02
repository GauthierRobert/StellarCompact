---
name: procedural-galaxy
description: Use when implementing or modifying the procedural galaxy generator — seed-based star placement, spiral-arm density fields, bulge/halo/dust, spectral class & brightness distributions, planet roster generation, and the promotion/demotion of stars between procedural scenery and active persisted systems. Read BEFORE writing galaxy generation code on client or server.
---

# Procedural Galaxy Generation

The catalog is a pure function of `gameSeed` + position. Billions of potential stars cost nothing on disk; only active systems are persisted. Server, client, and engine all derive the same star from the same seed+coords.

## Generation rules
- **Hash-based determinism:** a star's existence and attributes derive from `hash(cellCoords, seed)`. Same inputs → same star, forever.
- **Galaxy shape via density field:** placement probability follows a log-spiral arm function + central bulge + sparse halo (mirror the PoC `spiralDensity`). This makes the aggregate *look* like a real galaxy at every zoom.
- **Spectral class & brightness:** weighted toward cool dwarfs (realistic IMF-ish); rare luminous giants. Class → approximate blackbody colour (shared with renderer palette).
- **Planet rosters:** only "system" stars (brighter/selected) carry planets; generate biome, orbit, size, rings, moons deterministically from the star's seed.

## Promotion / demotion (the scale trick)
- Scenery star = pure procedural, not in DB.
- **Promote** on colonisation: insert an `active_system` (+planets/buildings) into PostgreSQL; the simulation now tracks it.
- **Demote** on abandonment: delete the active rows; the star reverts to procedural scenery.
- The DB stores only divergence from the procedural baseline → simulation stays cheap regardless of galaxy size.

## Consistency requirements
- The client's scenery generation MUST match the server's exactly (same hash, same constants) so client-rendered scenery and server truth agree without shipping data.
- Generation constants live in shared config; never fork them between client and server.

## References
- `docs/architecture/02-galaxy-scale.md`, `docs/specs/data-model.md`
- `poc/galaxy-navigator.html` (spiralDensity reference)
- Cross-skill: `lod-tiling`, `galaxy-rendering`.
