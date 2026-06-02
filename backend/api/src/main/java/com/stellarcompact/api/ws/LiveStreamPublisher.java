package com.stellarcompact.api.ws;

import com.stellarcompact.api.match.TickListener;
import com.stellarcompact.engine.resolve.PublicEvent;
import com.stellarcompact.engine.state.FactionId;
import com.stellarcompact.engine.state.GameState;
import com.stellarcompact.orchestrator.sovereign.SystemAdjacency;
import com.stellarcompact.orchestrator.sovereign.WorldView;
import com.stellarcompact.orchestrator.sovereign.WorldViewBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Fans a committed tick out to the live STOMP stream (board card E6-04). It is the
 * {@link TickListener} the {@code InMemoryMatchService} invokes once per tick, and the
 * one place the public topics and owner-only queues are written.
 *
 * <p><b>What goes where.</b>
 * <ul>
 *   <li>{@link StompTopics#ticks} - a heartbeat (tick + status) to every spectator.</li>
 *   <li>{@link StompTopics#events} - each common-knowledge {@link PublicEvent}, the
 *       shared record that makes reputation work.</li>
 *   <li>{@link StompTopics#overlay} - a thin diff pointer; clients resync the heavy
 *       overlay detail via REST {@code overlay?sinceTick=} (skill rule 1; no heavy
 *       payload on the socket).</li>
 *   <li>{@code /user/queue/faction/{id}/view} - each faction fog-filtered
 *       {@link WorldView}, addressed to the owner principal by name.</li>
 * </ul>
 *
 * <p><b>Fog boundary (the security crux, server-side).</b> A per-faction view is built
 * here through the authoritative {@link WorldViewBuilder} (own state in full, everyone
 * else fog-limited) and is sent with
 * {@link SimpMessagingTemplate#convertAndSendToUser} to <em>the registered owner
 * principal only</em> - resolved server-side from the {@link FactionOwnershipRegistry},
 * never from anything the client supplied. Spring routes a user-destination message only
 * to sessions whose authenticated principal matches, so a non-owner session is never
 * sent another faction view. If a faction has no registered owner, nothing is published
 * for it (default deny). Raw {@link GameState} is never serialised to any client.
 */
@Component
public class LiveStreamPublisher implements TickListener {

    private static final Logger LOG = LoggerFactory.getLogger(LiveStreamPublisher.class);

    private final SimpMessagingTemplate messaging;
    private final FactionOwnershipRegistry ownership;

    public LiveStreamPublisher(SimpMessagingTemplate messaging,
                               FactionOwnershipRegistry ownership) {
        this.messaging = messaging;
        this.ownership = ownership;
    }

    @Override
    public void onTickCommitted(String gameId, GameState state, SystemAdjacency adjacency,
                                List<PublicEvent> events) {
        publicTick(gameId, state);
        publicEvents(gameId, events);
        publicOverlay(gameId, state, events);
        ownerViews(gameId, state, adjacency);
    }

    // ===== public fan-out ======================================================

    private void publicTick(String gameId, GameState state) {
        LiveMessages.TickEvent heartbeat =
                new LiveMessages.TickEvent(state.tick(), state.status().name(),
                        System.currentTimeMillis());
        messaging.convertAndSend(StompTopics.ticks(gameId), heartbeat);
    }

    private void publicEvents(String gameId, List<PublicEvent> events) {
        for (PublicEvent e : events) {
            messaging.convertAndSend(StompTopics.events(gameId),
                    LiveMessages.PublicEventMessage.from(e));
        }
    }

    /**
     * Emit a thin overlay-changed pointer. We list the system ids named by this tick
     * events as a hint; the client resyncs full overlay state for its bbox over REST.
     * Heavy per-system detail never travels here.
     */
    private void publicOverlay(String gameId, GameState state, List<PublicEvent> events) {
        List<Long> changed = events.stream()
                .flatMap(e -> e.systemId().stream())
                .map(s -> {
                    try {
                        return Long.parseLong(s.value());
                    } catch (NumberFormatException ex) {
                        return null;
                    }
                })
                .filter(id -> id != null)
                .distinct()
                .toList();
        messaging.convertAndSend(StompTopics.overlay(gameId),
                new LiveMessages.OverlayDelta(state.tick(), changed));
    }

    // ===== owner-only views ====================================================

    /**
     * Build and deliver each seat fog-filtered WorldView to its owner principal only.
     * The owner is resolved server-side from the registry; an unowned seat (e.g. a bot
     * with no human) is simply not published - there is no client to receive it and no
     * default recipient.
     */
    private void ownerViews(String gameId, GameState state, SystemAdjacency adjacency) {
        for (FactionId faction : state.factions().keySet()) {
            String owner = ownership.ownerOf(gameId, faction);
            if (owner == null) {
                continue; // no registered human owner; nothing leaves the server
            }
            WorldView view = WorldViewBuilder.build(state, adjacency, faction);
            messaging.convertAndSendToUser(
                    owner, StompTopics.factionViewUserDestination(faction.value()), view);
        }
    }
}
