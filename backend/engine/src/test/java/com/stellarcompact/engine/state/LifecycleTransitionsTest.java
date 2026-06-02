package com.stellarcompact.engine.state;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guarded lifecycle state-machine tests (E1-15; game-design 07 section 4). Asserts that
 * every legal edge of CREATED -> LOBBY -> RUNNING -> (PAUSED &lt;-&gt; RUNNING) ->
 * CONCLUDED -> ARCHIVED is allowed and every other transition is rejected.
 */
class LifecycleTransitionsTest {

    /** The complete legal-edge set, authored independently of the production map. */
    private static final Map<GameStatus, Set<GameStatus>> LEGAL = Map.of(
            GameStatus.CREATED, Set.of(GameStatus.LOBBY),
            GameStatus.LOBBY, Set.of(GameStatus.RUNNING),
            GameStatus.RUNNING, Set.of(GameStatus.PAUSED, GameStatus.CONCLUDED),
            GameStatus.PAUSED, Set.of(GameStatus.RUNNING, GameStatus.CONCLUDED),
            GameStatus.CONCLUDED, Set.of(GameStatus.ARCHIVED),
            GameStatus.ARCHIVED, Set.of());

    private static GameState stateWith(GameStatus status) {
        return new GameState(7L, 1L, status, "small-default", 1,
                Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Set.of());
    }

    @Test
    void everyLegalTransitionIsAllowedAndEveryIllegalOneRejected() {
        for (GameStatus from : GameStatus.values()) {
            Set<GameStatus> allowed = LEGAL.get(from);
            for (GameStatus to : GameStatus.values()) {
                boolean expected = allowed.contains(to);
                assertEquals(expected, LifecycleTransitions.canTransition(from, to),
                        from + " -> " + to + " legality");
            }
        }
    }

    @Test
    void selfTransitionIsNotLegal() {
        for (GameStatus s : GameStatus.values()) {
            assertFalse(LifecycleTransitions.canTransition(s, s), s + " -> " + s + " self");
        }
    }

    @Test
    void happyPathWalksTheWholeLifecycle() {
        GameState s = stateWith(GameStatus.CREATED);
        s = LifecycleTransitions.transition(s, GameStatus.LOBBY);
        assertEquals(GameStatus.LOBBY, s.status());
        s = LifecycleTransitions.transition(s, GameStatus.RUNNING);
        s = LifecycleTransitions.transition(s, GameStatus.PAUSED);
        s = LifecycleTransitions.transition(s, GameStatus.RUNNING); // resume
        s = LifecycleTransitions.transition(s, GameStatus.CONCLUDED);
        s = LifecycleTransitions.transition(s, GameStatus.ARCHIVED);
        assertEquals(GameStatus.ARCHIVED, s.status());
    }

    @Test
    void illegalTransitionThrows() {
        GameState concluded = stateWith(GameStatus.CONCLUDED);
        assertThrows(IllegalArgumentException.class,
                () -> LifecycleTransitions.transition(concluded, GameStatus.RUNNING));
        GameState created = stateWith(GameStatus.CREATED);
        assertThrows(IllegalArgumentException.class,
                () -> LifecycleTransitions.transition(created, GameStatus.RUNNING)); // skips LOBBY
    }

    @Test
    void nullArgumentsAreRejected() {
        assertFalse(LifecycleTransitions.canTransition(null, GameStatus.LOBBY));
        assertFalse(LifecycleTransitions.canTransition(GameStatus.CREATED, null));
        assertThrows(IllegalArgumentException.class,
                () -> LifecycleTransitions.transition(null, GameStatus.LOBBY));
    }

    @Test
    void archivedIsTerminal() {
        for (GameStatus to : GameStatus.values()) {
            assertFalse(LifecycleTransitions.canTransition(GameStatus.ARCHIVED, to),
                    "ARCHIVED is terminal, must not transition to " + to);
        }
        assertTrue(List.of(GameStatus.values()).size() >= 6, "all six lifecycle states present");
    }
}
