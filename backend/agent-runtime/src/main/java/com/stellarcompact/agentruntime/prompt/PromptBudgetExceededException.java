package com.stellarcompact.agentruntime.prompt;

/**
 * Thrown by {@link PromptAssembler} when an assembled prompt's estimated token count
 * exceeds the configured budget ({@code stellar-compact.agent.prompt.max-tokens}) —
 * card E4-02.
 *
 * <p><b>Budget rule.</b> The WorldView is already built to be token-compact server-side
 * (E3-02 fog-filtering), and the static system-prompt sections are fixed and small. So
 * an over-budget prompt signals a genuine misconfiguration or an abnormally large
 * WorldView, not a routine condition. The assembler therefore <em>fails fast</em> here
 * rather than silently truncating — truncating the WorldView would hand the model a
 * partial, misleading perception (it might "see" only some of its systems and make a
 * bad move), and truncating the schema/rules would let it emit invalid output. The
 * orchestrator treats this exactly like any other agent-call failure for that phase: a
 * {@code Hold} for that faction this tick, logged — the galaxy never waits on, nor is
 * misled by, one over-budget brain. Raising the budget is a config-only fix.
 *
 * <p>Unchecked so it propagates cleanly out of the per-agent virtual thread to the
 * orchestrator's per-phase {@code StructuredTaskScope}, which already maps a failed
 * subtask to {@code Hold}.
 */
public final class PromptBudgetExceededException extends RuntimeException {

    private final int estimatedTokens;
    private final int maxTokens;

    public PromptBudgetExceededException(int estimatedTokens, int maxTokens) {
        super("Assembled prompt is over the token budget: estimated %d tokens > max %d. "
                .formatted(estimatedTokens, maxTokens)
                + "Raise stellar-compact.agent.prompt.max-tokens or shrink the WorldView.");
        this.estimatedTokens = estimatedTokens;
        this.maxTokens = maxTokens;
    }

    /** @return the estimated token count that tripped the budget. */
    public int estimatedTokens() {
        return estimatedTokens;
    }

    /** @return the configured maximum that was exceeded. */
    public int maxTokens() {
        return maxTokens;
    }
}