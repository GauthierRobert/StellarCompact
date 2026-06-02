package com.stellarcompact.api.faction;

import com.stellarcompact.agentruntime.chat.ModelTier;

import java.util.List;

/**
 * The {@code GET /api/factions/{id}} response (board card E6-02). It carries a Sovereign
 * seat's <b>public</b> identity always, and its <b>owner-only sensitive</b> configuration
 * (persona text, goals, hard constraints, model tier) only when the requester is the
 * registered owner of the seat.
 *
 * <p><b>The redaction is the security crux (principle 2; this card is owner-only
 * redaction).</b> A view is built by exactly one of two factories so the choice is a
 * server-side decision, never a flag the client can flip:
 * <ul>
 *   <li>{@link #owner} - the full view: sensitive fields populated.</li>
 *   <li>{@link #redacted} - the public view: {@code owner=false}, {@code persona=null},
 *       {@code goals}/{@code hardConstraints} empty, {@code modelTier=null}. No sensitive
 *       text is ever placed into a redacted view, so there is nothing to leak even if the
 *       response is logged or cached by a non-owner.</li>
 * </ul>
 * Whether the persona/goals/constraints are even <em>set</em> is itself sensitive (it
 * reveals an owner's strategy), so the redacted view exposes neither the values nor a
 * "configured" boolean derived from them.
 *
 * @param factionId   the seat handle ({@code gameId:seatId}) - public
 * @param gameId      the match the seat belongs to - public
 * @param seatId      the engine faction id within the match (e.g. {@code faction-1}) - public
 * @param configured  whether a configuration has been attached - public, coarse (presence
 *                    only, not its contents)
 * @param owner       true iff this view was built for the seat's owner (sensitive fields
 *                    are populated); false for the redacted public view
 * @param persona         OWNER-ONLY: the persona text (null when redacted)
 * @param goals           OWNER-ONLY: strategic goals (empty when redacted)
 * @param hardConstraints OWNER-ONLY: hard constraints (empty when redacted)
 * @param modelTier       OWNER-ONLY: the model tier (null when redacted)
 */
public record FactionConfigView(
        String factionId,
        String gameId,
        String seatId,
        boolean configured,
        boolean owner,
        String persona,
        List<String> goals,
        List<String> hardConstraints,
        ModelTier modelTier
) {
    public FactionConfigView {
        goals = goals == null ? List.of() : List.copyOf(goals);
        hardConstraints = hardConstraints == null ? List.of() : List.copyOf(hardConstraints);
    }

    /** The full owner view: every sensitive field populated from the stored config. */
    public static FactionConfigView owner(FactionConfig config) {
        return new FactionConfigView(
                config.factionHandle(),
                config.gameId(),
                config.seatId().value(),
                config.isConfigured(),
                true,
                config.sovereignConfig().persona(),
                config.sovereignConfig().goals(),
                config.sovereignConfig().hardConstraints(),
                config.modelTier());
    }

    /**
     * The redacted public view: identity only; every owner-only field is stripped. This
     * is the view a non-owner (or unauthenticated) requester receives.
     */
    public static FactionConfigView redacted(FactionConfig config) {
        return new FactionConfigView(
                config.factionHandle(),
                config.gameId(),
                config.seatId().value(),
                config.isConfigured(),
                false,
                null,
                List.of(),
                List.of(),
                null);
    }
}
