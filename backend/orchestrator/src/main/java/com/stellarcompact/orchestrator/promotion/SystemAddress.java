package com.stellarcompact.orchestrator.promotion;

import com.stellarcompact.engine.state.SystemId;

/**
 * The bidirectional, lossless bridge between a procedural star's numeric id (the
 * {@code long} {@code Star#id()} the galaxy module seeds rosters from) and the
 * engine's string-typed {@link SystemId} (the key a promoted {@code ActiveSystem}
 * lives under in {@code GameState}).
 *
 * <p><strong>Why a stable encoding matters (E2-05 round-trip).</strong> The galaxy
 * module addresses stars by an opaque 64-bit id; the engine addresses active
 * systems by a human-readable {@link SystemId} string. Promotion must carry the
 * star id <em>through</em> the engine id so demotion + re-promotion regenerates the
 * very same procedural roster from the seed. This class is the single, pure,
 * reversible mapping: {@code toSystemId(starId)} and {@code toStarId(systemId)} are
 * exact inverses, so {@code toStarId(toSystemId(x)) == x} for every {@code long x}.
 *
 * <p>The encoding is the fixed prefix {@value #PREFIX} followed by the unsigned
 * decimal of the {@code long}. Unsigned decimal is used (rather than signed) so the
 * id never carries a {@code '-'} and parses back deterministically across JVMs; the
 * full 64-bit range round-trips because {@link Long#parseUnsignedLong(String)}
 * reverses {@link Long#toUnsignedString(long)} exactly.
 *
 * <p>Pure and framework-free: no I/O, no Spring, no clock, no randomness.
 */
public final class SystemAddress {

    /** Fixed, recognisable prefix marking a galaxy-derived active-system id. */
    public static final String PREFIX = "sys-";

    private SystemAddress() {
    }

    /**
     * Encode a procedural star id ({@code Star#id()}) as the engine {@link SystemId}
     * its promoted {@code ActiveSystem} will be keyed by. Deterministic and total.
     */
    public static SystemId toSystemId(long starId) {
        return new SystemId(PREFIX + Long.toUnsignedString(starId));
    }

    /**
     * Decode the procedural star id back out of a {@link SystemId} produced by
     * {@link #toSystemId(long)}. The exact inverse of the encoding.
     *
     * @throws IllegalArgumentException if {@code id} was not produced by this class
     *                                  (wrong prefix or non-numeric body)
     */
    public static long toStarId(SystemId id) {
        if (id == null) {
            throw new IllegalArgumentException("SystemId must be set");
        }
        String v = id.value();
        if (!v.startsWith(PREFIX)) {
            throw new IllegalArgumentException(
                    "SystemId '" + v + "' is not a galaxy-derived id (missing prefix '" + PREFIX + "')");
        }
        String body = v.substring(PREFIX.length());
        try {
            return Long.parseUnsignedLong(body);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    "SystemId '" + v + "' has a non-numeric star-id body: " + body, e);
        }
    }
}
