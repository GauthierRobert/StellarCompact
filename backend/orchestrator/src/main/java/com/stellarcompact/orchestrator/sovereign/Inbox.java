package com.stellarcompact.orchestrator.sovereign;

import com.stellarcompact.engine.action.AgentResponse;
import com.stellarcompact.engine.state.FactionId;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The negotiation message inbox the orchestrator carries between negotiation rounds and
 * across ticks (card E4-06). It maps each recipient {@link FactionId} to the
 * free-text {@link WorldView.InboxMessage}s addressed to it, and the
 * {@link WorldViewBuilder} surfaces a recipient's own slice into its
 * {@link WorldView#inbox()}.
 *
 * <p><b>Why this lives in the orchestrator, not engine {@code GameState}.</b> Free-text
 * negotiation messages have <em>no mechanical force</em> (diplomacy 04 section 1): they
 * are persuasion, threat, bluff. The engine resolver is pure and deterministic and must
 * never fold a wall-clock-ordered chat log into the golden state hash (principle 1), so
 * messages are transient orchestration state delivered into the recipient's next
 * perception, never into authoritative state. A pending trade/treaty <em>offer</em>, by
 * contrast, IS engine state (a directed {@code MarketOrder} / proposed {@code Treaty}
 * with an addressee and an expiry tick) and persists in {@code GameState} until
 * accepted/declined/expired - so offers are NOT carried here; only messages are.
 *
 * <p><b>Effect-free by construction.</b> An {@code Inbox} can only ever produce
 * {@link WorldView.InboxMessage} entries appended to a recipient's view. It is never
 * read by the engine and contributes nothing to the submitted action batch, so a
 * message can change what an agent <em>chooses</em> next, but can never by itself change
 * any resource, score or treaty - the only binding path is a validated structured
 * action resolved by the engine in the Action phase.
 *
 * <p><b>Immutability &amp; replay-stability.</b> The backing map is defensively copied
 * to unmodifiable lists, safe to share across the virtual threads the negotiation
 * fan-out spawns. Each recipient's list preserves insertion (delivery) order; the
 * builder sorts nothing here because message order is the conversation order, which is
 * orchestration-transient and not part of the deterministic engine boundary.
 */
public record Inbox(Map<FactionId, List<WorldView.InboxMessage>> byRecipient) {

    /** The empty inbox: every faction's slice is empty. The first-round / first-tick value. */
    public static final Inbox EMPTY = new Inbox(Map.of());

    public Inbox {
        if (byRecipient == null) {
            byRecipient = Map.of();
        } else {
            Map<FactionId, List<WorldView.InboxMessage>> copy = new LinkedHashMap<>();
            for (var e : byRecipient.entrySet()) {
                copy.put(e.getKey(), List.copyOf(e.getValue()));
            }
            byRecipient = Map.copyOf(copy);
        }
    }

    /**
     * @param recipient a faction
     * @return the messages addressed to {@code recipient}, in delivery order; never null,
     * empty when nothing was sent to it
     */
    public List<WorldView.InboxMessage> forRecipient(FactionId recipient) {
        return byRecipient.getOrDefault(recipient, List.of());
    }

    /** @return true iff no recipient has any delivered message. */
    public boolean isEmpty() {
        return byRecipient.values().stream().allMatch(List::isEmpty);
    }

    /**
     * Build an inbox by routing free-text negotiation {@link AgentResponse.Message}s to
     * their addressees, stamping each with the {@code sentTick} it was sent on. A message
     * to an unknown/self faction is still routed by its {@code to} field (the orchestrator
     * has already engine-validated {@code SendMessage} actions; raw {@code Message}s from
     * the response are non-binding free text and carry their own {@code to}). Messages
     * already present in {@code carryOver} (e.g. earlier negotiation rounds) are preserved
     * ahead of the newly routed ones, so a multi-round conversation accumulates in order.
     *
     * @param carryOver messages already delivered this tick (earlier rounds), or EMPTY
     * @param from       the sender of every message in {@code newMessages}
     * @param newMessages the sender's free-text messages this round (never null)
     * @param sentTick    the tick these messages were sent on
     * @return a new inbox merging carry-over and freshly routed messages
     */
    public static Inbox route(Inbox carryOver, FactionId from,
                              List<AgentResponse.Message> newMessages, long sentTick) {
        Map<FactionId, List<WorldView.InboxMessage>> merged = new LinkedHashMap<>();
        Inbox base = carryOver == null ? EMPTY : carryOver;
        for (var e : base.byRecipient().entrySet()) {
            merged.put(e.getKey(), new ArrayList<>(e.getValue()));
        }
        if (newMessages != null) {
            for (AgentResponse.Message m : newMessages) {
                if (m == null) {
                    continue;
                }
                merged.computeIfAbsent(m.to(), k -> new ArrayList<>())
                        .add(new WorldView.InboxMessage(from, m.text(), sentTick));
            }
        }
        return new Inbox(merged);
    }

    /**
     * Convenience used by the loop carrying this tick's gathered messages into the next
     * tick's context. Each {@code (sender, messages)} pair is routed in order.
     *
     * @param senderMessages an ordered list of (sender, that sender's messages) pairs
     * @param sentTick       the tick the messages were sent on
     * @return the assembled inbox for the next perception
     */
    public static Inbox of(List<Map.Entry<FactionId, List<AgentResponse.Message>>> senderMessages,
                           long sentTick) {
        Inbox acc = EMPTY;
        if (senderMessages != null) {
            for (var e : senderMessages) {
                acc = route(acc, e.getKey(), e.getValue(), sentTick);
            }
        }
        return acc;
    }

    /** Stable comparator handy for tests/debug: by recipient id. */
    public static final Comparator<FactionId> BY_ID = Comparator.comparing(FactionId::value);
}
