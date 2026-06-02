package com.stellarcompact.engine.state;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;
import java.util.Optional;

/**
 * A stack of ships under one faction, located at a system or travelling a lane
 * (game-design 05 section 1; data-model {@code fleet(location_system_id?,
 * enroute_path_json?, stance)}).
 *
 * <p>Location is genuinely optional in two complementary ways, so both are
 * {@link Optional}:
 * <ul>
 *   <li>{@code location} - the system the fleet is parked at; empty while the
 *       fleet is en route.</li>
 *   <li>{@code enroutePath} - the ordered list of systems the fleet is
 *       traversing; empty while the fleet is stationary. The list is the
 *       remaining path; the resolver advances movement along it (game-design 03
 *       MoveFleet) and may force interception combat mid-transit (05 section 4).</li>
 * </ul>
 * Exactly one of the two is normally present; the golden hash handles
 * {@code Optional} and {@code List} explicitly so this models cleanly.
 *
 * <p>{@code ships} is a defensive copy; an empty stack is legal (a fleet can be
 * annihilated to zero before the resolver removes it).
 */
@JsonIgnoreProperties(ignoreUnknown = false)
public record Fleet(
        FleetId id,
        FactionId owner,
        Optional<SystemId> location,
        Optional<List<SystemId>> enroutePath,
        FleetStance stance,
        List<Ship> ships
) {
    public Fleet {
        if (id == null) {
            throw new IllegalArgumentException("Fleet.id must be set");
        }
        if (owner == null) {
            throw new IllegalArgumentException("Fleet.owner must be set");
        }
        if (location == null) {
            throw new IllegalArgumentException("Fleet.location must be set (use Optional.empty())");
        }
        if (enroutePath == null) {
            throw new IllegalArgumentException("Fleet.enroutePath must be set (use Optional.empty())");
        }
        if (stance == null) {
            throw new IllegalArgumentException("Fleet.stance must be set");
        }
        // Deep-copy the optionally-present path so the held list is unmodifiable.
        enroutePath = enroutePath.map(List::copyOf);
        ships = List.copyOf(ships);
    }
}
