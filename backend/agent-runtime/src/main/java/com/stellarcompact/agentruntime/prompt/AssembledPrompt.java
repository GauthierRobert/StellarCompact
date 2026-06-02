package com.stellarcompact.agentruntime.prompt;

import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.List;

/**
 * The output of {@link PromptAssembler} (card E4-02): the fully assembled
 * system + user messages for one Sovereign call, plus the estimated token count the
 * budget was checked against.
 *
 * <p>It exposes both the raw text (handy for tests, logging and the golden snapshot)
 * and a ready-to-send Spring AI {@link Prompt}. The {@link Prompt} is built lazily
 * each time {@link #toSpringPrompt()} is called from the immutable text, so the
 * record itself stays a plain, framework-light value (provider-neutral: the only
 * Spring AI types touched are the vendor-agnostic message/prompt abstractions).
 *
 * @param systemMessage  the full system-prompt text (persona + goals + constraints +
 *                       rules + schema + worked example)
 * @param userMessage    the full user-message text (the serialized compact WorldView)
 * @param estimatedTokens the estimator's token count for {@code system + user}, the
 *                       value compared against the configured budget
 */
public record AssembledPrompt(
        String systemMessage,
        String userMessage,
        int estimatedTokens
) {

    public AssembledPrompt {
        if (systemMessage == null) {
            throw new IllegalArgumentException("AssembledPrompt.systemMessage must be set");
        }
        if (userMessage == null) {
            throw new IllegalArgumentException("AssembledPrompt.userMessage must be set");
        }
        if (estimatedTokens < 0) {
            throw new IllegalArgumentException("AssembledPrompt.estimatedTokens must be >= 0");
        }
    }

    /**
     * @return a Spring AI {@link Prompt} of [SystemMessage, UserMessage] in that
     * order, ready to hand to a provider-neutral {@code ChatClient}. Built fresh per
     * call from the immutable text.
     */
    public Prompt toSpringPrompt() {
        return new Prompt(List.of(
                new SystemMessage(systemMessage),
                new UserMessage(userMessage)));
    }
}