package com.stellarcompact.agentruntime.chat;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A test-double {@link ChatModel} with NO external service. It stands in for any
 * provider (Ollama, OpenAI, …) and records the {@link ChatOptions} of the last call so
 * tests can assert which concrete model name tier routing pushed onto the request. Its
 * mere presence as a bean triggers Spring AI's real {@code ChatClient.Builder}
 * auto-configuration, so the wiring under test is the production wiring — only the
 * transport is stubbed.
 */
public class RecordingChatModel implements ChatModel {

    private final AtomicReference<ChatOptions> lastOptions = new AtomicReference<>();
    private final AtomicReference<String> lastPromptText = new AtomicReference<>();

    @Override
    public ChatResponse call(Prompt prompt) {
        lastOptions.set(prompt.getOptions());
        lastPromptText.set(prompt.getContents());
        return new ChatResponse(List.of(new Generation(new AssistantMessage("ok"))));
    }

    /** @return the model name the last call was routed with, or {@code null} if unset. */
    public String lastModel() {
        ChatOptions opts = lastOptions.get();
        return opts == null ? null : opts.getModel();
    }

    public String lastPromptText() {
        return lastPromptText.get();
    }
}
