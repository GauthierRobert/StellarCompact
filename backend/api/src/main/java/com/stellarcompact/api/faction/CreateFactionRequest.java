package com.stellarcompact.api.faction;

import com.stellarcompact.agentruntime.chat.ModelTier;

import java.util.List;

/**
 * The {@code POST /api/games/{id}/factions} body (board card E6-02; rest-api spec,
 * section Sovereign configuration): the human-authored configuration of one Sovereign
 * seat. These are <em>inputs</em> the owner supplies (persona/voice, strategic goals,
 * inviolable hard constraints, model tier) - they are not engine/game state and never
 * flow through the deterministic resolver (principle 1); the agent-runtime injects them
 * into the system prompt so the LLM plays in character ({@code PromptAssembler}, E4-02).
 *
 * <p><b>Owner is NOT in the body.</b> The owning principal is taken from the request's
 * authenticated principal / owner token server-side ({@link FactionConfigController}),
 * never from a client-settable body field - a body-supplied owner would let any client
 * claim ownership of any seat. The body carries only the seat's configuration.
 *
 * <p><b>Defensive by construction.</b> Lists default to empty; the persisted
 * {@link com.stellarcompact.agentruntime.prompt.SovereignConfig} additionally trims and
 * drops blank entries. {@code modelTier} defaults to {@link ModelTier#SMALL} (the cheap
 * local-model default, principle 4) when omitted.
 *
 * @param personaPreset   optional named persona preset (informational label; the
 *                        effective persona text is {@code customPersona} when present,
 *                        else a render of the preset name - kept simple here)
 * @param customPersona   free-text persona/voice for the Sovereign; takes precedence
 *                        over {@code personaPreset}
 * @param goals           ordered strategic goals, highest priority first (may be empty)
 * @param hardConstraints inviolable owner-imposed rules (advisory to the model; the
 *                        ENGINE remains the authority) (may be empty)
 * @param modelTier       the orchestration model tier to route this seat to (null =
 *                        {@link ModelTier#SMALL}); never a vendor/model name (principle 4)
 */
public record CreateFactionRequest(
        String personaPreset,
        String customPersona,
        List<String> goals,
        List<String> hardConstraints,
        ModelTier modelTier
) {
    /** The effective persona text: custom text wins; otherwise the preset name; else blank. */
    public String effectivePersona() {
        if (customPersona != null && !customPersona.isBlank()) {
            return customPersona;
        }
        if (personaPreset != null && !personaPreset.isBlank()) {
            return personaPreset;
        }
        return "";
    }

    /** The chosen tier, defaulting to the cheap local default when omitted. */
    public ModelTier effectiveTier() {
        return modelTier == null ? ModelTier.SMALL : modelTier;
    }
}
