package com.stellarcompact.engine.resolve;

import com.stellarcompact.engine.state.FactionId;
import com.stellarcompact.engine.state.ResourceBundle;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The single authoritative debit/escrow seam for one tick (security prereq of
 * E1-05, from the E1-04 review; spec {@code docs/specs/agent-io-schema.md}
 * section 4a "Atomic escrow at resolution").
 *
 * <p><b>Why this exists.</b> Affordability in {@link
 * com.stellarcompact.engine.validation.ActionValidator} is only a <em>per-action
 * snapshot</em>: it checks one spend against the stockpile as it stood at
 * validation time. Two validated spends can each individually pass yet
 * <em>collectively</em> overdraw a single stockpile. If each resolver handler
 * debited the faction directly and ad hoc, that overdraw would silently drive a
 * stockpile negative. This ledger closes that hole: every spend in a tick is
 * <em>accumulated</em> here as the resolver walks its steps, and then applied in
 * one final authoritative pass via {@link #settle}, which is the only place a
 * faction stockpile is debited. Because settlement sums all of a faction's spends
 * before subtracting, N validated spends can never collectively overdraw without
 * being detected.
 *
 * <p><b>This card establishes the seam; later cards fill in the math.</b> The
 * per-handler step logic in E1-06/E1-07 must call {@link #escrow} instead of
 * debiting a {@link com.stellarcompact.engine.state.Faction} directly. The
 * overdraft <em>policy</em> (reject the marginal spend? clamp? cascade into
 * deficit/attrition per economy 02?) is a balance/economy decision owned by those
 * cards; {@link #settle} here applies the conservative, deterministic default of
 * subtracting accrued spends and surfacing any overdraft through
 * {@link #wouldOverdraw}, leaving the richer policy to be slotted in without moving
 * the seam.
 *
 * <p><b>Purity.</b> Plain accumulation, no I/O, no clock, no RNG. The map is a
 * {@link LinkedHashMap} so accrual order is deterministic, but settlement sums
 * per-component and is therefore order-independent anyway. Not thread-safe by
 * design: resolution is single-threaded (skill "concurrency boundary").
 */
public final class SpendLedger {

    /** Accrued total spend per faction this tick; applied atomically at {@link #settle}. */
    private final Map<FactionId, ResourceBundle> pending = new LinkedHashMap<>();

    /**
     * Record that {@code actor} owes {@code cost} this tick. Accumulates into the
     * faction's running total rather than debiting immediately - nothing touches a
     * stockpile until {@link #settle}. A zero/empty cost is a harmless no-op.
     *
     * @param actor the spending faction (never {@code null})
     * @param cost  the resources to debit at settlement (never {@code null})
     */
    public void escrow(FactionId actor, ResourceBundle cost) {
        if (actor == null) {
            throw new IllegalArgumentException("SpendLedger.escrow: actor must be set");
        }
        if (cost == null) {
            throw new IllegalArgumentException("SpendLedger.escrow: cost must be set");
        }
        pending.merge(actor, cost, ResourceBundle::plus);
    }

    /**
     * @return the total accrued spend for {@code actor} so far this tick (never
     * {@code null}; {@link ResourceBundle#ZERO} if nothing escrowed).
     */
    public ResourceBundle accrued(FactionId actor) {
        return pending.getOrDefault(actor, ResourceBundle.ZERO);
    }

    /**
     * @return {@code true} iff {@code available} cannot cover the total accrued spend
     * for {@code actor} in every component - i.e. the collected spends would
     * overdraw. This is the collective-overdraft check the per-action validation
     * snapshot cannot make.
     */
    public boolean wouldOverdraw(FactionId actor, ResourceBundle available) {
        return !available.canAfford(accrued(actor));
    }

    /**
     * @return {@code true} iff no spend has been escrowed this tick.
     */
    public boolean isEmpty() {
        return pending.isEmpty();
    }

    /**
     * Apply the accrued spend for {@code actor} to its current {@code stockpiles} in
     * one shot and return the resulting stockpile. This is the <em>single
     * authoritative debit</em>: the only subtraction of a faction's resources in the
     * tick. Subtracting the summed total (not each spend in turn) is what makes the
     * pass atomic - the result is identical regardless of escrow order.
     *
     * <p>The overdraft policy is intentionally minimal in this skeleton card: the
     * accrued total is subtracted as-is (which may go negative, exactly as
     * {@link ResourceBundle#minus} documents for the deficit/attrition model).
     * E1-06/E1-07 replace this body with the chosen policy (reject marginal spend /
     * clamp / cascade to attrition) <em>at this same seam</em>, after consulting
     * {@link #wouldOverdraw}.
     *
     * @param actor      the faction to debit (never {@code null})
     * @param stockpiles its current stockpile (never {@code null})
     * @return the post-debit stockpile
     */
    public ResourceBundle settle(FactionId actor, ResourceBundle stockpiles) {
        if (stockpiles == null) {
            throw new IllegalArgumentException("SpendLedger.settle: stockpiles must be set");
        }
        return stockpiles.minus(accrued(actor));
    }
}
