package com.stellarcompact.api.faction;

/**
 * Thrown when a {@code PATCH /api/factions/{id}} (standing-directive edit) is attempted
 * while the owning match is {@code RUNNING}. Standing directives are editable only
 * between matches (board card E6-02: persistent galaxies, between matches), so a
 * mid-match edit is rejected as {@code 409 Conflict} (a 4xx, mirroring the lifecycle
 * guard in E6-01). Re-steering a Sovereign mid-match would also be an unfair timing
 * channel, so this is a hard gate, not advisory.
 */
public class DirectivesLockedException extends RuntimeException {
    public DirectivesLockedException(String message) {
        super(message);
    }
}
