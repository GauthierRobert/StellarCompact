package com.stellarcompact.api.faction;

import com.stellarcompact.agentruntime.chat.ModelTier;

import java.util.List;

/**
 * The {@code PATCH /api/factions/{id}} body (board card E6-02): an edit to a Sovereign's
 * standing directives for persistent galaxies. Only the present (non-null) fields are
 * applied; a null field leaves the stored value unchanged (PATCH semantics).
 *
 * <p><b>Lifecycle-gated.</b> Standing directives are editable only <em>between</em>
 * matches: the service rejects a PATCH while the match is {@code RUNNING}
 * ({@link DirectivesLockedException} -&gt; 409). This keeps an owner from re-steering a
 * Sovereign mid-match (which would also be an unfair information/timing channel).
 *
 * <p>Owner is taken from the principal/owner token server-side, never the body - a
 * non-owner PATCH is rejected before any field is read.
 *
 * @param customPersona   replacement persona text (null = leave unchanged)
 * @param goals           replacement goal list (null = leave unchanged; empty = clear)
 * @param hardConstraints replacement hard-constraint list (null = leave unchanged)
 * @param modelTier       replacement model tier (null = leave unchanged)
 */
public record UpdateDirectivesRequest(
        String customPersona,
        List<String> goals,
        List<String> hardConstraints,
        ModelTier modelTier
) {
}
