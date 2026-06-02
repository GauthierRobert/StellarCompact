package com.stellarcompact.agentruntime.parse;

import com.stellarcompact.engine.action.AgentResponse;

/**
 * The outcome of the structured-output parse stage (card E4-03): either a typed
 * {@link AgentResponse} was coerced out of the model text, or the text was rejected
 * outright with a closed {@link ParseRejectionCode}.
 *
 * <p>This is a sealed two-case result rather than a thrown exception on purpose: the
 * parse stage must <b>never throw uncontrolled</b> on hostile model output (spec
 * section 5/5a). A rejection is a first-class value the caller (the E4-04 re-prompt
 * path) pattern-matches over - exactly one re-prompt, then Hold.
 *
 * <p>A {@link Parsed} result still carries <em>unvalidated</em> actions: parsing only
 * guarantees the wire shape (closed Action set, sentinel for unknowns). Engine-rule
 * validation (legality in the current {@code GameState}) is E1-04 / E4-04's job and
 * is deliberately not done here - "validate after parse, never trust the model".
 */
public sealed interface ParseResult permits ParseResult.Parsed, ParseResult.Rejected {

    /** @return {@code true} iff this is a {@link Parsed} result. */
    default boolean isParsed() {
        return this instanceof Parsed;
    }

    /**
     * A successfully coerced response. {@code response} is shape-valid and immutable;
     * its {@code actions} may contain {@code UnknownAction} sentinels (unknown variants
     * that degraded rather than aborting the parse). It is NOT yet engine-validated.
     */
    record Parsed(AgentResponse response) implements ParseResult {
        public Parsed {
            if (response == null) {
                throw new IllegalArgumentException("ParseResult.Parsed.response must be set");
            }
        }
    }

    /**
     * The model output could not be turned into a usable response.
     *
     * @param code   the closed, actor-safe reason
     * @param detail a short diagnostic for server-side logging ONLY - it may quote
     *               parser internals and MUST NOT be echoed verbatim into a re-prompt
     *               (the re-prompt uses {@code code}); never null
     */
    record Rejected(ParseRejectionCode code, String detail) implements ParseResult {
        public Rejected {
            if (code == null) {
                throw new IllegalArgumentException("ParseResult.Rejected.code must be set");
            }
            detail = detail == null ? "" : detail;
        }

        static Rejected of(ParseRejectionCode code, String detail) {
            return new Rejected(code, detail);
        }
    }
}
