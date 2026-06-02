package com.stellarcompact.engine.state;

/**
 * State of a {@link Building} in a planet slot. Construction queues then becomes
 * active after its build time (game-design 03 Build); a building goes idle when
 * its upkeep cannot be paid (economy 02 section 2 deficit handling).
 */
public enum BuildingStatus {
    /** Queued/under construction; {@code progress} advances toward build time. */
    UNDER_CONSTRUCTION,
    /** Built and producing/providing its effect. */
    ACTIVE,
    /** Built but disabled (e.g. unpaid upkeep) - produces nothing until restored. */
    IDLE
}
