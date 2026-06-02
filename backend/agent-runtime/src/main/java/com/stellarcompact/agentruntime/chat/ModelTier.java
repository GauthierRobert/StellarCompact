package com.stellarcompact.agentruntime.chat;

/**
 * An <em>orchestration</em> abstraction over model capability/cost — never a vendor
 * or a concrete model name.
 *
 * <p>A Sovereign is configured with a tier (a cheap local model for free play, a
 * larger hosted model for premium/tournament play). The mapping from a tier to a
 * concrete model — and therefore to a provider — lives entirely in configuration
 * ({@link AgentRuntimeProperties#tiers()}); swapping the model behind a tier, or the
 * provider behind that model, is a properties/profile change with no code change
 * (principle 4, provider neutrality).
 *
 * <p>The tiers are deliberately coarse and provider-agnostic. They say nothing about
 * <em>which</em> model answers — only about the class of model the orchestrator is
 * willing to spend on for that seat.
 */
public enum ModelTier {

    /** Cheapest/fastest — the default for free play (typically a small local model). */
    SMALL,

    /** Mid capability — a balance of quality and cost. */
    MEDIUM,

    /** Highest capability — premium/tournament play (typically a hosted model). */
    LARGE
}
