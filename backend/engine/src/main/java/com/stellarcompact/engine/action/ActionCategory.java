package com.stellarcompact.engine.action;

/**
 * The fixed resolution categories of game-design 03 ("Resolution order"),
 * lowest ordinal first. The deterministic resolver (E1-05) groups validated
 * actions by this category and resolves the categories in declaration order,
 * then by faction id, then by submission order - the determinism contract (see
 * {@code .claude/skills/game-engine-determinism}).
 *
 * <p>This enum exists in this card chiefly to host {@link #of(Action)}, whose
 * body is an <b>exhaustive {@code switch} over {@link Action} with no
 * {@code default} branch</b>. That switch is the compile-time proof that the
 * sealed set is complete and closed: adding a 26th variant (or removing one)
 * breaks compilation here until it is classified, exactly as the resolver will
 * later be forced to handle it.
 */
public enum ActionCategory {

    /** 1. Treaty break/accept + war declarations - state changes resolve first. */
    DIPLOMATIC_STATE,
    /** 2. Espionage operations. */
    ESPIONAGE,
    /** 3. Fleet movement & interception. */
    MOVEMENT,
    /** 4. Combat (attacks, assaults). */
    COMBAT,
    /** 5. Blockade / raid effects on routes. */
    INTERDICTION,
    /** 6/7. Construction, research, terraform, colonisation. */
    DEVELOPMENT,
    /** 8. Trade proposals / acceptances - market match & escrow settlement. */
    TRADE,
    /** Negotiation chatter and ultimatums - no mechanical state change of their own. */
    DIPLOMATIC_SOFT,
    /** The explicit no-op, and any ignorable future/unknown variant. */
    NONE;

    /**
     * Classify an {@link Action} into its resolution category.
     *
     * <p>The {@code switch} is deliberately exhaustive with <b>no {@code default}
     * branch</b> over the sealed {@link Action} hierarchy (all 25 variants plus
     * the {@link UnknownAction} forward-compat sentinel). This is the card's
     * compile-time exhaustiveness guarantee: a new permitted variant will fail to
     * compile until it is added here.
     *
     * @param action any action (never {@code null})
     * @return the fixed resolution category it belongs to
     */
    public static ActionCategory of(Action action) {
        return switch (action) {
            // 1. Diplomatic state changes (resolve first).
            case Action.BreakTreaty ignored -> DIPLOMATIC_STATE;
            case Action.AcceptTreaty ignored -> DIPLOMATIC_STATE;
            case Action.DeclineTreaty ignored -> DIPLOMATIC_STATE;
            case Action.ProposeTreaty ignored -> DIPLOMATIC_STATE;
            case Action.DeclareWar ignored -> DIPLOMATIC_STATE;

            // 2. Espionage.
            case Action.Espionage ignored -> ESPIONAGE;

            // 3. Movement & interception.
            case Action.MoveFleet ignored -> MOVEMENT;

            // 4. Combat.
            case Action.Attack ignored -> COMBAT;

            // 5. Blockade / raid.
            case Action.Blockade ignored -> INTERDICTION;
            case Action.Raid ignored -> INTERDICTION;

            // 6/7. Construction, research, terraform, colonisation.
            case Action.Build ignored -> DEVELOPMENT;
            case Action.Research ignored -> DEVELOPMENT;
            case Action.Terraform ignored -> DEVELOPMENT;
            case Action.BuildFleet ignored -> DEVELOPMENT;
            case Action.Explore ignored -> DEVELOPMENT;
            case Action.Colonize ignored -> DEVELOPMENT;
            case Action.EstablishRoute ignored -> DEVELOPMENT;

            // 8. Trade match & escrow settlement.
            case Action.ProposeTrade ignored -> TRADE;
            case Action.AcceptTrade ignored -> TRADE;
            case Action.DeclineTrade ignored -> TRADE;
            case Action.WithdrawTrade ignored -> TRADE;

            // Soft diplomacy (no mechanical state change of its own).
            case Action.SendMessage ignored -> DIPLOMATIC_SOFT;
            case Action.Tribute ignored -> DIPLOMATIC_SOFT;
            case Action.DemandTribute ignored -> DIPLOMATIC_SOFT;

            // No-op + ignorable future input.
            case Action.Hold ignored -> NONE;
            case UnknownAction ignored -> NONE;
        };
    }
}
