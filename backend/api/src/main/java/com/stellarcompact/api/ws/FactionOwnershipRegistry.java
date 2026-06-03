package com.stellarcompact.api.ws;

import com.stellarcompact.engine.state.FactionId;

import java.util.List;

/**
 * The authoritative server-side answer to "may this principal see this Sovereign's
 * private WorldView?" (board card E6-04, the security crux; principle 2 - agents and
 * clients are untrusted). It is the single source of truth the
 * {@link OwnerViewAuthorizationInterceptor} consults on every owner-view SUBSCRIBE and
 * that the {@link LiveStreamPublisher} consults before pushing a per-faction view.
 *
 * <p><b>Why a seam.</b> Like {@code MatchService} / {@code GalaxyStateSource}, the
 * mapping lives behind an interface so the in-memory binding used now
 * ({@link InMemoryFactionOwnershipRegistry}) can be swapped for an auth-backed one
 * (principal resolved from a session token, ownership read from the match's seat
 * assignment table) without touching the broker config, the interceptor or the
 * publisher. The trust boundary is this single method.
 *
 * <p><b>Default deny.</b> {@link #owns} returns {@code false} for an unknown principal,
 * an unbound faction, or a {@code null} principal (an unauthenticated session). The
 * interceptor rejects the subscription on {@code false}; the publisher never enqueues a
 * view for a principal that does not own the faction. There is no path by which a
 * non-owner subscription resolves to {@code true}.
 */
public interface FactionOwnershipRegistry {

    /**
     * @return {@code true} iff {@code principalName} is the registered owner of
     *     {@code faction} in {@code gameId}; {@code false} for any unknown/unbound/null
     *     combination (default deny).
     */
    boolean owns(String principalName, String gameId, FactionId faction);

    /**
     * @return the principal name bound as owner of {@code faction} in {@code gameId},
     *     or {@code null} if the faction is unbound. Used by the publisher to address
     *     the owner's user queue.
     */
    String ownerOf(String gameId, FactionId faction);

    /**
     * Reverse lookup: every {@code (gameId, faction)} owned by {@code principalName}, in
     * ascending {@code (gameId, factionId)} order. Backs the {@code GET /api/me/games}
     * dashboard — the only games a logged-in user is shown are the ones they own a seat in.
     * Returns an empty list for a {@code null}/unknown principal (default deny).
     */
    List<OwnedFaction> factionsOwnedBy(String principalName);

    /**
     * A single ownership edge: the principal owns {@code faction} in {@code gameId}. The
     * {@code gameId:factionId} pair reconstructs the config handle the owner uses to read
     * its seat and to subscribe to its owner-only WorldView queue.
     */
    record OwnedFaction(String gameId, FactionId faction) {
    }
}
