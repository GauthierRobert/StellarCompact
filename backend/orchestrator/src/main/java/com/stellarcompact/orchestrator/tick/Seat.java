package com.stellarcompact.orchestrator.tick;

import com.stellarcompact.agentruntime.chat.ModelTier;
import com.stellarcompact.agentruntime.chat.SovereignChatClientFactory;
import com.stellarcompact.agentruntime.prompt.AssembledPrompt;
import com.stellarcompact.agentruntime.prompt.PromptAssembler;
import com.stellarcompact.agentruntime.prompt.SovereignConfig;
import com.stellarcompact.agentruntime.validate.ActionValidationLoop;
import com.stellarcompact.agentruntime.validate.ValidatedDecision;
import com.stellarcompact.agentruntime.validate.ValidationContext;
import com.stellarcompact.engine.action.Action;
import com.stellarcompact.engine.action.AgentResponse;
import com.stellarcompact.engine.state.FactionId;
import com.stellarcompact.engine.validation.ActionValidator;
import com.stellarcompact.engine.validation.ValidationResult;
import com.stellarcompact.orchestrator.sovereign.Sovereign;
import com.stellarcompact.orchestrator.sovereign.WorldView;
import com.stellarcompact.orchestrator.sovereign.WorldViewBuilder;

import java.util.ArrayList;
import java.util.List;

/**
 * One occupied faction seat as the {@link TickOrchestrator} sees it (card E4-05): a
 * function from a {@link TickContext} to a clean, engine-validated {@link SeatDecision}.
 * Two kinds exist - both implement the SAME contract so the orchestrator drives them
 * identically inside one per-phase {@code StructuredTaskScope}, one virtual thread each
 * (skill {@code agent-sovereign}, the per-phase fan-out):
 *
 * <ul>
 *   <li>{@link ScriptedSeat} - a deterministic {@link Sovereign} bot (E3-01) for tests
 *       and empty seats. It bypasses the LLM entirely but still runs under the same
 *       phase deadline, so a (pathologically) slow bot Holds just like a slow brain.</li>
 *   <li>{@link LlmSeat} - the production LLM path: build the fog-filtered
 *       {@link WorldView} server-side, assemble the prompt (E4-02), and run the
 *       agent-runtime {@link ActionValidationLoop} (E4-04: parse + validate + one
 *       re-prompt + Hold). This is the wiring the E4-04 author specified - the
 *       orchestrator feeds {@link SovereignChatClientFactory} + {@link PromptAssembler}
 *       into {@code decide(ChatClient, AssembledPrompt, ValidationContext)} per seat.</li>
 * </ul>
 *
 * <p><b>Must not throw the tick over.</b> {@link #decide(TickContext)} is invoked inside
 * a virtual thread that the scope may cancel/interrupt on deadline; an implementation
 * that throws degrades to a {@link SeatDecision#HOLD} for that seat (the orchestrator's
 * straggler handling treats {@code FAILED} subtasks as Hold). Implementations therefore
 * need not be defensive about deadline interruption themselves.
 *
 * <p><b>Provider neutrality.</b> The Spring AI {@code ChatClient} is obtained only via
 * {@link SovereignChatClientFactory}; no vendor type appears here or in the orchestrator.
 */
public sealed interface Seat permits Seat.ScriptedSeat, Seat.LlmSeat {

    /** @return the faction seat this agent plays. */
    FactionId factionId();

    /**
     * Produce this seat's validated decision for the current phase from authoritative
     * state. Runs on a virtual thread under the phase deadline.
     *
     * @param ctx the tick context (authoritative state + static-per-match inputs)
     * @return the clean, engine-validated decision; never null (empty = Hold)
     */
    SeatDecision decide(TickContext ctx);

    /**
     * A scripted-bot seat: project the fog-filtered {@link WorldView}, ask the
     * {@link Sovereign} to decide, then validate every emitted action against
     * authoritative {@link com.stellarcompact.engine.state.GameState} so only
     * {@code Valid} actions survive (closed agent I/O, principle&nbsp;5).
     */
    record ScriptedSeat(Sovereign sovereign) implements Seat {

        public ScriptedSeat {
            if (sovereign == null) {
                throw new IllegalArgumentException("ScriptedSeat.sovereign must be set");
            }
        }

        @Override
        public FactionId factionId() {
            return sovereign.factionId();
        }

        @Override
        public SeatDecision decide(TickContext ctx) {
            FactionId actor = sovereign.factionId();
            WorldView view = WorldViewBuilder.build(ctx.state(), ctx.adjacency(), actor);
            AgentResponse response = sovereign.decide(view);

            List<Action> valid = new ArrayList<>();
            for (Action action : response.actions()) {
                ValidationResult result = ActionValidator.validate(
                        ctx.state(), actor, action, ctx.profile(), ctx.network());
                if (result.isValid()) {
                    valid.add(action);
                }
            }
            return new SeatDecision(valid, response.messages());
        }
    }

    /**
     * The production LLM seat. Builds the WorldView, assembles the prompt for this
     * seat's tier, and runs the agent-runtime validate-after-parse loop. The returned
     * {@link ValidatedDecision} is already engine-validated and at-most-once-re-prompted,
     * so the orchestrator forwards its survivors straight through.
     *
     * @param factionId         the seat
     * @param config            the human-authored persona/goals/constraints (E4-02)
     * @param tier              the model tier to route this seat to (null = default tier)
     * @param chatClientFactory provider-neutral ChatClient source (E4-01)
     * @param promptAssembler   system+user prompt builder (E4-02)
     * @param validationLoop    parse + validate + single re-prompt + Hold (E4-04)
     */
    record LlmSeat(
            FactionId factionId,
            SovereignConfig config,
            ModelTier tier,
            SovereignChatClientFactory chatClientFactory,
            PromptAssembler promptAssembler,
            ActionValidationLoop validationLoop
    ) implements Seat {

        public LlmSeat {
            if (factionId == null) {
                throw new IllegalArgumentException("LlmSeat.factionId must be set");
            }
            if (config == null) {
                throw new IllegalArgumentException("LlmSeat.config must be set");
            }
            if (chatClientFactory == null) {
                throw new IllegalArgumentException("LlmSeat.chatClientFactory must be set");
            }
            if (promptAssembler == null) {
                throw new IllegalArgumentException("LlmSeat.promptAssembler must be set");
            }
            if (validationLoop == null) {
                throw new IllegalArgumentException("LlmSeat.validationLoop must be set");
            }
        }

        @Override
        public FactionId factionId() {
            return factionId;
        }

        @Override
        public SeatDecision decide(TickContext ctx) {
            WorldView view = WorldViewBuilder.build(ctx.state(), ctx.adjacency(), factionId);
            AssembledPrompt prompt = promptAssembler.assemble(config, view);
            ValidationContext vctx = new ValidationContext(
                    ctx.state(), factionId, ctx.profile(), ctx.network());

            ValidatedDecision decision = validationLoop.decide(
                    chatClientFactory.chatClientFor(tier), prompt, vctx);
            return new SeatDecision(decision.validActions(), decision.messages());
        }
    }
}
