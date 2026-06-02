package com.stellarcompact.agentruntime.prompt;

import java.util.List;

/**
 * The human-authored configuration of one Sovereign (card E4-02): its persona,
 * goals and hard constraints. These are <em>inputs</em> the human owner supplies
 * when configuring a seat (docs/architecture/03-agent-runtime.md section 2) — they
 * are NOT engine/game state and never flow through the deterministic resolver. The
 * agent-runtime injects them into the system prompt so the LLM plays in character
 * and within its owner's rules.
 *
 * <p><b>Untrusted, but author-supplied.</b> Unlike WorldView (server-built) and the
 * agent's output (untrusted model text), this config is authored by the seat's human
 * owner. It is still treated defensively here: every field is trimmed, nulls degrade
 * to empty, and the assembler renders it as plain text in a clearly-fenced section of
 * the system prompt so it cannot be confused with the rules/schema the engine dictates.
 *
 * <p><b>Immutability.</b> A deeply-immutable record with a defensively-copied
 * {@code goals}/{@code hardConstraints} list, safe to share across the virtual threads
 * the orchestrator fans out per tick.
 *
 * @param persona         a free-text persona/voice for the Sovereign (e.g. "A cautious
 *                        mercantile republic that prizes trade over conquest"). Blank
 *                        is allowed and renders as "(none specified)".
 * @param goals           ordered strategic goals, highest priority first. May be empty.
 * @param hardConstraints inviolable rules the owner imposes on its Sovereign (e.g.
 *                        "Never break a signed treaty", "Do not declare war first").
 *                        These are advisory to the model; the ENGINE remains the
 *                        authority (a constraint here never replaces engine validation).
 *                        May be empty.
 */
public record SovereignConfig(
        String persona,
        List<String> goals,
        List<String> hardConstraints
) {

    public SovereignConfig {
        persona = persona == null ? "" : persona.strip();
        goals = cleanList(goals);
        hardConstraints = cleanList(hardConstraints);
    }

    /** Convenience: a config with a persona only (no goals/constraints). */
    public static SovereignConfig ofPersona(String persona) {
        return new SovereignConfig(persona, List.of(), List.of());
    }

    /**
     * Trims each entry, drops null/blank entries, and returns an unmodifiable copy.
     * Order is preserved (goals are priority-ordered; constraints render in order).
     */
    private static List<String> cleanList(List<String> in) {
        if (in == null || in.isEmpty()) {
            return List.of();
        }
        return in.stream()
                .filter(s -> s != null && !s.isBlank())
                .map(String::strip)
                .toList();
    }
}
