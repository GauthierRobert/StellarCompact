package com.stellarcompact.api.match;

import com.stellarcompact.engine.state.FactionId;
import com.stellarcompact.engine.state.GameStatus;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
        GameSummary a = s.create(new CreateGameRequest(42L, null, 2, null, null, null));
        GameSummary b = s.create(new CreateGameRequest(42L, null, 2, null, null, null));
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
                s.create(new CreateGameRequest(1L, null, 1, null, null, null)).factions().size());
        assertEquals(InMemoryMatchService.MAX_FACTIONS,
                s.create(new CreateGameRequest(1L, null, 99, null, null, null)).factions().size());
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
                () -> s.create(new CreateGameRequest(1L, null, 2, null, null, "no-such-profile")));
    }

    @Test
    void advanceResolvesTicksAndAdvancesTheClock() {
        InMemoryMatchService s = new InMemoryMatchService();
        String id = s.create(new CreateGameRequest(99L, null, 2, null, null, null)).gameId();
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
        String id = s.create(new CreateGameRequest(7L, null, 2, null, null, null)).gameId();
        var view = s.stateFor(id, new FactionId("faction-1"));
        assertEquals("faction-1", view.self().id().value());
        // Owner sees its own home; faction-2 home is not in faction-1's reach (fog).
        assertTrue(view.ownSystems().stream().anyMatch(sv -> sv.id().value().equals("home-1")));
        assertTrue(view.ownSystems().stream().noneMatch(sv -> sv.id().value().equals("home-2")));
        assertTrue(view.neighbours().stream().noneMatch(n -> n.systemId().value().equals("home-2")));
    }
}
