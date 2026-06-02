package com.stellarcompact.agentruntime.parse;

/**
 * The closed set of reasons the structured-output parse stage (card E4-03) can
 * reject a model response <em>outright</em>, before any engine-rule validation
 * (E1-04 / E4-04) runs.
 *
 * <p>These are <b>transport/shape</b> rejections, distinct from the engine's
 * semantic {@code RejectionReason} (treaty-forbids, insufficient-resources, ...): a
 * code here means "this text is not a usable {@code AgentResponse} at all", whereas
 * an engine rejection means "this well-formed action is not legal in this state".
 * Keeping the two enums separate stops parse-layer DoS/garbage handling from leaking
 * into the deterministic engine vocabulary.
 *
 * <p><b>Why a code, not a free string.</b> The output of E4-03 feeds the single
 * re-prompt path (E4-04). A closed code lets that path render a fixed, actor-safe
 * reason ("your previous output was not valid JSON", ...) without echoing untrusted
 * model text back into the next prompt verbatim.
 */
public enum ParseRejectionCode {

    /**
     * The raw model output exceeded a configured raw-resource bound (total document
     * size, string length, nesting depth, number length) before parsing could even
     * begin - a pre-validation DoS guard (spec section 5a). The payload is rejected
     * without being fully parsed.
     */
    OVERSIZED,

    /**
     * No JSON object could be located in the output at all (empty/blank, or pure
     * prose with no {@code { ... }} body).
     */
    NO_JSON,

    /**
     * The located text is not well-formed JSON (syntax error, truncated object,
     * unbalanced braces).
     */
    MALFORMED_JSON,

    /**
     * The output is ambiguous: more than one top-level JSON object/value was found
     * (e.g. two concatenated objects, or a JSON array of responses). Per spec
     * section 5 ambiguous output is rejected rather than guessed at - picking one
     * silently would let a model smuggle a second hidden response.
     */
    AMBIGUOUS,

    /**
     * A single well-formed JSON object was found but it does not bind to the
     * {@code AgentResponse} shape (e.g. {@code actions} is not an array, a required
     * scalar has the wrong JSON type, or a record invariant in the top-level shape
     * was violated). A bad <em>nested action</em> does NOT land here - those are
     * isolated to an inert sentinel per spec section 5a; this is reserved for a
     * malformed <em>top-level</em> response.
     */
    SHAPE_INVALID,

    /**
     * A structural cap on the parsed response was exceeded - too many {@code actions}
     * or {@code messages} entries (spec section 5a raw caps). Distinct from per-entry
     * semantic caps (text length, ...) which E1-04 enforces.
     */
    TOO_MANY_ELEMENTS
}
