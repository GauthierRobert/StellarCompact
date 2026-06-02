package com.stellarcompact.orchestrator.tick;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * The real-time tick-loop timings for the orchestrator (card E4-05). Every timing the
 * {@link TickOrchestrator} uses is here, never a hardcoded constant (rule&nbsp;6):
 * the tick cadence and the per-phase deadlines that bound the structured-concurrency
 * fan-out.
 *
 * <p><b>Why properties, not the BalanceProfile.</b> These are <em>orchestration</em>
 * timings (wall-clock deadlines, pacing) - not gameplay numbers and emphatically not
 * engine inputs. The engine resolver is pure and must never see a wall-clock value
 * (principle&nbsp;1, determinism); a slower or faster phase deadline must never change
 * the resolved state. So the deadlines live in Spring config bound from the
 * {@code stellar-compact.orchestrator.tick} namespace, entirely outside the
 * deterministic engine boundary. The {@code BalanceProfile} keeps its own gameplay
 * tick pacing; this block is the <em>scheduler's</em> view and is what gates the
 * agent-call phases.
 *
 * <p>Each phase that calls agents (Negotiation, then Action) gets its own deadline so a
 * slow brain in one phase cannot consume another phase's budget. A {@code Duration} of
 * zero or negative for a phase means "effectively no wait" - the deadline has already
 * passed, every straggler Holds - and is legal (used by tests to force the straggler
 * path).
 *
 * <p>Example:
 * <pre>
 * stellar-compact:
 *   orchestrator:
 *     tick:
 *       interval: 5s
 *       negotiation-timeout: 3s
 *       action-timeout: 8s
 *       negotiation-rounds: 1
 * </pre>
 *
 * @param interval           the wall-clock cadence between tick starts (never null,
 *                           defaults to 5s)
 * @param negotiationTimeout the shared per-phase deadline for the Negotiation fan-out
 *                           (never null, defaults to 3s)
 * @param actionTimeout      the shared per-phase deadline for the Action fan-out - the
 *                           Perception build runs server-side first and is folded into
 *                           this agent-call phase (never null, defaults to 8s)
 * @param negotiationRounds  how many negotiation rounds to run (E4-06 fleshes these out;
 *                           {@code 0} skips negotiation entirely; defaults to 1)
 */
@ConfigurationProperties(prefix = "stellar-compact.orchestrator.tick")
public record TickProperties(
        Duration interval,
        Duration negotiationTimeout,
        Duration actionTimeout,
        int negotiationRounds
) {

    /** Default cadence between ticks when unset. */
    public static final Duration DEFAULT_INTERVAL = Duration.ofSeconds(5);
    /** Default per-phase deadline for the Negotiation fan-out when unset. */
    public static final Duration DEFAULT_NEGOTIATION_TIMEOUT = Duration.ofSeconds(3);
    /** Default per-phase deadline for the Action fan-out when unset. */
    public static final Duration DEFAULT_ACTION_TIMEOUT = Duration.ofSeconds(8);
    /** Default number of negotiation rounds when unset. */
    public static final int DEFAULT_NEGOTIATION_ROUNDS = 1;

    public TickProperties {
        if (interval == null) {
            interval = DEFAULT_INTERVAL;
        }
        if (negotiationTimeout == null) {
            negotiationTimeout = DEFAULT_NEGOTIATION_TIMEOUT;
        }
        if (actionTimeout == null) {
            actionTimeout = DEFAULT_ACTION_TIMEOUT;
        }
        if (negotiationRounds < 0) {
            negotiationRounds = DEFAULT_NEGOTIATION_ROUNDS;
        }
    }

    /** @return the all-defaults timings (handy for tests and non-Spring construction). */
    public static TickProperties defaults() {
        return new TickProperties(DEFAULT_INTERVAL, DEFAULT_NEGOTIATION_TIMEOUT,
                DEFAULT_ACTION_TIMEOUT, DEFAULT_NEGOTIATION_ROUNDS);
    }
}
