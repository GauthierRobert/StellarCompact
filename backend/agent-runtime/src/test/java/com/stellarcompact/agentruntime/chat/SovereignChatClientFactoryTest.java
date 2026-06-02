package com.stellarcompact.agentruntime.chat;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * E4-01 wiring proof against a STUBBED ChatModel (no Ollama/OpenAI needed).
 *
 * <p>Proves (a) the factory + provider-neutral ChatClient bean are constructed, (b)
 * tier → model routing resolves from configuration AND the resolved model name reaches
 * the underlying call, and (c) the default tier is SMALL when no profile overrides it.
 * Provider switching is shown to be config-only by {@link OpenAiProfileRoutingTest},
 * which flips the model names via a Spring profile with zero code change.
 */
@SpringBootTest(classes = ChatTestBootConfig.class)
@TestPropertySource(properties = {
        "stellar-compact.agent.default-tier=SMALL",
        "stellar-compact.agent.tiers.SMALL=test-small-model",
        "stellar-compact.agent.tiers.MEDIUM=test-medium-model",
        "stellar-compact.agent.tiers.LARGE=test-large-model"
})
class SovereignChatClientFactoryTest {

    @Autowired
    SovereignChatClientFactory factory;

    @Autowired
    AgentRuntimeProperties properties;

    @Autowired
    RecordingChatModel recordingChatModel;

    @Test
    void factoryAndProviderNeutralChatClientAreConstructed() {
        assertNotNull(factory, "SovereignChatClientFactory bean must be wired");
        ChatClient client = factory.chatClientFor(ModelTier.SMALL);
        assertNotNull(client, "ChatClient for a tier must not be null");
    }

    @Test
    void tierToModelRoutingResolvesFromConfig() {
        assertEquals("test-small-model", factory.resolvedModelFor(ModelTier.SMALL));
        assertEquals("test-medium-model", factory.resolvedModelFor(ModelTier.MEDIUM));
        assertEquals("test-large-model", factory.resolvedModelFor(ModelTier.LARGE));
    }

    @Test
    void resolvedModelNameReachesTheUnderlyingCall() {
        factory.chatClientFor(ModelTier.LARGE).prompt().user("hello").call().content();
        assertEquals("test-large-model", recordingChatModel.lastModel(),
                "the LARGE tier's configured model must be the model option on the call");

        factory.chatClientFor(ModelTier.SMALL).prompt().user("hi").call().content();
        assertEquals("test-small-model", recordingChatModel.lastModel(),
                "switching tier must switch the routed model with no code change");
    }

    @Test
    void defaultTierIsSmallAndResolvesToItsModel() {
        assertEquals(ModelTier.SMALL, factory.defaultTier());
        assertEquals(ModelTier.SMALL, properties.defaultTier());
        factory.chatClientForDefaultTier().prompt().user("x").call().content();
        assertEquals("test-small-model", recordingChatModel.lastModel());
    }

    @Test
    void unmappedTierLeavesModelUnsetSoProviderDefaultAnswers() {
        // The factory is robust: a tier with no configured model defers to the
        // provider's own default (model option left null) rather than failing.
        assertNull(AgentRuntimeProperties.of(ModelTier.SMALL, java.util.Map.of())
                .modelFor(ModelTier.LARGE));
    }
}
