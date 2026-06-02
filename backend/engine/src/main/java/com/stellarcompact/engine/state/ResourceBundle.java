package com.stellarcompact.engine.state;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * A stockpile of the five resources (game-design 02 section 1): the four
 * physical goods Energy/Minerals/Food/Tech plus the political-capital Influence.
 *
 * <p>Dedicated state bundle vs reusing {@code config.BalanceProfile.ResourceBundle}.
 * The config module already declares a five-field bundle, but it is a pure
 * data-carrier for parsed tunables. This card deliberately defines a <em>separate</em>
 * {@code state.ResourceBundle} for two reasons:
 * <ol>
 *   <li><b>Decoupling.</b> Mutable game state should not depend on the config
 *       module's record shape; the two evolve for different reasons (a config
 *       refactor must not ripple into state and break the golden hash).</li>
 *   <li><b>Economy ergonomics.</b> The economy/resolver cards need copy-on-write
 *       arithmetic - {@link #plus}, {@link #minus}, {@link #scale},
 *       {@link #canAfford} - which belong on the state value type, not on a
 *       config DTO. Every helper returns a <em>new</em> bundle; instances are
 *       never mutated, preserving the immutable-state determinism contract.</li>
 * </ol>
 *
 * <p>Values are {@code double} to match the per-tick fractional yields/upkeep in
 * the balance profiles; the resolver is responsible for any rounding policy.
 */
@JsonIgnoreProperties(ignoreUnknown = false)
public record ResourceBundle(
        double energy,
        double minerals,
        double food,
        double tech,
        double influence
) {

    /** The all-zero bundle - a convenient starting stockpile / additive identity. */
    public static final ResourceBundle ZERO = new ResourceBundle(0, 0, 0, 0, 0);

    /** @return a new bundle that is the component-wise sum of this and {@code other}. */
    public ResourceBundle plus(ResourceBundle other) {
        return new ResourceBundle(
                energy + other.energy,
                minerals + other.minerals,
                food + other.food,
                tech + other.tech,
                influence + other.influence);
    }

    /**
     * @return a new bundle that is the component-wise difference
     * {@code this - other}. May go negative; callers that must not overspend
     * should gate on {@link #canAfford(ResourceBundle)} first (the resolver
     * models deficits/attrition rather than impossible negatives - economy 02).
     */
    public ResourceBundle minus(ResourceBundle other) {
        return new ResourceBundle(
                energy - other.energy,
                minerals - other.minerals,
                food - other.food,
                tech - other.tech,
                influence - other.influence);
    }

    /** @return a new bundle with every component multiplied by {@code factor}. */
    public ResourceBundle scale(double factor) {
        return new ResourceBundle(
                energy * factor,
                minerals * factor,
                food * factor,
                tech * factor,
                influence * factor);
    }

    /**
     * @return {@code true} iff this bundle covers {@code cost} in every component
     * (no component of {@code this} is less than the matching component of
     * {@code cost}). Used by validation before a spend is applied.
     */
    public boolean canAfford(ResourceBundle cost) {
        return energy >= cost.energy
                && minerals >= cost.minerals
                && food >= cost.food
                && tech >= cost.tech
                && influence >= cost.influence;
    }
}
