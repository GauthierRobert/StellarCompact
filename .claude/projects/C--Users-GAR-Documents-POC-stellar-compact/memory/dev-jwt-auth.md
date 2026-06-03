---
name: dev-jwt-auth
description: How the dev username-only JWT auth flow is wired (backend done; Google-swap seam)
metadata:
  type: project
---

A dev, password-less **JWT auth flow** landed on branch `feat/dev-jwt-auth` (commit
`bbf317d`). Username only; designed to swap to Google OAuth later by changing only the token
issuer/decoder. See `docs/specs/rest-api.md` → "Authentication (dev)".

**Backend (api module), DONE + tested:**
- Spring Security resource-server, **HS256** symmetric key. `POST /api/auth/login {username}`
  → `{token, username, expiresAt, tokenType:"Bearer"}`. `sub` = username (charset
  `^[A-Za-z0-9_.-]{1,32}$`). Beans in `com.stellarcompact.api.security.SecurityConfig`
  (JwtDecoder/JwtEncoder/SecretKey/filter chain/CORS), props `stellar-compact.auth.jwt.*`.
- `GET /api/auth/me` (authenticated) echoes username. `GET /api/me/games` (authenticated) =
  **reverse lookup** `FactionOwnershipRegistry.factionsOwnedBy(principal)` joined with each
  match summary → `{username, games:[{gameId, factionId(=gameId:seatId), seatId, status,
  tick, gameSeed, balanceProfile, factionCount}]}`.
- Principal-first owner resolution already existed, so JWT drops in with **no controller
  rewiring**. Config writes still accept the `X-Owner-Token` dev stand-in.
- STOMP handshake now **verifies** `?access_token=<jwt>` (pins Principal=sub), closing the
  old `?principal=alice` spoof hole. Anonymous spectate still works.

**Why:** first real trust boundary for dev; lets a logged-in user own a Sovereign and watch
its games evolve via [[backend-jdk25-build]] api/app suites (all green).

**How to apply / still TODO:** the **frontend** half (login screen, Bearer HTTP interceptor,
`/me` dashboard, pass `access_token` to STOMP) is the remaining work. To tighten security,
flip config-write endpoints to `authenticated()` in `SecurityConfig` once the UI always logs
in. See [[boot4-mockmvc-security-testing]] for how the integration test is wired.
