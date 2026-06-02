package com.stellarcompact.api.match;

import com.stellarcompact.engine.state.GameStatus;

/**
 * Thrown by {@link MatchService} when a lifecycle transition is requested that the guarded
 * state machine ({@code LifecycleTransitions}, board card E1-15) forbids - e.g.
 * resuming a match that is not paused, starting a concluded match, or pausing one that is
 * not running. The controller maps it to {@code 409 Conflict} (a 4xx, per the card's
 * "illegal transition -> 4xx"): the request was well-formed but conflicts with the match's
 * current state.
 *
 * <p>Domain exception, not a Spring web type, so the {@link MatchService} seam carries no
 * web dependency and the guard is unit-testable without MVC.
 */
public class IllegalTransitionException extends RuntimeException {

    private final transient GameStatus from;
    private final transient GameStatus to;

    public IllegalTransitionException(GameStatus from, GameStatus to) {
        super("illegal lifecycle transition: " + from + " -> " + to);
        this.from = from;
        this.to = to;
    }

    public GameStatus from() {
        return from;
    }

    public GameStatus to() {
        return to;
    }
}
