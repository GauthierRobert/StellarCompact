package com.stellarcompact.agentruntime.validate;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * A deterministic test-double {@link ChatModel} that returns queued completions in order
 * and records every prompt it was called with. No external service; stands in for any
 * provider so the validate-after-parse re-prompt loop (E4-04) is exercised against the
 * real {@code ChatClient} wiring with a stubbed transport.
 *
 * <p>Used to assert the at-most-one-retry invariant: {@link #callCount()} must be 1 (no
 * re-prompt) or 2 (one re-prompt), never more; and {@link #promptText(int)} lets a test
 * confirm the rejection reason was fed into the second prompt.
 */
class ScriptedChatModel implements ChatModel {

    private final Deque<String> scriptedReplies = new ArrayDeque<>();
    private final List<String> promptTexts = new ArrayList<>();
    private final List<Prompt> prompts = new ArrayList<>();
    private boolean throwOnNextCall = false;

    ScriptedChatModel(String... replies) {
        for (String r : replies) {
            scriptedReplies.add(r);
        }
    }

    void enqueue(String reply) {
        scriptedReplies.add(reply);
    }

    void throwOnNextCall() {
        this.throwOnNextCall = true;
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        prompts.add(prompt);
        promptTexts.add(prompt.getContents());
        if (throwOnNextCall) {
            throwOnNextCall = false;
            throw new RuntimeException("simulated provider failure/timeout");
        }
        String reply = scriptedReplies.isEmpty() ? "" : scriptedReplies.poll();
        return new ChatResponse(List.of(new Generation(new AssistantMessage(reply))));
    }

    int callCount() {
        return promptTexts.size();
    }

    String promptText(int index) {
        return promptTexts.get(index);
    }
}
