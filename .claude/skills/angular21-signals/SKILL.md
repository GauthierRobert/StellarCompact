---
name: angular21-signals
description: Use when building the Angular 21 frontend — standalone components, signal-based state stores, zoneless change detection, the WebGL galaxy component lifecycle, STOMP WebSocket subscriptions feeding signals, and the HUD/config/spectator UI. Read BEFORE writing frontend code.
---

# Angular 21 (signals, zoneless) Frontend

The frontend is display-only and untrusted; all rules live server-side. It renders the galaxy and the live event stream and lets humans configure Sovereigns.

## Conventions
1. **Standalone components only** (no NgModules). Lazy-load feature areas (galaxy view, config, spectator).
2. **Signals for all state.** Camera (x,y,z, target vs rendered), selected system, overlay data, event feed — all signals/`computed`. No manual change detection juggling.
3. **Zoneless change detection.** Don't rely on Zone.js; drive updates through signals. The raf render loop for WebGL runs outside CD and reads signal values directly.
4. **Camera store** = a signal-based service mirroring the PoC camera (exponential zoom, target-vs-rendered easing, momentum). `scale`/`levelF` are `computed`.
5. **Galaxy renderer component** owns the WebGL2 canvas; `afterNextRender`/`ngAfterViewInit` starts the raf loop; tiles fetched via a tile service (HTTP) keyed by the camera bbox/zoom signals.
6. **WebSocket via STOMP** feeds signals: tick events, public events, overlay deltas, and (owner-only) the Sovereign's WorldView. Reconnect resyncs overlay via REST `sinceTick`.
7. **HUD/panels are DOM overlays** over the WebGL canvas, bound to signals (no per-frame DOM thrash).

## Boundaries
- Never request or render another faction's hidden state; the server fog-filters, the client just displays what it's given.
- Heavy star tiles come over HTTP/CDN; the socket carries only light live deltas (see realtime-websocket skill).

## References
- `docs/specs/websocket-protocol.md`, `docs/specs/rest-api.md`
- `poc/galaxy-navigator.html` (camera + rendering reference to port)
- Cross-skill: `galaxy-rendering`, `lod-tiling`, `realtime-websocket`.
