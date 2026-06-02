package com.stellarcompact.engine.state;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;
import java.util.Map;

/**
 * A treaty between two or more factions (game-design 04; data-model
 * {@code treaty(type, parties_json, terms_json, signed_tick, expires_tick,
 * status)}).
 *
 * <p>{@code parties} is the ordered list of signatory faction ids (kept as the
 * agents agreed it, so e.g. a Vassalage's suzerain/vassal ordering is preserved).
 * {@code terms} is a free-form, type-specific term map (duration, reparations,
 * resource scope, pricing, victory-sharing rule...) - modelled as a
 * {@code Map<String, String>} so the diplomacy card can evolve term shapes
 * without changing this record; the canonical hash sorts maps by key so term
 * ordering never leaks.
 *
 * <p>{@code signedTick}/{@code expiresTick} bound the active window; the resolver
 * expires a treaty when {@code tick >= expiresTick}.
 */
@JsonIgnoreProperties(ignoreUnknown = false)
public record Treaty(
        TreatyId id,
        TreatyType type,
        List<FactionId> parties,
        Map<String, String> terms,
        long signedTick,
        long expiresTick,
        TreatyStatus status
) {
    public Treaty {
        if (id == null) {
            throw new IllegalArgumentException("Treaty.id must be set");
        }
        if (type == null) {
            throw new IllegalArgumentException("Treaty.type must be set");
        }
        if (status == null) {
            throw new IllegalArgumentException("Treaty.status must be set");
        }
        parties = List.copyOf(parties);
        terms = Map.copyOf(terms);
        if (parties.size() < 2) {
            throw new IllegalArgumentException("Treaty.parties must have >= 2 signatories");
        }
    }
}
