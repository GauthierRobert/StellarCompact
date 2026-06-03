package com.stellarcompact.api.match;

import java.util.Locale;
import java.util.Optional;

/**
 * The closed whitelist of agent types that may sit a faction seat in a created match
 * (board card E11-04; findings doc {@code 10-four-agent-live-sim-findings.md} L6 / decision
 * P3). This enum is the <em>only</em> bridge between the untrusted {@code seats} field on
 * {@link CreateGameRequest} and a concrete orchestrator {@code Sovereign}: the service maps a
 * validated {@code SeatType} to an instance through an exhaustive {@code switch}, never by
 * reflectively loading a class name from request input (principle 5, closed agent I/O;
 * &#x1F512; security: no arbitrary instantiation).
 *
 * <p><b>Why parse from a string, not deserialize directly.</b> The request carries seat types
 * as raw strings so this class - not Jackson - owns the closed-set check: an unknown token is
 * surfaced as a clear, caller-facing {@code 400} via {@link #parse(String)} rather than a
 * framework deserialization error. Any value outside this set is rejected; nothing
 * default-throughs silently.
 *
 * <ul>
 *   <li>{@link #SCRIPTED} - the deterministic {@code ScriptedSovereign} (economy bot; the
 *       default seat, and the safe fill for empty seats).</li>
 *   <li>{@link #AGGRESSIVE} - the deterministic {@code AggressiveScriptedSovereign} (E10-03)
 *       that drives war/diplomacy so combat fires headlessly.</li>
 *   <li>{@link #LLM} - a Spring AI-backed Sovereign (provider by config, Ollama default,
 *       principle 4). <b>Whitelisted but not wired in this build</b>: the api module does not
 *       depend on {@code agent-runtime} and no concrete LLM {@code Sovereign} adapter exists
 *       on its classpath, so the service rejects an {@code LLM} seat with a clear {@code 400}
 *       rather than silently falling back to a scripted bot or instantiating Spring AI
 *       machinery from request input.</li>
 * </ul>
 */
public enum SeatType {
    SCRIPTED,
    AGGRESSIVE,
    LLM;

    /**
     * Parse one untrusted seat token against this closed whitelist, case-insensitively and
     * trimming surrounding whitespace. The token must name exactly one constant; an unknown,
     * blank or {@code null} token yields {@link Optional#empty()} so the caller can reject the
     * whole request with a clear {@code 400} (it never defaults through to a seat type).
     *
     * @param token the raw seat token from the request (may be {@code null})
     * @return the matching {@code SeatType}, or empty if the token is not in the whitelist
     */
    static Optional<SeatType> parse(String token) {
        if (token == null) {
            return Optional.empty();
        }
        String normalised = token.strip().toUpperCase(Locale.ROOT);
        for (SeatType type : values()) {
            if (type.name().equals(normalised)) {
                return Optional.of(type);
            }
        }
        return Optional.empty();
    }
}
