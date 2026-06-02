package com.stellarcompact.agentruntime.chat;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Scope;

/**
 * Minimal Spring Boot context for the E4-01 wiring tests. It imports the PRODUCTION
 * agent-runtime chat configuration ({@link AgentRuntimeChatConfiguration}, which is what
 * binds {@link AgentRuntimeProperties} and exposes {@link SovereignChatClientFactory} via
 * component scan of this package) and supplies a {@code ChatClient.Builder} built on a
 * stubbed {@code ChatModel} in place of any live provider.
 *
 * <p>In production this {@code ChatClient.Builder} is contributed by Spring AI's
 * auto-configuration from whichever {@code ChatModel} bean the active starter wires
 * (Ollama by default). Here we hand it the production {@link ChatClient#builder(
 * org.springframework.ai.chat.model.ChatModel) ChatClient.builder(ChatModel)} factory
 * over a {@link RecordingChatModel} so the wiring under test — the factory consuming a
 * provider-neutral builder and applying tier→model options — is the real wiring, with no
 * Ollama/OpenAI service and without dragging in the full model auto-config graph
 * (tool-calling, retry, observation). That the builder is provider-neutral is exactly the
 * point of principle 4: the factory cannot tell a stub from Ollama from OpenAI.
 *
 * <p>The builder is {@code prototype}-scoped to mirror Spring AI's own bean, so the
 * factory's {@code clone()}-per-tier usage is exercised against a prototype.
 */
@SpringBootConfiguration
@Import(AgentRuntimeChatConfiguration.class)
@ComponentScan(basePackageClasses = SovereignChatClientFactory.class)
class ChatTestBootConfig {

    @Bean
    RecordingChatModel chatModel() {
        return new RecordingChatModel();
    }

    @Bean
    @Scope("prototype")
    ChatClient.Builder chatClientBuilder(RecordingChatModel chatModel) {
        return ChatClient.builder(chatModel);
    }
}
