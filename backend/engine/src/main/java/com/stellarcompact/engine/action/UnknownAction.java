package com.stellarcompact.engine.action;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Forward-compatibility sentinel (spec section 6). Any {@code Action} JSON whose
 * {@code type} discriminator is not one of the 25 known variants deserialises to
 * this inert record instead of throwing, via {@code defaultImpl =
 * UnknownAction.class} on {@link Action}.
 *
 * <p><b>Why this exists.</b> The schema is versioned and additive: a newer
 * Sovereign may emit a future variant an older engine has never heard of. The
 * determinism + engine-authority contract requires that such input <em>degrades
 * safely</em> - a single unknown entry in an {@code actions[]} array must not
 * abort parsing of the whole {@link AgentResponse}. The validator (E1-04) treats
 * any {@code UnknownAction} as an unrecognised move and drops it / falls back to
 * {@link Action.Hold} for that slot.
 *
 * <p><b>Security.</b> This record is deliberately a black hole: it captures only
 * the offending {@code type} string for diagnostics and {@code @JsonIgnoreProperties
 * (ignoreUnknown = true)} swallows every other field. No future payload can be
 * smuggled into engine-internal types through it, and it is itself a closed leaf
 * of the sealed {@link Action} hierarchy - the resolver's exhaustive switch must
 * handle it (and does so by ignoring it), so an unknown action can never reach
 * authoritative state.
 *
 * <p>It is part of the {@code permits} list of {@link Action} so the sealed set
 * stays closed at compile time while remaining open to <em>ignorable</em> future
 * input at parse time.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record UnknownAction(String type) implements Action {

    public UnknownAction {
        // type may legitimately be null when the discriminator was entirely
        // absent; normalise to a stable label so equals()/hashCode() and any
        // diagnostic logging downstream are well-defined.
        if (type == null || type.isBlank()) {
            type = "<unknown>";
        }
    }

    @Override
    public String type() {
        return type;
    }
}
