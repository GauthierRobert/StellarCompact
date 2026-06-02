package com.stellarcompact.api.ws;

import com.stellarcompact.engine.state.FactionId;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;

import java.security.Principal;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * The security crux of E6-04, unit-tested directly on the channel interceptor (no broker
 * needed): a SUBSCRIBE to an owner-view destination is admitted ONLY when the session
 * principal is the registered owner of that faction in that game. Every other case -
 * non-owner principal, no principal, wrong game, unbound faction - is dropped (default
 * deny). Public {@code /topic} subscriptions are always admitted.
 */
class OwnerViewAuthorizationInterceptorTest {

    private static final String GAME = "game-A";

    private final InMemoryFactionOwnershipRegistry registry = new InMemoryFactionOwnershipRegistry();
    private final OwnerViewAuthorizationInterceptor interceptor =
            new OwnerViewAuthorizationInterceptor(registry);

    private Message<byte[]> subscribe(String dest, String principal, String gameId) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
        accessor.setDestination(dest);
        if (principal != null) {
            accessor.setUser(named(principal));
        }
        if (gameId != null) {
            accessor.setNativeHeader("gameId", gameId);
        }
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }

    private static Principal named(String name) {
        return () -> name;
    }

    @Test
    void ownerMaySubscribeToOwnView() {
        registry.bind("alice", GAME, new FactionId("faction-1"));
        Message<byte[]> msg = subscribe("/user/queue/faction/faction-1/view", "alice", GAME);

        Message<?> result = interceptor.preSend(msg, null);

        assertSame(msg, result, "owner subscription must pass through unchanged");
    }

    @Test
    void nonOwnerIsDeniedAnotherFactionView() {
        registry.bind("alice", GAME, new FactionId("faction-1"));
        registry.bind("bob", GAME, new FactionId("faction-2"));

        Message<byte[]> attack = subscribe("/user/queue/faction/faction-1/view", "bob", GAME);
        Message<?> result = interceptor.preSend(attack, null);

        assertNull(result, "a non-owner subscription to another faction view MUST be dropped");
    }

    @Test
    void unauthenticatedPrincipalIsDenied() {
        registry.bind("alice", GAME, new FactionId("faction-1"));
        Message<byte[]> msg = subscribe("/user/queue/faction/faction-1/view", null, GAME);

        assertNull(interceptor.preSend(msg, null),
                "a session with no principal cannot subscribe to any owner view");
    }

    @Test
    void crossGameOwnershipDoesNotLeak() {
        registry.bind("alice", GAME, new FactionId("faction-1"));
        Message<byte[]> msg = subscribe("/user/queue/faction/faction-1/view", "alice", "game-B");

        assertNull(interceptor.preSend(msg, null),
                "ownership is keyed by (game, faction); it must not transfer across games");
    }

    @Test
    void unboundFactionIsDenied() {
        Message<byte[]> msg = subscribe("/user/queue/faction/faction-9/view", "alice", GAME);
        assertNull(interceptor.preSend(msg, null),
                "a faction nobody owns cannot be subscribed to (default deny)");
    }

    @Test
    void publicTopicSubscriptionIsAlwaysAdmitted() {
        Message<byte[]> msg = subscribe("/topic/games/" + GAME + "/events", null, GAME);
        Message<?> result = interceptor.preSend(msg, null);
        assertNotNull(result, "public spectate topics carry only common knowledge; always allowed");
        assertSame(msg, result);
    }

    @Test
    void resolvedUserQueueFormIsAlsoGated() {
        registry.bind("alice", GAME, new FactionId("faction-1"));
        Message<byte[]> bobAttack = subscribe("/queue/faction/faction-1/view", "bob", GAME);
        assertNull(interceptor.preSend(bobAttack, null));
    }
}
