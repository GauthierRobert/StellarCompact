---
name: realtime-websocket
description: Use when implementing the live tick stream — Spring WebSocket/STOMP server topics, per-faction private WorldView delivery, public event broadcasting, overlay deltas, and the Angular STOMP client. Read BEFORE touching realtime transport. Keep this channel light; heavy tiles go over HTTP.
---

# Realtime WebSocket / STOMP

The socket is the live spine of spectating and per-Sovereign perception. It must stay **light at scale** — heavy star tiles go over HTTP/CDN, never here.

## Topics (server → client)
- `/topic/games/{gameId}/ticks` — tick/phase heartbeats
- `/topic/games/{gameId}/events` — public events (war, treaty, capture, battle, victory…) — the shared common knowledge that makes reputation work
- `/topic/games/{gameId}/overlay` — overlay deltas (changed systems/routes since last tick), optionally scoped to spectator bbox
- `/user/queue/faction/{factionId}/view` — owner-only WorldView each tick

## Rules
1. **Deltas, not snapshots.** Overlay messages are diffs since last tick; resync via REST `overlay?sinceTick=` on reconnect.
2. **Fog of war enforced server-side** before anything is sent to a non-owner. Never broadcast hidden faction state.
3. **No star tiles over the socket.** Only small live deltas + events + per-faction views.
4. Scope overlay deltas to the spectator's camera bbox when provided (`/app/games/{gameId}/spectate { cameraBbox }`) to bound payload at scale.
5. Backpressure: if a client lags, coalesce overlay deltas; events are never dropped (they're the public record).

## References
- `docs/specs/websocket-protocol.md`
- Cross-skill: `angular21-signals`, `agent-sovereign`.
