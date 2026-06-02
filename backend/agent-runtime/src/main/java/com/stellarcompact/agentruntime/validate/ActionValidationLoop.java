package com.stellarcompact.agentruntime.validate;

import com.stellarcompact.agentruntime.parse.AgentResponseParser;
import com.stellarcompact.agentruntime.parse.ParseRejectionCode;
import com.stellarcompact.agentruntime.parse.ParseResult;
import com.stellarcompact.agentruntime.prompt.AssembledPrompt;
import com.stellarcompact.engine.action.Action;
import com.stellarcompact.engine.action.AgentResponse;
import com.stellarcompact.engine.validation.ActionValidator;
import com.stellarcompact.engine.validation.ValidationResult;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Validate-after-parse with exactly one re-prompt, then Hold (card E4-04,
 * security-sensitive). Turns an assembled prompt (E4-02) into a clean, engine-validated
 * Action[] for the resolver:
 *
 * <ol>
 *   <li>Issue the prompt through the provider-neutral ChatClient (E4-01).</li>
 *   <li>Parse the completion via AgentResponseParser (E4-03, untrusted-text boundary).</li>
 *   <li>Validate EVERY parsed action against authoritative GameState through the pure
 *       engine ActionValidator (E1-04). Never trust the model self-report.</li>
 *   <li>On ANY rejection - a parse-stage Rejected or one-or-more action validation
 *       rejections - issue exactly ONE re-prompt that appends the specific reason(s),
 *       built only from closed codes / engine messages (RejectionFeedback); raw model
 *       text is never reflected back.</li>
 *   <li>After the single re-prompt, keep only the actions that now validate; anything
 *       still rejected (or a still-bad parse, or a model error/timeout) is dropped. An
 *       empty surviving set is a Hold.</li>
 * </ol>
 *
 * <h2>At-most-one-retry invariant</h2>
 * The ChatClient is invoked at most TWICE total: once for the initial turn and, only if
 * that turn had any rejection, once more for the single re-prompt. There is no further
 * loop - the second turn survivors are final regardless of whether they too contain
 * rejections. ValidatedDecision.rePrompted() records whether the second call happened.
 *
 * <h2>Security</h2>
 * The re-prompt feedback is built by RejectionFeedback from the closed ParseRejectionCode
 * / engine RejectionReason+message only. The model own free text is never an input to the
 * next prompt, so a hostile completion cannot inject instructions into the re-prompt
 * (spec section 5/5a). A model call that throws (provider error/timeout) degrades to a
 * drop/Hold rather than propagating.
 *
 * <h2>Provider neutrality</h2>
 * The only model seam is the injected ChatClient from SovereignChatClientFactory; no
 * vendor type appears here.
 */
@Component
public class ActionValidationLoop {

    private final AgentResponseParser parser;

    public ActionValidationLoop(AgentResponseParser parser) {
        this.parser = parser;
    }

    /**
     * Run the full parse -&gt; validate -&gt; single-re-prompt -&gt; Hold flow for one turn.
     *
     * @param chatClient the provider-neutral client for this seat tier (not null)
     * @param prompt     the assembled system+user prompt for this turn (E4-02, not null)
     * @param ctx        the authoritative validation inputs (not null)
     * @return the clean validated decision; never null
     */
    public ValidatedDecision decide(ChatClient chatClient, AssembledPrompt prompt,
                                    ValidationContext ctx) {
        if (chatClient == null) {
            throw new IllegalArgumentException("decide: chatClient must be set");
        }
        if (prompt == null) {
            throw new IllegalArgumentException("decide: prompt must be set");
        }
        if (ctx == null) {
            throw new IllegalArgumentException("decide: ctx must be set");
        }

        Attempt first = attempt(chatClient, prompt.toSpringPrompt(), ctx);
        if (first.clean()) {
            return ValidatedDecision.of(first.valid(), first.messages(), false);
        }

        Prompt rePrompt = buildRePrompt(prompt, first.feedback());
        Attempt second = attempt(chatClient, rePrompt, ctx);

        return ValidatedDecision.of(second.valid(), second.messages(), true);
    }

    private Attempt attempt(ChatClient chatClient, Prompt prompt, ValidationContext ctx) {
        String completion;
        try {
            completion = chatClient.prompt(prompt).call().content();
        } catch (RuntimeException e) {
            return Attempt.parseRejected(
                    RejectionFeedback.forParseRejection(ParseRejectionCode.NO_JSON));
        }

        ParseResult parsed = parser.parse(completion);
        if (parsed instanceof ParseResult.Rejected rej) {
            return Attempt.parseRejected(RejectionFeedback.forParseRejection(rej.code()));
        }

        AgentResponse response = ((ParseResult.Parsed) parsed).response();
        List<Action> valid = new ArrayList<>();
        List<ValidationResult.Rejected> rejections = new ArrayList<>();
        for (Action action : response.actions()) {
            ValidationResult result = ActionValidator.validate(
                    ctx.state(), ctx.actor(), action, ctx.profile(), ctx.network());
            switch (result) {
                case ValidationResult.Valid ignored -> valid.add(action);
                case ValidationResult.Rejected r -> rejections.add(r);
            }
        }

        if (rejections.isEmpty()) {
            return Attempt.clean(valid, response.messages());
        }
        return new Attempt(valid, response.messages(),
                RejectionFeedback.forValidationRejections(rejections), false);
    }

    private static Prompt buildRePrompt(AssembledPrompt original, String feedback) {
        return new Prompt(List.of(
                new SystemMessage(original.systemMessage()),
                new UserMessage(original.userMessage()),
                new UserMessage(feedback)));
    }

    private record Attempt(List<Action> valid, List<AgentResponse.Message> messages,
                           String feedback, boolean clean) {

        static Attempt clean(List<Action> valid, List<AgentResponse.Message> messages) {
            return new Attempt(valid, messages, null, true);
        }

        static Attempt parseRejected(String feedback) {
            return new Attempt(List.of(), List.of(), feedback, false);
        }
    }
}
