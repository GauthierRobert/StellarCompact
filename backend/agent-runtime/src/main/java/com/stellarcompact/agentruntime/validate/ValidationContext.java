package com.stellarcompact.agentruntime.validate;

import com.stellarcompact.engine.config.BalanceProfile;
import com.stellarcompact.engine.map.LaneNetwork;
import com.stellarcompact.engine.state.FactionId;
import com.stellarcompact.engine.state.GameState;

/**
 * The authoritative inputs the {@link ActionValidationLoop} feeds to the pure engine
 * {@code ActionValidator} (card E4-04). It bundles exactly what {@code validate(...)}
 * needs for one Sovereign's turn, so the loop signature stays small and the validation
 * call site is provider-neutral and engine-only.
 *
 * <p>The {@code LaneNetwork} is the static-per-match lane graph the orchestrator
 * projects (E1-09); pass {@link LaneNetwork#EMPTY} when none is available, mirroring the
 * validator's own four-arg degrade-to-shape-only behaviour. Everything here is an
 * immutable engine value; nothing here is agent-supplied.
 *
 * @param state   authoritative game state this turn (never mutated by validation)
 * @param actor   the faction whose actions are being validated
 * @param profile active balance profile (source of gameplay numbers)
 * @param network active-region lane graph, or {@link LaneNetwork#EMPTY}
 */
public record ValidationContext(
        GameState state,
        FactionId actor,
        BalanceProfile profile,
        LaneNetwork network
) {

    public ValidationContext {
        if (state == null) {
            throw new IllegalArgumentException("ValidationContext.state must be set");
        }
        if (actor == null) {
            throw new IllegalArgumentException("ValidationContext.actor must be set");
        }
        if (profile == null) {
            throw new IllegalArgumentException("ValidationContext.profile must be set");
        }
        if (network == null) {
            throw new IllegalArgumentException(
                    "ValidationContext.network must be set (use LaneNetwork.EMPTY)");
        }
    }

    /** Convenience: a context with no lane graph (adjacency/reachability degrade to shape-only). */
    public static ValidationContext of(GameState state, FactionId actor, BalanceProfile profile) {
        return new ValidationContext(state, actor, profile, LaneNetwork.EMPTY);
    }
}
