package com.stellarcompact.orchestrator.sovereign;

import com.stellarcompact.engine.state.SystemId;

import java.util.List;
import java.util.Map;

/**
 * The orchestrator-side lane-adjacency lookup the {@link WorldViewBuilder} consults
 * for sensor-range / lane-adjacency fog-of-war visibility (card E3-02): a system one
 * lane hop from a system this faction owns (or has a fleet parked at) is revealed as
 * a fog-limited neighbour.
 *
 * <p><b>Why this lives in the orchestrator, not the engine snapshot.</b> The lane
 * graph is heavy, static-per-match map geometry; per principle 3 and the engine
 * purity rule it is never embedded in the per-tick {@link com.stellarcompact.engine.state.GameState}
 * (which would bloat the golden hash). The orchestrator owns the galaxy lane graph
 * and projects the active region into this small read-only adjacency, exactly as it
 * passes the {@code BalanceProfile} separately to the resolver. A later engine card
 * (E1-09/E2-03 lane network) can supply a richer backing without changing this
 * fog-builder seam.
 *
 * <p>It is a pure function {@code SystemId -> neighbours}; no I/O, no clock, no
 * randomness, so the view it helps build stays replay-stable.
 */
@FunctionalInterface
public interface SystemAdjacency {

    /**
     * @param system a system id
     * @return the ids of systems one lane hop away (never {@code null}; empty when the
     * system is isolated or unknown to this adjacency)
     */
    List<SystemId> neighbours(SystemId system);

    /**
     * The explicit "no lane graph supplied" adjacency: every system is isolated, so
     * adjacency-based sensor visibility reveals nothing (default-deny). The builder
     * then surfaces only own systems and allied-vision systems.
     */
    SystemAdjacency NONE = system -> List.of();

    /**
     * Build a {@link SystemAdjacency} from an explicit, undirected adjacency map.
     * The map is defensively snapshotted; missing keys yield no neighbours. Both
     * directions should be present in {@code adjacency} for symmetric lanes (the
     * builder only ever queries one direction, from an owned/fleet anchor).
     *
     * @param adjacency system id -&gt; its one-hop neighbour ids
     * @return an immutable adjacency over a copy of {@code adjacency}
     */
    static SystemAdjacency of(Map<SystemId, List<SystemId>> adjacency) {
        Map<SystemId, List<SystemId>> snapshot = Map.copyOf(adjacency);
        return system -> snapshot.getOrDefault(system, List.of());
    }
}
