package com.stellarcompact.api.match;

import com.stellarcompact.engine.config.BalanceProfile;
import com.stellarcompact.engine.map.LaneNetwork;
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
import com.stellarcompact.engine.state.Planet;
import com.stellarcompact.engine.state.PlanetId;
import com.stellarcompact.engine.state.ResourceBundle;
import com.stellarcompact.engine.state.Ship;
import com.stellarcompact.engine.state.SystemId;
import com.stellarcompact.orchestrator.sovereign.SystemAdjacency;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Builds a small, deterministic starting {@link GameState} (plus the matching
 * {@link LaneNetwork} for validation and {@link SystemAdjacency} for fog reveal) for a
 * newly-created match (board card E6-01). A pure function of {@code (seed, factionCount,
 * balanceProfileName)} - no I/O, no clock, no randomness - so the same create request
 * always seeds the same galaxy (principle 1: the api picks the seed once; the engine
 * never sees an unseeded source).
 *
 * <p><b>Scope.</b> This is the api module's compact bootstrap, mirroring the orchestrator
 * test scenario's shape (E3-03 {@code GalaxyMatchScenario}) without pulling the full
 * galaxy generator: each faction gets one home system (a 3-slot Terran planet) with a
 * parked scout fleet and rich stockpiles, plus one neutral system one lane hop away to
 * explore toward. A later card can swap this for the galaxy-generated promotion path
 * behind the same {@link MatchService} seam.
 *
 * <p>The match is built in {@link GameStatus#CREATED}; the service transitions it through
 * the guarded lifecycle (CREATED -&gt; LOBBY -&gt; RUNNING) on start.
 */
final class MatchBootstrap {

    /** Rich enough that the scripted bots' Mine build-floor clears comfortably. */
    private static final ResourceBundle RICH = new ResourceBundle(5000, 5000, 5000, 5000, 100);

    private MatchBootstrap() {
    }

    /** The seated faction ids for {@code factionCount} seats: {@code faction-1..N}. */
    static List<FactionId> factionIds(int factionCount) {
        List<FactionId> ids = new ArrayList<>(factionCount);
        for (int i = 1; i <= factionCount; i++) {
            ids.add(new FactionId("faction-" + i));
        }
        return List.copyOf(ids);
    }

    /**
     * The starting snapshot in {@link GameStatus#CREATED}. Each faction {@code k} owns
     * {@code home-k} (a 3-slot planet) with {@code fleet-k} parked there, and there is a
     * neutral {@code neutral-k} one lane hop away.
     */
    static GameState initialState(long seed, int factionCount, BalanceProfile profile) {
        List<FactionId> ids = factionIds(factionCount);

        Map<FactionId, Faction> factions = new LinkedHashMap<>();
        Map<SystemId, ActiveSystem> systems = new LinkedHashMap<>();
        Map<FleetId, Fleet> fleets = new LinkedHashMap<>();

        for (int i = 0; i < ids.size(); i++) {
            int k = i + 1;
            FactionId fid = ids.get(i);
            factions.put(fid, new Faction(fid, "Sovereign-" + k, 0.0, RICH,
                    Map.of(), Set.of()));

            SystemId home = homeOf(k);
            Planet planet = new Planet(new PlanetId("planet-" + k), Biome.TERRAN, 3, 100, List.of());
            systems.put(home, new ActiveSystem(home, "home-" + k, new Coords(k * 10L, 0),
                    Optional.of(fid), List.of(planet), 100, 1.0));

            SystemId neutral = neutralOf(k);
            systems.put(neutral, new ActiveSystem(neutral, "neutral-" + k,
                    new Coords(k * 10L, 5), Optional.empty(), List.of(), 0, 1.0));

            FleetId fleet = new FleetId("fleet-" + k);
            fleets.put(fleet, new Fleet(fleet, fid, Optional.of(home), Optional.empty(),
                    FleetStance.BALANCED, List.of(new Ship("scout", 1))));
        }

        return new GameState(seed, 0L, GameStatus.CREATED, profile.name(), profile.version(),
                factions, systems, fleets, Map.of(), Map.of(), Map.of(), Set.of());
    }

    /**
     * The lane network for the bootstrap: each home is joined to its own neutral system by
     * a single one-tick lane (so the bots' Explore branch is reachable and validatable).
     */
    static LaneNetwork laneNetwork(int factionCount) {
        LaneNetwork.Builder builder = LaneNetwork.builder();
        for (int k = 1; k <= factionCount; k++) {
            builder.addLane(homeOf(k), neutralOf(k), 1);
        }
        return builder.build();
    }

    /** The fog adjacency mirroring {@link #laneNetwork(int)} (sensor reveal across lanes). */
    static SystemAdjacency adjacency(int factionCount) {
        Map<SystemId, List<SystemId>> adj = new LinkedHashMap<>();
        for (int k = 1; k <= factionCount; k++) {
            adj.put(homeOf(k), List.of(neutralOf(k)));
            adj.put(neutralOf(k), List.of(homeOf(k)));
        }
        return SystemAdjacency.of(adj);
    }

    private static SystemId homeOf(int k) {
        return new SystemId("home-" + k);
    }

    private static SystemId neutralOf(int k) {
        return new SystemId("neutral-" + k);
    }
}
