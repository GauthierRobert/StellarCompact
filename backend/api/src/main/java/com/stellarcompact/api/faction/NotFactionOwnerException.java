package com.stellarcompact.api.faction;

/**
 * Thrown when a mutating faction-config operation ({@code POST} re-attach / {@code PATCH})
 * is attempted by a principal that is not the seat's registered owner, or by an
 * unauthenticated request. Mapped to {@code 403 Forbidden}.
 *
 * <p>Note the asymmetry with {@code GET}: a non-owner <em>read</em> is NOT an error - it
 * succeeds with a redacted view (so the seat's public identity stays discoverable). Only
 * <em>writes</em> are owner-gated to a 403; reads are owner-gated to redaction.
 */
public class NotFactionOwnerException extends RuntimeException {
    public NotFactionOwnerException(String message) {
        super(message);
    }
}
