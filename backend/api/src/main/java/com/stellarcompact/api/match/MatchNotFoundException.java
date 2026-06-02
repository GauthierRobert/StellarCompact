package com.stellarcompact.api.match;

/**
 * Thrown by {@link MatchService} when an operation references a {@code gameId} (or a
 * requester faction) that does not exist. The controller maps it to {@code 404 Not Found}.
 * Keeping it a domain exception (not a Spring web type) keeps the service seam free of any
 * web dependency, so it stays unit-testable without MVC.
 */
public class MatchNotFoundException extends RuntimeException {
    public MatchNotFoundException(String message) {
        super(message);
    }
}
