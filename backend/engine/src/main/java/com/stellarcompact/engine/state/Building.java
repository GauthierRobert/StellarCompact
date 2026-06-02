package com.stellarcompact.engine.state;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * A building occupying one slot on a {@link Planet} (game-design 02 section 6;
 * data-model {@code building(slot_index, type, status, progress)}).
 *
 * <p>{@code slotIndex} is the planet-local slot this building sits in (planets
 * have a limited slot count by size). {@code progress} is ticks accrued toward
 * the type's build time while {@link BuildingStatus#UNDER_CONSTRUCTION}; the
 * resolver flips status to {@link BuildingStatus#ACTIVE} once progress reaches
 * the configured build time.
 */
@JsonIgnoreProperties(ignoreUnknown = false)
public record Building(
        int slotIndex,
        BuildingType type,
        BuildingStatus status,
        int progress
) {
    public Building {
        if (slotIndex < 0) {
            throw new IllegalArgumentException("Building.slotIndex must be >= 0");
        }
        if (type == null) {
            throw new IllegalArgumentException("Building.type must be set");
        }
        if (status == null) {
            throw new IllegalArgumentException("Building.status must be set");
        }
        if (progress < 0) {
            throw new IllegalArgumentException("Building.progress must be >= 0");
        }
    }
}
