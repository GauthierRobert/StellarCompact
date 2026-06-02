# PoC — Galaxy Navigator

`galaxy-navigator.html` is the current proof of concept (the "v8" iteration) for the galaxy view. Open it in a browser.

It demonstrates the rendering approach the production client will formalise:
- **Fixed dataset** (5000 stars generated once) — the Google-Maps model: render fixed data with a camera transform; nothing regenerates on zoom, so nothing pops.
- **Zoom-gated detail** — at galaxy scale you see only star points; planets/orbits/rings/routes fade in only when a system is large on screen.
- **Black space, point-like stars, additive glow** — astrophotography look; diffraction spikes on the brightest.
- **Maps-style camera** — exponential zoom, target-vs-rendered easing, momentum fling, cursor-anchored zoom; double-click a star to dive in.
- **De-scribbled routes** — only colonised systems carry lanes; long lanes hidden when zoomed out.

## How it maps to production (see docs/architecture/02-galaxy-scale.md)
- The embedded `const GALAXY = {...}` is exactly what the backend will serve. In production it becomes tile fetches by viewport (`/api/galaxy/{seed}/tile/{z}/{x}/{y}`), and the catalog is procedural so it scales to billions of stars.
- Canvas2D here → WebGL2 instanced point sprites in production (phase 2).
- The camera model ports directly to an Angular signal store.

This PoC is sufficient for the small-galaxy tier. The tile service + WebGL2 + floating origin are what unlock billion-star galaxies.
