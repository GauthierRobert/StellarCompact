package com.stellarcompact.api.ws;

import com.stellarcompact.engine.state.FactionId;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory {@link FactionOwnershipRegistry} (the current E6-04 backing, mirroring the
 * {@code InMemoryMatchService} seam pattern). A later auth card replaces this with a
 * binding derived from the match seat-assignment table and a session-authenticated
 * principal, behind the same interface.
 *
 * <p><b>Keying.</b> Ownership is keyed by {@code (gameId, factionId)} so the same human
 * principal can own different factions across concurrent matches, and so two principals
 * in the same match stay isolated. The map ({@code key -> principal}) also lets the
 * publisher address the owner user queue.
 *
 * <p><b>Default deny by construction.</b> {@link #owns} only ever returns {@code true}
 * when an explicit {@link #bind} placed exactly this principal against exactly this
 * {@code (gameId, faction)}. A {@code null} principal, an unbound faction, or a
 * different principal all fall through to {@code false}.
 */
@Component
public class InMemoryFactionOwnershipRegistry implements FactionOwnershipRegistry {

    private final Map<String, String> ownerByKey = new ConcurrentHashMap<>();

    /** Bind {@code principalName} as the sole owner of {@code faction} in {@code gameId}. */
    public void bind(String principalName, String gameId, FactionId faction) {
        if (principalName == null || principalName.isBlank()) {
            throw new IllegalArgumentException("principalName must be non-blank");
        }
        ownerByKey.put(key(gameId, faction), principalName);
    }

    /** Remove all bindings for a match (e.g. when it concludes). */
    public void clearGame(String gameId) {
        String prefix = gameId + " ";
        ownerByKey.keySet().removeIf(k -> k.startsWith(prefix));
    }

    @Override
    public boolean owns(String principalName, String gameId, FactionId faction) {
        if (principalName == null || gameId == null || faction == null) {
            return false;
        }
        return principalName.equals(ownerByKey.get(key(gameId, faction)));
    }

    @Override
    public String ownerOf(String gameId, FactionId faction) {
        if (gameId == null || faction == null) {
            return null;
        }
        return ownerByKey.get(key(gameId, faction));
    }

    private static String key(String gameId, FactionId faction) {
        return gameId + " " + faction.value();
    }
}
