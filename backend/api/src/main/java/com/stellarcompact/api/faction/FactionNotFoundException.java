package com.stellarcompact.api.faction;

/**
 * Thrown by {@link FactionConfigService} when an operation references a faction handle
 * (or a seat within a match) that does not exist. The controller maps it to
 * {@code 404 Not Found}. A domain exception (no Spring web type) keeps the service seam
 * unit-testable without MVC.
 */
public class FactionNotFoundException extends RuntimeException {
    public FactionNotFoundException(String message) {
        super(message);
    }
}
