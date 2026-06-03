package com.stellarcompact.api.auth;

/**
 * Result of a successful {@code POST /api/auth/login}. The client stores {@code token} and
 * sends it as {@code Authorization: Bearer <token>} thereafter.
 *
 * @param token     the signed JWT (HS256, {@code sub} = username)
 * @param username  the echoed identity ({@code sub})
 * @param expiresAt ISO-8601 instant the token stops being valid ({@code exp})
 * @param tokenType always {@code "Bearer"} — the scheme to use in the Authorization header
 */
public record LoginResponse(String token, String username, String expiresAt, String tokenType) {
}
