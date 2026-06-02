package com.stellarcompact.orchestrator.tick;

import com.stellarcompact.engine.action.Action;
import com.stellarcompact.engine.action.AgentResponse;

import java.util.List;

/**
 * One seat's clean, engine-validated outcome for one tick phase (card E4-05) - the
 * orchestrator-level unification of the two seat kinds. A {@link Seat.ScriptedSeat}
 * produces this by validating its bot's {@code AgentResponse} against authoritative
 * state; a {@link Seat.LlmSeat} produces it from the agent-runtime
 * {@code ValidatedDecision} (which already validated + re-prompted). Either way the
 * orchestrator sees the same shape: only actions that passed the engine
 * {@code ActionValidator}, plus any negotiation messages.
 *
 * <p><b>Hold semantics (skill rule 2).</b> A seat that produced nothing valid - because
 * it idled, errored, or <em>missed the phase deadline</em> - {@link #holds()}: an empty
 * {@link #validActions()}. The orchestrator never materialises a {@code Hold} action;
 * an empty validated batch <em>is</em> the hold. This is the value a straggler
 * contributes when the {@code StructuredTaskScope} deadline cancels it.
 *
 * @param validActions actions that passed engine validation, in submission order; never
 *                     null, empty for a hold
 * @param messages     negotiation messages from the accepted response; never null
 */
public record SeatDecision(
        List<Action> validActions,
        List<AgentResponse.Message> messages
) {

    public SeatDecision {
        validActions = validActions == null ? List.of() : List.copyOf(validActions);
        messages = messages == null ? List.of() : List.copyOf(messages);
    }

    /** The hold outcome: no actions, no messages. The straggler / error / idle value. */
    public static final SeatDecision HOLD = new SeatDecision(List.of(), List.of());

    /** @return true iff no valid action survived (the seat idles this phase). */
    public boolean holds() {
        return validActions.isEmpty();
    }
}
