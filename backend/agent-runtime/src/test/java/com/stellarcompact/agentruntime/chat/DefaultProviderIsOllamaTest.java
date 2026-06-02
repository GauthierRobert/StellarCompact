package com.stellarcompact.agentruntime.chat;

import org.junit.jupiter.api.Test;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.model.ollama.autoconfigure.OllamaApiAutoConfiguration;
import org.springframework.ai.model.ollama.autoconfigure.OllamaChatAutoConfiguration;
import org.springframework.ai.model.tool.autoconfigure.ToolCallingAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the DEFAULT provider is Ollama (principle 4) with no profile override.
 *
 * <p>Uses an {@link ApplicationContextRunner} over the real Ollama auto-configuration
 * that the {@code spring-ai-starter-model-ollama} dependency contributes. With no
 * {@code spring.ai.model.chat} override the Ollama chat auto-config is active and an
 * {@link OllamaChatModel} is the {@code ChatModel} the ChatClient would be built on —
 * i.e. Ollama answers unless a profile/property selects another provider. No Ollama
 * service is contacted: {@code pull-model-strategy=never} keeps bean creation offline,
 * and the runner never issues a chat call.
 */
class DefaultProviderIsOllamaTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    ToolCallingAutoConfiguration.class,
                    OllamaApiAutoConfiguration.class,
                    OllamaChatAutoConfiguration.class))
            .withPropertyValues(
                    "spring.ai.ollama.base-url=http://localhost:11434",
                    "spring.ai.ollama.init.pull-model-strategy=never");

    @Test
    void ollamaChatModelIsTheDefaultProviderWithNoOverride() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(OllamaChatModel.class);
        });
    }

    @Test
    void explicitlySelectingOllamaAlsoYieldsOllama() {
        runner.withPropertyValues("spring.ai.model.chat=ollama").run(context -> {
            assertThat(context).hasSingleBean(OllamaChatModel.class);
        });
    }
}
