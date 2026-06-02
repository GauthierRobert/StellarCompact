package com.stellarcompact.api.match;

import com.stellarcompact.engine.resolve.PublicEvent;

import java.util.List;

/**
 * A page of the append-only public event log for
 * {@code GET /api/games/{id}/events?fromTick=} (rest-api spec; the public event log of
 * E1-16 / the {@code event_log} of E5-01). The log is the replay/spectate backbone:
 * galaxy-wide common-knowledge events, ordered by tick then resolver-emission sequence.
 *
 * <p><b>Pagination by tick (the card's done-when).</b> {@code fromTick} is an inclusive
 * lower bound: the page carries exactly the events with {@code tick >= fromTick}, in
 * order, capped at a page size. {@code nextFromTick} is the cursor to pass on the next
 * request to continue past this page (the tick after the last one returned), or
 * {@code -1} when the page reached the end of the log.
 *
 * <p>Public-only: {@link PublicEvent} is, by definition, common knowledge - no fog
 * filtering applies (everyone sees the same event stream), so this endpoint needs no
 * requester.
 *
 * @param gameId       the match id
 * @param fromTick     the inclusive lower-bound tick this page was requested with
 * @param events       the events on this page, tick-then-seq ordered
 * @param nextFromTick the cursor for the next page, or {@code -1} at the end of the log
 */
public record EventsPage(
        String gameId,
        long fromTick,
        List<Event> events,
        long nextFromTick
) {
    public EventsPage {
        events = events == null ? List.of() : List.copyOf(events);
    }

    /**
     * One public event row, faithful to the wire shape {@code { type, parties[],
     * systemId?, tick }} (E1-16 / websocket-protocol). {@code systemId} is null for the
     * faction-/treaty-scoped kinds.
     *
     * @param tick     the tick the event occurred on
     * @param seq      the resolver-emission index within the tick (the total-order tiebreak)
     * @param type     the wire discriminator (e.g. {@code "WarDeclared"})
     * @param parties  the involved faction ids
     * @param systemId the system the event concerns, or null
     */
    public record Event(long tick, int seq, String type, List<String> parties, String systemId) {
        public Event {
            parties = parties == null ? List.of() : List.copyOf(parties);
        }
    }
}
