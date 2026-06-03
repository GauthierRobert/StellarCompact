package com.stellarcompact.api.ws;

import org.springframework.lang.Nullable;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;
import org.springframework.web.socket.server.support.DefaultHandshakeHandler;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.security.Principal;
import java.util.List;
import java.util.Map;

/**
 * Resolves and pins the session identity at the HTTP handshake (board card E6-04
 * support), so every later STOMP frame on the session carries a stable, server-held
 * {@code principal} and {@code gameId}. Resolving at the handshake (not per-frame) is the
 * robust Spring pattern: the values live in the WebSocket session attributes / session
 * user and cannot be re-supplied or altered by a later client frame.
 *
 * <p><b>Identity sources (in order).</b>
 * <ol>
 *   <li>{@code ?access_token=<jwt>} — when a {@link JwtDecoder} is wired and the token
 *       <b>verifies</b> (signature + {@code exp} + {@code iss}), the principal is the token's
 *       {@code sub}. This is the real dev-auth path and the only one that can reach an
 *       owner-only WorldView queue.</li>
 *   <li>{@code ?principal=<name>} — the legacy thin stand-in (no verification). Retained for
 *       anonymous spectators and existing tests; a spectator owns no faction, so the
 *       {@link OwnerViewAuthorizationInterceptor} still denies every owner view.</li>
 * </ol>
 * Pinning a <em>verified</em> {@code sub} closes the prior hole where {@code ?principal=alice}
 * was trusted verbatim — an attacker can no longer assert another user's identity to read
 * their private stream, because the ownership gate now sees a cryptographically-verified name.
 *
 * <p><b>Trust boundary (for X-01).</b> The authorization decision still always consults the
 * {@link FactionOwnershipRegistry}, never the client. Swapping the dev HS256 decoder for a
 * Google-JWKS decoder (the planned OAuth move) changes only what {@code access_token} is
 * verified against, not this seam.
 */
public final class HandshakeContext {

    /** Session attribute key for the resolved game id. */
    public static final String GAME_ID_ATTR = "gameId";
    /** Session attribute key for the resolved principal name. */
    public static final String PRINCIPAL_ATTR = "principalName";

    private HandshakeContext() {
    }

    /** Handshake handler that pins the session {@link Principal} (token-verified when present). */
    public static HandshakeHandler handshakeHandler(@Nullable JwtDecoder jwtDecoder) {
        return new Resolver(jwtDecoder);
    }

    /** Handshake interceptor that copies {@code gameId} + resolved principal into attributes. */
    public static HandshakeInterceptor handshakeInterceptor(@Nullable JwtDecoder jwtDecoder) {
        return new AttrCopy(jwtDecoder);
    }

    private static String queryParam(URI uri, String name) {
        if (uri == null) {
            return null;
        }
        List<String> values = UriComponentsBuilder.fromUri(uri).build()
                .getQueryParams().get(name);
        return values == null || values.isEmpty() ? null : values.get(0);
    }

    /**
     * The single identity-resolution rule shared by the handler and the interceptor: a
     * verified {@code access_token} wins; otherwise the legacy {@code principal} param;
     * otherwise {@code null} (anonymous).
     */
    @Nullable
    private static String resolvePrincipalName(URI uri, @Nullable JwtDecoder jwtDecoder) {
        String token = queryParam(uri, "access_token");
        if (token != null && !token.isBlank() && jwtDecoder != null) {
            try {
                Jwt jwt = jwtDecoder.decode(token);
                String sub = jwt.getSubject();
                if (sub != null && !sub.isBlank()) {
                    return sub;
                }
            } catch (JwtException ignored) {
                // Invalid/expired token: fall through. An unverified token never grants identity.
            }
        }
        String name = queryParam(uri, "principal");
        return name == null || name.isBlank() ? null : name;
    }

    /** Pins the per-session principal (token-verified {@code sub}, else legacy param). */
    private static final class Resolver extends DefaultHandshakeHandler {
        @Nullable
        private final JwtDecoder jwtDecoder;

        Resolver(@Nullable JwtDecoder jwtDecoder) {
            this.jwtDecoder = jwtDecoder;
        }

        @Override
        protected Principal determineUser(ServerHttpRequest request, WebSocketHandler wsHandler,
                                          Map<String, Object> attributes) {
            String name = resolvePrincipalName(request.getURI(), jwtDecoder);
            if (name == null) {
                return super.determineUser(request, wsHandler, attributes);
            }
            return new NamedPrincipal(name);
        }
    }

    /** Copies {@code gameId} + the resolved principal from the handshake URI into attributes. */
    private static final class AttrCopy implements HandshakeInterceptor {
        @Nullable
        private final JwtDecoder jwtDecoder;

        AttrCopy(@Nullable JwtDecoder jwtDecoder) {
            this.jwtDecoder = jwtDecoder;
        }

        @Override
        public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                       WebSocketHandler wsHandler, Map<String, Object> attributes) {
            String gameId = queryParam(request.getURI(), "gameId");
            if (gameId != null) {
                attributes.put(GAME_ID_ATTR, gameId);
            }
            String principal = resolvePrincipalName(request.getURI(), jwtDecoder);
            if (principal != null) {
                attributes.put(PRINCIPAL_ATTR, principal);
            }
            return true;
        }

        @Override
        public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler wsHandler, Exception exception) {
            // no-op
        }
    }

    /** A minimal {@link Principal} carrying just the resolved name. */
    record NamedPrincipal(String name) implements Principal {
        @Override
        public String getName() {
            return name;
        }
    }
}
