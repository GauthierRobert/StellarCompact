package com.stellarcompact.api.faction;

import com.stellarcompact.agentruntime.chat.ModelTier;
import com.stellarcompact.agentruntime.prompt.SovereignConfig;
import com.stellarcompact.engine.state.FactionId;

/**
 * The persisted configuration of one Sovereign seat (board card E6-02): the stored
 * {@link SovereignConfig} (persona/goals/hard-constraints, the exact type the E4-02
 * {@code PromptAssembler} consumes) plus its {@link ModelTier} (E4-01 routing), keyed by
 * the match it belongs to and the engine faction id within it.
 *
 * <p><b>This is what feeds prompt assembly.</b> The stored {@link #sovereignConfig()} is
 * the same {@code SovereignConfig} record the agent-runtime's {@code PromptAssembler}
 * and the orchestrator's {@code LlmSeat} take - so a seat configured through this REST
 * surface is directly usable to build that seat's system prompt for its chosen tier. No
 * translation layer: the REST payload is normalised into the agent-runtime type once,
 * here, at store time.
 *
 * <p><b>Not engine state.</b> Configuration never enters {@code GameState} or the
 * deterministic resolver (principle 1). It lives in the api's faction-config registry.
 *
 * <p>The {@link #factionHandle()} ({@code gameId:seatId}) is the globally-unique handle
 * the {@code GET /api/factions/{id}} path uses, since seat ids ({@code faction-1}) repeat
 * across matches.
 *
 * @param gameId          the match this seat belongs to
 * @param seatId          the engine faction id within the match
 * @param sovereignConfig the persona/goals/hard-constraints (agent-runtime type)
 * @param modelTier       the model tier to route this seat to
 * @param configured      whether an owner has attached a configuration (vs. an
 *                        auto-created default placeholder)
 */
public record FactionConfig(
        String gameId,
        FactionId seatId,
        SovereignConfig sovereignConfig,
        ModelTier modelTier,
        boolean configured
) {
    public FactionConfig {
        if (gameId == null || gameId.isBlank()) {
            throw new IllegalArgumentException("FactionConfig.gameId must be set");
        }
        if (seatId == null) {
            throw new IllegalArgumentException("FactionConfig.seatId must be set");
        }
        if (sovereignConfig == null) {
            sovereignConfig = new SovereignConfig("", java.util.List.of(), java.util.List.of());
        }
        if (modelTier == null) {
            modelTier = ModelTier.SMALL;
        }
    }

    /** The globally-unique seat handle used by the {@code GET /api/factions/{id}} path. */
    public String factionHandle() {
        return handle(gameId, seatId);
    }

    boolean isConfigured() {
        return configured;
    }

    /** Build the canonical {@code gameId:seatId} handle. */
    public static String handle(String gameId, FactionId seatId) {
        return gameId + ":" + seatId.value();
    }
}
