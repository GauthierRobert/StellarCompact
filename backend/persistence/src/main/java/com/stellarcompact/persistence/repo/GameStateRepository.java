package com.stellarcompact.persistence.repo;

import com.fasterxml.jackson.core.type.TypeReference;
import com.stellarcompact.engine.state.ActiveSystem;
import com.stellarcompact.engine.state.Biome;
import com.stellarcompact.engine.state.Building;
import com.stellarcompact.engine.state.BuildingStatus;
import com.stellarcompact.engine.state.BuildingType;
import com.stellarcompact.engine.state.Coords;
import com.stellarcompact.engine.state.Faction;
import com.stellarcompact.engine.state.FactionId;
import com.stellarcompact.engine.state.Fleet;
import com.stellarcompact.engine.state.FleetId;
import com.stellarcompact.engine.state.FleetStance;
import com.stellarcompact.engine.state.GameState;
import com.stellarcompact.engine.state.GameStatus;
import com.stellarcompact.engine.state.MarketOrder;
import com.stellarcompact.engine.state.MarketOrderId;
import com.stellarcompact.engine.state.MarketSide;
import com.stellarcompact.engine.state.OrderStatus;
import com.stellarcompact.engine.state.PhysicalResource;
import com.stellarcompact.engine.state.Planet;
import com.stellarcompact.engine.state.PlanetId;
import com.stellarcompact.engine.state.ResourceBundle;
import com.stellarcompact.engine.state.Route;
import com.stellarcompact.engine.state.RouteId;
import com.stellarcompact.engine.state.RouteKind;
import com.stellarcompact.engine.state.RouteStatus;
import com.stellarcompact.engine.state.Ship;
import com.stellarcompact.engine.state.SystemId;
import com.stellarcompact.engine.state.TechId;
import com.stellarcompact.engine.state.TechProgress;
import com.stellarcompact.engine.state.TechStatus;
import com.stellarcompact.engine.state.Treaty;
import com.stellarcompact.engine.state.TreatyId;
import com.stellarcompact.engine.state.TreatyStatus;
import com.stellarcompact.engine.state.TreatyType;
import com.stellarcompact.engine.state.WarState;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Loads and saves the bounded <em>active set</em> of a match into/out of the
 * engine's in-memory {@link GameState} (board card E5-02; data-model spec;
 * architecture 02 section 7).
 *
 * <h2>Scope: only the active set, never the catalog</h2>
 * Per the scale-discipline rule (rule 3) only systems that have diverged from the
 * procedural baseline are persisted; the billion-star catalog is a pure function of
 * the seed and is never stored. So this repository touches exactly the twelve
 * mutable-state tables and never any star/tile/catalog table. {@link #save} writes
 * the divergence; {@link #load} reconstructs the engine snapshot from it (the seed
 * regenerates everything else).
 *
 * <h2>Why JdbcClient + explicit mapping (not JPA)</h2>
 * Engine state is plain immutable {@code record}s with value-record ids
 * ({@link SystemId} etc.), nested collections and {@link Optional} fields, and the
 * engine module must stay pure/framework-free (no persistence annotations on engine
 * types). JPA wants mutable entities with a no-arg constructor and annotations on the
 * mapped types; mapping the records explicitly with {@link JdbcClient} keeps the
 * engine untouched - the engine stays the single source of truth for shape and the
 * row&lt;-&gt;record translation lives entirely here.
 *
 * <h2>The game UUID vs the engine snapshot</h2>
 * {@link GameState} keys everything by string engine ids and carries the match's
 * {@code gameSeed} ({@code long}); {@code game.id} is a surrogate {@code uuid}. The
 * two are bridged by passing the {@code gameId} explicitly to {@link #save}/{@link
 * #load}. Three snapshot fields with no dedicated {@code game} column - {@code tick},
 * {@code balanceProfileVersion} and the {@code wars} set - ride in
 * {@code game.params_json} so the snapshot round-trips exactly without changing the
 * spec's column set.
 *
 * <h2>save = upsert + prune (so demotion is automatic)</h2>
 * {@link #save} runs in one transaction: it upserts {@code game} + {@code faction},
 * then for the child tables deletes the game's existing rows and re-inserts the
 * snapshot's. The active set is bounded (a few thousand rows), so a delete-then-insert
 * diff is simple and correct, and demotion falls out for free: a system dropped from
 * {@code state.systems()} (the {@code GameState.withoutSystem} seam) is simply not
 * re-inserted, so its {@code active_system}/{@code planet}/{@code building} rows are
 * gone after the save. {@link #insertSystem}/{@link #deleteSystem} additionally expose
 * the explicit per-system promotion/demotion path (E2-05).
 */
@Repository
public class GameStateRepository {

    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {
    };

    private final JdbcClient jdbc;

    public GameStateRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // =========================================================================
    // SAVE
    // =========================================================================

    /**
     * Upsert the bounded active set of {@code state} under {@code gameId} and prune
     * anything no longer present (demoted systems, withdrawn orders, removed fleets).
     * One transaction: the whole snapshot lands atomically or not at all.
     */
    @Transactional
    public void save(UUID gameId, GameState state) {
        if (gameId == null) {
            throw new IllegalArgumentException("save.gameId must be set");
        }
        if (state == null) {
            throw new IllegalArgumentException("save.state must be set");
        }
        upsertGame(gameId, state);
        upsertFactions(gameId, state);
        // Child tables: delete-then-insert diff. Delete leaf-first (FKs), insert root-first.
        deleteChildren(gameId);
        insertSystems(gameId, state);   // active_system -> planet -> building
        insertFleets(gameId, state);    // fleet -> ship
        insertTreaties(gameId, state);
        insertRoutes(gameId, state);
        insertMarketOrders(gameId, state);
        insertTechProgress(state);
    }

    private void upsertGame(UUID gameId, GameState state) {
        // Three slices of engine state have no dedicated spec column: scalars
        // (tick, balanceProfileVersion, a mirror of seed), the wars Set, and each
        // faction's revealedIntel Set (E1-13). They are genuine engine state, so they
        // ride in game.params_json - keeping the spec's column set unchanged while the
        // snapshot still round-trips exactly. (A later card may give wars/intel their
        // own tables; the data-model spec defines neither today.)
        List<Map<String, Object>> wars = new ArrayList<>();
        for (WarState w : state.wars()) {
            wars.add(Map.of("a", w.a().value(), "b", w.b().value(), "since", w.sinceTick()));
        }
        Map<String, List<String>> revealed = new LinkedHashMap<>();
        for (Faction f : state.factions().values()) {
            if (!f.revealedIntel().isEmpty()) {
                revealed.put(f.id().value(),
                        f.revealedIntel().stream().map(FactionId::value).sorted().toList());
            }
        }
        Map<String, Object> paramMap = new LinkedHashMap<>();
        paramMap.put("tick", state.tick());
        paramMap.put("balanceProfileVersion", state.balanceProfileVersion());
        paramMap.put("seed", state.gameSeed());
        paramMap.put("wars", wars);
        paramMap.put("revealedIntel", revealed);
        String params = Json.write(paramMap);
        jdbc.sql("""
                INSERT INTO game (id, seed, status, params_json, balance_profile)
                VALUES (:id, :seed, :status, :params::jsonb, :profile)
                ON CONFLICT (id) DO UPDATE SET
                    seed = EXCLUDED.seed,
                    status = EXCLUDED.status,
                    params_json = EXCLUDED.params_json,
                    balance_profile = EXCLUDED.balance_profile
                """)
                .param("id", gameId)
                .param("seed", state.gameSeed())
                .param("status", state.status().name())
                .param("params", params)
                .param("profile", state.balanceProfileName())
                .update();
    }

    private void upsertFactions(UUID gameId, GameState state) {
        // Prune factions no longer in the snapshot; their children cascade in the DB.
        List<String> keep = state.factions().keySet().stream().map(FactionId::value).toList();
        if (keep.isEmpty()) {
            jdbc.sql("DELETE FROM faction WHERE game_id = :g").param("g", gameId).update();
        } else {
            jdbc.sql("DELETE FROM faction WHERE game_id = :g AND id NOT IN (:keep)")
                    .param("g", gameId).param("keep", keep).update();
        }
        for (Faction f : state.factions().values()) {
            ResourceBundle s = f.stockpiles();
            jdbc.sql("""
                    INSERT INTO faction (id, game_id, name, reputation,
                                         influence, energy, minerals, food, tech, status)
                    VALUES (:id, :g, :name, :rep, :inf, :en, :min, :food, :tech, :status)
                    ON CONFLICT (id) DO UPDATE SET
                        name = EXCLUDED.name,
                        reputation = EXCLUDED.reputation,
                        influence = EXCLUDED.influence,
                        energy = EXCLUDED.energy,
                        minerals = EXCLUDED.minerals,
                        food = EXCLUDED.food,
                        tech = EXCLUDED.tech,
                        status = EXCLUDED.status
                    """)
                    .param("id", f.id().value())
                    .param("g", gameId)
                    .param("name", f.name())
                    .param("rep", f.reputation())
                    .param("inf", s.influence())
                    .param("en", s.energy())
                    .param("min", s.minerals())
                    .param("food", s.food())
                    .param("tech", s.tech())
                    .param("status", "ACTIVE")
                    .update();
        }
    }

    /**
     * Delete every child-table row of the game (everything except {@code game} and
     * {@code faction}, upserted/pruned above). Leaf-first so FKs hold; re-inserted
     * immediately by the {@code insert*} calls in {@link #save}.
     */
    private void deleteChildren(UUID gameId) {
        jdbc.sql("DELETE FROM market_order WHERE game_id = :g").param("g", gameId).update();
        jdbc.sql("DELETE FROM route WHERE game_id = :g").param("g", gameId).update();
        jdbc.sql("DELETE FROM treaty WHERE game_id = :g").param("g", gameId).update();
        jdbc.sql("DELETE FROM fleet WHERE game_id = :g").param("g", gameId).update();
        jdbc.sql("""
                DELETE FROM tech_progress WHERE faction_id IN
                    (SELECT id FROM faction WHERE game_id = :g)
                """).param("g", gameId).update();
        jdbc.sql("DELETE FROM active_system WHERE game_id = :g").param("g", gameId).update();
    }

    private void insertSystems(UUID gameId, GameState state) {
        for (ActiveSystem sys : state.systems().values()) {
            insertSystemRows(gameId, sys);
        }
    }

    /** Insert one active_system + its planet + building rows. Shared by save and {@link #insertSystem}. */
    private void insertSystemRows(UUID gameId, ActiveSystem sys) {
        jdbc.sql("""
                INSERT INTO active_system
                    (id, game_id, seed_coord_x, seed_coord_y, name, owner_faction_id,
                     population, loyalty)
                VALUES (:id, :g, :x, :y, :name, :owner, :pop, :loyalty)
                """)
                .param("id", sys.id().value())
                .param("g", gameId)
                .param("x", sys.coords().x())
                .param("y", sys.coords().y())
                .param("name", sys.name())
                .param("owner", sys.owner().map(FactionId::value).orElse(null))
                .param("pop", sys.population())
                .param("loyalty", sys.loyalty())
                .update();
        for (Planet p : sys.planets()) {
            jdbc.sql("""
                    INSERT INTO planet (id, active_system_id, biome, slots_total, population)
                    VALUES (:id, :sys, :biome, :slots, :pop)
                    """)
                    .param("id", p.id().value())
                    .param("sys", sys.id().value())
                    .param("biome", p.biome().name())
                    .param("slots", p.slotsTotal())
                    .param("pop", p.population())
                    .update();
            for (Building b : p.buildings()) {
                jdbc.sql("""
                        INSERT INTO building (planet_id, slot_index, type, status, progress)
                        VALUES (:planet, :slot, :type, :status, :progress)
                        """)
                        .param("planet", p.id().value())
                        .param("slot", b.slotIndex())
                        .param("type", b.type().name())
                        .param("status", b.status().name())
                        .param("progress", b.progress())
                        .update();
            }
        }
    }

    private void insertFleets(UUID gameId, GameState state) {
        for (Fleet fleet : state.fleets().values()) {
            String pathJson = fleet.enroutePath()
                    .map(path -> Json.write(path.stream().map(SystemId::value).toList()))
                    .orElse(null);
            jdbc.sql("""
                    INSERT INTO fleet (id, game_id, faction_id, location_system_id,
                                       enroute_path_json, eta_ticks, stance)
                    VALUES (:id, :g, :fac, :loc, :path::jsonb, :eta, :stance)
                    """)
                    .param("id", fleet.id().value())
                    .param("g", gameId)
                    .param("fac", fleet.owner().value())
                    .param("loc", fleet.location().map(SystemId::value).orElse(null))
                    .param("path", pathJson)
                    .param("eta", fleet.etaTicks().orElse(null))
                    .param("stance", fleet.stance().name())
                    .update();
            for (Ship ship : fleet.ships()) {
                jdbc.sql("""
                        INSERT INTO ship (fleet_id, spec, count)
                        VALUES (:fleet, :spec, :count)
                        """)
                        .param("fleet", fleet.id().value())
                        .param("spec", ship.spec())
                        .param("count", ship.count())
                        .update();
            }
        }
    }

    private void insertTreaties(UUID gameId, GameState state) {
        for (Treaty t : state.treaties().values()) {
            String parties = Json.write(t.parties().stream().map(FactionId::value).toList());
            jdbc.sql("""
                    INSERT INTO treaty (id, game_id, type, parties_json, terms_json,
                                        signed_tick, expires_tick, status)
                    VALUES (:id, :g, :type, :parties::jsonb, :terms::jsonb,
                            :signed, :expires, :status)
                    """)
                    .param("id", t.id().value())
                    .param("g", gameId)
                    .param("type", t.type().name())
                    .param("parties", parties)
                    .param("terms", Json.write(t.terms()))
                    .param("signed", t.signedTick())
                    .param("expires", t.expiresTick())
                    .param("status", t.status().name())
                    .update();
        }
    }

    private void insertRoutes(UUID gameId, GameState state) {
        for (Route r : state.routes().values()) {
            String resources = Json.write(r.resources().stream().map(PhysicalResource::name).toList());
            jdbc.sql("""
                    INSERT INTO route (id, game_id, owner_faction_id, system_a, system_b,
                                       kind, resources_json, volume, status)
                    VALUES (:id, :g, :owner, :a, :b, :kind, :res::jsonb, :vol, :status)
                    """)
                    .param("id", r.id().value())
                    .param("g", gameId)
                    .param("owner", r.owner().value())
                    .param("a", r.systemA().value())
                    .param("b", r.systemB().value())
                    .param("kind", r.kind().name())
                    .param("res", resources)
                    .param("vol", r.volume())
                    .param("status", r.status().name())
                    .update();
        }
    }

    private void insertMarketOrders(UUID gameId, GameState state) {
        for (MarketOrder o : state.marketOrders().values()) {
            jdbc.sql("""
                    INSERT INTO market_order (id, game_id, hub_system_id, faction_id, side,
                                              resource, qty, price, placed_tick, expires_tick,
                                              addressee, status)
                    VALUES (:id, :g, :hub, :fac, :side, :res, :qty, :price,
                            :placed, :expires, :addr, :status)
                    """)
                    .param("id", o.id().value())
                    .param("g", gameId)
                    .param("hub", o.hubSystem().value())
                    .param("fac", o.faction().value())
                    .param("side", o.side().name())
                    .param("res", o.resource().name())
                    .param("qty", o.quantity())
                    .param("price", o.price())
                    .param("placed", o.placedTick())
                    .param("expires", o.expiresTick())
                    .param("addr", o.addressee().map(FactionId::value).orElse(null))
                    .param("status", o.status().name())
                    .update();
        }
    }

    private void insertTechProgress(GameState state) {
        for (Faction f : state.factions().values()) {
            for (TechProgress tp : f.techProgress().values()) {
                jdbc.sql("""
                        INSERT INTO tech_progress (faction_id, tech_id, status, progress)
                        VALUES (:fac, :tech, :status, :progress)
                        """)
                        .param("fac", f.id().value())
                        .param("tech", tp.techId().value())
                        .param("status", tp.status().name())
                        .param("progress", tp.progress())
                        .update();
            }
        }
    }

    // =========================================================================
    // Explicit per-system promotion / demotion (E2-05)
    // =========================================================================

    /**
     * Persist a single promoted system: insert its {@code active_system} + planet +
     * building rows. The promotion path of E2-05 ({@code PromotionService.promote}
     * produces the {@link ActiveSystem}; this lays it down). The
     * {@code (game_id, seed_coords)} unique key rejects a double-insert.
     */
    @Transactional
    public void insertSystem(UUID gameId, ActiveSystem system) {
        if (gameId == null || system == null) {
            throw new IllegalArgumentException("insertSystem.gameId/system must be set");
        }
        insertSystemRows(gameId, system);
    }

    /**
     * Persist a demotion: delete the active row for {@code systemId}. Planet/building
     * rows cascade ({@code ON DELETE CASCADE}), so the star reverts to pure procedural
     * scenery with no orphan rows (the {@code GameState.withoutSystem} seam). Deleting
     * an absent id is a no-op.
     */
    @Transactional
    public void deleteSystem(UUID gameId, SystemId systemId) {
        if (gameId == null || systemId == null) {
            throw new IllegalArgumentException("deleteSystem.gameId/systemId must be set");
        }
        jdbc.sql("DELETE FROM active_system WHERE game_id = :g AND id = :id")
                .param("g", gameId)
                .param("id", systemId.value())
                .update();
    }

    // =========================================================================
    // LOAD
    // =========================================================================

    /**
     * Reconstruct the engine {@link GameState} snapshot for {@code gameId} from the
     * persisted active set. Returns {@code null} if no such game row exists.
     */
    @Transactional(readOnly = true)
    public GameState load(UUID gameId) {
        if (gameId == null) {
            throw new IllegalArgumentException("load.gameId must be set");
        }
        GameRow game = jdbc.sql("""
                SELECT seed, status, balance_profile, params_json
                FROM game WHERE id = :id
                """)
                .param("id", gameId)
                .query(GameRow.class)
                .optional()
                .orElse(null);
        if (game == null) {
            return null;
        }
        Map<String, Object> params = Json.read(game.params_json(), new TypeReference<>() {
        });
        long tick = ((Number) params.getOrDefault("tick", 0)).longValue();
        int version = ((Number) params.getOrDefault("balanceProfileVersion", 1)).intValue();
        Map<FactionId, Set<FactionId>> revealed = loadRevealedIntel(params);

        return new GameState(
                game.seed(),
                tick,
                GameStatus.valueOf(game.status()),
                game.balance_profile(),
                version,
                loadFactions(gameId, revealed),
                loadSystems(gameId),
                loadFleets(gameId),
                loadTreaties(gameId),
                loadRoutes(gameId),
                loadMarketOrders(gameId),
                loadWars(params));
    }

    private Map<FactionId, Faction> loadFactions(UUID gameId,
                                                 Map<FactionId, Set<FactionId>> revealed) {
        Map<FactionId, Faction> result = new LinkedHashMap<>();
        Map<FactionId, Map<TechId, TechProgress>> tech = loadTechProgress(gameId);
        List<FactionRow> rows = jdbc.sql("""
                SELECT id, name, reputation, influence, energy, minerals, food, tech
                FROM faction WHERE game_id = :g ORDER BY id
                """).param("g", gameId).query(FactionRow.class).list();
        for (FactionRow r : rows) {
            FactionId id = FactionId.of(r.id());
            ResourceBundle stock = new ResourceBundle(
                    r.energy(), r.minerals(), r.food(), r.tech(), r.influence());
            result.put(id, new Faction(id, r.name(), r.reputation(), stock,
                    tech.getOrDefault(id, Map.of()),
                    revealed.getOrDefault(id, Set.of())));
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private Map<FactionId, Set<FactionId>> loadRevealedIntel(Map<String, Object> params) {
        Object raw = params.get("revealedIntel");
        if (!(raw instanceof Map<?, ?> map)) {
            return Map.of();
        }
        Map<FactionId, Set<FactionId>> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : map.entrySet()) {
            FactionId faction = FactionId.of((String) e.getKey());
            Set<FactionId> spies = new LinkedHashSet<>();
            for (Object spy : (List<Object>) e.getValue()) {
                spies.add(FactionId.of((String) spy));
            }
            result.put(faction, spies);
        }
        return result;
    }

    private Map<FactionId, Map<TechId, TechProgress>> loadTechProgress(UUID gameId) {
        Map<FactionId, Map<TechId, TechProgress>> result = new LinkedHashMap<>();
        List<TechRow> rows = jdbc.sql("""
                SELECT tp.faction_id, tp.tech_id, tp.status, tp.progress
                FROM tech_progress tp
                JOIN faction f ON f.id = tp.faction_id
                WHERE f.game_id = :g
                ORDER BY tp.faction_id, tp.tech_id
                """).param("g", gameId).query(TechRow.class).list();
        for (TechRow r : rows) {
            FactionId fac = FactionId.of(r.faction_id());
            TechId tech = TechId.of(r.tech_id());
            result.computeIfAbsent(fac, k -> new LinkedHashMap<>())
                    .put(tech, new TechProgress(tech, TechStatus.valueOf(r.status()), r.progress()));
        }
        return result;
    }

    private Map<SystemId, ActiveSystem> loadSystems(UUID gameId) {
        Map<SystemId, ActiveSystem> result = new LinkedHashMap<>();
        Map<String, List<Planet>> planets = loadPlanets(gameId);
        List<SystemRow> rows = jdbc.sql("""
                SELECT id, seed_coord_x, seed_coord_y, name, owner_faction_id, population, loyalty
                FROM active_system WHERE game_id = :g ORDER BY id
                """).param("g", gameId).query(SystemRow.class).list();
        for (SystemRow r : rows) {
            SystemId id = SystemId.of(r.id());
            result.put(id, new ActiveSystem(
                    id,
                    r.name(),
                    new Coords(r.seed_coord_x(), r.seed_coord_y()),
                    Optional.ofNullable(r.owner_faction_id()).map(FactionId::of),
                    planets.getOrDefault(r.id(), List.of()),
                    r.population(),
                    r.loyalty()));
        }
        return result;
    }

    private Map<String, List<Planet>> loadPlanets(UUID gameId) {
        Map<String, List<Building>> buildings = loadBuildings(gameId);
        Map<String, List<Planet>> result = new LinkedHashMap<>();
        List<PlanetRow> rows = jdbc.sql("""
                SELECT p.id, p.active_system_id, p.biome, p.slots_total, p.population
                FROM planet p
                JOIN active_system s ON s.id = p.active_system_id
                WHERE s.game_id = :g
                ORDER BY p.active_system_id, p.id
                """).param("g", gameId).query(PlanetRow.class).list();
        for (PlanetRow r : rows) {
            Planet planet = new Planet(
                    PlanetId.of(r.id()),
                    Biome.valueOf(r.biome()),
                    r.slots_total(),
                    r.population(),
                    buildings.getOrDefault(r.id(), List.of()));
            result.computeIfAbsent(r.active_system_id(), k -> new ArrayList<>()).add(planet);
        }
        return result;
    }

    private Map<String, List<Building>> loadBuildings(UUID gameId) {
        Map<String, List<Building>> result = new LinkedHashMap<>();
        List<BuildingRow> rows = jdbc.sql("""
                SELECT b.planet_id, b.slot_index, b.type, b.status, b.progress
                FROM building b
                JOIN planet p ON p.id = b.planet_id
                JOIN active_system s ON s.id = p.active_system_id
                WHERE s.game_id = :g
                ORDER BY b.planet_id, b.slot_index
                """).param("g", gameId).query(BuildingRow.class).list();
        for (BuildingRow r : rows) {
            result.computeIfAbsent(r.planet_id(), k -> new ArrayList<>())
                    .add(new Building(r.slot_index(),
                            BuildingType.valueOf(r.type()),
                            BuildingStatus.valueOf(r.status()),
                            r.progress()));
        }
        return result;
    }

    private Map<FleetId, Fleet> loadFleets(UUID gameId) {
        Map<FleetId, Fleet> result = new LinkedHashMap<>();
        Map<String, List<Ship>> ships = loadShips(gameId);
        List<FleetRow> rows = jdbc.sql("""
                SELECT id, faction_id, location_system_id, enroute_path_json, eta_ticks, stance
                FROM fleet WHERE game_id = :g ORDER BY id
                """).param("g", gameId).query(FleetRow.class).list();
        for (FleetRow r : rows) {
            FleetId id = FleetId.of(r.id());
            Optional<List<SystemId>> path = Optional.ofNullable(r.enroute_path_json())
                    .map(json -> Json.read(json, STRING_LIST).stream().map(SystemId::of).toList());
            result.put(id, new Fleet(
                    id,
                    FactionId.of(r.faction_id()),
                    Optional.ofNullable(r.location_system_id()).map(SystemId::of),
                    path,
                    FleetStance.valueOf(r.stance()),
                    ships.getOrDefault(r.id(), List.of()),
                    Optional.ofNullable(r.eta_ticks())));
        }
        return result;
    }

    private Map<String, List<Ship>> loadShips(UUID gameId) {
        Map<String, List<Ship>> result = new LinkedHashMap<>();
        List<ShipRow> rows = jdbc.sql("""
                SELECT sh.fleet_id, sh.spec, sh.count
                FROM ship sh
                JOIN fleet fl ON fl.id = sh.fleet_id
                WHERE fl.game_id = :g
                ORDER BY sh.fleet_id, sh.spec
                """).param("g", gameId).query(ShipRow.class).list();
        for (ShipRow r : rows) {
            result.computeIfAbsent(r.fleet_id(), k -> new ArrayList<>())
                    .add(new Ship(r.spec(), r.count()));
        }
        return result;
    }

    private Map<TreatyId, Treaty> loadTreaties(UUID gameId) {
        Map<TreatyId, Treaty> result = new LinkedHashMap<>();
        List<TreatyRow> rows = jdbc.sql("""
                SELECT id, type, parties_json, terms_json, signed_tick, expires_tick, status
                FROM treaty WHERE game_id = :g ORDER BY id
                """).param("g", gameId).query(TreatyRow.class).list();
        for (TreatyRow r : rows) {
            TreatyId id = TreatyId.of(r.id());
            List<FactionId> parties = Json.read(r.parties_json(), STRING_LIST)
                    .stream().map(FactionId::of).toList();
            Map<String, String> terms = Json.read(r.terms_json(), new TypeReference<>() {
            });
            result.put(id, new Treaty(id, TreatyType.valueOf(r.type()), parties, terms,
                    r.signed_tick(), r.expires_tick(), TreatyStatus.valueOf(r.status())));
        }
        return result;
    }

    private Map<RouteId, Route> loadRoutes(UUID gameId) {
        Map<RouteId, Route> result = new LinkedHashMap<>();
        List<RouteRow> rows = jdbc.sql("""
                SELECT id, owner_faction_id, system_a, system_b, kind, resources_json, volume, status
                FROM route WHERE game_id = :g ORDER BY id
                """).param("g", gameId).query(RouteRow.class).list();
        for (RouteRow r : rows) {
            RouteId id = RouteId.of(r.id());
            List<PhysicalResource> resources = Json.read(r.resources_json(), STRING_LIST)
                    .stream().map(PhysicalResource::valueOf).toList();
            result.put(id, new Route(id, FactionId.of(r.owner_faction_id()),
                    SystemId.of(r.system_a()), SystemId.of(r.system_b()),
                    RouteKind.valueOf(r.kind()), resources, r.volume(),
                    RouteStatus.valueOf(r.status())));
        }
        return result;
    }

    private Map<MarketOrderId, MarketOrder> loadMarketOrders(UUID gameId) {
        Map<MarketOrderId, MarketOrder> result = new LinkedHashMap<>();
        List<OrderRow> rows = jdbc.sql("""
                SELECT id, hub_system_id, faction_id, side, resource, qty, price,
                       placed_tick, expires_tick, addressee, status
                FROM market_order WHERE game_id = :g ORDER BY id
                """).param("g", gameId).query(OrderRow.class).list();
        for (OrderRow r : rows) {
            MarketOrderId id = MarketOrderId.of(r.id());
            result.put(id, new MarketOrder(id,
                    SystemId.of(r.hub_system_id()),
                    FactionId.of(r.faction_id()),
                    MarketSide.valueOf(r.side()),
                    PhysicalResource.valueOf(r.resource()),
                    r.qty(), r.price(), r.placed_tick(), r.expires_tick(),
                    Optional.ofNullable(r.addressee()).map(FactionId::of),
                    OrderStatus.valueOf(r.status())));
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private Set<WarState> loadWars(Map<String, Object> params) {
        Object raw = params.get("wars");
        if (!(raw instanceof List<?> list)) {
            return Set.of();
        }
        Set<WarState> wars = new LinkedHashSet<>();
        for (Object o : list) {
            Map<String, Object> m = (Map<String, Object>) o;
            FactionId a = FactionId.of((String) m.get("a"));
            FactionId b = FactionId.of((String) m.get("b"));
            long since = ((Number) m.get("since")).longValue();
            wars.add(WarState.between(a, b, since));
        }
        return wars;
    }

    // =========================================================================
    // Row record carriers (JdbcClient maps SELECT columns into these by name)
    // =========================================================================

    record GameRow(long seed, String status, String balance_profile, String params_json) {
    }

    record FactionRow(String id, String name, double reputation, double influence,
                      double energy, double minerals, double food, double tech) {
    }

    record TechRow(String faction_id, String tech_id, String status, int progress) {
    }

    record SystemRow(String id, long seed_coord_x, long seed_coord_y, String name,
                     String owner_faction_id, long population, double loyalty) {
    }

    record PlanetRow(String id, String active_system_id, String biome, int slots_total, long population) {
    }

    record BuildingRow(String planet_id, int slot_index, String type, String status, int progress) {
    }

    record FleetRow(String id, String faction_id, String location_system_id,
                    String enroute_path_json, Integer eta_ticks, String stance) {
    }

    record ShipRow(String fleet_id, String spec, int count) {
    }

    record TreatyRow(String id, String type, String parties_json, String terms_json,
                     long signed_tick, long expires_tick, String status) {
    }

    record RouteRow(String id, String owner_faction_id, String system_a, String system_b,
                    String kind, String resources_json, double volume, String status) {
    }

    record OrderRow(String id, String hub_system_id, String faction_id, String side,
                    String resource, double qty, double price, long placed_tick,
                    long expires_tick, String addressee, String status) {
    }
}
