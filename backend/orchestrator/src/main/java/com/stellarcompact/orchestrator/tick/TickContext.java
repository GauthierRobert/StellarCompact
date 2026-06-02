package com.stellarcompact.orchestrator.tick;

import com.stellarcompact.engine.config.BalanceProfile;
import com.stellarcompact.engine.map.LaneNetwork;
import com.stellarcompact.engine.state.GameState;
import com.stellarcompact.orchestrator.sovereign.SystemAdjacency;

/**
 * The read-only inputs a {@link Seat} needs to decide one phase (card E4-05): the
 * authoritative snapshot plus the static-per-match inputs ({@link BalanceProfile},
 * {@link LaneNetwork} for validation, {@link SystemAdjacency} for fog-of-war sensor
 * reveal). It is a deeply-immutable bundle, safe to share across the virtual threads the
 * orchestrator fans out per phase - nothing here is mutated by a seat's decide pass
 * (validation and WorldView projection are pure reads of {@code state}).
 *
 * @param state     authoritative game state this tick (never mutated by a seat)
 * @param profile   active balance profile (source of every gameplay number)
 * @param network   active-region lane graph for validation, or {@link LaneNetwork#EMPTY}
 * @param adjacency lane adjacency for fog-of-war sensor reveal, or
 *                  {@link SystemAdjacency#NONE}
 */
public record TickContext(
        GameState state,
        BalanceProfile profile,
        LaneNetwork network,
        SystemAdjacency adjacency
) {

    public TickContext {
        if (state == null) {
            throw new IllegalArgumentException("TickContext.state must be set");
        }
        if (profile == null) {
            throw new IllegalArgumentException("TickContext.profile must be set");
        }
        if (network == null) {
            network = LaneNetwork.EMPTY;
        }
        if (adjacency == null) {
            adjacency = SystemAdjacency.NONE;
        }
    }
}
