package com.stellarcompact.agentruntime.chat;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.Map;

/**
 * Provider-neutral source of {@link ChatClient}s for Sovereign agents (card E4-01).
 *
 * <p>This is the single seam every later agent-runtime card builds on: prompt
 * assembly (E4-02) and structured-output coercion (E4-03) take a {@link ChatClient}
 * from here and never touch a provider type. The factory itself is built on the
 * Spring-AI-autoconfigured {@link ChatClient.Builder}, which is in turn wired from
 * <em>whatever</em> {@code ChatModel} bean the active starter contributes — Ollama by
 * default, OpenAI/others when their profile/starter is active. There is no vendor,
 * model name, or endpoint anywhere in this class (principle 4): the only model input
 * is the {@link ModelTier} → model-name mapping in {@link AgentRuntimeProperties},
 * which is pure configuration.
 *
 * <h2>How tier routing works</h2>
 * A tier is resolved to a concrete model name via {@link AgentRuntimeProperties}, then
 * applied as the per-client {@code model} option through the provider-neutral
 * {@link ChatOptions} abstraction. Because the option is set on the {@code ChatClient}
 * (not on a provider-specific options type), swapping the provider behind a tier never
 * touches this code. When a tier has no configured model the {@code model} option is
 * left unset and the provider's own default model answers — the client is still fully
 * functional.
 *
 * <p>One {@link ChatClient} is built per tier eagerly at construction and cached;
 * {@link ChatClient} instances are immutable and thread-safe, so the cache is shared
 * freely across the many virtual threads the orchestrator fans out per tick.
 */
@Component
public class SovereignChatClientFactory {

    private final AgentRuntimeProperties properties;
    private final Map<ModelTier, ChatClient> clientsByTier;

    /**
     * @param builderPrototype the Spring-AI-autoconfigured {@link ChatClient.Builder}.
     *                         It is provider-agnostic — it carries whichever
     *                         {@code ChatModel} bean is active — and is a prototype, so
     *                         we mutate a fresh copy per tier.
     * @param properties       the tier → model configuration surface
     */
    public SovereignChatClientFactory(ChatClient.Builder builderPrototype,
                                      AgentRuntimeProperties properties) {
        this.properties = properties;
        Map<ModelTier, ChatClient> cache = new EnumMap<>(ModelTier.class);
        for (ModelTier tier : ModelTier.values()) {
            cache.put(tier, buildClient(builderPrototype, properties.modelFor(tier)));
        }
        this.clientsByTier = Map.copyOf(cache);
    }

    /**
     * @return the {@link ChatClient} for {@code tier}, pre-configured with that tier's
     * resolved model (or the provider default when the tier maps to no model). Never
     * null for a non-null tier.
     */
    public ChatClient chatClientFor(ModelTier tier) {
        if (tier == null) {
            return chatClientForDefaultTier();
        }
        return clientsByTier.get(tier);
    }

    /**
     * @return the {@link ChatClient} for the configured {@link
     * AgentRuntimeProperties#defaultTier() default tier}. Used for seats whose
     * Sovereign config does not name a tier.
     */
    public ChatClient chatClientForDefaultTier() {
        return clientsByTier.get(properties.defaultTier());
    }

    /**
     * @return the orchestration default tier (from config), exposed so callers can log
     * or surface which tier a tier-less seat will use.
     */
    public ModelTier defaultTier() {
        return properties.defaultTier();
    }

    /**
     * @return the concrete model name a tier resolves to, or {@code null} if the tier
     * defers to the provider default. Useful for diagnostics/tests; carries no vendor
     * meaning by itself.
     */
    public String resolvedModelFor(ModelTier tier) {
        return properties.modelFor(tier);
    }

    private static ChatClient buildClient(ChatClient.Builder builderPrototype, String model) {
        ChatClient.Builder builder = builderPrototype.clone();
        if (model != null) {
            // Per-client model selection through the provider-neutral ChatOptions:
            // the concrete provider's ChatModel reads getModel() and routes accordingly.
            builder = builder.defaultOptions(ChatOptions.builder().model(model));
        }
        return builder.build();
    }
}
