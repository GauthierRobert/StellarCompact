package com.stellarcompact.engine.resolve;

import com.stellarcompact.engine.state.FactionId;
import com.stellarcompact.engine.state.FleetId;
import com.stellarcompact.engine.state.SystemId;

/**
 * The clean hand-off seam from MOVEMENT (E1-09) to COMBAT (E1-10): a forced
 * engagement that interception has <em>triggered</em> but whose resolution (the
 * power comparison, seeded variance roll and proportional losses of game-design 05
 * section 2) belongs to the next card.
 *
 * <p><b>Why a record, not inline combat.</b> E1-09 owns "a hostile fleet contesting
 * a lane forces a battle mid-transit" - i.e. <em>detecting</em> the contested lane
 * and identifying the two belligerents - but must NOT implement combat (that is
 * E1-10, and no combat primitive exists yet). This record is the documented,
 * minimal contract between the two: the MOVEMENT step produces an ordered,
 * deterministic list of {@code PendingBattle}s (one per intercepted lane traversal),
 * and the COMBAT step (E1-10) will consume them, seed each from
 * {@code gameSeed XOR tick XOR battleId} and apply losses/capture. Until E1-10
 * lands, the COMBAT step receives an empty list (interception is config-gated off in
 * profiles that have not opted in) or simply does not yet consume them - either way
 * no fight is resolved on an unvalidated path.
 *
 * <p><b>battleId / determinism.</b> {@code battleId} is the per-battle salt key the
 * combat RNG will use ({@code SaltDomain.COMBAT.salt(battleId)} or, for the
 * interception detection roll, {@code SaltDomain.INTERCEPTION}). It is derived purely
 * and stably from the participants and the contested lane (see
 * {@link MovementResolution}) so the same tick reproduces the same battle id - the
 * replay contract. The field order of this record is part of the deterministic
 * canonical form; do not reorder.
 *
 * @param battleId       stable per-battle salt key (pure function of the participants + lane + tick)
 * @param contestedLaneA one endpoint of the contested lane (canonical {@code <=} orientation by id)
 * @param contestedLaneB the other endpoint of the contested lane
 * @param movingFleet    the fleet that was traversing the lane (the "attacker" of record)
 * @param movingOwner    the moving fleet's owner
 * @param interceptor    the hostile fleet holding/contesting the lane (the "defender")
 * @param interceptorOwner the interceptor's owner (at war with {@code movingOwner})
 */
public record PendingBattle(
        long battleId,
        SystemId contestedLaneA,
        SystemId contestedLaneB,
        FleetId movingFleet,
        FactionId movingOwner,
        FleetId interceptor,
        FactionId interceptorOwner
) {
    public PendingBattle {
        if (contestedLaneA == null || contestedLaneB == null) {
            throw new IllegalArgumentException("PendingBattle contested lane endpoints must be set");
        }
        if (movingFleet == null || interceptor == null) {
            throw new IllegalArgumentException("PendingBattle fleets must be set");
        }
        if (movingOwner == null || interceptorOwner == null) {
            throw new IllegalArgumentException("PendingBattle owners must be set");
        }
    }
}
