package com.stellarcompact.api.faction;

import com.stellarcompact.agentruntime.prompt.AssembledPrompt;

/**
 * The application-service seam behind the Sovereign-config REST endpoints (board card
 * E6-02; rest-api spec, section Sovereign configuration). It owns the per-seat
 * configuration registry, the owner binding, and the lifecycle gate on directive edits.
 *
 * <p><b>Why an interface (the E6-01/E6-04 seam pattern).</b> Like {@code MatchService}
 * and {@code FactionOwnershipRegistry}, configuration storage is behind a seam so the
 * in-memory backing ({@link InMemoryFactionConfigService}) can be swapped for a
 * persistence-backed one (a {@code faction_config} table joined to the seat-assignment
 * table) without touching the controller or its DTOs.
 *
 * <p><b>Owner is a parameter, resolved server-side.</b> Every method that depends on
 * owner identity takes the resolved principal name as an explicit argument; the
 * controller derives it from the authenticated principal / owner token, never from a
 * client-settable body field (principle 2). The service treats a {@code null}/blank
 * principal as "not the owner" (default deny for writes; redaction for reads).
 *
 * <p><b>This config feeds prompt assembly.</b> {@link #assembledPromptFor} renders a
 * seat's stored {@code SovereignConfig} through the agent-runtime {@code PromptAssembler}
 * (E4-02) so the prompt a configured seat would receive reflects the persona/goals/
 * constraints stored here - the direct, testable link to the LLM path (orchestrator
 * {@code LlmSeat}).
 */
public interface FactionConfigService {

    /**
     * Create/attach a Sovereign configuration to a seat in {@code gameId} and bind
     * {@code ownerPrincipal} as its owner. The seat is chosen as the first unconfigured
     * seat in the match (ascending id order). Idempotent re-attach by the same owner
     * updates the existing config; a different principal attaching to an already-owned
     * seat is rejected.
     *
     * @param gameId         the match
     * @param ownerPrincipal the resolved owning principal (non-blank)
     * @param request        the seat configuration
     * @return the attached seat handle
     * @throws com.stellarcompact.api.match.MatchNotFoundException if no such match
     * @throws NotFactionOwnerException if {@code ownerPrincipal} is blank, or no free seat
     */
    AttachFactionResponse attach(String gameId, String ownerPrincipal, CreateFactionRequest request);

    /**
     * Read a seat's configuration. The view is the full owner view iff
     * {@code requesterPrincipal} is the seat's registered owner; otherwise it is the
     * redacted public view (identity only). A non-owner read is NOT an error.
     *
     * @throws FactionNotFoundException if no such faction handle
     */
    FactionConfigView view(String factionHandle, String requesterPrincipal);

    /**
     * Apply a standing-directive edit. Owner-gated (non-owner -&gt; 403) and lifecycle-gated
     * (match RUNNING -&gt; 409); editable only between matches.
     *
     * @throws FactionNotFoundException   if no such faction handle
     * @throws NotFactionOwnerException   if the requester is not the owner
     * @throws DirectivesLockedException  if the owning match is RUNNING
     */
    FactionConfigView updateDirectives(
            String factionHandle, String requesterPrincipal, UpdateDirectivesRequest request);

    /**
     * Assemble the system+user prompt a configured seat would receive, from its stored
     * {@code SovereignConfig} (the link from this config to prompt assembly, E4-02). The
     * {@code worldView} is any token-compact JSON-serialisable perception object (the
     * orchestrator passes its own {@code WorldView}); tests pass a tiny stand-in.
     *
     * @throws FactionNotFoundException if no such faction handle
     */
    AssembledPrompt assembledPromptFor(String factionHandle, Object worldView);
}
