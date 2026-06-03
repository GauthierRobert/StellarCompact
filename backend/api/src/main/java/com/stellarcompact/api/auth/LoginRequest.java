package com.stellarcompact.api.auth;

/**
 * Body of {@code POST /api/auth/login}. Username only — the dev flow has no passwords (see
 * {@code docs/specs/rest-api.md} "Authentication (dev)"). Validation of the username charset
 * happens in {@link AuthController}.
 *
 * @param username the requested identity (closed charset {@code ^[A-Za-z0-9_.-]{1,32}$})
 */
public record LoginRequest(String username) {
}
