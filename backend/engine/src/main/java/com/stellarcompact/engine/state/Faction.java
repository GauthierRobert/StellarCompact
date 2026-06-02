package com.stellarcompact.engine.state;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.Map;

/**
 * A Sovereign faction's authoritative game state (game-design 02/04; data-model
 * {@code faction(name, reputation, influence, energy, minerals, food, tech,
 * model_tier, status)}).
 *
 * <p>Stockpiles are carried as a single {@link ResourceBundle} (the four
 * physical resources + accrued Influence) rather than five loose fields, so the
 * economy card can do copy-on-write arithmetic. {@code reputation} is the public
 * ledger value (game-design 04 section 3).
 *
 * <p>Persona / goals / constraints (the human-authored {@code *_json} columns)
 * are deliberately <em>not</em> modelled here: they are inputs to the agent
 * runtime, not engine state the resolver reads, so keeping them out preserves
 * the engine's purity and keeps the deterministic state minimal. {@code modelTier}
 * is likewise an orchestration concern, omitted here.
 *
 * <p>{@code techProgress} is this faction's view of the tech DAG, keyed by
 * {@link TechId}; the map is defensively copied and the canonical hash sorts it.
 */
@JsonIgnoreProperties(ignoreUnknown = false)
public record Faction(
        FactionId id,
        String name,
        double reputation,
        ResourceBundle stockpiles,
        Map<TechId, TechProgress> techProgress
) {
    public Faction {
        if (id == null) {
            throw new IllegalArgumentException("Faction.id must be set");
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Faction.name must be non-blank");
        }
        if (stockpiles == null) {
            throw new IllegalArgumentException("Faction.stockpiles must be set");
        }
        techProgress = Map.copyOf(techProgress);
    }

    /**
     * Copy-on-write: a new Faction identical to this one but with the given
     * stockpiles. The economy card mutates stockpiles every tick; this keeps the
     * call site immutable.
     */
    public Faction withStockpiles(ResourceBundle newStockpiles) {
        return new Faction(id, name, reputation, newStockpiles, techProgress);
    }

    /**
     * Copy-on-write: a new Faction identical to this one but with the given tech
     * DAG view. The Research step (E1-08) inserts/advances a {@link TechProgress}
     * node every tick a faction researches; this keeps the call site immutable.
     */
    public Faction withTechProgress(Map<TechId, TechProgress> newTechProgress) {
        return new Faction(id, name, reputation, stockpiles, newTechProgress);
    }
}
