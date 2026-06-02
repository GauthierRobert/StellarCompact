package com.stellarcompact.engine.state;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * A first-class state of open war between two factions (game-design 05; the
 * "war-state record" the E1-04 security review (spec section 4a, F1) defers to
 * this card E1-09). War is the positive precondition that legalises a kinetic
 * action against a <em>non-neutral</em> target: absence of a forbidding treaty is
 * necessary but <em>not sufficient</em> - a faction must not strike one it is at
 * peace-but-not-treaty with. {@link com.stellarcompact.engine.validation.ActionValidator}
 * consults the active wars to gate {@code Attack}/{@code Blockade}/{@code Raid}.
 *
 * <p><b>Symmetric, canonical pair.</b> War is mutual, so {@code (a, b)} and
 * {@code (b, a)} denote the same war. The compact constructor stores the pair in a
 * canonical order ({@code a.value() <= b.value()} by string) so the same war is one
 * value regardless of who declared it - two {@code WarState}s for the same pair are
 * {@link #equals} and collapse in a {@link java.util.Set}. {@code sinceTick} records
 * when the war began (for war-exhaustion / events in later cards); it is excluded
 * from identity so re-declaring an existing war does not create a duplicate.
 *
 * <p>Immutable record; no I/O, no clock, no RNG.
 */
@JsonIgnoreProperties(ignoreUnknown = false)
public record WarState(FactionId a, FactionId b, long sinceTick) {

    public WarState {
        if (a == null || b == null) {
            throw new IllegalArgumentException("WarState parties must both be set");
        }
        if (a.equals(b)) {
            throw new IllegalArgumentException("a faction cannot be at war with itself: " + a.value());
        }
        if (sinceTick < 0) {
            throw new IllegalArgumentException("WarState.sinceTick must be >= 0");
        }
        // Canonical orientation so the unordered pair is one value.
        if (a.value().compareTo(b.value()) > 0) {
            FactionId tmp = a;
            a = b;
            b = tmp;
        }
    }

    /** Factory that normalises the pair; {@code sinceTick} is when the war began. */
    public static WarState between(FactionId x, FactionId y, long sinceTick) {
        return new WarState(x, y, sinceTick);
    }

    /** @return true iff {@code faction} is one of this war's two belligerents. */
    public boolean involves(FactionId faction) {
        return a.equals(faction) || b.equals(faction);
    }

    /** @return true iff this war is exactly between {@code x} and {@code y} (either order). */
    public boolean isBetween(FactionId x, FactionId y) {
        return (a.equals(x) && b.equals(y)) || (a.equals(y) && b.equals(x));
    }

    /**
     * War identity is the unordered pair only - {@code sinceTick} is excluded so
     * re-declaring an existing war is idempotent (it does not reset the start tick
     * nor create a duplicate set member).
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof WarState other)) {
            return false;
        }
        return a.equals(other.a) && b.equals(other.b);
    }

    @Override
    public int hashCode() {
        return a.hashCode() * 31 + b.hashCode();
    }
}
