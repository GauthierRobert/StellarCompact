package com.stellarcompact.engine.validation;

import com.stellarcompact.engine.state.TreatyId;

import java.util.Optional;

/**
 * The verdict the pure {@link ActionValidator} returns for a single
 * agent-proposed {@code Action}: either {@link Valid} or {@link Rejected}
 * (game-design 03; spec {@code docs/specs/agent-io-schema.md} section 4).
 *
 * <p>Modelled as a {@code sealed interface} of two record variants so callers
 * (the resolver pre-pass in E1-05, the agent-runtime re-prompt in E4-04) switch
 * exhaustively with no {@code default} - a rejected action can never silently
 * fall through to resolution.
 *
 * <p><b>Determinism / purity.</b> Both variants are immutable records carrying no
 * mutable state and no clock/RNG; validation is a pure function of
 * {@code (GameState, actor, Action, BalanceProfile)}.
 */
public sealed interface ValidationResult permits ValidationResult.Valid, ValidationResult.Rejected {

    /** @return {@code true} iff this verdict permits the action to reach resolution. */
    boolean isValid();

    /** The action is legal against authoritative state and may be resolved. */
    record Valid() implements ValidationResult {
        @Override
        public boolean isValid() {
            return true;
        }
    }

    /**
     * The action is illegal and must never touch state. Carries a stable
     * {@link RejectionReason} {@code code} plus a precise, human-readable
     * {@code message} fed back verbatim on the Sovereign's single re-prompt
     * (E4-04), and optional structured context the spec requires - currently the
     * blocking {@link #treatyId} for {@link RejectionReason#TREATY_FORBIDS} /
     * {@link RejectionReason#NOT_AT_PEACE}.
     *
     * <p>The {@code message} must be non-blank and, where a context value exists,
     * must include it (so the agent's re-prompt can act on it).
     */
    record Rejected(RejectionReason code, String message, Optional<TreatyId> treatyId)
            implements ValidationResult {

        public Rejected {
            if (code == null) {
                throw new IllegalArgumentException("Rejected.code must be set");
            }
            if (message == null || message.isBlank()) {
                throw new IllegalArgumentException("Rejected.message must be non-blank");
            }
            if (treatyId == null) {
                throw new IllegalArgumentException("Rejected.treatyId must be set (use Optional.empty())");
            }
        }

        @Override
        public boolean isValid() {
            return false;
        }
    }

    // ---- factory helpers (keep call sites in the validator terse) -------------

    /** The singleton-style accept verdict. */
    static ValidationResult valid() {
        return new Valid();
    }

    /** A rejection with no structured context beyond its message. */
    static ValidationResult reject(RejectionReason code, String message) {
        return new Rejected(code, message, Optional.empty());
    }

    /** A treaty-scoped rejection that carries the blocking treaty id in its context. */
    static ValidationResult rejectTreaty(RejectionReason code, TreatyId treatyId, String message) {
        return new Rejected(code, message, Optional.of(treatyId));
    }
}
