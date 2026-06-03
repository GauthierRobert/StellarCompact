package com.stellarcompact.api.match;

import java.util.List;

/**
 * The {@code POST /api/games} body (rest-api spec, section Match lifecycle):
 * {@code { size, factionCount, tickIntervalMs, victoryCondition, balanceProfile, seats }}.
 *
 * <p>All fields are optional with sensible, config-driven defaults applied by the
 * {@link MatchService} - the only hard requirement is that, once normalised,
 * {@code factionCount >= 2} (a match needs at least two Sovereigns to be a contest).
 * A {@code seed} may be supplied for a reproducible galaxy; when omitted the service
 * derives one deterministically so the create call is still side-effect-free w.r.t.
 * any wall-clock entropy in the engine (principle 1 - the engine never sees an
 * unseeded source; the api picks the seed once, up front).
 *
 * <p><b>Per-seat agent selection (E11-04, security-sensitive).</b> {@code seats} is an
 * optional, per-seat list of agent-type tokens chosen from the closed {@link SeatType}
 * whitelist ({@code SCRIPTED} / {@code AGGRESSIVE} / {@code LLM}, case-insensitive). It is
 * carried as raw strings, not a {@code List<SeatType>}, on purpose: the {@link MatchService}
 * - not the JSON layer - owns the closed-set validation, so an unknown token is rejected with
 * a clear {@code 400} rather than a framework deserialization error, and a class name is never
 * reflectively loaded from this untrusted input. When provided the list length must equal the
 * <em>clamped</em> faction count (else {@code 400}); when omitted every seat defaults to
 * {@code SCRIPTED} (the existing, lower-risk default behaviour - the live mix is opt-in).
 *
 * @param seed             optional explicit galaxy seed (null = service-derived)
 * @param size             optional galaxy-size label (small/large); informational here
 * @param factionCount     number of Sovereign seats (defaulted/clamped by the service)
 * @param tickIntervalMs   optional wall-clock cadence hint (orchestration timing only;
 *                         never an engine input - principle 1)
 * @param victoryCondition optional victory-condition label (informational; the active
 *                         condition lives in the balance profile, rule 6)
 * @param balanceProfile   optional balance-profile name to load (defaulted by the service)
 * @param seats            optional per-seat agent-type tokens (closed {@link SeatType}
 *                         whitelist); null/empty = all seats default to {@code SCRIPTED}.
 *                         When present its size must equal the clamped {@code factionCount}.
 */
public record CreateGameRequest(
        Long seed,
        String size,
        Integer factionCount,
        Long tickIntervalMs,
        String victoryCondition,
        String balanceProfile,
        List<String> seats
) {
}
