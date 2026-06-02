package com.stellarcompact.agentruntime.validate;

import com.stellarcompact.engine.action.Action;
import com.stellarcompact.engine.action.AgentResponse;

import java.util.List;

/**
 * The clean, engine-validated outcome of one Sovereign turn (card E4-04): only the
 * {@link Action}s that passed {@code ActionValidator} against authoritative
 * {@code GameState}, ready to hand to the resolver (E1-05). Everything that failed
 * parse or validation - even after the single re-prompt - has already been dropped.
 *
 * <p><b>Hold semantics.</b> A faction that produced nothing valid this turn
 * {@code holds}: {@link #validActions()} is empty and {@link #holds()} is {@code true}.
 * The orchestrator treats that as the explicit no-op (a {@code Hold} action need not be
 * materialised; an empty validated batch <em>is</em> a hold). When at least one action
 * survives, {@code holds()} is false and the surviving actions resolve in order.
 *
 * <p><b>At-most-one-retry invariant.</b> {@link #rePrompted()} records whether the
 * single permitted re-prompt was issued. The loop that produces this value issues the
 * model call at most twice total (initial + at most one re-prompt); this flag lets the
 * orchestrator and tests confirm that bound was respected.
 *
 * <p><b>Messages.</b> {@link #messages()} carries the negotiation-phase messages from
 * the response the validated actions came from (the re-prompt response if one occurred,
 * else the first). Messages are not engine-validated here (that is the negotiation
 * phase's concern); they are surfaced so the orchestrator can route them.
 *
 * @param validActions the actions that passed validation, in submission order; never
 *                     null, may be empty (a hold)
 * @param messages     the negotiation messages from the accepted response; never null
 * @param holds        true iff no valid action survived (the faction idles this turn)
 * @param rePrompted   true iff the single re-prompt was issued before settling
 */
public record ValidatedDecision(
        List<Action> validActions,
        List<AgentResponse.Message> messages,
        boolean holds,
        boolean rePrompted
) {

    public ValidatedDecision {
        validActions = validActions == null ? List.of() : List.copyOf(validActions);
        messages = messages == null ? List.of() : List.copyOf(messages);
        if (!validActions.isEmpty() && holds) {
            throw new IllegalArgumentException(
                    "ValidatedDecision: holds must be false when validActions is non-empty");
        }
    }

    /** A hold outcome (no actions) carrying any messages from the response. */
    static ValidatedDecision hold(List<AgentResponse.Message> messages, boolean rePrompted) {
        return new ValidatedDecision(List.of(), messages, true, rePrompted);
    }

    /** An outcome with surviving actions (degrades to a hold if the list is empty). */
    static ValidatedDecision of(List<Action> validActions, List<AgentResponse.Message> messages,
                                boolean rePrompted) {
        if (validActions == null || validActions.isEmpty()) {
            return hold(messages, rePrompted);
        }
        return new ValidatedDecision(validActions, messages, false, rePrompted);
    }
}
