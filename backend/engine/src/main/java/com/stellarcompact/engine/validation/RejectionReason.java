package com.stellarcompact.engine.validation;

/**
 * The closed set of machine-readable reasons an {@link ActionValidator} can give
 * for rejecting an agent-proposed {@code Action} (game-design 03 legality rules;
 * spec {@code docs/specs/agent-io-schema.md} section 4).
 *
 * <p><b>Why a code plus a message.</b> A {@link ValidationResult.Rejected} carries
 * one of these stable codes <em>and</em> a human-readable message. The code is for
 * the engine/telemetry (stable, switchable, never localised); the message is fed
 * back verbatim to the Sovereign on its single re-prompt (E4-04), so it must be
 * precise and actionable. The two never disagree - the message is generated from
 * the code plus the offending context.
 *
 * <p><b>Security (this is the legality gate).</b> Agents are untrusted. A
 * rejection message references only facts the actor could legitimately know
 * (its own stockpiles, the public treaty ledger, an id it itself supplied) -
 * never hidden enemy state. The codes here are deliberately coarse so they cannot
 * be used as an oracle to probe fog-of-war (e.g. a kinetic action that is illegal
 * because of a public treaty reports {@link #TREATY_FORBIDS}, and existence checks
 * report {@link #TARGET_UNKNOWN} without revealing why the target is unknown).
 */
public enum RejectionReason {

    /** The actor's stockpiles do not cover the action's cost (game-design 03 affordability). */
    INSUFFICIENT_RESOURCES,

    /**
     * The actor does not own the referenced system / planet / fleet, or referenced
     * an asset that is not theirs to command (game-design 03 ownership rules).
     */
    NOT_OWNED,

    /**
     * The target system is not adjacent to anything the actor owns or has a fleet in
     * (game-design 03 Explore). NOTE: full adjacency needs the lane graph (E2-03);
     * see {@link ActionValidator} for what is checked now vs deferred.
     */
    NOT_ADJACENT,

    /**
     * The actor tried to {@code Explore} a system it has <em>already</em>
     * revealed/explored (game-design 03 Explore: "Adds it to the faction's known
     * map"); a redundant re-Explore is a no-op that wastes a resolver slot, so the
     * validator rejects it (E10-06; 3-agent-sim finding F1). The check reads only the
     * actor's own {@code exploredSystems} set, so the rejection references solely what
     * the actor itself already knows - it never leaks another faction's hidden state
     * nor whether the system is owned. The actor should explore an unrevealed frontier
     * system, colonise, or Hold instead.
     */
    ALREADY_REVEALED,

    /**
     * No usable path of lanes exists from the fleet's location to the destination
     * (game-design 03 MoveFleet). The well-formedness of an agent-supplied
     * {@code path[]} is checked now; lane existence is deferred to E2-03.
     */
    NO_PATH,

    /**
     * An active treaty forbids this action between the parties (e.g. a
     * NonAggression/Alliance/Ceasefire pact makes Attack/DeclareWar illegal -
     * game-design 03/04). The offending treaty id is included in the message.
     */
    TREATY_FORBIDS,

    /** A prerequisite tech for this action has not been unlocked (game-design 03 Research/Build). */
    TECH_PREREQ_MISSING,

    /** No free building slot on the target planet (game-design 03 Build). */
    NO_FREE_SLOT,

    /**
     * A kinetic action (Attack/Blockade/Raid) requires a state of war with the
     * target and none exists (game-design 03 section C). War state is not yet a
     * first-class state record; see {@link ActionValidator} - this is a
     * deferred check, the treaty-forbids gate is enforced now.
     */
    NOT_AT_WAR,

    /**
     * A referenced id does not exist in (the actor's legitimate view of) game
     * state - an unknown faction, system, planet, fleet, route, treaty or offer.
     */
    TARGET_UNKNOWN,

    /** A referenced trade/treaty offer has passed its expiry tick (game-design 03 trade). */
    OFFER_EXPIRED,

    /**
     * The actor targeted itself where the action requires a distinct counterparty
     * (e.g. DeclareWar/ProposeTrade/Attack on oneself - game-design 03).
     */
    SELF_TARGET,

    /**
     * The diplomatic precondition is "not currently bound" and it is violated
     * (e.g. DeclareWar while a NonAggression pact still binds). Distinct from
     * {@link #TREATY_FORBIDS} only in intent; the blocking treaty id is still named.
     */
    NOT_AT_PEACE,

    /**
     * The action's payload is internally inconsistent in a way the sealed type
     * could not prevent (e.g. a trade where give and receive are both empty, an
     * over-cap message, a self-referential route A==B). Shape is guaranteed by the
     * record constructors; this is semantic well-formedness.
     */
    MALFORMED,

    /**
     * The {@code UnknownAction} forward-compat sentinel: an action whose wire
     * {@code type} is not one of the known variants. Never executed - always
     * rejected here so it degrades to a Hold (spec section 6 / 5a).
     */
    UNKNOWN_ACTION
}
