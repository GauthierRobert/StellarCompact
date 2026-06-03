package com.stellarcompact.orchestrator.sovereign;

import com.stellarcompact.engine.action.Action;
import com.stellarcompact.engine.action.AgentResponse;
import com.stellarcompact.engine.action.AttackTarget;
import com.stellarcompact.engine.config.BalanceProfile;
import com.stellarcompact.engine.config.BalanceProfileLoader;
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
import com.stellarcompact.engine.state.Planet;
import com.stellarcompact.engine.state.PlanetId;
import com.stellarcompact.engine.state.ResourceBundle;
import com.stellarcompact.engine.state.Route;
import com.stellarcompact.engine.state.RouteId;
import com.stellarcompact.engine.state.Ship;
import com.stellarcompact.engine.state.SystemId;
import com.stellarcompact.engine.state.TechId;
import com.stellarcompact.engine.state.TechProgress;
import com.stellarcompact.engine.state.Treaty;
import com.stellarcompact.engine.state.TreatyId;
import com.stellarcompact.engine.state.TreatyStatus;
import com.stellarcompact.engine.state.TreatyType;
import com.stellarcompact.engine.state.WarState;
import com.stellarcompact.engine.validation.ActionValidator;
import com.stellarcompact.engine.validation.ValidationResult;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link AggressiveScriptedSovereign} (E10-03). The bot is exercised
 * against fog-filtered {@link WorldView}s built by the production {@link WorldViewBuilder}
 * over hand-authored, deterministic states built inline here (this test does NOT touch the
 * shared {@code SovereignFixtures}, owned by parallel cards). Assertions cover: the
 * escalation ladder emits {@code DeclareWar} + {@code Attack} against a revealed rival,
 * every action the engine validator accepts is valid, treaty legality suppresses the war,
 * determinism (same view yields the same response), and the explicit-Hold fallback.
 */
class AggressiveScriptedSovereignTest {

    private static final FactionId ALPHA = new FactionId("alpha");
    private static final FactionId ENEMY = new FactionId("enemy");

    private static final SystemId ALPHA_HOME = new SystemId("sysAlphaHome");
    private static final SystemId ENEMY_SYS = new SystemId("sysEnemy");
    private static final PlanetId ALPHA_PLANET = new PlanetId("planetAlpha");
    private static final PlanetId ENEMY_PLANET = new PlanetId("planetEnemy");
    private static final FleetId ALPHA_FLEET = new FleetId("fleetAlpha");
    private static final FleetId ENEMY_FLEET = new FleetId("fleetEnemy");
    private static final TreatyId NAP = new TreatyId("treatyNap");

    /** Lane adjacency: ALPHA_HOME and ENEMY_SYS are one hop apart (so ENEMY is sensor-revealed). */
    private static SystemAdjacency adjacency() {
        return SystemAdjacency.of(Map.of(
                ALPHA_HOME, List.of(ENEMY_SYS),
                ENEMY_SYS, List.of(ALPHA_HOME)));
    }

    // ===== assertions =========================================================

    @Test
    void escalationLadderDeclaresWarAndAttacksARevealedRival() {
        GameState state = warScenario(Map.of());
        AggressiveScriptedSovereign bot = new AggressiveScriptedSovereign(ALPHA);

        WorldView view = WorldViewBuilder.build(state, adjacency(), ALPHA);
        AgentResponse response = bot.decide(view);

        assertTrue(response.messages().isEmpty(), "the aggressive bot is silent in negotiation");

        Action war = firstOf(response, Action.DeclareWar.class);
        assertNotNull(war, "bot must declare war on the revealed rival");
        assertEquals(ENEMY, ((Action.DeclareWar) war).target(), "war target is the revealed enemy");

        Action attack = firstOf(response, Action.Attack.class);
        assertNotNull(attack, "bot must order an assault on the enemy system");
        AttackTarget target = ((Action.Attack) attack).target();
        assertInstanceOf(AttackTarget.OnSystem.class, target, "assault targets the enemy SYSTEM");
        assertEquals(ENEMY_SYS, ((AttackTarget.OnSystem) target).system(), "assaults the revealed enemy system");
        assertEquals(ALPHA_FLEET, ((Action.Attack) attack).fleet(), "assaults with the bot own idle fleet");
    }

    @Test
    void declareWarValidatesAndAttackIsGatedUntilWarExists() {
        // Pre-war snapshot: the validator accepts DeclareWar but rejects the same-tick
        // Attack as NOT_AT_WAR (war is recorded by resolution, not before). AggressiveMatchTest
        // proves the next-tick Attack then validates; here we assert the per-action gate.
        GameState state = warScenario(Map.of());
        BalanceProfile profile = profile();
        AggressiveScriptedSovereign bot = new AggressiveScriptedSovereign(ALPHA);
        WorldView view = WorldViewBuilder.build(state, adjacency(), ALPHA);
        AgentResponse response = bot.decide(view);

        for (Action action : response.actions()) {
            ValidationResult result = ActionValidator.validate(state, ALPHA, action, profile);
            if (action instanceof Action.Attack) {
                assertFalse(result.isValid(),
                        "pre-war, the same-tick Attack must be rejected (NOT_AT_WAR) and dropped");
            } else {
                assertTrue(result.isValid(),
                        "non-attack ladder action must validate pre-war: " + action.type() + " " + result);
            }
        }
    }

    @Test
    void attackValidatesOnceTheWarStateExists() {
        // With the war already recorded, the Attack passes the validator (war-state gate met).
        GameState state = warScenario(Map.of()).withWar(ALPHA, ENEMY, 0L);
        BalanceProfile profile = profile();
        AggressiveScriptedSovereign bot = new AggressiveScriptedSovereign(ALPHA);
        WorldView view = WorldViewBuilder.build(state, adjacency(), ALPHA);
        AgentResponse response = bot.decide(view);

        Action attack = firstOf(response, Action.Attack.class);
        assertNotNull(attack, "bot still orders the assault while the enemy holds the system");
        ValidationResult result = ActionValidator.validate(state, ALPHA, attack, profile);
        assertTrue(result.isValid(), "with a war state recorded the Attack must validate: " + result);
    }

    @Test
    void anActiveNonAggressionTreatySuppressesWarAndAttack() {
        // ALPHA and ENEMY hold an ACTIVE NonAggression treaty: the bot must NOT declare war
        // or attack (the validator would reject both as TREATY_FORBIDS).
        Treaty nap = new Treaty(NAP, TreatyType.NON_AGGRESSION, List.of(ALPHA, ENEMY), Map.of(),
                0L, 1000L, TreatyStatus.ACTIVE);
        GameState state = warScenario(Map.of(NAP, nap));
        AggressiveScriptedSovereign bot = new AggressiveScriptedSovereign(ALPHA);
        WorldView view = WorldViewBuilder.build(state, adjacency(), ALPHA);
        AgentResponse response = bot.decide(view);

        assertNull(firstOf(response, Action.DeclareWar.class),
                "a binding NonAggression treaty must suppress the war declaration");
        assertNull(firstOf(response, Action.Attack.class),
                "no attack against a NonAggression partner");
    }

    @Test
    void everyActionTheValidatorAcceptsIsValidAcrossAssortedStates() {
        BalanceProfile profile = profile();
        // The pre-war state and the at-war state both must yield only valid (or knowingly
        // gated) actions; the only gated case is the pre-war Attack (NOT_AT_WAR).
        for (GameState state : List.of(warScenario(Map.of()), warScenario(Map.of()).withWar(ALPHA, ENEMY, 0L))) {
            AggressiveScriptedSovereign bot = new AggressiveScriptedSovereign(ALPHA);
            WorldView view = WorldViewBuilder.build(state, adjacency(), ALPHA);
            AgentResponse response = bot.decide(view);
            boolean atWar = state.atWar(ALPHA, ENEMY);
            for (Action action : response.actions()) {
                ValidationResult result = ActionValidator.validate(state, ALPHA, action, profile);
                boolean expectedValid = !(action instanceof Action.Attack) || atWar;
                assertEquals(expectedValid, result.isValid(),
                        "validity mismatch for " + action.type() + " (atWar=" + atWar + "): " + result);
            }
        }
    }

    @Test
    void sameInputProducesSameOutputAcrossRuns() {
        GameState state = warScenario(Map.of());
        WorldView view = WorldViewBuilder.build(state, adjacency(), ALPHA);
        AgentResponse a = new AggressiveScriptedSovereign(ALPHA).decide(view);
        AgentResponse b = new AggressiveScriptedSovereign(ALPHA).decide(view);
        assertEquals(actionTypes(a), actionTypes(b), "deterministic: identical action sequence");
    }

    @Test
    void holdsWhenThereIsNoRivalAndNothingToBuild() {
        // ALPHA poor (below build floor), no enemy neighbour revealed, no idle fleet -> Hold.
        Map<FactionId, Faction> factions = Map.of(ALPHA, faction(ALPHA, poor()));
        Map<SystemId, ActiveSystem> systems = Map.of(
                ALPHA_HOME, ownedSystem(ALPHA_HOME, ALPHA, List.of(fullPlanet(ALPHA_PLANET))));
        GameState state = gameState(factions, systems, Map.of(), Map.of());

        AggressiveScriptedSovereign bot = new AggressiveScriptedSovereign(ALPHA);
        WorldView view = WorldViewBuilder.build(state, SystemAdjacency.NONE, ALPHA);
        AgentResponse response = bot.decide(view);

        assertEquals(1, response.actions().size(), "exactly one action when idle");
        assertInstanceOf(Action.Hold.class, response.actions().get(0), "idle bot emits the explicit Hold");
    }

    @Test
    void aRichBotWithAShipyardBuildsAWarship() {
        // ALPHA rich, owns a system WITH an active Shipyard (and a free slot) -> the
        // BuildFleet rung fires for a corvette.
        Planet withYard = new Planet(ALPHA_PLANET, Biome.TERRAN, 2, 100,
                List.of(new Building(0, BuildingType.SHIPYARD, BuildingStatus.ACTIVE, 0)));
        Map<FactionId, Faction> factions = Map.of(ALPHA, faction(ALPHA, rich()));
        Map<SystemId, ActiveSystem> systems = Map.of(
                ALPHA_HOME, ownedSystem(ALPHA_HOME, ALPHA, List.of(withYard)));
        GameState state = gameState(factions, systems, Map.of(), Map.of());

        AggressiveScriptedSovereign bot = new AggressiveScriptedSovereign(ALPHA);
        WorldView view = WorldViewBuilder.build(state, SystemAdjacency.NONE, ALPHA);
        AgentResponse response = bot.decide(view);

        Action buildFleet = firstOf(response, Action.BuildFleet.class);
        assertNotNull(buildFleet, "a shipyard owner must build a warship");
        assertEquals(AggressiveScriptedSovereign.WARSHIP_SPEC, ((Action.BuildFleet) buildFleet).shipSpec());
    }

    // ===== helpers ============================================================

    private static List<String> actionTypes(AgentResponse r) {
        return r.actions().stream().map(Action::type).toList();
    }

    private static Action firstOf(AgentResponse r, Class<? extends Action> type) {
        for (Action a : r.actions()) {
            if (type.isInstance(a)) {
                return a;
            }
        }
        return null;
    }

    /**
     * ALPHA (rich, with an idle combat fleet of corvettes at its home) faces ENEMY, which
     * owns ENEMY_SYS one lane hop away (so the fog builder reveals it as an enemy-owned
     * neighbour). Optional extra treaties are merged in. ENEMY holds a thin garrison so the
     * assault can resolve to a capture in the match test.
     */
    private static GameState warScenario(Map<TreatyId, Treaty> extraTreaties) {
        Map<FactionId, Faction> factions = Map.of(
                ALPHA, faction(ALPHA, rich()),
                ENEMY, faction(ENEMY, rich()));
        Map<SystemId, ActiveSystem> systems = Map.of(
                ALPHA_HOME, ownedSystem(ALPHA_HOME, ALPHA, List.of(emptyPlanet(ALPHA_PLANET, 3))),
                ENEMY_SYS, ownedSystem(ENEMY_SYS, ENEMY, List.of(emptyPlanet(ENEMY_PLANET, 2))));
        Map<FleetId, Fleet> fleets = Map.of(
                ALPHA_FLEET, combatFleet(ALPHA_FLEET, ALPHA, ALPHA_HOME, "corvette", 8),
                ENEMY_FLEET, combatFleet(ENEMY_FLEET, ENEMY, ENEMY_SYS, "scout", 1));
        return gameState(factions, systems, fleets, extraTreaties);
    }

    private static GameState gameState(Map<FactionId, Faction> factions,
                                       Map<SystemId, ActiveSystem> systems,
                                       Map<FleetId, Fleet> fleets,
                                       Map<TreatyId, Treaty> treaties) {
        return new GameState(7L, 0L, GameStatus.RUNNING, "small-default", 1,
                factions, systems, fleets, treaties,
                Map.<RouteId, Route>of(), Map.<MarketOrderId, MarketOrder>of(),
                java.util.Set.<WarState>of());
    }

    private static ResourceBundle rich() {
        return new ResourceBundle(5000, 5000, 5000, 5000, 100);
    }

    private static ResourceBundle poor() {
        return new ResourceBundle(0, 0, 0, 0, 0);
    }

    private static Faction faction(FactionId id, ResourceBundle stockpiles) {
        return new Faction(id, "F-" + id.value(), 0.0, stockpiles, Map.<TechId, TechProgress>of());
    }

    private static Planet emptyPlanet(PlanetId id, int slots) {
        return new Planet(id, Biome.TERRAN, slots, 100, List.of());
    }

    private static Planet fullPlanet(PlanetId id) {
        return new Planet(id, Biome.TERRAN, 1, 100,
                List.of(new Building(0, BuildingType.MINE, BuildingStatus.ACTIVE, 0)));
    }

    private static ActiveSystem ownedSystem(SystemId id, FactionId owner, List<Planet> planets) {
        return new ActiveSystem(id, "name-" + id.value(), new Coords(0, 0),
                Optional.of(owner), planets, 100, 1.0);
    }

    private static Fleet combatFleet(FleetId id, FactionId owner, SystemId at, String spec, int count) {
        return new Fleet(id, owner, Optional.of(at), Optional.empty(),
                FleetStance.AGGRESSIVE, List.of(new Ship(spec, count)));
    }

    /** The shipped small-default profile (corvette stats, combat numbers) from the classpath. */
    private static BalanceProfile profile() {
        try (InputStream in = AggressiveScriptedSovereignTest.class
                .getResourceAsStream("/balance/small-default.json")) {
            if (in == null) {
                throw new IllegalStateException("missing /balance/small-default.json on the test classpath");
            }
            return BalanceProfileLoader.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
