package com.stellarcompact.orchestrator.sovereign;

import com.stellarcompact.engine.action.AgentResponse;
import com.stellarcompact.engine.state.FactionId;

/**
 * The orchestrator's view of one agent playing one faction seat (E3-01).
 *
 * <p>A Sovereign is, conceptually, a pure function {@code WorldView -> AgentResponse}
 * ({@code {messages[], actions[]}}) - see {@code .claude/skills/agent-sovereign} and
 * {@code docs/specs/agent-io-schema.md} section 7. The default production
 * implementation is LLM-backed (card E4, in the {@code agent-runtime} module); the
 * {@link ScriptedSovereign} bot in this package implements the <em>same</em>
 * interface for tests and to fill empty seats.
 *
 * <p><b>Provider neutrality (principle 4, non-negotiable).</b> This is a plain Java
 * interface with <em>no</em> Spring or Spring AI types in its signature. The LLM
 * Sovereign wires a Spring AI {@code ChatClient} behind this contract; the
 * orchestrator never sees a vendor type. Swapping Ollama for OpenAI is a config
 * change behind an implementation of this interface, never a change here.
 *
 * <p><b>Orchestration contract.</b> The orchestrator fans out across all seated
 * Sovereigns inside a per-phase {@code StructuredTaskScope} under a shared deadline
 * (one virtual thread each). A Sovereign that times out or throws contributes
 * {@code Hold} for that phase - the galaxy never waits on one brain. Accordingly:
 * {@link #decide(WorldView)} <b>must not throw</b> on a well-formed {@link WorldView},
 * and an agent with nothing useful to do returns a single {@code Hold} action and no
 * messages rather than an empty or null response.
 *
 * <p><b>Determinism note.</b> The interface itself imposes no determinism (an LLM is
 * non-deterministic by nature; reproducibility comes from the recorded action log,
 * see {@code docs/architecture/03-agent-runtime.md} section 4). The scripted bot,
 * however, <em>is</em> contractually deterministic - same {@link WorldView} (and any
 * seed it was constructed with) yields the same {@link AgentResponse} every run.
 */
public interface Sovereign {

    /**
     * @return the faction seat this Sovereign plays. The orchestrator uses it to map
     * the returned {@link AgentResponse} back to its actor when building the
     * tick's submitted-action batch.
     */
    FactionId factionId();

    /**
     * Decide this faction's response for one tick from its fog-filtered perception.
     *
     * @param view the per-faction {@link WorldView} (never {@code null}); the agent's
     *             entire perception this tick
     * @return the agent's {@link AgentResponse} - negotiation messages plus the
     * ordered action list. Never {@code null}; returns a lone {@code Hold} when idle.
     * Output is still validated by the engine's {@code ActionValidator} before it can
     * reach resolution; this method's contract is only to produce a well-formed,
     * non-null response.
     */
    AgentResponse decide(WorldView view);
}
