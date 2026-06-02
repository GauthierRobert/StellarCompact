package com.stellarcompact.engine.resolve;

import com.stellarcompact.engine.action.Action;
import com.stellarcompact.engine.action.EspionageOperation;
import com.stellarcompact.engine.config.BalanceProfile;
import com.stellarcompact.engine.hash.GoldenStateHash;
import com.stellarcompact.engine.state.GameState;
import com.stellarcompact.engine.state.SystemId;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static com.stellarcompact.engine.resolve.ResolveFixtures.ALPHA;
import static com.stellarcompact.engine.resolve.ResolveFixtures.BETA;
import static com.stellarcompact.engine.resolve.ResolveFixtures.GAMMA;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Resolver wiring + determinism tests. The per-step gameplay math is stubbed in
 * E1-05, so these assert the contract this card actually delivers: the resolver
 * runs all eleven steps without error, leaves a stubbed tick byte-identical, and is
 * order-insensitive to the input action list (it imposes its own canonical order).
 */
class ResolverTest {

    private static final BalanceProfile PROFILE = ResolveFixtures.profile();

    private static List<SubmittedAction> sampleBatch() {
        List<SubmittedAction> batch = new ArrayList<>();
        batch.add(new SubmittedAction(ALPHA, new Action.DeclareWar(BETA), 0));
        batch.add(new SubmittedAction(BETA, new Action.Explore(new SystemId("sysA")), 0));
        batch.add(new SubmittedAction(GAMMA, new Action.Espionage(ALPHA, EspionageOperation.SCOUT), 0));
        batch.add(new SubmittedAction(ALPHA, new Action.Hold(), 1));        // soft/no-op: dropped
        batch.add(new SubmittedAction(BETA, new Action.SendMessage(ALPHA, "hi"), 1)); // dropped
        return batch;
    }

    @Test
    void resolveLeavesStateUnchangedForActionsWithoutStatefulHandlers() {
        // A batch of only soft/no-op + still-stubbed handlers (Explore/Espionage are
        // stubs in their respective cards) must leave the tick byte-identical. NOTE:
        // DeclareWar is deliberately excluded here because E1-12 made it stateful (it
        // records a WarState and docks reputation) - see declareWarRecordsWarState.
        List<SubmittedAction> inert = new ArrayList<>();
        inert.add(new SubmittedAction(BETA, new Action.Explore(new SystemId("sysA")), 0));
        inert.add(new SubmittedAction(GAMMA, new Action.Espionage(ALPHA, EspionageOperation.SCOUT), 0));
        inert.add(new SubmittedAction(ALPHA, new Action.Hold(), 1));
        inert.add(new SubmittedAction(BETA, new Action.SendMessage(ALPHA, "hi"), 1));
        GameState before = ResolveFixtures.baseState();
        GameState after = Resolver.resolve(before, inert, PROFILE, before.gameSeed());
        assertEquals(GoldenStateHash.sha256Hex(before), GoldenStateHash.sha256Hex(after));
    }

    @Test
    void declareWarRecordsWarState() {
        // E1-12: DeclareWar is now stateful - the DIPLOMATIC_STATE step records the war.
        GameState before = ResolveFixtures.baseState();
        List<SubmittedAction> batch = List.of(
                new SubmittedAction(ALPHA, new Action.DeclareWar(BETA), 0));
        GameState after = Resolver.resolve(before, batch, PROFILE, before.gameSeed());
        assertFalse(before.atWar(ALPHA, BETA), "no war before");
        assertTrue(after.atWar(ALPHA, BETA), "DeclareWar must record a positive war state");
        assertTrue(after.atWar(BETA, ALPHA), "war is symmetric");
    }

    @Test
    void resolveIsDeterministicAcrossRuns() {
        GameState before = ResolveFixtures.baseState();
        GameState a = Resolver.resolve(before, sampleBatch(), PROFILE, before.gameSeed());
        GameState b = Resolver.resolve(before, sampleBatch(), PROFILE, before.gameSeed());
        assertEquals(GoldenStateHash.sha256Hex(a), GoldenStateHash.sha256Hex(b));
    }

    @Test
    void resolveIsInsensitiveToInputActionOrder() {
        // The resolver imposes (step, faction, submission) order itself, so a
        // shuffled input list must produce an identical next state.
        GameState before = ResolveFixtures.baseState();
        GameState canonical = Resolver.resolve(before, sampleBatch(), PROFILE, before.gameSeed());
        String goldenHex = GoldenStateHash.sha256Hex(canonical);

        List<SubmittedAction> shuffled = new ArrayList<>(sampleBatch());
        Collections.reverse(shuffled);
        GameState reordered = Resolver.resolve(before, shuffled, PROFILE, before.gameSeed());
        assertEquals(goldenHex, GoldenStateHash.sha256Hex(reordered));
    }

    @Test
    void resolveRejectsNullArguments() {
        GameState s = ResolveFixtures.baseState();
        assertThrows(IllegalArgumentException.class,
                () -> Resolver.resolve(null, List.of(), PROFILE, 1L));
        assertThrows(IllegalArgumentException.class,
                () -> Resolver.resolve(s, null, PROFILE, 1L));
        assertThrows(IllegalArgumentException.class,
                () -> Resolver.resolve(s, List.of(), null, 1L));
    }

    @Test
    void resolveHandlesEmptyActionList() {
        GameState before = ResolveFixtures.baseState();
        GameState after = Resolver.resolve(before, List.of(), PROFILE, before.gameSeed());
        assertEquals(GoldenStateHash.sha256Hex(before), GoldenStateHash.sha256Hex(after));
    }
}
