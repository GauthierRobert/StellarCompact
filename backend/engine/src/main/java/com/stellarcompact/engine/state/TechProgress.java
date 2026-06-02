package com.stellarcompact.engine.state;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * A faction's progress on a single tech node (game-design 03 Research, 06 tree;
 * data-model {@code tech_progress(tech_id, status, progress)}).
 *
 * <p>Held inside the owning {@link Faction} keyed by {@link TechId}.
 * {@code progress} is ticks accrued toward the node's research time (config)
 * while {@link TechStatus#RESEARCHING}; the resolver flips to
 * {@link TechStatus#UNLOCKED} on completion.
 */
@JsonIgnoreProperties(ignoreUnknown = false)
public record TechProgress(
        TechId techId,
        TechStatus status,
        int progress
) {
    public TechProgress {
        if (techId == null) {
            throw new IllegalArgumentException("TechProgress.techId must be set");
        }
        if (status == null) {
            throw new IllegalArgumentException("TechProgress.status must be set");
        }
        if (progress < 0) {
            throw new IllegalArgumentException("TechProgress.progress must be >= 0");
        }
    }
}
