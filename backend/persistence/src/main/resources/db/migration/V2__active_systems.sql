-- =============================================================================
-- V2 - Active systems + their planets + buildings (the promotion footprint)
-- Data-model spec:
--   active_system(id, game_id, seed_coords, name, owner_faction_id?,
--                 biome_summary, population, loyalty)
--   planet(id, active_system_id, biome, slots_total, population)
--   building(id, planet_id, slot_index, type, status, progress)
--
-- Only systems that have DIVERGED from the procedural baseline are stored
-- (rule 3): colonising promotes a star -> one active_system (+ planet/building
-- rows); abandoning demotes it -> rows deleted (E2-05). The vast catalog is a
-- pure function of (seed, coords) and is never persisted.
--
--  * seed_coords = the engine state.Coords (long x, long y) seed-relative address
--    tying the active system back to its procedural star. Stored as two bigints.
--  * ids are engine string ids (state.SystemId / PlanetId), hence text.
--  * owner_faction_id is nullable (state.ActiveSystem.owner is Optional - a
--    neutral/contested frontier system has no owner).
--  * biome/type/status are constrained to the engine's closed enum sets, stored
--    by name(). loyalty is in [0,1]; populations are bigint (engine long).
-- =============================================================================

CREATE TABLE active_system (
    id                text             PRIMARY KEY,
    game_id           uuid             NOT NULL
                          REFERENCES game (id) ON DELETE CASCADE,
    seed_coord_x      bigint           NOT NULL,
    seed_coord_y      bigint           NOT NULL,
    name              text             NOT NULL,
    owner_faction_id  text
                          REFERENCES faction (id) ON DELETE SET NULL,
    biome_summary     jsonb            NOT NULL DEFAULT '{}'::jsonb,
    population        bigint           NOT NULL DEFAULT 0
                          CONSTRAINT active_system_population_chk CHECK (population >= 0),
    loyalty           double precision NOT NULL DEFAULT 1.0
                          CONSTRAINT active_system_loyalty_chk
                          CHECK (loyalty >= 0.0 AND loyalty <= 1.0),
    CONSTRAINT active_system_name_not_blank CHECK (length(btrim(name)) > 0),
    -- a seed-relative star is unique within a game (promotion never double-inserts)
    CONSTRAINT active_system_seed_coords_uk UNIQUE (game_id, seed_coord_x, seed_coord_y)
);

CREATE INDEX active_system_game_id_idx          ON active_system (game_id);
CREATE INDEX active_system_owner_faction_id_idx ON active_system (owner_faction_id);

CREATE TABLE planet (
    id                text             PRIMARY KEY,
    active_system_id  text             NOT NULL
                          REFERENCES active_system (id) ON DELETE CASCADE,
    biome             text             NOT NULL
                          CONSTRAINT planet_biome_chk
                          CHECK (biome IN ('OCEANIC','TERRAN','ARID','DESERT',
                                           'VOLCANIC','FROZEN','TOXIC','GAS_GIANT')),
    slots_total       integer          NOT NULL DEFAULT 0
                          CONSTRAINT planet_slots_total_chk CHECK (slots_total >= 0),
    population        bigint           NOT NULL DEFAULT 0
                          CONSTRAINT planet_population_chk CHECK (population >= 0)
);

CREATE INDEX planet_active_system_id_idx ON planet (active_system_id);

CREATE TABLE building (
    id          bigint  GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    planet_id   text    NOT NULL
                    REFERENCES planet (id) ON DELETE CASCADE,
    slot_index  integer NOT NULL
                    CONSTRAINT building_slot_index_chk CHECK (slot_index >= 0),
    type        text    NOT NULL
                    CONSTRAINT building_type_chk
                    CHECK (type IN ('MINE','SOLAR_ARRAY','FARM','RESEARCH_LAB',
                                    'MARKET_HUB','SHIPYARD','DEFENSE_PLATFORM',
                                    'MONUMENT','TERRAFORMER')),
    status      text    NOT NULL
                    CONSTRAINT building_status_chk
                    CHECK (status IN ('UNDER_CONSTRUCTION','ACTIVE','IDLE')),
    progress    integer NOT NULL DEFAULT 0
                    CONSTRAINT building_progress_chk CHECK (progress >= 0),
    -- one building per planet slot (the engine keeps buildings in stable slot order)
    CONSTRAINT building_planet_slot_uk UNIQUE (planet_id, slot_index)
);

CREATE INDEX building_planet_id_idx ON building (planet_id);
