package com.stellarcompact.engine.progression;

import com.stellarcompact.engine.state.FactionId;

/**
 * Everything - and ONLY everything - a Sovereign carries across the small-&gt;large
 * boundary into a new match (E9-01; game-design 06 section 5, 07 section 6).
 *
 * <p>This record is the materialised form of the cardinal carry-over rule: it holds
 * <b>identity and reputation, nothing else</b>. There is deliberately no resource, tech,
 * fleet or territory field on it, so it is structurally impossible to smuggle material
 * advantage across a match boundary through this type. The fresh material loadout a
 * carried faction starts the new match with comes entirely from the destination match's
 * own {@code progression.starterStockpile} (the same starter every faction gets), applied
 * by {@link ProgressionEvaluation#seedFaction}.
 *
 * <ul>
 *   <li>{@code faction} - the persistent identity (same {@link FactionId} across the
 *       campaign).</li>
 *   <li>{@code displayName} - the Sovereign's name.</li>
 *   <li>{@code carriedReputation} - the reputation seed for the new match: the prior
 *       standing's reputation scaled by the destination profile's
 *       {@code progression.reputationCarryWeight} (a config fraction in [0,1]). With
 *       weight {@code 0.0} this is {@code 0.0} - a clean, identity-only carry.</li>
 * </ul>
 *
 * <p>Pure, deeply immutable value object.
 */
public record CarriedIdentity(
        FactionId faction,
        String displayName,
        double carriedReputation
) {
    public CarriedIdentity {
        if (faction == null) {
            throw new IllegalArgumentException("CarriedIdentity.faction must be set");
        }
        if (displayName == null || displayName.isBlank()) {
            throw new IllegalArgumentException("CarriedIdentity.displayName must be non-blank");
        }
    }
}
