package com.stellarcompact.engine.resolve;

import com.stellarcompact.engine.action.Action;
import com.stellarcompact.engine.action.ActionCategory;
import com.stellarcompact.engine.action.UnknownAction;
import com.stellarcompact.engine.state.FactionId;

/**
 * One already-validated action queued for resolution, tagged with the faction that
 * issued it ({@code actor}) and its {@code submissionOrder} - the stable index of
 * the action within that faction's submitted batch this tick.
 *
 * <p>This is the unit the {@link Resolver} sorts and dispatches. Only actions that
 * passed {@link com.stellarcompact.engine.validation.ActionValidator} as
 * {@code Valid} ever become a {@code SubmittedAction}; rejected actions never reach
 * the resolver (skill rule 6, "validate before resolve").
 *
 * <p><b>Deterministic ordering key.</b> The resolver orders submitted actions by
 * {@code (step, actor, submissionOrder)} exactly as game-design 03 mandates:
 * <ol>
 *   <li>{@link #step()} - the fixed resolution step (category order), the primary key;</li>
 *   <li>{@code actor} - by faction id string, the secondary key;</li>
 *   <li>{@code submissionOrder} - the index the agent submitted the action at, the
 *       final tie-break so two actions by the same faction in the same step keep a
 *       stable, reproducible order.</li>
 * </ol>
 * No {@code HashMap} iteration order ever participates, so the order is total and
 * replay-stable.
 *
 * <p>Immutable record; carries no mutable state, clock or RNG.
 *
 * @param actor           the faction that issued the action (never {@code null})
 * @param action          the validated action (closed sealed set, never {@code null})
 * @param submissionOrder index of this action within {@code actor}'s batch ({@code >= 0})
 */
public record SubmittedAction(FactionId actor, Action action, int submissionOrder) {

    public SubmittedAction {
        if (actor == null) {
            throw new IllegalArgumentException("SubmittedAction.actor must be set");
        }
        if (action == null) {
            throw new IllegalArgumentException("SubmittedAction.action must be set");
        }
        if (submissionOrder < 0) {
            throw new IllegalArgumentException("SubmittedAction.submissionOrder must be >= 0");
        }
    }

    /**
     * The fixed {@link ResolutionStep} this action resolves in. Derived from the
     * action's {@link ActionCategory} (the exhaustive, no-default classification),
     * then mapped onto the eleven-step schedule. Actions whose category carries no
     * gameplay state change of its own ({@code DIPLOMATIC_SOFT}, {@code NONE} - i.e.
     * {@link Action.Hold}, {@link UnknownAction}, chatter and ultimatums) report
     * {@code null}: they are not scheduled into any resolution step and are dropped
     * by the resolver's grouping.
     *
     * @return the resolution step, or {@code null} if this action drives no step
     */
    public ResolutionStep step() {
        return stepOf(action);
    }

    /**
     * Maps an {@link Action} to the {@link ResolutionStep} its effects resolve in,
     * via the exhaustive {@link ActionCategory#of(Action)} switch. Returns
     * {@code null} for categories that are not gameplay-resolution steps.
     *
     * @param action any action (never {@code null})
     * @return the step it feeds, or {@code null} if none
     */
    public static ResolutionStep stepOf(Action action) {
        ActionCategory category = ActionCategory.of(action);
        return switch (category) {
            case DIPLOMATIC_STATE -> ResolutionStep.DIPLOMATIC_STATE;
            case ESPIONAGE -> ResolutionStep.ESPIONAGE;
            case MOVEMENT -> ResolutionStep.MOVEMENT;
            case COMBAT -> ResolutionStep.COMBAT;
            case INTERDICTION -> ResolutionStep.INTERDICTION;
            // The DEVELOPMENT action bucket feeds the two consecutive development
            // steps; Colonize splits out to step 7, everything else to step 6.
            case DEVELOPMENT -> action instanceof Action.Colonize
                    ? ResolutionStep.COLONISATION
                    : ResolutionStep.DEVELOPMENT;
            case TRADE -> ResolutionStep.MARKET;
            // Soft diplomacy and no-ops are not resolution steps: no scheduled slot.
            case DIPLOMATIC_SOFT, NONE -> null;
        };
    }
}
