package com.stellarcompact.api.ws;

import com.stellarcompact.engine.state.FactionId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;

import java.security.Principal;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The owner-only enforcement point for the live WorldView stream (board card E6-04, the
 * security crux; principle 2 - clients are untrusted, all authority is server-side).
 *
 * <p><b>Where it sits.</b> Registered on the STOMP client-inbound channel
 * ({@link WebSocketConfig#configureClientInboundChannel}), so it sees every frame a
 * client sends <em>before</em> the broker acts on it. On a {@code SUBSCRIBE} whose
 * destination is an owner-view destination ({@code /user/queue/faction/{id}/view} or the
 * already-resolved {@code /queue/faction/{id}/view}), it extracts the target faction id
 * and asks the {@link FactionOwnershipRegistry} whether the session principal owns that
 * faction in the game it is currently spectating. If not, it returns {@code null} - the
 * frame is dropped and the subscription is never created. There is therefore no
 * subscription through which a non-owner could ever receive another faction view.
 *
 * <p><b>Two independent guarantees (defence in depth).</b>
 * <ol>
 *   <li><b>Routing:</b> the server publishes views with
 *       {@code convertAndSendToUser(ownerPrincipal, ...)}; Spring enqueues such a message
 *       only onto sessions whose principal matches, so a non-owner session is physically
 *       never sent another faction view even if a subscription somehow existed.</li>
 *   <li><b>Subscription gate (this class):</b> we additionally refuse to create the
 *       subscription at all, so the denial is explicit and observable, and an
 *       unauthenticated ({@code null}) principal cannot subscribe to any owner view.</li>
 * </ol>
 *
 * <p><b>Default deny.</b> No principal, no game context, an unknown faction, or a
 * not-owned faction all result in the frame being dropped. Public {@code /topic/**}
 * subscriptions are untouched - they carry only common-knowledge state.
 *
 * <p>The game the session is scoped to is taken from the {@code gameId} native STOMP
 * header on the {@code CONNECT}/{@code SUBSCRIBE} frame; the registry binding is keyed by
 * {@code (gameId, factionId)} so a principal owning faction-1 in game A cannot use that to
 * read faction-1 in game B.
 */
public class OwnerViewAuthorizationInterceptor implements ChannelInterceptor {

    private static final Logger LOG = LoggerFactory.getLogger(OwnerViewAuthorizationInterceptor.class);

    /** Matches both {@code /user/queue/faction/{id}/view} and {@code /queue/faction/{id}/view}. */
    private static final Pattern OWNER_VIEW =
            Pattern.compile("^(?:/user)?/queue/faction/([^/]+)/view$");

    private final FactionOwnershipRegistry ownership;

    public OwnerViewAuthorizationInterceptor(FactionOwnershipRegistry ownership) {
        this.ownership = ownership;
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(message);
        if (accessor.getMessageType() != SimpMessageType.SUBSCRIBE) {
            return message;
        }
        String destination = accessor.getDestination();
        if (destination == null) {
            return message;
        }
        Matcher m = OWNER_VIEW.matcher(destination);
        if (!m.matches()) {
            // Not an owner-view subscription (e.g. a public /topic/** spectate). Allow.
            return message;
        }

        String factionId = m.group(1);
        String principalName = principalName(accessor);
        String gameId = gameId(accessor);

        if (principalName == null || gameId == null
                || !ownership.owns(principalName, gameId, new FactionId(factionId))) {
            LOG.warn("DENY owner-view subscription: principal={} gameId={} faction={} dest={}",
                    principalName, gameId, factionId, destination);
            // Drop the frame: no subscription is created, the fog boundary holds.
            return null;
        }
        return message;
    }

    /**
     * The session principal: the handshake-pinned user
     * ({@link HandshakeContext#handshakeHandler}), falling back to the
     * principal-name session attribute. Never read from a client frame.
     */
    private static String principalName(StompHeaderAccessor accessor) {
        Principal user = accessor.getUser();
        if (user != null && user.getName() != null) {
            return user.getName();
        }
        Object attr = sessionAttribute(accessor, HandshakeContext.PRINCIPAL_ATTR);
        return attr instanceof String s ? s : null;
    }

    /**
     * The session game id: pinned at the handshake into session attributes
     * ({@link HandshakeContext#GAME_ID_ATTR}); falls back to a native {@code gameId}
     * header to keep the interceptor directly unit-testable without a live session.
     */
    private static String gameId(StompHeaderAccessor accessor) {
        Object attr = sessionAttribute(accessor, HandshakeContext.GAME_ID_ATTR);
        if (attr instanceof String s) {
            return s;
        }
        return accessor.getFirstNativeHeader("gameId");
    }

    private static Object sessionAttribute(StompHeaderAccessor accessor, String key) {
        var attrs = accessor.getSessionAttributes();
        return attrs == null ? null : attrs.get(key);
    }
}
