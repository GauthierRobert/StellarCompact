package com.stellarcompact.agentruntime.validate;

import com.stellarcompact.agentruntime.parse.ParseRejectionCode;
import com.stellarcompact.engine.validation.RejectionReason;
import com.stellarcompact.engine.validation.ValidationResult;

import java.util.List;

/**
 * Renders the corrective feedback appended to the single re-prompt (card E4-04),
 * security-sensitive. This is the prompt-injection boundary: the feedback is built
 * <b>only</b> from closed reason codes and engine-produced messages, NEVER from raw
 * model output.
 *
 * <h2>Why this is safe to feed back</h2>
 * Two distinct sources reach this class, each with a different trust level:
 * <ul>
 *   <li><b>Parse rejections</b> ({@link ParseRejectionCode}) - a closed enum. We map
 *       each constant to a fixed, hand-authored sentence. The parser's
 *       {@code Rejected.detail} (which may quote hostile model bytes) is
 *       <em>deliberately ignored here</em> - it is server-log-only (spec section 5).
 *       So a model cannot smuggle an instruction into the next prompt by crafting its
 *       malformed output: only the code, which it does not control the rendering of,
 *       survives.</li>
 *   <li><b>Validation rejections</b> ({@link ValidationResult.Rejected}) - the
 *       {@code message} is produced by the pure engine {@code ActionValidator}, not by
 *       the model. Per the engine's own contract (RejectionReason javadoc) that message
 *       references only actor-knowable facts (own stockpiles, the public treaty ledger,
 *       an id the actor itself supplied) and is explicitly intended to be fed back
 *       verbatim. It is therefore trusted text. We still prefix it with the stable
 *       {@link RejectionReason} code so the agent gets the machine-readable reason too.</li>
 * </ul>
 *
 * The model's own free text (its messages, any prose around its JSON) is never an input
 * to this class. That is the invariant: re-prompt feedback derives only from
 * engine/parser-controlled values.
 */
final class RejectionFeedback {

    private RejectionFeedback() {
    }

    /** Header line introducing the corrective block in the re-prompt. */
    static final String HEADER =
            "Your previous response was rejected. Fix ONLY the issues below and resend a "
                    + "single valid JSON AgentResponse. Do not repeat the rejected items.";

    /**
     * Build the feedback for a parse-stage rejection (no actions were even extractable).
     * Uses the closed {@link ParseRejectionCode} only; the parser detail is never echoed.
     */
    static String forParseRejection(ParseRejectionCode code) {
        return HEADER + "\n- " + parseReason(code);
    }

    /**
     * Build the feedback for one or more per-action validation rejections. Each line
     * names the stable engine {@link RejectionReason} code and the engine-produced,
     * actor-safe message verbatim. {@code rejections} must be non-empty.
     */
    static String forValidationRejections(List<ValidationResult.Rejected> rejections) {
        StringBuilder sb = new StringBuilder(HEADER);
        for (ValidationResult.Rejected r : rejections) {
            sb.append("\n- [").append(r.code()).append("] ").append(r.message());
        }
        return sb.toString();
    }

    /**
     * Fixed, model-uncontrolled sentence per parse-rejection code. No branch echoes the
     * raw output - the text is entirely authored here.
     */
    private static String parseReason(ParseRejectionCode code) {
        return switch (code) {
            case OVERSIZED -> "[OVERSIZED] Your output was too large. Emit a single compact "
                    + "JSON object only.";
            case NO_JSON -> "[NO_JSON] No JSON object was found. Respond with exactly one JSON "
                    + "AgentResponse object and no prose.";
            case MALFORMED_JSON -> "[MALFORMED_JSON] Your output was not well-formed JSON. "
                    + "Resend a single syntactically valid JSON object.";
            case AMBIGUOUS -> "[AMBIGUOUS] Your output contained more than one top-level JSON "
                    + "value. Respond with exactly one JSON object.";
            case SHAPE_INVALID -> "[SHAPE_INVALID] Your JSON did not match the AgentResponse "
                    + "schema. Use {\"messages\":[...],\"actions\":[...]} with the documented "
                    + "action shapes.";
            case TOO_MANY_ELEMENTS -> "[TOO_MANY_ELEMENTS] You emitted too many actions or "
                    + "messages. Send fewer, prioritised entries.";
        };
    }
}
