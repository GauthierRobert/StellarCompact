package com.stellarcompact.api.match;

/**
 * The {@code POST /api/games} body (rest-api spec, section Match lifecycle):
 * {@code { size, factionCount, tickIntervalMs, victoryCondition, balanceProfile }}.
 *
 * <p>All fields are optional with sensible, config-driven defaults applied by the
 * {@link MatchService} - the only hard requirement is that, once normalised,
 * {@code factionCount >= 2} (a match needs at least two Sovereigns to be a contest).
 * A {@code seed} may be supplied for a reproducible galaxy; when omitted the service
 * derives one deterministically so the create call is still side-effect-free w.r.t.
 * any wall-clock entropy in the engine (principle 1 - the engine never sees an
 * unseeded source; the api picks the seed once, up front).
 *
 * @param seed             optional explicit galaxy seed (null = service-derived)
 * @param size             optional galaxy-size label (small/large); informational here
 * @param factionCount     number of Sovereign seats (defaulted/clamped by the service)
 * @param tickIntervalMs   optional wall-clock cadence hint (orchestration timing only;
 *                         never an engine input - principle 1)
 * @param victoryCondition optional victory-condition label (informational; the active
 *                         condition lives in the balance profile, rule 6)
 * @param balanceProfile   optional balance-profile name to load (defaulted by the service)
 */
public record CreateGameRequest(
        Long seed,
        String size,
        Integer factionCount,
        Long tickIntervalMs,
        String victoryCondition,
        String balanceProfile
) {
}
