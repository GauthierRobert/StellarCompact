package com.stellarcompact.agentruntime.chat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Companion to {@link SovereignChatClientFactoryTest}: same code, same factory, a
 * different Spring profile — proving provider/model selection is CONFIG-ONLY.
 *
 * <p>The {@code fakeopenai} profile (application-fakeopenai.yml) remaps the tiers to
 * OpenAI-style model names and changes the default tier to MEDIUM. No Java changes;
 * the routed model names follow the config. The transport is still the stub, because
 * provider neutrality means the factory does not care which provider is behind the
 * ChatClient.
 */
@SpringBootTest(classes = ChatTestBootConfig.class)
@ActiveProfiles("fakeopenai")
class OpenAiProfileRoutingTest {

    @Autowired
    SovereignChatClientFactory factory;

    @Autowired
    RecordingChatModel recordingChatModel;

    @Test
    void profileRemapsTiersWithoutCodeChange() {
        assertEquals(ModelTier.MEDIUM, factory.defaultTier());
        assertEquals("gpt-4o-mini", factory.resolvedModelFor(ModelTier.SMALL));
        assertEquals("gpt-4o", factory.resolvedModelFor(ModelTier.MEDIUM));
        assertEquals("gpt-4.1", factory.resolvedModelFor(ModelTier.LARGE));
    }

    @Test
    void defaultTierClientUsesTheProfileModel() {
        factory.chatClientForDefaultTier().prompt().user("hi").call().content();
        assertEquals("gpt-4o", recordingChatModel.lastModel(),
                "default tier (MEDIUM under this profile) must route its configured model");
    }
}
