-- =============================================================================
-- V4 - event_log : append-only, tick-ordered public event stream (powers replay)
-- Data-model spec:  event_log(id, game_id, tick, type, payload_json)  -- append-only
--
-- Shape.  A typed event_type + a jsonb payload + tick, exactly as the card
-- prescribes. The ten public event kinds come from engine PublicEvent
-- (resolve/PublicEvent.java) and the websocket protocol
-- (/topic/games/{gameId}/events): { type, parties[], systemId?, tick }. The wire
-- discriminator (PublicEvent.type(), the variant simple name) is stored in
-- event_type and CHECK-constrained to the closed set of 10; the full per-variant
-- body ({ parties[], systemId?, declarer/target/... }) lives in payload_json.
--
-- Ordering.  Monotonic, total, replay-stable order per game by (game_id, tick,
-- seq): tick is the engine tick; seq is the within-tick emission index (the
-- resolver records events in fixed step order, already (step, actor,
-- submissionOrder)-ordered, so seq preserves that pure ordering). The surrogate
-- id is a global bigint identity; the meaningful order is (tick, seq).
--
-- Append-only + tick-order enforcement.  The determinism/replay contract says the
-- log is append-only and ordered by tick (skills/game-engine-determinism;
-- PublicEvent javadoc). We enforce it at the DB boundary, not by convention:
--   * a BEFORE UPDATE OR DELETE trigger raises an exception, so rows can never be
--     mutated or removed once written (truly append-only);
--   * recorded_at defaults to now() and is immutable by the same trigger;
--   * (game_id, tick, seq) is UNIQUE so no two events collide in the order, and a
--     non-decreasing tick per game is enforced by a trigger that rejects an insert
--     whose tick is below the max tick already logged for that game (events may
--     never be appended out of tick order).
-- =============================================================================

CREATE TABLE event_log (
    id           bigint      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    game_id      uuid        NOT NULL
                     REFERENCES game (id) ON DELETE CASCADE,
    tick         bigint      NOT NULL
                     CONSTRAINT event_log_tick_chk CHECK (tick >= 0),
    seq          integer     NOT NULL DEFAULT 0
                     CONSTRAINT event_log_seq_chk CHECK (seq >= 0),
    event_type   text        NOT NULL
                     CONSTRAINT event_log_type_chk
                     CHECK (event_type IN ('WarDeclared','TreatySigned','TreatyBroken',
                                           'AllianceFormed','SystemCaptured','BattleResolved',
                                           'RouteEstablished','RouteRaided','FactionEliminated',
                                           'VictoryAchieved')),
    payload_json jsonb       NOT NULL DEFAULT '{}'::jsonb,
    recorded_at  timestamptz NOT NULL DEFAULT now(),
    -- total, replay-stable order within a game: (tick, seq) is unique
    CONSTRAINT event_log_game_tick_seq_uk UNIQUE (game_id, tick, seq)
);

-- Primary read/replay path: stream a game's events in tick then seq order.
CREATE INDEX event_log_game_tick_seq_idx ON event_log (game_id, tick, seq);

-- --- append-only guard: forbid UPDATE and DELETE on the event log --------------
CREATE OR REPLACE FUNCTION event_log_forbid_mutation()
RETURNS trigger
LANGUAGE plpgsql
AS $fn$
BEGIN
    RAISE EXCEPTION 'event_log is append-only: % is not permitted', TG_OP
        USING ERRCODE = 'restrict_violation';
END;
$fn$;

CREATE TRIGGER event_log_no_update
    BEFORE UPDATE ON event_log
    FOR EACH ROW EXECUTE FUNCTION event_log_forbid_mutation();

CREATE TRIGGER event_log_no_delete
    BEFORE DELETE ON event_log
    FOR EACH ROW EXECUTE FUNCTION event_log_forbid_mutation();

-- --- tick-order guard: a new event may not precede the game's last logged tick -
CREATE OR REPLACE FUNCTION event_log_enforce_tick_order()
RETURNS trigger
LANGUAGE plpgsql
AS $fn$
DECLARE
    max_tick bigint;
BEGIN
    SELECT max(tick) INTO max_tick FROM event_log WHERE game_id = NEW.game_id;
    IF max_tick IS NOT NULL AND NEW.tick < max_tick THEN
        RAISE EXCEPTION
            'event_log is tick-ordered: tick % precedes last logged tick % for game %',
            NEW.tick, max_tick, NEW.game_id
            USING ERRCODE = 'restrict_violation';
    END IF;
    RETURN NEW;
END;
$fn$;

CREATE TRIGGER event_log_tick_order
    BEFORE INSERT ON event_log
    FOR EACH ROW EXECUTE FUNCTION event_log_enforce_tick_order();
