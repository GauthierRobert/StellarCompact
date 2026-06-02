package com.stellarcompact.agentruntime.prompt;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.stellarcompact.agentruntime.chat.AgentRuntimeProperties;
import com.stellarcompact.agentruntime.chat.AgentRuntimeProperties.PromptProperties;
import org.springframework.stereotype.Component;

/**
 * Assembles the system + user messages for one Sovereign call (card E4-02).
 *
 * <p>This is assembly ONLY: it builds the prompt the {@code ChatClient} (E4-01) is
 * given. Structured-output coercion (E4-03) and validation/re-prompt (E4-04) are
 * separate, later cards and are deliberately not done here.
 *
 * <pre>
 * system = persona + goals + hard constraints
 *        + compact rules summary
 *        + strict output JSON schema (the closed Action set)
 *        + one worked example
 * user   = the serialized compact WorldView
 * </pre>
 *
 * <p><b>Determinism.</b> Given the same {@link SovereignConfig} and the same WorldView,
 * the assembled prompt is byte-for-byte identical: the static sections are fixed
 * strings, the config renders in a fixed layout, and the WorldView is serialised with
 * a mapper configured for stable key ordering. This is what the golden-prompt test
 * pins.
 *
 * <p><b>WorldView dependency decision.</b> {@code agent-runtime} depends only on
 * {@code engine} + {@code galaxy}, NOT on {@code orchestrator} (where the production
 * {@code WorldView} record lives). Adding that dependency would couple the runtime to
 * the orchestrator and risk a cycle (the orchestrator is what drives the runtime). So
 * the assembler accepts the WorldView as an opaque {@code Object} at the module
 * boundary and serialises it itself: the orchestrator passes its own {@code WorldView}
 * record in unchanged, and any token-compact, JSON-serialisable view object works. The
 * runtime owns the serialisation contract (the deterministic mapper) without owning —
 * or depending on — the view's concrete type.
 *
 * <p><b>Provider neutrality.</b> The assembler produces {@link AssembledPrompt} (raw
 * text + a vendor-agnostic Spring AI {@code Prompt}); it never names a model or
 * provider. Tier→model routing stays in {@link AgentRuntimeProperties} / the
 * {@code SovereignChatClientFactory}.
 */
@Component
public class PromptAssembler {

    private final AgentRuntimeProperties properties;
    private final ActionSchemaCatalog schema;
    private final ObjectMapper worldViewMapper;

    public PromptAssembler(AgentRuntimeProperties properties, ActionSchemaCatalog schema) {
        this.properties = properties;
        this.schema = schema;
        this.worldViewMapper = deterministicMapper();
    }

    /**
     * Builds a deterministic, defensively-configured mapper for serialising the
     * compact WorldView into the user message.
     *
     * <ul>
     *   <li><b>Stable ordering</b> ({@code SORT_PROPERTIES_ALPHABETICALLY} +
     *       {@code ORDER_MAP_ENTRIES_BY_KEYS}) so the same view always serialises to
     *       the same bytes — the basis of the golden test.</li>
     *   <li><b>Jdk8Module</b> so the WorldView's {@code Optional<>} fields (and the
     *       Action records') serialise to their value / {@code null} rather than a
     *       bean shape.</li>
     *   <li><b>No default typing.</b> This is a SERIALISER only; we never enable
     *       polymorphic default typing here (the untrusted-deserialisation hardening
     *       of spec section 5a lives in E4-03's parse mapper, not this one).</li>
     * </ul>
     */
    private static ObjectMapper deterministicMapper() {
        return new ObjectMapper()
                .registerModule(new Jdk8Module())
                .configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true)
                .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true)
                .configure(SerializationFeature.FAIL_ON_EMPTY_BEANS, false)
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    /**
     * Assemble the prompt for one Sovereign call.
     *
     * @param config    the human-authored persona/goals/hard-constraints for this seat
     *                  (not null)
     * @param worldView the compact, fog-filtered WorldView for this faction this tick
     *                  (not null) — serialised verbatim into the user message
     * @return the assembled system + user messages, with the estimated token count
     * @throws PromptBudgetExceededException if the estimated tokens exceed the
     *                                       configured budget (see that type for the rule)
     * @throws IllegalArgumentException      if {@code config} or {@code worldView} is null,
     *                                       or the WorldView cannot be serialised
     */
    public AssembledPrompt assemble(SovereignConfig config, Object worldView) {
        if (config == null) {
            throw new IllegalArgumentException("PromptAssembler.assemble: config must be set");
        }
        if (worldView == null) {
            throw new IllegalArgumentException("PromptAssembler.assemble: worldView must be set");
        }

        String systemMessage = buildSystemMessage(config);
        String userMessage = buildUserMessage(worldView);

        PromptProperties prompt = properties.prompt();
        int estimatedTokens = prompt.estimateTokens(systemMessage)
                + prompt.estimateTokens(userMessage);
        if (prompt.budgetEnforced() && estimatedTokens > prompt.maxTokens()) {
            throw new PromptBudgetExceededException(estimatedTokens, prompt.maxTokens());
        }

        return new AssembledPrompt(systemMessage, userMessage, estimatedTokens);
    }

    /**
     * The system prompt: persona + goals + hard constraints + rules summary + strict
     * output schema + one worked example, in a fixed, clearly-fenced layout.
     */
    private String buildSystemMessage(SovereignConfig config) {
        StringBuilder sb = new StringBuilder(2048);

        sb.append("You are the Sovereign of a faction in Stellar Compact.\n\n");

        sb.append("== PERSONA ==\n");
        sb.append(config.persona().isBlank() ? "(none specified)" : config.persona());
        sb.append("\n\n");

        sb.append("== GOALS (highest priority first) ==\n");
        appendNumberedOrNone(sb, config.goals());
        sb.append('\n');

        sb.append("== HARD CONSTRAINTS (never violate these) ==\n");
        appendNumberedOrNone(sb, config.hardConstraints());
        sb.append('\n');

        sb.append("== ").append("RULES ==\n");
        sb.append(schema.rulesSummary());
        sb.append("\n\n");

        sb.append("== OUTPUT ==\n");
        sb.append(schema.outputSchema());
        sb.append("\n\n");

        sb.append(schema.workedExample());

        return sb.toString();
    }

    /** The user message: just the serialized compact WorldView, prefixed for context. */
    private String buildUserMessage(Object worldView) {
        return "WORLDVIEW (your perception this tick):\n" + serializeWorldView(worldView);
    }

    private String serializeWorldView(Object worldView) {
        try {
            return worldViewMapper
                    .writerWithDefaultPrettyPrinter()
                    .writeValueAsString(worldView);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException(
                    "PromptAssembler: WorldView is not serialisable to JSON", e);
        }
    }

    private static void appendNumberedOrNone(StringBuilder sb, java.util.List<String> items) {
        if (items.isEmpty()) {
            sb.append("(none specified)\n");
            return;
        }
        int i = 1;
        for (String item : items) {
            sb.append(i++).append(". ").append(item).append('\n');
        }
    }
}