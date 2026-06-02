package com.stellarcompact.engine.state;

/**
 * Lifecycle of a {@link Treaty} (proposed -> active -> ended). A treaty stays in
 * state after it ends so the public event/reputation history (game-design 04)
 * can reference it.
 */
public enum TreatyStatus {
    /** Offered, awaiting AcceptTreaty/DeclineTreaty. */
    PROPOSED,
    /** Accepted and engine-enforced. */
    ACTIVE,
    /** Ran to its expiry tick honourably. */
    EXPIRED,
    /** Terminated early by BreakTreaty (public, reputation-penalised). */
    BROKEN
}
