package com.stellarcompact.api.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Configuration for the dev username-only JWT flow (see
 * {@code docs/specs/rest-api.md} "Authentication (dev)"). Bound from
 * {@code stellar-compact.auth.jwt.*}.
 *
 * <p><b>Dev posture.</b> The {@code secret} is an HMAC (HS256) signing key and MUST be
 * overridden (≥ 32 bytes) in any non-dev environment — the baked-in default exists only so
 * the demo boots without extra config. When the project moves to Google OAuth, this whole
 * symmetric-secret path is replaced by a JWKS-backed decoder and an external issuer; the
 * REST/STOMP surfaces and the ownership trust decisions do not change.
 */
@ConfigurationProperties(prefix = "stellar-compact.auth.jwt")
public class JwtProperties {

    /**
     * HS256 signing secret. Must be at least 32 bytes (256 bits). The default is a clearly
     * marked dev value — override via {@code stellar-compact.auth.jwt.secret} in real envs.
     */
    private String secret = "dev-only-insecure-stellar-compact-jwt-secret-change-me";

    /** Token lifetime; {@code exp = iat + ttl}. */
    private Duration ttl = Duration.ofHours(12);

    /** Token issuer claim ({@code iss}); also validated on decode. */
    private String issuer = "stellar-compact-dev";

    public String getSecret() {
        return secret;
    }

    public void setSecret(String secret) {
        this.secret = secret;
    }

    public Duration getTtl() {
        return ttl;
    }

    public void setTtl(Duration ttl) {
        this.ttl = ttl;
    }

    public String getIssuer() {
        return issuer;
    }

    public void setIssuer(String issuer) {
        this.issuer = issuer;
    }
}
