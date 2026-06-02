-- =============================================================================
-- V1 - Match lifecycle root: game + faction
-- Data-model spec (docs/specs/data-model.md):
--   game(id, seed, status, params_json, balance_profile, created_at)
--   faction(id, game_id, name, owner_user_id, persona_json, goals_json,
--           constraints_json, reputation, influence, energy, minerals, food,
--           tech, model_tier, status)
--
-- Notes
--  * Only mutable game state is persisted (rule 3 / architecture 01 section 6);
--    the procedural star catalog is NOT stored.
--  * game.id is a true surrogate uuid. faction.id is the engine's human-readable
--    string id (state.FactionId wraps a non-blank String), so it is text - the
--    persisted id must round-trip the engine value exactly.
--  * status columns are constrained to the engine's closed enum sets so a bad
--    write is rejected at the DB boundary (engine GameStatus / a faction-level
--    lifecycle). Enum values are stored by name() (the engine hashes by name()).
--  * resource stockpiles are double in the engine (state.ResourceBundle), so
--    double precision here; reputation is also double.
-- =============================================================================

CREATE TABLE game (
    id              uuid         PRIMARY KEY,
    seed            bigint       NOT NULL,
    status          text         NOT NULL
                        CONSTRAINT game_status_chk
                        CHECK (status IN ('CREATED','LOBBY','RUNNING','PAUSED',
                                          'CONCLUDED','ARCHIVED')),
    params_json     jsonb        NOT NULL DEFAULT '{}'::jsonb,
    balance_profile text         NOT NULL,
    created_at      timestamptz  NOT NULL DEFAULT now()
);

CREATE TABLE faction (
    id                text             PRIMARY KEY,
    game_id           uuid             NOT NULL
                          REFERENCES game (id) ON DELETE CASCADE,
    name              text             NOT NULL,
    owner_user_id     uuid,                       -- nullable: AI-only / unclaimed Sovereign
    persona_json      jsonb            NOT NULL DEFAULT '{}'::jsonb,
    goals_json        jsonb            NOT NULL DEFAULT '{}'::jsonb,
    constraints_json  jsonb            NOT NULL DEFAULT '{}'::jsonb,
    reputation        double precision NOT NULL DEFAULT 0,
    -- the five stockpiles (engine state.ResourceBundle): four physical + influence
    influence         double precision NOT NULL DEFAULT 0,
    energy            double precision NOT NULL DEFAULT 0,
    minerals          double precision NOT NULL DEFAULT 0,
    food              double precision NOT NULL DEFAULT 0,
    tech              double precision NOT NULL DEFAULT 0,
    model_tier        text,                       -- orchestration concern, optional
    status            text             NOT NULL DEFAULT 'ACTIVE'
                          CONSTRAINT faction_status_chk
                          CHECK (status IN ('ACTIVE','ELIMINATED')),
    CONSTRAINT faction_name_not_blank CHECK (length(btrim(name)) > 0)
);

CREATE INDEX faction_game_id_idx ON faction (game_id);
