package com.stellarcompact.engine.action;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.stellarcompact.engine.state.FactionId;

import java.util.List;

/**
 * What a Sovereign returns each tick (spec docs/specs/agent-io-schema.md
 * section 2): a batch of negotiation {@link Message}s and an ordered list of
 * {@link Action}s, tagged with the {@code schemaVersion} the agent emitted
 * against.
 *
 * <p><b>Untrusted boundary.</b> This is the top-level shape of raw agent output.
 * It is intentionally minimal - messages, actions, version - and carries no
 * engine-internal state; the agent cannot name anything authoritative through it.
 * Both lists are defensively copied to unmodifiable lists in the compact
 * constructor, so a parsed response is immutable and safe to hand to the
 * single-threaded validator/resolver.
 *
 * <p><b>Forward compatibility.</b> {@code schemaVersion} lets an older engine
 * recognise a newer agent. Unknown action {@code type}s inside {@code actions}
 * deserialise to {@link UnknownAction} (never throwing); see {@link Action}. The
 * record itself is lenient on unknown <em>top-level</em> keys
 * ({@code ignoreUnknown = true}) so a future field a newer agent adds does not
 * crash an older parser - again, degrade rather than fail.
 *
 * <p>Validation (E1-04) decides what to keep; this card only fixes the closed
 * shape.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AgentResponse(
        List<Message> messages,
        List<Action> actions,
        int schemaVersion
) {

    /**
     * The current wire schema version emitted by this engine build. Adding an
     * {@link Action} variant is a minor bump (spec section 6); the constant lives
     * here as the single source of truth and is the value a freshly constructed
     * {@link #now(List, List)} response carries.
     */
    public static final int CURRENT_SCHEMA_VERSION = 1;

    public AgentResponse {
        // Tolerate a null list from a sloppy/minimal agent payload by treating it
        // as empty, then defensively copy to an unmodifiable snapshot.
        messages = messages == null ? List.of() : List.copyOf(messages);
        actions = actions == null ? List.of() : List.copyOf(actions);
        if (schemaVersion < 0) {
            throw new IllegalArgumentException("AgentResponse.schemaVersion must be >= 0");
        }
    }

    /**
     * Convenience factory stamping {@link #CURRENT_SCHEMA_VERSION}. Used by the
     * engine/tests when constructing a response in code (agents themselves emit
     * JSON that is parsed, carrying whatever version they declared).
     */
    public static AgentResponse now(List<Message> messages, List<Action> actions) {
        return new AgentResponse(messages, actions, CURRENT_SCHEMA_VERSION);
    }

    /**
     * A single negotiation-phase message addressed to one faction
     * (spec section 2). Free-form text; the length cap is enforced at validation
     * (E1-04), not in this shape record.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Message(FactionId to, String text) {
        public Message {
            if (to == null) {
                throw new IllegalArgumentException("Message.to must be set");
            }
            if (text == null) {
                throw new IllegalArgumentException("Message.text must be set");
            }
        }
    }
}
