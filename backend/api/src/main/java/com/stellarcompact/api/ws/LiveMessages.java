package com.stellarcompact.api.ws;

import com.stellarcompact.engine.resolve.PublicEvent;

import java.util.List;
import java.util.Optional;

/**
 * The small, JSON-serialisable wire shapes carried over the public STOMP topics
 * (board card E6-04; {@code docs/specs/websocket-protocol.md}). They are deliberately
 * tiny: tick heartbeats, common-knowledge events, and overlay diffs. No heavy star/tile
 * data ever travels here (skill rule 3 - tiles go over HTTP/CDN).
 *
 * <p>These are transport DTOs, not engine state. The per-faction owner-only WorldView is
 * the existing {@code orchestrator.WorldView} record, sent unchanged on the user queue.
 */
public final class LiveMessages {

    private LiveMessages() {
    }

    /**
     * {@code /topic/games/{gameId}/ticks} heartbeat: which tick committed and the
     * lifecycle status after it. {@code startedAt} is a server wall-clock millis stamp
     * for client-side latency display ONLY - it is never an engine input (determinism).
     */
    public record TickEvent(long tick, String status, long startedAt) {
    }

    /**
     * {@code /topic/games/{gameId}/events} entry, mapping a {@link PublicEvent} to the
     * spec wire shape {@code { type, parties[], systemId?, tick }}. Common knowledge:
     * every spectator and Sovereign sees the same event stream (this is what makes the
     * reputation system work).
     */
    public record PublicEventMessage(String type, List<String> parties,
                                     Long systemId, long tick) {
        public PublicEventMessage {
            parties = parties == null ? List.of() : List.copyOf(parties);
        }

        /** Project an engine {@link PublicEvent} into its wire form. */
        public static PublicEventMessage from(PublicEvent e) {
            List<String> parties = e.parties().stream().map(f -> f.value()).toList();
            Long systemId = e.systemId().map(s -> parseId(s.value())).orElse(null);
            return new PublicEventMessage(e.type(), parties, systemId, e.tick());
        }

        private static Long parseId(String raw) {
            try {
                return Long.parseLong(raw);
            } catch (NumberFormatException ex) {
                return null;
            }
        }
    }

    /**
     * {@code /topic/games/{gameId}/overlay} diff. A thin pointer payload: it announces
     * that the overlay changed as of a tick and (optionally) which systems changed, so a
     * lagging or just-connected client knows to resync the heavy detail via the REST
     * {@code overlay?sinceTick=} endpoint (E6-03). Bounded by the spectator bbox the
     * session declared; empty {@code changedSystems} means "something changed, resync".
     */
    public record OverlayDelta(long asOfTick, List<Long> changedSystems) {
        public OverlayDelta {
            changedSystems = changedSystems == null ? List.of() : List.copyOf(changedSystems);
        }
    }

    /** Optional spectator bbox a client SENDs to scope overlay deltas (skill rule 4). */
    public record SpectateRequest(double minX, double minY, double maxX, double maxY) {
        public Optional<String> validationError() {
            if (maxX <= minX || maxY <= minY) {
                return Optional.of("bbox max must exceed min on both axes");
            }
            return Optional.empty();
        }
    }
}
