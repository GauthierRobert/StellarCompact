package com.stellarcompact.orchestrator.sovereign;

import com.stellarcompact.engine.state.ActiveSystem;
import com.stellarcompact.engine.state.FactionId;
import com.stellarcompact.engine.state.GameState;
import com.stellarcompact.engine.state.SystemId;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * TEST-ONLY thin adapter over the production {@link WorldViewBuilder} (card E3-02).
 *
 * <p>The E3-01 {@link ScriptedSovereign} tests were written against a simpler fog
 * model where <em>every</em> non-owned active system is visible as a neighbour (so
 * the bot's Explore heuristic could see a neutral system). To keep those tests
 * exercising the <b>real</b> builder rather than a parallel stand-in, this helper
 * now delegates to {@link WorldViewBuilder#build(GameState, SystemAdjacency, FactionId)}
 * with a fully-connected {@link SystemAdjacency} over all systems in {@code state} -
 * i.e. it asserts "every other system is one lane hop away", reproducing the old
 * all-neighbours-visible behaviour through the authoritative fog filter.
 *
 * <p>The dedicated fog-of-war leakage tests ({@code WorldViewBuilderTest}) call the
 * builder directly with explicit, sparse adjacencies to prove default-deny; this
 * adapter is only a convenience for the pre-existing bot tests.
 */
final class WorldViewProjection {

    private WorldViewProjection() {
    }

    /** Project {@code state} into the per-faction view {@code self} would perceive. */
    static WorldView project(GameState state, FactionId self) {
        return WorldViewBuilder.build(state, fullyConnected(state), self);
    }

    /**
     * An adjacency that connects every system to every other system in {@code state}
     * - so the builder's sensor-reach reveals all non-owned systems as neighbours,
     * matching the original E3-01 projection's visibility.
     */
    private static SystemAdjacency fullyConnected(GameState state) {
        List<SystemId> ids = new ArrayList<>();
        for (ActiveSystem sys : state.systems().values()) {
            ids.add(sys.id());
        }
        Map<SystemId, List<SystemId>> adjacency = new LinkedHashMap<>();
        for (SystemId from : ids) {
            List<SystemId> others = new ArrayList<>();
            for (SystemId to : ids) {
                if (!to.equals(from)) {
                    others.add(to);
                }
            }
            adjacency.put(from, List.copyOf(others));
        }
        return SystemAdjacency.of(adjacency);
    }
}
