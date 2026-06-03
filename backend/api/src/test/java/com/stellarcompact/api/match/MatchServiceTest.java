package com.stellarcompact.api.match;

import com.stellarcompact.engine.state.FactionId;
import com.stellarcompact.engine.state.GameStatus;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link InMemoryMatchService} that do not need MVC: the guarded lifecycle
 * state machine (illegal transitions throw), deterministic creation from a seed, the
 * synchronous tick advance, and the fog boundary at the service level.
 */
class MatchServiceTest {

    @Test
    void createIsDeterministicForAFixedSeed() {
        InMemoryMatchService s = new InMemoryMatchService();
        GameSummary a = s.create(new CreateGameRequest(42L, null, 2, null, null, null, null));
        GameSummary b = s.create(new CreateGameRequest(42L, null, 2, null, null, null, null));
        // Same seed in => same galaxy seed + same seat roster (distinct ids per registry).
        assertEquals(a.gameSeed(), b.gameSeed());
        assertEquals(a.factions(), b.factions());
        assertNotEquals(a.gameId(), b.gameId());
        assertEquals(GameStatus.CREATED, a.status());
    }

    @Test
    void factionCountIsClampedToTheLegalRange() {
        InMemoryMatchService s = new InMemoryMatchService();
        assertEquals(InMemoryMatchService.MIN_FACTIONS,
                s.create(new CreateGameRequest(1L, null, 1, null, null, null, null)).factions().size());
        assertEquals(InMemoryMatchService.MAX_FACTIONS,
                s.create(new CreateGameRequest(1L, null, 99, null, null, null, null)).factions().size());
    }

    @Test
    void illegalTransitionsAreRejectedByTheGuard() {
        InMemoryMatchService s = new InMemoryMatchService();
        String id = s.create(null).gameId();

        // CREATED cannot pause or resume.
        assertThrows(IllegalTransitionException.class, () -> s.pause(id));
        assertThrows(IllegalTransitionException.class, () -> s.resume(id));

        s.start(id);          // CREATED -> LOBBY -> RUNNING
        s.pause(id);          // RUNNING -> PAUSED (also stops the loop)
        // PAUSED cannot start (only resume).
        assertThrows(IllegalTransitionException.class, () -> s.start(id));
        s.resume(id);         // PAUSED -> RUNNING
        s.pause(id);          // park it
    }

    @Test
    void unknownMatchThrowsNotFound() {
        InMemoryMatchService s = new InMemoryMatchService();
        assertThrows(MatchNotFoundException.class, () -> s.summary("nope"));
        assertThrows(MatchNotFoundException.class, () -> s.leaderboard("nope"));
        assertThrows(MatchNotFoundException.class, () -> s.events("nope", 0, 10));
    }

    @Test
    void unknownBalanceProfileIsRejected() {
        InMemoryMatchService s = new InMemoryMatchService();
        assertThrows(MatchNotFoundException.class,
                () -> s.create(new CreateGameRequest(1L, null, 2, null, null, "no-such-profile", null)));
    }

    @Test
    void advanceResolvesTicksAndAdvancesTheClock() {
        InMemoryMatchService s = new InMemoryMatchService();
        String id = s.create(new CreateGameRequest(99L, null, 2, null, null, null, null)).gameId();
        s.start(id);
        s.pause(id); // stop the background loop; drive synchronously
        s.resume(id);
        s.pause(id);
        // Re-running synchronously requires RUNNING; resume then advance.
        s.resume(id);
        int resolved = s.advanceForTest(id, 5);
        assertTrue(resolved > 0);
        assertTrue(s.summary(id).tick() >= 1, "tick clock advanced");
        s.pause(id);
    }

    @Test
    void stateForIsFogFilteredAndOwnerSeesSelf() {
        InMemoryMatchService s = new InMemoryMatchService();
        String id = s.create(new CreateGameRequest(7L, null, 2, null, null, null, null)).gameId();
        var v1 = s.stateFor(id, new FactionId("faction-1"));
        var v2 = s.stateFor(id, new FactionId("faction-2"));
        assertEquals("faction-1", v1.self().id().value());
        // Owner sees at least one of its own (galaxy-generated, sys-*) systems in full.
        assertFalse(v1.ownSystems().isEmpty());
        assertTrue(v1.ownSystems().stream().allMatch(sv -> sv.id().value().startsWith("sys-")));
        assertTrue(v1.ownSystems().stream().allMatch(sv -> sv.owned()));
        // E11-01 connected galaxy: a rival may now be perceptible, but ONLY as a fog-limited
        // NeighbourView (owner + roughStrength; no stockpiles/economy). The fog boundary that
        // must hold regardless of topology: faction-2's owned systems are never exposed as
        // systems faction-1 owns.
        java.util.Set<String> faction2Owned = new java.util.HashSet<>();
        v2.ownSystems().forEach(sv -> faction2Owned.add(sv.id().value()));
        assertFalse(faction2Owned.isEmpty());
        assertTrue(v1.ownSystems().stream().noneMatch(sv -> faction2Owned.contains(sv.id().value())),
                "fog: faction-2's systems must never appear as faction-1's own");
    }

    // ===== E11-04: per-seat agent-type selection (closed whitelist) =============

    @Test
    void noSeatsDefaultsToAllScripted() {
        InMemoryMatchService s = new InMemoryMatchService();
        String id = s.create(new CreateGameRequest(5L, null, 3, null, null, null, null)).gameId();
        assertEquals(List.of("ScriptedSovereign", "ScriptedSovereign", "ScriptedSovereign"),
                s.seatTypesForTest(id));
    }

    @Test
    void explicitSeatMixSeatsTheRequestedTypesInOrder() {
        InMemoryMatchService s = new InMemoryMatchService();
        // A mix that exercises combat: case-insensitive tokens, mapped per seat in order.
        String id = s.create(new CreateGameRequest(5L, null, 3, null, null, null,
                List.of("scripted", "AGGRESSIVE", "Aggressive"))).gameId();
        assertEquals(List.of("ScriptedSovereign", "AggressiveScriptedSovereign",
                "AggressiveScriptedSovereign"), s.seatTypesForTest(id));
    }

    @Test
    void aggressiveSeatIsActuallySeated() {
        InMemoryMatchService s = new InMemoryMatchService();
        String id = s.create(new CreateGameRequest(5L, null, 2, null, null, null,
                List.of("AGGRESSIVE", "AGGRESSIVE"))).gameId();
        assertTrue(s.seatTypesForTest(id).stream()
                        .allMatch("AggressiveScriptedSovereign"::equals),
                "both seats should be the aggressive bot");
    }

    @Test
    void unknownSeatTypeIsRejected() {
        InMemoryMatchService s = new InMemoryMatchService();
        // An out-of-whitelist token must be rejected (mapped to 400), never defaulted through.
        assertThrows(IllegalArgumentException.class,
                () -> s.create(new CreateGameRequest(5L, null, 2, null, null, null,
                        List.of("SCRIPTED", "OVERLORD"))));
    }

    @Test
    void llmSeatIsRejectedAsUnavailable() {
        InMemoryMatchService s = new InMemoryMatchService();
        // LLM is whitelisted but unwired in this build: reject, never silently fall back.
        assertThrows(IllegalArgumentException.class,
                () -> s.create(new CreateGameRequest(5L, null, 2, null, null, null,
                        List.of("LLM", "SCRIPTED"))));
    }

    @Test
    void seatsLengthMustMatchClampedFactionCount() {
        InMemoryMatchService s = new InMemoryMatchService();
        // Too few seats for the seat count => 400.
        assertThrows(IllegalArgumentException.class,
                () -> s.create(new CreateGameRequest(5L, null, 3, null, null, null,
                        List.of("SCRIPTED", "AGGRESSIVE"))));
        // Length is checked against the CLAMPED count: factionCount 1 clamps to MIN (2), so a
        // single-seat list is a mismatch and is rejected (the clamp is what the galaxy is built
        // with, so seats must line up with it).
        assertThrows(IllegalArgumentException.class,
                () -> s.create(new CreateGameRequest(5L, null, 1, null, null, null,
                        List.of("SCRIPTED"))));
    }

    @Test
    void emptySeatsListDefaultsToScripted() {
        InMemoryMatchService s = new InMemoryMatchService();
        // An explicitly empty list is treated as "unspecified" (default policy), not a length
        // mismatch - so the convenient "{}" / "[]" create still works.
        String id = s.create(new CreateGameRequest(5L, null, 2, null, null, null, List.of()))
                .gameId();
        assertEquals(List.of("ScriptedSovereign", "ScriptedSovereign"), s.seatTypesForTest(id));
    }
}
