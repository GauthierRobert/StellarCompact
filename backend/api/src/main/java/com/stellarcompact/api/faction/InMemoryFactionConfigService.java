package com.stellarcompact.api.faction;

import com.stellarcompact.agentruntime.chat.ModelTier;
import com.stellarcompact.agentruntime.prompt.AssembledPrompt;
import com.stellarcompact.agentruntime.prompt.PromptAssembler;
import com.stellarcompact.agentruntime.prompt.SovereignConfig;
import com.stellarcompact.api.match.GameSummary;
import com.stellarcompact.api.match.MatchService;
import com.stellarcompact.api.ws.InMemoryFactionOwnershipRegistry;
import com.stellarcompact.engine.state.FactionId;
import com.stellarcompact.engine.state.GameStatus;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-process {@link FactionConfigService} (board card E6-02), mirroring the
 * {@code InMemoryMatchService} seam pattern: a registry of per-seat
 * {@link FactionConfig} keyed by the {@code gameId:seatId} handle.
 *
 * <p><b>Owner mechanism (the security crux).</b> Ownership is the project's existing
 * server-side trust boundary, the {@code FactionOwnershipRegistry} (E6-04). {@code attach}
 * binds the resolved principal as the seat's owner; {@code view} consults the registry to
 * decide owner-full vs. redacted; {@code updateDirectives} requires ownership. The owner
 * is NEVER read from a request body - the controller resolves it from the principal /
 * owner token and passes it in. The concrete {@link InMemoryFactionOwnershipRegistry} is
 * injected (not just the read-only interface) because attach must {@code bind}; a later
 * persistence card swaps both backings behind their interfaces.
 *
 * <p><b>Lifecycle gate.</b> {@code updateDirectives} reads the match status via
 * {@link MatchService#summary} and rejects a {@code RUNNING} match
 * ({@link DirectivesLockedException} maps to 409): standing directives are editable only
 * between matches.
 *
 * <p><b>Feeds prompt assembly.</b> {@code attach} normalises the REST payload into a
 * {@link SovereignConfig} - the exact agent-runtime type the {@link PromptAssembler}
 * (E4-02) and orchestrator {@code LlmSeat} consume - so a configured seat is directly
 * usable to build its system prompt. {@link #assembledPromptFor} serves that link.
 */
@Component
public class InMemoryFactionConfigService implements FactionConfigService {

    private final MatchService matches;
    private final InMemoryFactionOwnershipRegistry ownership;
    private final PromptAssembler promptAssembler;

    /** handle ({@code gameId:seatId}) maps to the stored config. */
    private final Map<String, FactionConfig> configs = new ConcurrentHashMap<>();

    public InMemoryFactionConfigService(MatchService matches,
                                        InMemoryFactionOwnershipRegistry ownership,
                                        PromptAssembler promptAssembler) {
        this.matches = matches;
        this.ownership = ownership;
        this.promptAssembler = promptAssembler;
    }

    @Override
    public AttachFactionResponse attach(String gameId, String ownerPrincipal,
                                        CreateFactionRequest request) {
        if (ownerPrincipal == null || ownerPrincipal.isBlank()) {
            // No principal means no claim to a seat (default deny for writes).
            throw new NotFactionOwnerException(
                    "an owner principal is required to attach a Sovereign configuration");
        }
        // Match existence (404 if unknown) plus the seat roster.
        GameSummary summary = matches.summary(gameId);
        CreateFactionRequest req = request == null
                ? new CreateFactionRequest(null, null, null, null, null) : request;

        FactionId seat = chooseSeat(gameId, ownerPrincipal, summary.factions());

        SovereignConfig sovereign = new SovereignConfig(
                req.effectivePersona(), req.goals(), req.hardConstraints());
        ModelTier tier = req.effectiveTier();

        FactionConfig config = new FactionConfig(gameId, seat, sovereign, tier, true);
        configs.put(config.factionHandle(), config);
        // Bind ownership through the project's authoritative registry (server-side trust).
        ownership.bind(ownerPrincipal, gameId, seat);

        return new AttachFactionResponse(config.factionHandle(), gameId, seat.value());
    }

    @Override
    public FactionConfigView view(String factionHandle, String requesterPrincipal) {
        FactionConfig config = require(factionHandle);
        boolean isOwner = ownership.owns(requesterPrincipal, config.gameId(), config.seatId());
        // The redaction decision is made HERE, server-side, from the authoritative
        // registry - never from a client-supplied flag.
        return isOwner ? FactionConfigView.owner(config) : FactionConfigView.redacted(config);
    }

    @Override
    public FactionConfigView updateDirectives(String factionHandle, String requesterPrincipal,
                                              UpdateDirectivesRequest request) {
        FactionConfig config = require(factionHandle);
        if (!ownership.owns(requesterPrincipal, config.gameId(), config.seatId())) {
            throw new NotFactionOwnerException(
                    "only the seat owner may edit standing directives");
        }
        // Lifecycle gate: editable only between matches (reject while RUNNING).
        GameSummary summary = matches.summary(config.gameId());
        if (summary.status() == GameStatus.RUNNING) {
            throw new DirectivesLockedException(
                    "standing directives are editable only between matches; match "
                            + config.gameId() + " is RUNNING");
        }

        UpdateDirectivesRequest req = request == null
                ? new UpdateDirectivesRequest(null, null, null, null) : request;

        SovereignConfig current = config.sovereignConfig();
        String persona = req.customPersona() != null ? req.customPersona() : current.persona();
        List<String> goals = req.goals() != null ? req.goals() : current.goals();
        List<String> constraints = req.hardConstraints() != null
                ? req.hardConstraints() : current.hardConstraints();
        ModelTier tier = req.modelTier() != null ? req.modelTier() : config.modelTier();

        FactionConfig updated = new FactionConfig(
                config.gameId(), config.seatId(),
                new SovereignConfig(persona, goals, constraints), tier, true);
        configs.put(updated.factionHandle(), updated);
        return FactionConfigView.owner(updated);
    }

    @Override
    public AssembledPrompt assembledPromptFor(String factionHandle, Object worldView) {
        FactionConfig config = require(factionHandle);
        return promptAssembler.assemble(config.sovereignConfig(), worldView);
    }

    // ===== helpers =============================================================

    private FactionConfig require(String factionHandle) {
        FactionConfig config = factionHandle == null ? null : configs.get(factionHandle);
        if (config == null) {
            throw new FactionNotFoundException("no faction configuration with id " + factionHandle);
        }
        return config;
    }

    /**
     * Choose the seat this attach configures. If {@code ownerPrincipal} already owns a
     * seat in this match (re-attach), reuse it; otherwise take the first seat that is
     * unowned, in ascending id order. No free seat is a 403 (all seats are taken).
     */
    private FactionId chooseSeat(String gameId, String ownerPrincipal, List<String> seatIds) {
        // Re-attach: keep the seat this principal already owns.
        for (String s : seatIds) {
            FactionId fid = new FactionId(s);
            if (ownerPrincipal.equals(ownership.ownerOf(gameId, fid))) {
                return fid;
            }
        }
        // Otherwise the first unowned seat (ascending; the roster is already sorted).
        for (String s : seatIds) {
            FactionId fid = new FactionId(s);
            if (ownership.ownerOf(gameId, fid) == null) {
                return fid;
            }
        }
        throw new NotFactionOwnerException(
                "no free seat to attach a Sovereign in match " + gameId);
    }
}
