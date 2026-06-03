package com.stellarcompact.api.auth;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The dev authentication surface (spec {@code docs/specs/rest-api.md} "Authentication (dev)").
 *
 * <ul>
 *   <li>{@code POST /api/auth/login} — exchange a username (no password) for a signed JWT.</li>
 *   <li>{@code GET  /api/auth/me} — echo the authenticated identity (requires a valid token;
 *       the {@link SecurityConfig} filter chain rejects an absent/invalid token with 401).</li>
 * </ul>
 *
 * <p><b>Closed input.</b> The username must match {@link #USERNAME} — a closed charset, so a
 * login body can never smuggle structure into the {@code sub} claim (principle 5 spirit:
 * validate untrusted input against a closed schema). Any other value is {@code 400}.
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    /** Closed username charset: letters, digits, and {@code _ . -}; 1–32 chars. */
    private static final Pattern USERNAME = Pattern.compile("^[A-Za-z0-9_.-]{1,32}$");

    private final DevTokenService tokens;

    public AuthController(DevTokenService tokens) {
        this.tokens = tokens;
    }

    @PostMapping("/login")
    public LoginResponse login(@RequestBody(required = false) LoginRequest request) {
        String username = request == null ? null : request.username();
        username = username == null ? null : username.strip();
        if (username == null || !USERNAME.matcher(username).matches()) {
            throw new IllegalArgumentException(
                    "username must match " + USERNAME.pattern() + " (letters, digits, _ . -; 1–32 chars)");
        }
        return tokens.mint(username);
    }

    @GetMapping("/me")
    public Map<String, String> me(Principal principal) {
        // The filter chain guarantees a non-null principal here (endpoint is `authenticated()`).
        return Map.of("username", principal.getName());
    }

    /** Malformed username maps to 400. */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<String> badRequest(IllegalArgumentException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(e.getMessage());
    }
}
