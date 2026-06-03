package com.stellarcompact.api.ws;

import com.stellarcompact.engine.state.FactionId;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory {@link FactionOwnershipRegistry} (the current E6-04 backing, mirroring the
 * {@code InMemoryMatchService} seam pattern). A later auth card replaces this with a
 * binding derived from the match seat-assignment table and a session-authenticated
 * principal, behind the same interface.
 *
 * <p><b>Keying.</b> Ownership is keyed by a {@code (gameId, factionId)} record so the same
 * human principal can own different factions across concurrent matches, two principals in
 * the same match stay isolated, and the {@link #factionsOwnedBy reverse lookup} can
 * reconstruct each edge unambiguously (a string key would be ambiguous if a gameId ever
 * contained the separator). The map ({@code key -> principal}) also lets the publisher
 * address the owner user queue.
 *
 * <p><b>Default deny by construction.</b> {@link #owns} only ever returns {@code true}
 * when an explicit {@link #bind} placed exactly this principal against exactly this
 * {@code (gameId, faction)}. A {@code null} principal, an unbound faction, or a
 * different principal all fall through to {@code false}.
 */
@Component
public class InMemoryFactionOwnershipRegistry implements FactionOwnershipRegistry {

    private final Map<Key, String> ownerByKey = new ConcurrentHashMap<>();

    /** Bind {@code principalName} as the sole owner of {@code faction} in {@code gameId}. */
    public void bind(String principalName, String gameId, FactionId faction) {
        if (principalName == null || principalName.isBlank()) {
            throw new IllegalArgumentException("principalName must be non-blank");
        }
        ownerByKey.put(new Key(gameId, faction.value()), principalName);
    }

    /** Remove all bindings for a match (e.g. when it concludes). */
    public void clearGame(String gameId) {
        ownerByKey.keySet().removeIf(k -> k.gameId().equals(gameId));
    }

    @Override
    public boolean owns(String principalName, String gameId, FactionId faction) {
        if (principalName == null || gameId == null || faction == null) {
            return false;
        }
        return principalName.equals(ownerByKey.get(new Key(gameId, faction.value())));
    }

    @Override
    public String ownerOf(String gameId, FactionId faction) {
        if (gameId == null || faction == null) {
            return null;
        }
        return ownerByKey.get(new Key(gameId, faction.value()));
    }

    @Override
    public List<OwnedFaction> factionsOwnedBy(String principalName) {
        if (principalName == null || principalName.isBlank()) {
            return List.of();
        }
        return ownerByKey.entrySet().stream()
                .filter(e -> principalName.equals(e.getValue()))
                .map(e -> new OwnedFaction(e.getKey().gameId(), new FactionId(e.getKey().faction())))
                .sorted(Comparator
                        .comparing((OwnedFaction o) -> o.gameId())
                        .thenComparing(o -> o.faction().value()))
                .toList();
    }

    /** Composite ownership key; {@code faction} is the {@link FactionId} value. */
    private record Key(String gameId, String faction) {
    }
}
