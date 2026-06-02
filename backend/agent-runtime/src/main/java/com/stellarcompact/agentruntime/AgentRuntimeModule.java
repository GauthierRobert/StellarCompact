package com.stellarcompact.agentruntime;

/**
 * Placeholder for the Spring AI agent-runtime module. Real ChatClient
 * integration (WorldView prompt build, agent JSON parse/validate, one
 * re-prompt, pluggable provider) arrives in later cards (E3-xx).
 */
public final class AgentRuntimeModule {

    private AgentRuntimeModule() {
    }

    public static String name() {
        return "agent-runtime";
    }
}
