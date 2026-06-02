-- =============================================================================
-- V3 - Military, diplomatic and economic mutable state
-- Data-model spec:
--   fleet(id, game_id, faction_id, location_system_id?, enroute_path_json?, stance)
--   ship(id, fleet_id, spec, count)
--   treaty(id, game_id, type, parties_json, terms_json, signed_tick,
--          expires_tick, status)
--   route(id, game_id, system_a, system_b, kind, resources_json, volume, status)
--   market_order(id, game_id, hub_system_id, side, resource, qty, price,
--                faction_id, placed_tick, status)
--   tech_progress(id, faction_id, tech_id, status, progress)
--
--   ids the engine keys by string (FleetId/TreatyId/RouteId/MarketOrderId/TechId)
--   are text. ship.id is a surrogate identity bigint. fleet location/path are
--   Optional (nullable); eta_ticks (E1-09) is carried so multi-tick travel
--   round-trips. parties/terms/resources are jsonb. enum-shaped columns are
--   CHECK-constrained to the engine closed sets. ticks are bigint (engine long);
--   volume/price/qty are double precision.
-- =============================================================================

CREATE TABLE fleet (
    id                  text    PRIMARY KEY,
    game_id             uuid    NOT NULL
                            REFERENCES game (id) ON DELETE CASCADE,
    faction_id          text    NOT NULL
                            REFERENCES faction (id) ON DELETE CASCADE,
    location_system_id  text
                            REFERENCES active_system (id) ON DELETE SET NULL,
    enroute_path_json   jsonb,
    eta_ticks           integer
                            CONSTRAINT fleet_eta_ticks_chk CHECK (eta_ticks IS NULL OR eta_ticks >= 0),
    stance              text    NOT NULL
                            CONSTRAINT fleet_stance_chk
                            CHECK (stance IN ('AGGRESSIVE','BALANCED','DEFENSIVE','EVASIVE'))
);

CREATE INDEX fleet_game_id_idx    ON fleet (game_id);
CREATE INDEX fleet_faction_id_idx ON fleet (faction_id);
CREATE INDEX fleet_location_idx   ON fleet (location_system_id);

CREATE TABLE ship (
    id        bigint  GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    fleet_id  text    NOT NULL
                  REFERENCES fleet (id) ON DELETE CASCADE,
    spec      text    NOT NULL
                  CONSTRAINT ship_spec_not_blank CHECK (length(btrim(spec)) > 0),
    count     integer NOT NULL DEFAULT 0
                  CONSTRAINT ship_count_chk CHECK (count >= 0),
    CONSTRAINT ship_fleet_spec_uk UNIQUE (fleet_id, spec)
);

CREATE INDEX ship_fleet_id_idx ON ship (fleet_id);

CREATE TABLE treaty (
    id            text   PRIMARY KEY,
    game_id       uuid   NOT NULL
                      REFERENCES game (id) ON DELETE CASCADE,
    type          text   NOT NULL
                      CONSTRAINT treaty_type_chk
                      CHECK (type IN ('CEASEFIRE','NON_AGGRESSION','TRADE_PACT',
                                      'DEFENSIVE_PACT','ALLIANCE','VASSALAGE')),
    parties_json  jsonb  NOT NULL,
    terms_json    jsonb  NOT NULL DEFAULT '{}'::jsonb,
    signed_tick   bigint NOT NULL,
    expires_tick  bigint NOT NULL,
    status        text   NOT NULL
                      CONSTRAINT treaty_status_chk
                      CHECK (status IN ('PROPOSED','ACTIVE','EXPIRED','BROKEN'))
);

CREATE INDEX treaty_game_id_idx ON treaty (game_id);

CREATE TABLE route (
    id               text             PRIMARY KEY,
    game_id          uuid             NOT NULL
                         REFERENCES game (id) ON DELETE CASCADE,
    owner_faction_id text             NOT NULL
                         REFERENCES faction (id) ON DELETE CASCADE,
    system_a         text             NOT NULL
                         REFERENCES active_system (id) ON DELETE CASCADE,
    system_b         text             NOT NULL
                         REFERENCES active_system (id) ON DELETE CASCADE,
    kind             text             NOT NULL
                         CONSTRAINT route_kind_chk
                         CHECK (kind IN ('ALLIED','COMMERCIAL','CONTESTED')),
    resources_json   jsonb            NOT NULL DEFAULT '[]'::jsonb,
    volume           double precision NOT NULL DEFAULT 0
                         CONSTRAINT route_volume_chk CHECK (volume >= 0),
    status           text             NOT NULL
                         CONSTRAINT route_status_chk
                         CHECK (status IN ('ACTIVE','BLOCKADED','SUSPENDED'))
);

CREATE INDEX route_game_id_idx ON route (game_id);
CREATE INDEX route_owner_idx   ON route (owner_faction_id);

CREATE TABLE market_order (
    id            text             PRIMARY KEY,
    game_id       uuid             NOT NULL
                      REFERENCES game (id) ON DELETE CASCADE,
    hub_system_id text             NOT NULL
                      REFERENCES active_system (id) ON DELETE CASCADE,
    faction_id    text             NOT NULL
                      REFERENCES faction (id) ON DELETE CASCADE,
    side          text             NOT NULL
                      CONSTRAINT market_order_side_chk CHECK (side IN ('BUY','SELL')),
    resource      text             NOT NULL
                      CONSTRAINT market_order_resource_chk
                      CHECK (resource IN ('ENERGY','MINERALS','FOOD','TECH')),
    qty           double precision NOT NULL
                      CONSTRAINT market_order_qty_chk CHECK (qty >= 0),
    price         double precision NOT NULL
                      CONSTRAINT market_order_price_chk CHECK (price >= 0),
    placed_tick   bigint           NOT NULL,
    expires_tick  bigint           NOT NULL,
    addressee     text
                      REFERENCES faction (id) ON DELETE CASCADE,
    status        text             NOT NULL
                      CONSTRAINT market_order_status_chk
                      CHECK (status IN ('OPEN','PARTIALLY_FILLED','FILLED','CANCELLED'))
);

CREATE INDEX market_order_game_id_idx ON market_order (game_id);
CREATE INDEX market_order_hub_idx     ON market_order (hub_system_id);
CREATE INDEX market_order_faction_idx ON market_order (faction_id);

CREATE TABLE tech_progress (
    id          bigint  GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    faction_id  text    NOT NULL
                    REFERENCES faction (id) ON DELETE CASCADE,
    tech_id     text    NOT NULL,
    status      text    NOT NULL
                    CONSTRAINT tech_progress_status_chk
                    CHECK (status IN ('LOCKED','RESEARCHING','UNLOCKED')),
    progress    integer NOT NULL DEFAULT 0
                    CONSTRAINT tech_progress_progress_chk CHECK (progress >= 0),
    CONSTRAINT tech_progress_faction_tech_uk UNIQUE (faction_id, tech_id)
);

CREATE INDEX tech_progress_faction_id_idx ON tech_progress (faction_id);
