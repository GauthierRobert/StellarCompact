package com.stellarcompact.api.ws;

import com.stellarcompact.api.match.CreateGameRequest;
import com.stellarcompact.api.match.GameSummary;
import com.stellarcompact.api.match.InMemoryMatchService;
import com.stellarcompact.engine.state.FactionId;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.lang.NonNull;
import org.springframework.messaging.converter.MappingJackson2MessageConverter;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;

import java.lang.reflect.Type;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end STOMP test over the embedded server (board card E6-04). Boots the real
 * broker + publisher + match service, runs a couple of ticks, and asserts the three
 * delivery contracts:
 * <ol>
 *   <li>a spectator on the public events/ticks topics receives the broadcast;</li>
 *   <li>the owner principal of faction-1 receives faction-1 WorldView on its user
 *       queue;</li>
 *   <li>a principal that does NOT own faction-1 (it owns faction-2) never receives
 *       faction-1 view - the security crux, asserted as a hard negative.</li>
 * </ol>
 */
@SpringBootTest(classes = LiveStreamTestApp.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class LiveStreamIntegrationTest {

    @LocalServerPort
    int port;

    @Autowired
    InMemoryMatchService matchService;

    @Autowired
    InMemoryFactionOwnershipRegistry ownership;

    private WebSocketStompClient stompClient() {
        WebSocketStompClient client = new WebSocketStompClient(new StandardWebSocketClient());
        client.setMessageConverter(new MappingJackson2MessageConverter());
        return client;
    }

    private StompSession connect(String principal, String gameId)
            throws ExecutionException, InterruptedException, TimeoutException {
        // Identity is pinned at the HTTP handshake (query params), not per-frame.
        StringBuilder url = new StringBuilder("ws://localhost:").append(port).append("/ws");
        char sep = '?';
        if (principal != null) {
            url.append(sep).append("principal=").append(principal);
            sep = '&';
        }
        if (gameId != null) {
            url.append(sep).append("gameId=").append(gameId);
        }
        WebSocketHttpHeaders handshake = new WebSocketHttpHeaders();
        return stompClient()
                .connectAsync(url.toString(), handshake, new StompHeaders(),
                        new StompSessionHandlerAdapter() {})
                .get(5, TimeUnit.SECONDS);
    }

    /** A subscription sink that records every payload it receives, as a String map. */
    private static final class Sink extends StompSessionHandlerAdapter implements StompFrameHandler {
        final ConcurrentLinkedQueue<Object> received = new ConcurrentLinkedQueue<>();

        @Override
        @NonNull
        public Type getPayloadType(@NonNull StompHeaders headers) {
            return Map.class;
        }

        @Override
        public void handleFrame(@NonNull StompHeaders headers, Object payload) {
            if (payload != null) {
                received.add(payload);
            }
        }
    }

    @Test
    void spectatorGetsPublicStreamOwnerGetsOwnViewNonOwnerIsDenied() throws Exception {
        // Create a 2-faction match and bind human owners BEFORE starting.
        GameSummary game = matchService.create(new CreateGameRequest(123L, null, 2, null, null, null));
        String gameId = game.gameId();
        ownership.bind("alice", gameId, new FactionId("faction-1"));
        ownership.bind("bob", gameId, new FactionId("faction-2"));

        // 1) Spectator subscribes to public topics (no principal needed).
        Sink spectatorTicks = new Sink();
        Sink spectatorEvents = new Sink();
        StompSession spectator = connect(null, gameId);
        spectator.subscribe(StompTopics.ticks(gameId), spectatorTicks);
        spectator.subscribe(StompTopics.events(gameId), spectatorEvents);

        // 2) alice (owner of faction-1) subscribes to her own view.
        Sink aliceView = new Sink();
        StompSession alice = connect("alice", gameId);
        alice.subscribe(StompTopics.factionViewSubscription("faction-1"), aliceView);

        // 3) bob (owner of faction-2) tries to subscribe to faction-1's view - must be denied.
        Sink bobAttackView = new Sink();
        StompSession bob = connect("bob", gameId);
        bob.subscribe(StompTopics.factionViewSubscription("faction-1"), bobAttackView);

        // bob also subscribes to his own legitimate view (sanity: routing works for owners).
        Sink bobOwnView = new Sink();
        bob.subscribe(StompTopics.factionViewSubscription("faction-2"), bobOwnView);

        // Let subscriptions settle, then drive ticks deterministically.
        Thread.sleep(300);
        matchService.start(gameId);

        // Spectator must receive at least one tick heartbeat.
        await().atMost(5, TimeUnit.SECONDS)
                .untilAsserted(() -> assertFalse(spectatorTicks.received.isEmpty(),
                        "spectator must receive public tick heartbeats"));

        // alice must receive her own faction-1 WorldView.
        await().atMost(5, TimeUnit.SECONDS)
                .untilAsserted(() -> assertFalse(aliceView.received.isEmpty(),
                        "owner of faction-1 must receive faction-1 WorldView"));

        // bob must receive his own faction-2 WorldView (owner routing works).
        await().atMost(5, TimeUnit.SECONDS)
                .untilAsserted(() -> assertFalse(bobOwnView.received.isEmpty(),
                        "owner of faction-2 must receive faction-2 WorldView"));

        // SECURITY CRUX: bob must NEVER receive faction-1's view, even after many ticks.
        Thread.sleep(1000);
        assertTrue(bobAttackView.received.isEmpty(),
                "a non-owner must NEVER receive another faction's WorldView (fog boundary)");

        matchService.pause(gameId);
        spectator.disconnect();
        alice.disconnect();
        bob.disconnect();
    }
}
