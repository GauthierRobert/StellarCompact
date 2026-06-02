package com.stellarcompact.api.ws;

/**
 * Central, single-source-of-truth STOMP destination naming for the live tick stream
 * (board card E6-04; {@code docs/specs/websocket-protocol.md}). Keeping every
 * destination string in one place means the broker config, the per-tick publisher and
 * the authorization interceptor can never drift out of agreement on a topic name - a
 * mismatch there would silently break delivery or, worse, the fog boundary.
 *
 * <p><b>Prefixes (must match {@link WebSocketConfig}).</b> Public fan-out lives under
 * {@code /topic/**}; per-Sovereign owner-only delivery lives under the user-scoped
 * {@code /user/queue/**} prefix, which Spring rewrites per authenticated session so a
 * message addressed to one principal is physically never enqueued for another.
 *
 * <p>Pure string assembly; no Spring, no state.
 */
public final class StompTopics {

    /** Broker prefix for public, fan-out destinations. */
    public static final String TOPIC_PREFIX = "/topic";
    /** Broker prefix for per-user queues (Spring rewrites {@code /user/queue/**}). */
    public static final String QUEUE_PREFIX = "/queue";
    /** Application destination prefix for client {@code SEND} frames (e.g. spectate). */
    public static final String APP_PREFIX = "/app";
    /** Prefix Spring uses to resolve user destinations on the inbound side. */
    public static final String USER_PREFIX = "/user";

    private StompTopics() {
    }

    /** Public tick/phase heartbeat topic for a game. */
    public static String ticks(String gameId) {
        return TOPIC_PREFIX + "/games/" + gameId + "/ticks";
    }

    /** Public common-knowledge event topic for a game. */
    public static String events(String gameId) {
        return TOPIC_PREFIX + "/games/" + gameId + "/events";
    }

    /** Public overlay-delta topic for a game (bbox-scoped per spectator session). */
    public static String overlay(String gameId) {
        return TOPIC_PREFIX + "/games/" + gameId + "/overlay";
    }

    /**
     * Owner-only WorldView destination, relative to a principal's user queue. The
     * leading {@code /user} is added by clients when subscribing
     * ({@code /user/queue/faction/{id}/view}); the server publishes the
     * non-prefixed {@link #factionViewUserDestination} via
     * {@code convertAndSendToUser(principal, ...)}.
     */
    public static String factionViewUserDestination(String factionId) {
        return QUEUE_PREFIX + "/faction/" + factionId + "/view";
    }

    /**
     * The full client-facing subscription destination a Sovereign owner subscribes to:
     * {@code /user/queue/faction/{id}/view}. Used by the authorization interceptor to
     * recognise (and gate) owner-view subscriptions.
     */
    public static String factionViewSubscription(String factionId) {
        return USER_PREFIX + QUEUE_PREFIX + "/faction/" + factionId + "/view";
    }

    /** App destination a spectator SENDs its camera bbox to. */
    public static String spectateDestination(String gameId) {
        return APP_PREFIX + "/games/" + gameId + "/spectate";
    }
}
