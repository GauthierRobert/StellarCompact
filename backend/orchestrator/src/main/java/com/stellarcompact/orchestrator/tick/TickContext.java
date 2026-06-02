package com.stellarcompact.orchestrator.tick;

import com.stellarcompact.engine.config.BalanceProfile;
import com.stellarcompact.engine.map.LaneNetwork;
import com.stellarcompact.engine.state.GameState;
import com.stellarcompact.orchestrator.sovereign.Inbox;
import com.stellarcompact.orchestrator.sovereign.SystemAdjacency;

/**
 * The read-only inputs a {@link Seat} needs to decide one phase (card E4-05): the
 * authoritative snapshot plus the static-per-match inputs ({@link BalanceProfile},
 * {@link LaneNetwork} for validation, {@link SystemAdjacency} for fog-of-war sensor
 * reveal), plus the negotiation {@link Inbox} (card E4-06). It is a deeply-immutable
 * bundle, safe to share across the virtual threads the orchestrator fans out per phase -
 * nothing here is mutated by a seat's decide pass (validation and WorldView projection
 * are pure reads of {@code state}).
 *
 * <p><b>The inbox seam (E4-06).</b> Negotiation messages are transient, per-tick
 * orchestration state - NOT engine {@link GameState} (the pure resolver never sees free
 * text). They are delivered to a recipient by threading them through this context: the
 * orchestrator hands each seat the {@link Inbox} so {@link Seat#decide} surfaces the
 * recipient's own slice into its {@code WorldView}. A message A sends to B in negotiation
 * round 1 is folded into the inbox the orchestrator passes for round 2 (and the loop
 * carries this tick's gathered messages into the next tick's context), so a recipient
 * sees, in its next view, what was sent to it (diplomacy 04 section 1). Pending
 * trade/treaty <em>offers</em>, by contrast, ARE engine state ({@code MarketOrder} /
 * {@code Treaty} with addressee + expiry) and persist across ticks in {@code state}
 * itself until accepted/declined/expired - the {@code WorldViewBuilder} already surfaces
 * them, so they need no inbox.
 *
 * @param state     authoritative game state this tick (never mutated by a seat)
 * @param profile   active balance profile (source of every gameplay number)
 * @param network   active-region lane graph for validation, or {@link LaneNetwork#EMPTY}
 * @param adjacency lane adjacency for fog-of-war sensor reveal, or
 *                  {@link SystemAdjacency#NONE}
 * @param inbox     negotiation messages to deliver into recipients' views this phase,
 *                  or {@link Inbox#EMPTY} (the prior-tick / first-round case)
 */
public record TickContext(
        GameState state,
        BalanceProfile profile,
        LaneNetwork network,
        SystemAdjacency adjacency,
        Inbox inbox
) {

    public TickContext {
        if (state == null) {
            throw new IllegalArgumentException("TickContext.state must be set");
        }
        if (profile == null) {
            throw new IllegalArgumentException("TickContext.profile must be set");
        }
        if (network == null) {
            network = LaneNetwork.EMPTY;
        }
        if (adjacency == null) {
            adjacency = SystemAdjacency.NONE;
        }
        if (inbox == null) {
            inbox = Inbox.EMPTY;
        }
    }

    /**
     * Backwards-compatible constructor for the common case of no delivered messages
     * (the first tick, or callers that do not thread negotiation across ticks).
     */
    public TickContext(GameState state, BalanceProfile profile, LaneNetwork network,
                       SystemAdjacency adjacency) {
        this(state, profile, network, adjacency, Inbox.EMPTY);
    }

    /** @return a copy of this context with {@code inbox} replaced (used between rounds). */
    public TickContext withInbox(Inbox newInbox) {
        return new TickContext(state, profile, network, adjacency, newInbox);
    }
}
