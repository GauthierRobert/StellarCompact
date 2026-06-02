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
 *
 * <p><b>Travel ETA (E1-09).</b> {@code etaTicks} is the number of ticks remaining
 * until the fleet completes the lane it is currently traversing and reaches the next
 * system on its {@code enroutePath}. It is the single source of truth for "is this
 * fleet en route" ({@link #enRoute()}): empty while stationary, present (&gt;= 1 at
 * launch) while travelling.
 *
 * <p>While en route the fleet uses both location fields to pin the lane it is on:
 * {@code location} holds the <em>lane origin</em> (the system it is flying away from)
 * and the head of {@code enroutePath} is the <em>lane target</em> (the system it is
 * flying toward), with the rest of {@code enroutePath} the onward route. That lets
 * the MOVEMENT step name the contested lane for interception (game-design 05 section
 * 4) without a separate "current lane" field. The step decrements {@code etaTicks}
 * each tick; when it hits zero the fleet arrives at the lane target - that target
 * becomes {@code location}, it is popped from {@code enroutePath}, and the fleet
 * either begins the next lane (resetting {@code etaTicks} to that lane's length) or,
 * if the route is now exhausted, parks ({@code etaTicks} cleared, {@code enroutePath}
 * empty). Keeping the remaining ETA on the fleet makes multi-tick travel a pure,
 * replay-stable function of state - no wall-clock, no recomputation from a launch
 * time. A stationary fleet has {@code location} present, {@code enroutePath} empty
 * and {@code etaTicks} empty.
 */
@JsonIgnoreProperties(ignoreUnknown = false)
public record Fleet(
        FleetId id,
        FactionId owner,
        Optional<SystemId> location,
        Optional<List<SystemId>> enroutePath,
        FleetStance stance,
        List<Ship> ships,
        Optional<Integer> etaTicks
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
        // etaTicks is additive in E1-09; a null tolerated as "not travelling" so an
        // older positional caller / partial JSON degrades gracefully.
        etaTicks = etaTicks == null ? Optional.empty() : etaTicks;
        if (etaTicks.isPresent() && etaTicks.get() < 0) {
            throw new IllegalArgumentException("Fleet.etaTicks must be >= 0 when present");
        }
        // Deep-copy the optionally-present path so the held list is unmodifiable.
        enroutePath = enroutePath.map(List::copyOf);
        ships = List.copyOf(ships);
    }

    /**
     * Backwards-compatible six-arg constructor for a stationary or path-only fleet
     * (no travel ETA). Existing call sites that predate E1-09 keep compiling; the new
     * field defaults to {@link Optional#empty()}.
     */
    public Fleet(FleetId id, FactionId owner, Optional<SystemId> location,
                 Optional<List<SystemId>> enroutePath, FleetStance stance, List<Ship> ships) {
        this(id, owner, location, enroutePath, stance, ships, Optional.empty());
    }

    /**
     * Copy-on-write: this fleet relocated/launched. A stationary fleet sets
     * {@code location}, clears {@code enroutePath}/{@code etaTicks}; an en-route fleet
     * clears {@code location} and carries its remaining path + ETA. Used by the
     * MOVEMENT step.
     */
    public Fleet withTravel(Optional<SystemId> newLocation,
                            Optional<List<SystemId>> newPath,
                            Optional<Integer> newEta) {
        return new Fleet(id, owner, newLocation, newPath, stance, ships, newEta);
    }

    /**
     * Copy-on-write: this fleet with a different ship stack (combat attrition, E1-10).
     * Kept here so the interception/combat seam can shrink a fleet without rebuilding
     * the whole record at the call site.
     */
    public Fleet withShips(List<Ship> newShips) {
        return new Fleet(id, owner, location, enroutePath, stance, newShips, etaTicks);
    }

    /** @return {@code true} iff the fleet is currently traversing a lane (has a remaining ETA). */
    public boolean enRoute() {
        return etaTicks.isPresent();
    }
}
