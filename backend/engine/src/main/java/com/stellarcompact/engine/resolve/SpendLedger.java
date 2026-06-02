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
     * Accrued total credit (inflow) per faction this tick; the passive economy
     * step (E1-06 production) credits a faction's gross production here so that
     * settlement applies one net delta (credits minus debits) atomically. Kept
     * separate from {@link #pending} so {@link #accrued} continues to report only
     * the gross spend an agent committed (what {@link #wouldOverdraw} must guard).
     */
    private final Map<FactionId, ResourceBundle> credited = new LinkedHashMap<>();

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
     * Record an inflow {@code gain} for {@code actor} this tick (E1-06 passive
     * production). Like {@link #escrow} it touches no stockpile until {@link
     * #settle}; at settlement the net is {@code stockpile + credited - accrued},
     * floored at zero per component (no negative balances - economy 02 deficit
     * model). A zero/empty gain is a harmless no-op.
     *
     * @param actor the producing faction (never {@code null})
     * @param gain  the resources to credit at settlement (never {@code null})
     */
    public void credit(FactionId actor, ResourceBundle gain) {
        if (actor == null) {
            throw new IllegalArgumentException("SpendLedger.credit: actor must be set");
        }
        if (gain == null) {
            throw new IllegalArgumentException("SpendLedger.credit: gain must be set");
        }
        credited.merge(actor, gain, ResourceBundle::plus);
    }

    /**
     * @return the total accrued credit (inflow) for {@code actor} so far this tick
     * (never {@code null}; {@link ResourceBundle#ZERO} if nothing credited).
     */
    public ResourceBundle creditedTo(FactionId actor) {
        return credited.getOrDefault(actor, ResourceBundle.ZERO);
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
     * @return {@code true} iff no spend has been escrowed and no inflow credited
     * this tick - i.e. settlement would be a no-op.
     */
    public boolean isEmpty() {
        return pending.isEmpty() && credited.isEmpty();
    }

    /**
     * @return the set of factions touched this tick by either an escrowed spend or
     * a credited inflow; the {@link Resolver} settles exactly these. Order is
     * unspecified - the caller settles per faction independently.
     */
    public java.util.Set<FactionId> touched() {
        java.util.Set<FactionId> all = new java.util.LinkedHashSet<>(pending.keySet());
        all.addAll(credited.keySet());
        return all;
    }

    /**
     * Apply the accrued spend for {@code actor} to its current {@code stockpiles} in
     * one shot and return the resulting stockpile. This is the <em>single
     * authoritative debit</em>: the only subtraction of a faction's resources in the
     * tick. Subtracting the summed total (not each spend in turn) is what makes the
     * pass atomic - the result is identical regardless of escrow order.
     *
     * <p>Overdraft policy (E1-06). The net applied is
     * {@code stockpile + credited - accrued}, then <em>floored at zero per
     * component</em>: a stockpile never goes negative (economy 02 - a faction that
     * cannot pay upkeep suffers attrition, modelled by the PRODUCTION step, rather
     * than holding an impossible negative balance). Production is credited and
     * upkeep/spends are debited in the same net so the subtraction order cannot
     * matter; flooring is the conservative deficit clamp. Whether a faction was in
     * deficit (and therefore attrited) is decided by the PRODUCTION step via
     * {@link #wouldOverdraw} before settlement; this clamp is the final guard.
     *
     * @param actor      the faction to settle (never {@code null})
     * @param stockpiles its current stockpile (never {@code null})
     * @return the post-settlement stockpile, floored at zero per component
     */
    public ResourceBundle settle(FactionId actor, ResourceBundle stockpiles) {
        if (stockpiles == null) {
            throw new IllegalArgumentException("SpendLedger.settle: stockpiles must be set");
        }
        ResourceBundle net = stockpiles.plus(creditedTo(actor)).minus(accrued(actor));
        return new ResourceBundle(
                Math.max(0.0, net.energy()),
                Math.max(0.0, net.minerals()),
                Math.max(0.0, net.food()),
                Math.max(0.0, net.tech()),
                Math.max(0.0, net.influence()));
    }
}
