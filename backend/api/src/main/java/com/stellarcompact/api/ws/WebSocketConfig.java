package com.stellarcompact.api.ws;

import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

/**
 * The STOMP-over-WebSocket broker configuration for the live tick stream (board card
 * E6-04; {@code docs/specs/websocket-protocol.md}). A NEW, self-contained config class -
 * it does not touch the REST surface (E6-01 match controllers, E6-03 tile/overlay
 * controllers) at all.
 *
 * <p><b>Topology.</b>
 * <ul>
 *   <li>App-handled (client {@code SEND}) destinations are prefixed {@code /app}
 *       (e.g. the optional spectate bbox).</li>
 *   <li>The simple in-memory broker serves the public fan-out prefix {@code /topic} and
 *       the per-user prefix {@code /queue}; {@code /user} is the user-destination prefix
 *       Spring rewrites per authenticated session.</li>
 *   <li>The STOMP handshake endpoint is {@code /ws} (SockJS fallback enabled for browser
 *       reach; the heavy tiles still go over plain HTTP, never this socket).</li>
 * </ul>
 *
 * <p><b>Security wiring (the crux).</b> Two interceptors are installed on the
 * client-inbound channel, in order:
 * <ol>
 *   <li>{@link StompPrincipalInterceptor} establishes the session principal at
 *       {@code CONNECT} (server-held identity).</li>
 *   <li>{@link OwnerViewAuthorizationInterceptor} gates every owner-view
 *       {@code SUBSCRIBE} against the {@link FactionOwnershipRegistry}, dropping any
 *       subscription to a faction the principal does not own.</li>
 * </ol>
 * Combined with user-destination routing in {@link LiveStreamPublisher}, a non-owner can
 * neither subscribe to nor receive another faction WorldView.
 *
 * <p><b>Why the simple broker.</b> Adequate for the current single-node, in-memory match
 * registry (E6-01). A later card swaps in a STOMP relay (RabbitMQ/Artemis) for
 * multi-node fan-out without changing destinations, the publisher or the auth path.
 */
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private final FactionOwnershipRegistry ownership;

    public WebSocketConfig(FactionOwnershipRegistry ownership) {
        this.ownership = ownership;
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        // Pin the session principal + gameId at the handshake (server-held identity).
        registry.addEndpoint("/ws")
                .setAllowedOriginPatterns("*")
                .setHandshakeHandler(HandshakeContext.handshakeHandler())
                .addInterceptors(HandshakeContext.handshakeInterceptor())
                .withSockJS();
        registry.addEndpoint("/ws")
                .setAllowedOriginPatterns("*")
                .setHandshakeHandler(HandshakeContext.handshakeHandler())
                .addInterceptors(HandshakeContext.handshakeInterceptor());
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.setApplicationDestinationPrefixes(StompTopics.APP_PREFIX);
        registry.enableSimpleBroker(StompTopics.TOPIC_PREFIX, StompTopics.QUEUE_PREFIX);
        registry.setUserDestinationPrefix(StompTopics.USER_PREFIX);
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        // The identity is pinned at the handshake; here we authorize every owner-view
        // SUBSCRIBE against the ownership registry (the security crux).
        registration.interceptors(new OwnerViewAuthorizationInterceptor(ownership));
    }
}
