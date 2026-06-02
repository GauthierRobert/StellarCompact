package com.stellarcompact.engine.progression;

import com.stellarcompact.engine.state.FactionId;

/**
 * The immutable result/standing a Sovereign earned from one <b>concluded</b> match
 * (E9-01; game-design 07 section 2/6). It is the durable, cross-match summary the
 * progression loop carries forward - the only thing that survives a match.
 *
 * <p>A standing record is a pure projection of a concluded {@code GameState} plus its
 * {@code Scoring} ranking (see {@link ProgressionEvaluation#standingOf}); it holds NO
 * material state (no resources, tech, fleets or territory) by design, so it physically
 * cannot carry material advantage across the small-&gt;large boundary. It carries:
 *
 * <ul>
 *   <li>{@code faction} - the Sovereign's stable identity (the same {@link FactionId} it
 *       reuses across matches of a campaign).</li>
 *   <li>{@code displayName} - the Sovereign's name (identity, not advantage).</li>
 *   <li>{@code sizeClass} - the tier of the match this standing came from (a seat is
 *       earned by completing a {@link GalaxySizeClass#SMALL} match).</li>
 *   <li>{@code score} - the faction's final config-weighted {@code Scoring} value (feeds
 *       the seat threshold and tournament rankings).</li>
 *   <li>{@code reputation} - the faction's final public reputation (the one non-material
 *       quantity that may seed a carried identity, weighted by config).</li>
 *   <li>{@code placement} - 1-based finishing rank in the match (1 = won/top). A
 *       {@code placement == 1} is a win for seat purposes.</li>
 *   <li>{@code matchSize} - the number of factions in the concluded match (so placement
 *       is interpretable; a 1st of 8 differs from a 1st of 2).</li>
 * </ul>
 *
 * <p>Pure value object: no clock, no I/O, deeply immutable. Persistence of these records
 * across matches is an orchestration concern (persistence module); the engine only
 * derives and consumes them.
 */
public record StandingRecord(
        FactionId faction,
        String displayName,
        GalaxySizeClass sizeClass,
        double score,
        double reputation,
        int placement,
        int matchSize
) {
    public StandingRecord {
        if (faction == null) {
            throw new IllegalArgumentException("StandingRecord.faction must be set");
        }
        if (displayName == null || displayName.isBlank()) {
            throw new IllegalArgumentException("StandingRecord.displayName must be non-blank");
        }
        if (sizeClass == null) {
            throw new IllegalArgumentException("StandingRecord.sizeClass must be set");
        }
        if (placement < 1) {
            throw new IllegalArgumentException("StandingRecord.placement must be >= 1");
        }
        if (matchSize < 1) {
            throw new IllegalArgumentException("StandingRecord.matchSize must be >= 1");
        }
        if (placement > matchSize) {
            throw new IllegalArgumentException(
                    "StandingRecord.placement (" + placement + ") cannot exceed matchSize ("
                            + matchSize + ")");
        }
    }

    /** @return {@code true} iff this Sovereign finished first (won) the match. */
    public boolean won() {
        return placement == 1;
    }
}
