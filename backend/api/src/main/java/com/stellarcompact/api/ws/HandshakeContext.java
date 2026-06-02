package com.stellarcompact.api.ws;

import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
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
 * <p><b>Why both pieces.</b> The {@code principal} is the identity the
 * {@link OwnerViewAuthorizationInterceptor} authorizes and the {@link LiveStreamPublisher}
 * addresses; the {@code gameId} scopes ownership so faction-1 in game A is distinct from
 * faction-1 in game B. Both are read once here and trusted thereafter.
 *
 * <p><b>Trust boundary (for X-01).</b> Today the principal name and gameId come from the
 * handshake query string (or STOMP CONNECT headers, copied here). This is the
 * intentionally thin authentication stand-in: a later auth card replaces
 * {@link Resolver#determineUser} with a verified session token / OAuth identity at this
 * exact seam, without changing the authorization decision (which always consults the
 * {@link FactionOwnershipRegistry}, never the client). The ownership {@code bind} call is
 * what makes a principal meaningful; an unbound principal is denied every owner view.
 */
public final class HandshakeContext {

    /** Session attribute key for the resolved game id. */
    public static final String GAME_ID_ATTR = "gameId";
    /** Session attribute key for the resolved principal name. */
    public static final String PRINCIPAL_ATTR = "principalName";

    private HandshakeContext() {
    }

    /** Handshake handler that pins the session {@link Principal} from the query string. */
    public static HandshakeHandler handshakeHandler() {
        return new Resolver();
    }

    /** Handshake interceptor that copies {@code gameId} (and principal) into attributes. */
    public static HandshakeInterceptor handshakeInterceptor() {
        return new AttrCopy();
    }

    private static String queryParam(URI uri, String name) {
        if (uri == null) {
            return null;
        }
        List<String> values = UriComponentsBuilder.fromUri(uri).build()
                .getQueryParams().get(name);
        return values == null || values.isEmpty() ? null : values.get(0);
    }

    /** Pins the per-session principal from the {@code principal} query parameter. */
    private static final class Resolver extends DefaultHandshakeHandler {
        @Override
        protected Principal determineUser(ServerHttpRequest request, WebSocketHandler wsHandler,
                                          Map<String, Object> attributes) {
            String name = queryParam(request.getURI(), "principal");
            if (name == null || name.isBlank()) {
                return super.determineUser(request, wsHandler, attributes);
            }
            return new NamedPrincipal(name);
        }
    }

    /** Copies {@code gameId} + {@code principal} from the handshake URI into attributes. */
    private static final class AttrCopy implements HandshakeInterceptor {
        @Override
        public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                       WebSocketHandler wsHandler, Map<String, Object> attributes) {
            String gameId = queryParam(request.getURI(), "gameId");
            if (gameId != null) {
                attributes.put(GAME_ID_ATTR, gameId);
            }
            String principal = queryParam(request.getURI(), "principal");
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
