package com.stellarcompact.orchestrator.match;

import com.stellarcompact.engine.config.BalanceProfile;
import com.stellarcompact.engine.config.BalanceProfileLoader;
import com.stellarcompact.engine.map.LaneNetwork;
import com.stellarcompact.engine.resolve.PublicEvent;
import com.stellarcompact.engine.state.ActiveSystem;
import com.stellarcompact.engine.state.Biome;
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
import com.stellarcompact.engine.state.WarState;
import com.stellarcompact.orchestrator.sovereign.AggressiveScriptedSovereign;
import com.stellarcompact.orchestrator.sovereign.ScriptedSovereign;
import com.stellarcompact.orchestrator.sovereign.Sovereign;
import com.stellarcompact.orchestrator.sovereign.SystemAdjacency;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A headless mixed-bot match (E10-03) that proves the {@link AggressiveScriptedSovereign}
 * drives the previously-unexercised combat / diplomacy / victory engine paths the
 * 3-agent-sim flagged (game-design 09 finding F5: every peaceful scripted match ends in
 * {@code TICK_LIMIT} with zero public events).
 *
 * <p><b>Scenario.</b> Two factions one lane hop apart on a tiny galaxy:
 * <ul>
 *   <li><b>aggressor</b> - an {@link AggressiveScriptedSovereign} with a strong corvette
 *       battlegroup parked at its home. It declares war on its revealed rival and assaults
 *       the rival system.</li>
 *   <li><b>victim</b> - a peaceful {@link ScriptedSovereign} (the E10-01 economy bot) with
 *       only a token scout garrison. It never makes war.</li>
 * </ul>
 * Because combat has no fleet-positioning gate yet (E1-10 TODO) the aggressor's assault
 * needs only a recorded war state and an owned fleet; the war is declared on the first tick
 * and the assault lands on the next (the validator gates the same-tick Attack as
 * {@code NOT_AT_WAR}, the orchestrator drops it, then the next tick it validates against the
 * now-recorded war).
 *
 * <p><b>What it asserts.</b> The public-event stream contains {@code WarDeclared},
 * {@code BattleResolved} and {@code SystemCaptured}; the capture of the victim's only system
 * eliminates it ({@code FactionEliminated}) and hands the aggressor every habitable system,
 * firing the active DOMINATION condition ({@code VictoryAchieved}, outcome {@code VICTORY}).
 * Then it proves the run is fully deterministic by replaying the recorded {@code (seed,
 * action log)} and matching every per-tick hash, mirroring {@code ThreeAgentHourMatchTest}.
 */
class AggressiveMatchTest {

    private static final long SEED = 0xC0FFEE_BA771E5L;

    private static final FactionId AGGRESSOR = new FactionId("aggressor");
    private static final FactionId VICTIM = new FactionId("victim");

    private static final SystemId AGGRESSOR_HOME = new SystemId("sysAggressorHome");
    private static final SystemId VICTIM_HOME = new SystemId("sysVictimHome");
    private static final PlanetId AGGRESSOR_PLANET = new PlanetId("planetAggressor");
    private static final PlanetId VICTIM_PLANET = new PlanetId("planetVictim");
    private static final FleetId AGGRESSOR_FLEET = new FleetId("fleetAggressor");
    private static final FleetId VICTIM_FLEET = new FleetId("fleetVictim");

    /** Generous budget; the war concludes the match well inside this. */
    private static final int MAX_TICKS = 30;

    @Test
    void aggressorDrivesWarBattleCaptureAndAVictoryOutcome() {
        BalanceProfile profile = profile();
        LaneNetwork lanes = lanes();
        SystemAdjacency adjacency = adjacency();

        List<Sovereign> bots = List.of(
                new AggressiveScriptedSovereign(AGGRESSOR),
                new ScriptedSovereign(VICTIM));

        MatchRecord record = HeadlessMatchRunner.run(
                initialState(), bots, profile, lanes, adjacency, MAX_TICKS);

        // The previously-unexercised public-event paths now fire.
        Map<String, Long> events = eventCounts(record);
        assertTrue(events.getOrDefault("WarDeclared", 0L) >= 1,
                "the aggressor must declare war (event was never seen in peaceful matches): " + events);
        assertTrue(events.getOrDefault("BattleResolved", 0L) >= 1,
                "an assault must resolve into a battle: " + events);
        assertTrue(events.getOrDefault("SystemCaptured", 0L) >= 1,
                "the assault must capture the victim system: " + events);
        assertTrue(events.getOrDefault("FactionEliminated", 0L) >= 1,
                "losing its only system eliminates the victim: " + events);

        // Capturing every habitable system fires the active DOMINATION victory.
        assertTrue(events.getOrDefault("VictoryAchieved", 0L) >= 1,
                "domination over all systems must fire a victory: " + events);
        assertEquals(MatchRecord.Outcome.VICTORY, record.outcome(),
                "the match must conclude with a VICTORY, not the tick limit");
        assertEquals(GameStatus.CONCLUDED, record.finalState().status(),
                "a fired victory transitions the match to CONCLUDED");

        // The aggressor ends up owning the captured victim system.
        ActiveSystem capturedSystem = record.finalState().systems().get(VICTIM_HOME);
        assertNotNull(capturedSystem, "the captured system remains an active system");
        assertEquals(Optional.of(AGGRESSOR), capturedSystem.owner(),
                "the victim home is now owned by the aggressor");

        // The WarDeclared event names the right belligerents (aggressor -> victim).
        PublicEvent.WarDeclared war = record.eventLog().stream()
                .filter(e -> e instanceof PublicEvent.WarDeclared)
                .map(e -> (PublicEvent.WarDeclared) e)
                .findFirst().orElseThrow();
        assertEquals(AGGRESSOR, war.declarer(), "the aggressor is the war declarer");
        assertEquals(VICTIM, war.target(), "the war is declared on the victim");
    }

    @Test
    void theMatchIsFullyDeterministicViaReplay() {
        BalanceProfile profile = profile();
        LaneNetwork lanes = lanes();
        SystemAdjacency adjacency = adjacency();

        List<Sovereign> bots = List.of(
                new AggressiveScriptedSovereign(AGGRESSOR),
                new ScriptedSovereign(VICTIM));

        MatchRecord record = HeadlessMatchRunner.run(
                initialState(), bots, profile, lanes, adjacency, MAX_TICKS);

        // Replay the recorded (seed, action log) and assert it reproduces every tick hash -
        // the same determinism proof ThreeAgentHourMatchTest uses, now spanning combat.
        List<String> replayed = HeadlessMatchRunner.replay(record, initialState(), profile, lanes);
        assertEquals(record.perTickHashes(), replayed,
                "replay of the recorded (seed, action log) must reproduce every tick hash, incl. combat");

        // A second independent live run yields an identical action log + hash sequence.
        MatchRecord again = HeadlessMatchRunner.run(
                initialState(), List.of(new AggressiveScriptedSovereign(AGGRESSOR), new ScriptedSovereign(VICTIM)),
                profile, lanes, adjacency, MAX_TICKS);
        assertEquals(record.perTickHashes(), again.perTickHashes(),
                "two live runs of the same scenario must be byte-for-byte reproducible");
    }

    // ===== scenario (a pure function of SEED) =================================

    private static Map<String, Long> eventCounts(MatchRecord record) {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (PublicEvent e : record.eventLog()) {
            counts.merge(e.type(), 1L, Long::sum);
        }
        return counts;
    }

    /** The two homes joined by one lane (so each sees the other as a sensor-revealed neighbour). */
    private static LaneNetwork lanes() {
        return LaneNetwork.builder().addLane(AGGRESSOR_HOME, VICTIM_HOME, 1).build();
    }

    private static SystemAdjacency adjacency() {
        return SystemAdjacency.of(Map.of(
                AGGRESSOR_HOME, List.of(VICTIM_HOME),
                VICTIM_HOME, List.of(AGGRESSOR_HOME)));
    }

    private static ResourceBundle rich() {
        return new ResourceBundle(5000, 5000, 5000, 5000, 100);
    }

    private static Faction faction(FactionId id) {
        return new Faction(id, "Sovereign-" + id.value(), 0.0, rich(),
                Map.<TechId, TechProgress>of());
    }

    private static Planet emptyPlanet(PlanetId id, int slots) {
        return new Planet(id, Biome.TERRAN, slots, 100, List.of());
    }

    private static ActiveSystem ownedSystem(SystemId id, FactionId owner, List<Planet> planets) {
        return new ActiveSystem(id, "name-" + id.value(), new Coords(id.value().length(), 3),
                Optional.of(owner), planets, 100, 1.0);
    }

    private static Fleet combatFleet(FleetId id, FactionId owner, SystemId at, String spec, int count) {
        return new Fleet(id, owner, Optional.of(at), Optional.empty(),
                FleetStance.AGGRESSIVE, List.of(new Ship(spec, count)));
    }

    /**
     * The starting snapshot: aggressor home (3-slot planet, a strong corvette battlegroup),
     * victim home (2-slot planet, a token scout garrison), one lane between them. RUNNING on
     * the shipped {@code small-default} profile (DOMINATION active at 60%).
     */
    private static GameState initialState() {
        Map<FactionId, Faction> factions = new LinkedHashMap<>();
        factions.put(AGGRESSOR, faction(AGGRESSOR));
        factions.put(VICTIM, faction(VICTIM));

        Map<SystemId, ActiveSystem> systems = new LinkedHashMap<>();
        systems.put(AGGRESSOR_HOME, ownedSystem(AGGRESSOR_HOME, AGGRESSOR, List.of(emptyPlanet(AGGRESSOR_PLANET, 3))));
        systems.put(VICTIM_HOME, ownedSystem(VICTIM_HOME, VICTIM, List.of(emptyPlanet(VICTIM_PLANET, 2))));

        Map<FleetId, Fleet> fleets = new LinkedHashMap<>();
        // A strong corvette stack vs a lone scout: the assault overwhelmingly wins (the
        // seeded variance band still applies, but the power gap dwarfs it), so the capture
        // is deterministic for this seed.
        fleets.put(AGGRESSOR_FLEET, combatFleet(AGGRESSOR_FLEET, AGGRESSOR, AGGRESSOR_HOME, "corvette", 12));
        fleets.put(VICTIM_FLEET, combatFleet(VICTIM_FLEET, VICTIM, VICTIM_HOME, "scout", 1));

        return new GameState(SEED, 0L, GameStatus.RUNNING, "small-default", 1,
                factions, systems, fleets,
                Map.<TreatyId, Treaty>of(), Map.<RouteId, Route>of(),
                Map.<MarketOrderId, MarketOrder>of(), Set.<WarState>of());
    }

    /** The shipped small-default balance profile from the test classpath (rule 6). */
    private static BalanceProfile profile() {
        try (InputStream in = AggressiveMatchTest.class.getResourceAsStream("/balance/small-default.json")) {
            if (in == null) {
                throw new IllegalStateException("missing /balance/small-default.json on the test classpath");
            }
            return BalanceProfileLoader.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
