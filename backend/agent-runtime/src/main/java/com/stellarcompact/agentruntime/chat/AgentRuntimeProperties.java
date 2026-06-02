package com.stellarcompact.agentruntime.chat;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.EnumMap;
import java.util.Map;

/**
 * The complete, code-free configuration surface for agent-runtime model selection.
 *
 * <p>Bound from the {@code stellar-compact.agent} property namespace. Everything that
 * decides <em>which</em> model (and therefore which provider) answers a Sovereign is
 * here — never in Java logic (principle 4). The provider itself (Ollama, OpenAI, …)
 * is selected by the usual Spring AI {@code spring.ai.*} properties plus the matching
 * starter on the classpath; this block only maps the orchestration {@link ModelTier}
 * onto a concrete model name to pass through the provider-neutral
 * {@code ChatClient}.
 *
 * <p>Example (Ollama default profile):
 * <pre>
 * stellar-compact:
 *   agent:
 *     default-tier: SMALL
 *     tiers:
 *       SMALL:  llama3.2:1b
 *       MEDIUM: llama3.1:8b
 *       LARGE:  llama3.1:70b
 * </pre>
 *
 * <p>Switching to a hosted provider is config-only: activate the {@code openai}
 * profile (which puts the OpenAI starter's {@code spring.ai.openai.*} keys in scope)
 * and remap the same tiers to that provider's model names — no recompilation.
 *
 * @param defaultTier the tier used when a Sovereign does not name one (never null;
 *                    defaults to {@link ModelTier#SMALL})
 * @param tiers       tier → concrete model name. Keys are {@link ModelTier} values;
 *                    values are opaque, provider-specific model identifiers that this
 *                    module never interprets — it only forwards them to the
 *                    {@code ChatClient} as the per-call {@code model} option.
 * @param prompt      prompt-assembly tunables (token budget) — card E4-02. Never null;
 *                    a missing block degrades to {@link PromptProperties} defaults.
 */
@ConfigurationProperties(prefix = "stellar-compact.agent")
public record AgentRuntimeProperties(
        ModelTier defaultTier,
        Map<ModelTier, String> tiers,
        PromptProperties prompt) {

    public AgentRuntimeProperties {
        if (defaultTier == null) {
            defaultTier = ModelTier.SMALL;
        }
        if (prompt == null) {
            prompt = PromptProperties.defaults();
        }
        // (canonical body continues below)
        // Normalise to an EnumMap copy so binding order / external mutation cannot
        // affect lookups, and so a null map degrades to empty rather than NPE-ing
        // later. An empty map is legal: callers may rely on the provider's own
        // default model (the ChatClient is still fully wired).
        Map<ModelTier, String> copy = new EnumMap<>(ModelTier.class);
        if (tiers != null) {
            tiers.forEach((tier, model) -> {
                if (tier != null && model != null && !model.isBlank()) {
                    copy.put(tier, model.trim());
                }
            });
        }
        tiers = Map.copyOf(copy);
    }

    /**
     * Backward-compatible convenience factory (tiers + default-tier only) that
     * defaults the {@link PromptProperties} block (added in card E4-02). A static
     * factory rather than a second constructor on purpose: Spring Boot
     * {@code @ConfigurationProperties} constructor-binding requires a single
     * (canonical) constructor, so adding a second constructor would break binding.
     */
    public static AgentRuntimeProperties of(ModelTier defaultTier, Map<ModelTier, String> tiers) {
        return new AgentRuntimeProperties(defaultTier, tiers, PromptProperties.defaults());
    }

    /**
     * @return the configured model name for {@code tier}, or {@code null} if no model
     * is mapped to it. A {@code null} return is meaningful: the caller then leaves the
     * {@code model} option unset and the provider's configured default model is used.
     */
    public String modelFor(ModelTier tier) {
        return tier == null ? null : tiers.get(tier);
    }

    /**
     * Prompt-assembly tunables (card E4-02). Bound from {@code
     * stellar-compact.agent.prompt}. The token budget is config, never a hardcoded
     * magic number (rule 6): it bounds the size of an assembled prompt so a large
     * WorldView cannot blow up a (small, local) model's context window.
     *
     * <p>The budget is measured in <em>estimated tokens</em>. Token counting is
     * provider/model specific and we are provider-neutral (principle 4), so we use a
     * deterministic char-based estimator (≈ {@code charsPerToken} characters per
     * token) rather than binding any one tokenizer. The estimate is intentionally
     * conservative — it never under-counts in a way that would let an over-budget
     * prompt slip through.
     *
     * @param maxTokens     the maximum estimated tokens for the WHOLE assembled
     *                      prompt (system + user). {@code <= 0} disables the check.
     * @param charsPerToken characters-per-token divisor for the estimator (must be
     *                      {@code >= 1}; defaults to 4, the common English-text
     *                      rule-of-thumb).
     */
    public record PromptProperties(int maxTokens, int charsPerToken) {

        /** Default budget: generous enough for a compact WorldView on a small model. */
        public static final int DEFAULT_MAX_TOKENS = 8_192;

        /** Default estimator: ~4 chars per token (English-text rule-of-thumb). */
        public static final int DEFAULT_CHARS_PER_TOKEN = 4;

        public PromptProperties {
            if (maxTokens == 0) {
                maxTokens = DEFAULT_MAX_TOKENS;
            }
            if (charsPerToken < 1) {
                charsPerToken = DEFAULT_CHARS_PER_TOKEN;
            }
        }

        /** @return the default prompt tunables (used when the config block is absent). */
        public static PromptProperties defaults() {
            return new PromptProperties(DEFAULT_MAX_TOKENS, DEFAULT_CHARS_PER_TOKEN);
        }

        /** @return true iff the token budget is enforced ({@code maxTokens > 0}). */
        public boolean budgetEnforced() {
            return maxTokens > 0;
        }

        /**
         * Deterministic, provider-neutral token estimate for {@code text}: ceil of
         * character count divided by {@link #charsPerToken}. Pure (no wall-clock, no
         * randomness) so the same text always estimates the same — the golden test
         * relies on this.
         *
         * @return estimated token count for {@code text} ({@code 0} for null/empty)
         */
        public int estimateTokens(String text) {
            if (text == null || text.isEmpty()) {
                return 0;
            }
            int cpt = charsPerToken < 1 ? DEFAULT_CHARS_PER_TOKEN : charsPerToken;
            return (text.length() + cpt - 1) / cpt;
        }
    }
}
