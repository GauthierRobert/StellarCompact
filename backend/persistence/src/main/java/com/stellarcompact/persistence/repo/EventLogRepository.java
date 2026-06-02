package com.stellarcompact.persistence.repo;

import com.stellarcompact.engine.resolve.PublicEvent;
import com.stellarcompact.engine.state.FactionId;
import com.stellarcompact.engine.state.SystemId;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.UUID;

/**
 * Append-only, tick-ordered writer/reader for the {@code event_log} table (board card
 * E5-03; schema E5-01 {@code V4__event_log.sql}; data-model spec
 * {@code event_log(id, game_id, tick, type, payload_json)}).
 *
 * <h2>Append-only + tick-ordered (enforced in the DB, mirrored here)</h2>
 * {@code event_log} is the replay backbone (determinism/replay contract): rows are never
 * updated or deleted, and a game's ticks are non-decreasing. Those invariants are enforced
 * at the DB boundary by triggers (V4), so this repository never has to trust callers - it
 * only ever {@code INSERT}s. Within one tick the {@code seq} column is the resolver's
 * emission index (0..n-1), preserving the resolver's deterministic
 * {@code (step, actor, submissionOrder)} order; {@code (game_id, tick, seq)} is unique, so
 * the whole stream has a total, replay-stable order.
 *
 * <h2>Why it lives beside the active-set repository</h2>
 * Committing a tick is "persist the resolved active set AND append this tick's events,
 * atomically" (architecture 03 section 6: a tick fully commits or rolls back). Keeping the
 * event-log writer here lets {@link TickCommitService} compose this and
 * {@link GameStateRepository#save} in a single Spring transaction - if the active-set save
 * throws, the appended event rows roll back with it, so there is never a half-written tick
 * (no orphan {@code event_log} rows for a tick whose state never landed).
 *
 * <h2>Payload shape</h2>
 * Each {@link PublicEvent} is stored as its wire discriminator ({@link PublicEvent#type()},
 * the {@code event_type} column, CHECK-constrained to the closed set of ten) plus a
 * {@code payload_json} body carrying the spec fields {@code { parties[], systemId?, tick }}.
 * The body is intentionally faithful to the websocket wire shape so the replay/feed reader
 * can rehydrate it without re-running resolution.
 */
@Repository
public class EventLogRepository {

    private final JdbcClient jdbc;

    public EventLogRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Append {@code events} for {@code gameId} at {@code tick}, in order. {@code seq} is the
     * list index, so the persisted order is exactly the resolver's emission order. An empty
     * list is a no-op (a quiet tick still commits its state via the active-set save).
     *
     * <p>Not annotated {@code @Transactional} itself: it is meant to be called <em>inside</em>
     * the {@link TickCommitService} transaction so the appends share the same atomic unit as
     * the active-set save. (Calling it standalone still runs in an implicit single-statement
     * transaction per insert, which is fine for tooling, but the tick-commit path always wraps
     * it.)
     */
    public void appendTick(UUID gameId, long tick, List<PublicEvent> events) {
        if (gameId == null) {
            throw new IllegalArgumentException("appendTick.gameId must be set");
        }
        if (tick < 0) {
            throw new IllegalArgumentException("appendTick.tick must be >= 0");
        }
        if (events == null || events.isEmpty()) {
            return;
        }
        int seq = 0;
        for (PublicEvent event : events) {
            jdbc.sql("""
                    INSERT INTO event_log (game_id, tick, seq, event_type, payload_json)
                    VALUES (:g, :tick, :seq, :type, :payload::jsonb)
                    """)
                    .param("g", gameId)
                    .param("tick", tick)
                    .param("seq", seq++)
                    .param("type", event.type())
                    .param("payload", Json.write(payloadOf(event)))
                    .update();
        }
    }

    /**
     * The highest tick with at least one logged event for {@code gameId}, or empty when the
     * game has no events yet. Diagnostic helper for the resume path (the authoritative resume
     * tick is the saved snapshot's {@code state.tick()}; this exposes the log's frontier for
     * consistency checks).
     */
    @Transactional(readOnly = true)
    public OptionalLong lastLoggedTick(UUID gameId) {
        if (gameId == null) {
            throw new IllegalArgumentException("lastLoggedTick.gameId must be set");
        }
        Long max = jdbc.sql("SELECT max(tick) FROM event_log WHERE game_id = :g")
                .param("g", gameId)
                .query(Long.class)
                .optional()
                .orElse(null);
        return max == null ? OptionalLong.empty() : OptionalLong.of(max);
    }

    /** Count of logged events for a game (test/diagnostic helper). */
    @Transactional(readOnly = true)
    public long countEvents(UUID gameId) {
        return jdbc.sql("SELECT count(*) FROM event_log WHERE game_id = :g")
                .param("g", gameId)
                .query(Long.class)
                .single();
    }

    /**
     * Map a {@link PublicEvent} to its {@code payload_json} body - the websocket wire fields
     * {@code { parties[], systemId?, tick }}. Faction ids and the optional system id serialise
     * as bare strings; {@code systemId} is omitted when absent.
     */
    private static Map<String, Object> payloadOf(PublicEvent event) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("parties", event.parties().stream().map(FactionId::value).toList());
        event.systemId().map(SystemId::value).ifPresent(id -> body.put("systemId", id));
        body.put("tick", event.tick());
        return body;
    }
}
