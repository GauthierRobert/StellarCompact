# /galaxy-work — Start work on the galaxy view

Before any galaxy rendering or data work:
1. Read `.claude/skills/galaxy-rendering`, `lod-tiling`, `procedural-galaxy`.
2. Read `docs/architecture/02-galaxy-scale.md` and open `poc/galaxy-navigator.html`.
3. Confirm the change keeps: zoom-gated detail, black space, point stars, no popping, bounded per-frame work, viewport-only tile loading.
4. Delegate implementation to the `galaxy-renderer-engineer` subagent.

State which phase (1 fixed-dataset PoC → 2 WebGL2 → 3 tiles → 4 floating-origin/CDN) the work targets.
