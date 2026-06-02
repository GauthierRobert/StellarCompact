package com.stellarcompact.persistence;

/**
 * Placeholder for the persistence module (PostgreSQL repositories + object-store
 * adapter). Real repositories arrive in later cards.
 */
public final class PersistenceModule {

    private PersistenceModule() {
    }

    public static String name() {
        return "persistence";
    }
}
