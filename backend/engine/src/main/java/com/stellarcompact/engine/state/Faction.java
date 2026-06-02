package com.stellarcompact.engine.state;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

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
 *
 * <p>{@code revealedIntel} (E1-13) is the set of rival factions that have
 * successfully run a {@code Espionage(SCOUT)} operation against <em>this</em>
 * faction - i.e. the factions to whom this faction's hidden details now stand
 * revealed. It is persisted engine state (an asymmetric fog-of-war record), held
 * on the spied-upon faction rather than in a new {@code GameState} component so the
 * snapshot shape stays stable; it is defensively copied and the canonical hash
 * sorts it (set order never leaks). A reveal is monotone within a match - the
 * espionage step only adds to it.
 */
@JsonIgnoreProperties(ignoreUnknown = false)
public record Faction(
        FactionId id,
        String name,
        double reputation,
        ResourceBundle stockpiles,
        Map<TechId, TechProgress> techProgress,
        // --- E1-13 espionage: factions this one has been scouted/revealed to ---
        Set<FactionId> revealedIntel
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
        // Additive in E1-13; a null tolerated as "no intel revealed yet" so an older
        // positional caller / partial JSON degrades gracefully (forward-compatible).
        revealedIntel = revealedIntel == null ? Set.of() : Set.copyOf(revealedIntel);
    }

    /**
     * Backwards-compatible constructor predating the E1-13 {@code revealedIntel}
     * set: delegates to the canonical constructor with an empty default. Lets
     * pre-E1-13 callers/fixtures that build a faction positionally keep compiling.
     */
    public Faction(FactionId id, String name, double reputation, ResourceBundle stockpiles,
                   Map<TechId, TechProgress> techProgress) {
        this(id, name, reputation, stockpiles, techProgress, Set.of());
    }

    /**
     * Copy-on-write: a new Faction identical to this one but with the given
     * stockpiles. The economy card mutates stockpiles every tick; this keeps the
     * call site immutable.
     */
    public Faction withStockpiles(ResourceBundle newStockpiles) {
        return new Faction(id, name, reputation, newStockpiles, techProgress, revealedIntel);
    }

    /**
     * Copy-on-write: a new Faction identical to this one but with the given tech
     * DAG view. The Research step (E1-08) inserts/advances a {@link TechProgress}
     * node every tick a faction researches; this keeps the call site immutable.
     */
    public Faction withTechProgress(Map<TechId, TechProgress> newTechProgress) {
        return new Faction(id, name, reputation, stockpiles, newTechProgress, revealedIntel);
    }

    /**
     * Copy-on-write: a new Faction with {@code reputation} set to {@code newReputation}.
     * The espionage step (E1-13) applies the detected-operation reputation penalty
     * through this; later diplomacy cards reuse it for honour/betrayal deltas.
     */
    public Faction withReputation(double newReputation) {
        return new Faction(id, name, newReputation, stockpiles, techProgress, revealedIntel);
    }

    /**
     * Copy-on-write: a new Faction with {@code spy} added to {@code revealedIntel}
     * (idempotent - re-revealing leaves the set unchanged). The espionage step
     * (E1-13 {@code Espionage(SCOUT)}) calls this on the <em>target</em> faction when
     * a scout succeeds, recording that {@code spy} now sees this faction's details.
     */
    public Faction withRevealedTo(FactionId spy) {
        if (revealedIntel.contains(spy)) {
            return this;
        }
        Set<FactionId> next = new LinkedHashSet<>(revealedIntel);
        next.add(spy);
        return new Faction(id, name, reputation, stockpiles, techProgress, next);
    }
}
