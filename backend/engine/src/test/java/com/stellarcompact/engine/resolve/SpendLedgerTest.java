package com.stellarcompact.engine.resolve;

import com.stellarcompact.engine.state.FactionId;
import com.stellarcompact.engine.state.ResourceBundle;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The atomic-debit / escrow seam (E1-05 security prereq). These tests pin the
 * property the per-action validation snapshot cannot guarantee: N validated spends
 * in one tick are summed and applied in a single authoritative pass, so they cannot
 * collectively overdraw a stockpile without {@link SpendLedger#wouldOverdraw}
 * detecting it. Settlement is order-independent (sum-then-subtract).
 */
class SpendLedgerTest {

    private static final FactionId ALPHA = new FactionId("alpha");
    private static final FactionId BETA = new FactionId("beta");

    @Test
    void emptyLedgerSettlesToIdentity() {
        SpendLedger ledger = new SpendLedger();
        assertTrue(ledger.isEmpty());
        ResourceBundle stock = new ResourceBundle(100, 100, 100, 100, 100);
        assertEquals(stock, ledger.settle(ALPHA, stock));
        assertEquals(ResourceBundle.ZERO, ledger.accrued(ALPHA));
    }

    @Test
    void multipleEscrowsAccumulateAndSettleAtomically() {
        SpendLedger ledger = new SpendLedger();
        ledger.escrow(ALPHA, new ResourceBundle(0, 30, 0, 0, 0));
        ledger.escrow(ALPHA, new ResourceBundle(0, 40, 0, 0, 0));
        ledger.escrow(ALPHA, new ResourceBundle(10, 0, 0, 0, 0));
        assertFalse(ledger.isEmpty());

        // Accrued is the component-wise sum.
        assertEquals(new ResourceBundle(10, 70, 0, 0, 0), ledger.accrued(ALPHA));

        ResourceBundle stock = new ResourceBundle(100, 100, 100, 100, 100);
        assertEquals(new ResourceBundle(90, 30, 100, 100, 100), ledger.settle(ALPHA, stock));
    }

    @Test
    void detectsCollectiveOverdrawThatPerActionSnapshotsWouldMiss() {
        // Each spend (60 minerals) is individually affordable against a 100 stockpile,
        // but together (120) they overdraw. This is exactly the case validation
        // affordability (a per-action snapshot) cannot catch.
        SpendLedger ledger = new SpendLedger();
        ResourceBundle stock = new ResourceBundle(0, 100, 0, 0, 0);
        ledger.escrow(ALPHA, new ResourceBundle(0, 60, 0, 0, 0));
        assertFalse(ledger.wouldOverdraw(ALPHA, stock), "one spend of 60 fits in 100");
        ledger.escrow(ALPHA, new ResourceBundle(0, 60, 0, 0, 0));
        assertTrue(ledger.wouldOverdraw(ALPHA, stock), "120 collectively overdraws 100");
    }

    @Test
    void escrowIsPerFaction() {
        SpendLedger ledger = new SpendLedger();
        ledger.escrow(ALPHA, new ResourceBundle(0, 50, 0, 0, 0));
        ledger.escrow(BETA, new ResourceBundle(0, 10, 0, 0, 0));
        assertEquals(new ResourceBundle(0, 50, 0, 0, 0), ledger.accrued(ALPHA));
        assertEquals(new ResourceBundle(0, 10, 0, 0, 0), ledger.accrued(BETA));
    }

    @Test
    void creditAddsInflowAndSettlesNet() {
        // E1-06: production credits inflow; settlement applies stock + credit - spend.
        SpendLedger ledger = new SpendLedger();
        ledger.credit(ALPHA, new ResourceBundle(0, 0, 10, 0, 0));   // +10 food
        ledger.escrow(ALPHA, new ResourceBundle(5, 0, 0, 0, 0));    // -5 energy
        assertFalse(ledger.isEmpty());
        assertEquals(new ResourceBundle(0, 0, 10, 0, 0), ledger.creditedTo(ALPHA));

        ResourceBundle stock = new ResourceBundle(100, 100, 100, 100, 100);
        assertEquals(new ResourceBundle(95, 100, 110, 100, 100), ledger.settle(ALPHA, stock));
    }

    @Test
    void settleFloorsEachComponentAtZero() {
        // Overdraw is clamped to zero, never negative (economy 02 deficit model).
        SpendLedger ledger = new SpendLedger();
        ResourceBundle stock = new ResourceBundle(0, 5, 0, 0, 0);
        ledger.escrow(ALPHA, new ResourceBundle(0, 60, 0, 0, 0)); // can only pay 5
        assertEquals(new ResourceBundle(0, 0, 0, 0, 0), ledger.settle(ALPHA, stock));
    }

    @Test
    void touchedReportsEveryCreditedOrEscrowedFaction() {
        SpendLedger ledger = new SpendLedger();
        ledger.escrow(ALPHA, new ResourceBundle(1, 0, 0, 0, 0));
        ledger.credit(BETA, new ResourceBundle(0, 1, 0, 0, 0));
        assertTrue(ledger.touched().contains(ALPHA));
        assertTrue(ledger.touched().contains(BETA));
        assertEquals(2, ledger.touched().size());
    }

    @Test
    void rejectsNullArguments() {
        SpendLedger ledger = new SpendLedger();
        assertThrows(IllegalArgumentException.class,
                () -> ledger.escrow(null, ResourceBundle.ZERO));
        assertThrows(IllegalArgumentException.class,
                () -> ledger.escrow(ALPHA, null));
        assertThrows(IllegalArgumentException.class,
                () -> ledger.credit(null, ResourceBundle.ZERO));
        assertThrows(IllegalArgumentException.class,
                () -> ledger.credit(ALPHA, null));
        assertThrows(IllegalArgumentException.class,
                () -> ledger.settle(ALPHA, null));
    }
}
