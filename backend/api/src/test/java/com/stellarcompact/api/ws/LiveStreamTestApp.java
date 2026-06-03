package com.stellarcompact.api.ws;

import com.stellarcompact.api.match.InMemoryMatchService;
import com.stellarcompact.api.security.SecurityConfig;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

/**
 * Slim Spring Boot context for the E6-04 STOMP integration test. It deliberately does
 * NOT component-scan the whole api module (that would pull in the galaxy tile beans,
 * which need wiring irrelevant to the live stream). Instead it boots just the websocket
 * stack - the broker config, the publisher, the ownership registry - plus the match
 * service, on an embedded server with auto-configuration (so the messaging/web infra
 * Spring AI bring-up is present). There is no production app in the api module; this is
 * test-only.
 *
 * <p>{@link SecurityConfig} is imported so the security starter (now on the api classpath
 * for dev JWT auth) uses the project's permissive dev chain — {@code /ws/**} stays open — and
 * provides the {@code JwtDecoder} the broker handshake consumes, instead of Boot's
 * default lock-everything-down chain.
 */
@SpringBootConfiguration
@EnableAutoConfiguration
@Import({WebSocketConfig.class, LiveStreamPublisher.class, InMemoryFactionOwnershipRegistry.class,
        SecurityConfig.class})
public class LiveStreamTestApp {

    @Bean
    InMemoryMatchService matchService() {
        return new InMemoryMatchService();
    }
}
