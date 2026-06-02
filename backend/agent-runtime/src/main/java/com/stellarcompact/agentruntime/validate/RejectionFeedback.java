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
 *       verbatim. It is therefore trusted as to <em>source</em>. As defence-in-depth
 *       (X-01 finding X01-1) we still {@link #sanitize(String) sanitize} it - stripping
 *       control characters and capping length - because a handful of validator messages
 *       interpolate an agent-supplied free-string (e.g. {@code shipSpec}), and a model
 *       must never be able to restructure its own next prompt through that channel. We
 *       prefix the stable {@link RejectionReason} code so the agent still gets the
 *       machine-readable reason.</li>
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
            sb.append("\n- [").append(r.code()).append("] ").append(sanitize(r.message()));
        }
        return sb.toString();
    }

    /**
     * Defence-in-depth (X-01 finding X01-1): the engine {@code message} mostly references
     * typed ids/enums, but a few validator messages interpolate an agent-supplied free-string
     * verbatim (e.g. {@code BuildFleet.shipSpec}, a self-supplied {@code SystemId}). Those are
     * bounded only by the parser's 32&nbsp;KiB string cap, so a model could embed newline-led
     * instruction text and have it reflected into its own next prompt (self-injection). Before
     * any engine message enters the re-prompt we strip CR/LF and other control characters
     * (which is what would let injected text break out of the bullet line) and cap the length,
     * so reflected agent text can never restructure the prompt. The blast radius was always
     * same-agent-only (never another faction, never hidden state); this removes it entirely.
     */
    private static String sanitize(String message) {
        if (message == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(Math.min(message.length(), MAX_MESSAGE_CHARS));
        for (int i = 0; i < message.length() && out.length() < MAX_MESSAGE_CHARS; i++) {
            char c = message.charAt(i);
            // Replace any ISO control char (incl. \r, \n, \t) with a single space; keep the rest.
            out.append(Character.isISOControl(c) ? ' ' : c);
        }
        if (message.length() > MAX_MESSAGE_CHARS) {
            out.append('…');
        }
        return out.toString();
    }

    /** Upper bound on a single reflected validation message; ample for a real reason sentence. */
    private static final int MAX_MESSAGE_CHARS = 200;

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
