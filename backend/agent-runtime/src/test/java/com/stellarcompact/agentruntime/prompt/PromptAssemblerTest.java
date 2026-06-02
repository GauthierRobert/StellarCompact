package com.stellarcompact.agentruntime.prompt;

import com.stellarcompact.agentruntime.chat.AgentRuntimeProperties;
import com.stellarcompact.agentruntime.chat.AgentRuntimeProperties.PromptProperties;
import com.stellarcompact.agentruntime.chat.ModelTier;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.prompt.Prompt;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Card E4-02 — prompt assembly tests.
 *
 * <p>Proves: persona/goals/constraints are injected; the compact rules summary, the
 * strict closed-Action schema and the worked example are all present; the WorldView is
 * serialised into the user message; assembly is deterministic (a stored golden); and
 * an over-budget input is handled per the budget rule (fail-fast).
 */
class PromptAssemblerTest {

    /** Fixed human-authored config — the golden input half. */
    private static final SovereignConfig FIXED_CONFIG = new SovereignConfig(
            "A cautious mercantile republic that prizes trade over conquest.",
            List.of("Grow the economy", "Secure trade routes", "Avoid open war"),
            List.of("Never strike first", "Never break a signed treaty"));

    private static PromptAssembler assemblerWithBudget(int maxTokens) {
        AgentRuntimeProperties props = new AgentRuntimeProperties(
                ModelTier.SMALL,
                Map.of(),
                new PromptProperties(maxTokens, PromptProperties.DEFAULT_CHARS_PER_TOKEN));
        return new PromptAssembler(props, new ActionSchemaCatalog());
    }

    private static PromptAssembler defaultAssembler() {
        // Default config block (no explicit budget) => default budget.
        return new PromptAssembler(
                AgentRuntimeProperties.of(ModelTier.SMALL, Map.of()),
                new ActionSchemaCatalog());
    }

    @Test
    void injectsPersonaGoalsAndHardConstraints() {
        AssembledPrompt prompt = defaultAssembler().assemble(FIXED_CONFIG, TestWorldView.fixture());
        String sys = prompt.systemMessage();

        assertTrue(sys.contains("== PERSONA =="), "persona section header present");
        assertTrue(sys.contains("prizes trade over conquest"), "persona text injected");

        assertTrue(sys.contains("== GOALS (highest priority first) =="), "goals header present");
        assertTrue(sys.contains("1. Grow the economy"), "goals numbered in order");
        assertTrue(sys.contains("3. Avoid open war"), "all goals present, ordered");

        assertTrue(sys.contains("== HARD CONSTRAINTS (never violate these) =="), "constraints header");
        assertTrue(sys.contains("1. Never strike first"), "constraint 1 injected");
        assertTrue(sys.contains("2. Never break a signed treaty"), "constraint 2 injected");
    }

    @Test
    void embedsRulesSummarySchemaAndOneWorkedExample() {
        AssembledPrompt prompt = defaultAssembler().assemble(FIXED_CONFIG, TestWorldView.fixture());
        String sys = prompt.systemMessage();

        // Rules summary.
        assertTrue(sys.contains("RULES OF PLAY (compact summary):"), "rules summary present");

        // Strict closed-Action schema: header + every legal variant + the closed-set warning.
        assertTrue(sys.contains("STRICT OUTPUT SCHEMA"), "schema header present");
        assertTrue(sys.contains("\"schemaVersion\": " + ActionSchemaCatalog.SCHEMA_VERSION),
                "schema names the engine schema version");
        ActionSchemaCatalog catalog = new ActionSchemaCatalog();
        assertEquals(25, catalog.actionTypes().size(),
                "the closed set has exactly 25 variants (UnknownAction excluded)");
        for (String type : catalog.actionTypes()) {
            assertTrue(sys.contains("\"type\":\"" + type + "\""),
                    "schema documents the payload of closed-set variant " + type);
        }
        // The forward-compat sentinel must NEVER be advertised to the model.
        assertFalse(sys.contains("UnknownAction"), "UnknownAction sentinel must not be in the prompt");

        // Exactly one worked example.
        assertTrue(sys.contains("WORKED EXAMPLE"), "worked example present");
        assertEquals(1, countOccurrences(sys, "WORKED EXAMPLE"), "exactly ONE worked example");
        assertTrue(sys.contains("\"buildingType\": \"MINE\""), "worked example is a concrete response");
    }

    @Test
    void serializesWorldViewIntoTheUserMessage() {
        AssembledPrompt prompt = defaultAssembler().assemble(FIXED_CONFIG, TestWorldView.fixture());
        String user = prompt.userMessage();

        assertTrue(user.startsWith("WORLDVIEW"), "user message is the WorldView");
        // Field values from the fixture must be serialised into the user message.
        assertTrue(user.contains("\"tick\" : 42"), "tick serialised");
        assertTrue(user.contains("\"name\" : \"Mercantile Republic\""), "self name serialised");
        assertTrue(user.contains("faction-1"), "FactionId serialises as a bare string (@JsonValue)");
        assertTrue(user.contains("system-12"), "neutral neighbour serialised");
        assertTrue(user.contains("\"energy\" : 100.0"), "ResourceBundle serialised");

        // The WorldView lives in the USER message, never the system message.
        assertFalse(prompt.systemMessage().contains("Mercantile Republic"),
                "WorldView must not leak into the system prompt");
    }

    @Test
    void buildsSpringPromptWithSystemThenUserMessage() {
        AssembledPrompt prompt = defaultAssembler().assemble(FIXED_CONFIG, TestWorldView.fixture());
        Prompt springPrompt = prompt.toSpringPrompt();

        List<Message> messages = springPrompt.getInstructions();
        assertEquals(2, messages.size(), "system + user");
        assertEquals(MessageType.SYSTEM, messages.get(0).getMessageType());
        assertEquals(MessageType.USER, messages.get(1).getMessageType());
        assertEquals(prompt.systemMessage(), messages.get(0).getText());
        assertEquals(prompt.userMessage(), messages.get(1).getText());
    }

    @Test
    void assemblyIsDeterministic() {
        AssembledPrompt a = defaultAssembler().assemble(FIXED_CONFIG, TestWorldView.fixture());
        AssembledPrompt b = defaultAssembler().assemble(FIXED_CONFIG, TestWorldView.fixture());
        assertEquals(a.systemMessage(), b.systemMessage(), "system message is byte-stable");
        assertEquals(a.userMessage(), b.userMessage(), "user message is byte-stable");
        assertEquals(a.estimatedTokens(), b.estimatedTokens(), "token estimate is stable");
    }

    @Test
    void matchesStoredGoldenPrompt() throws IOException {
        AssembledPrompt prompt = defaultAssembler().assemble(FIXED_CONFIG, TestWorldView.fixture());
        // The golden is the full wire: system, a separator, then user — one stable string.
        String actual = prompt.systemMessage()
                + "\n----------8<---------- USER ----------8<----------\n"
                + prompt.userMessage();

        String golden = readGolden("/prompt/golden-system-and-user.txt");
        assertEquals(normalise(golden), normalise(actual),
                "assembled prompt drifted from the committed golden — "
                        + "review and re-bless prompt/golden-system-and-user.txt if intended");
    }

    @Test
    void overBudgetInputIsRejectedFailFast() {
        // A tiny budget the fixed (non-trivial) prompt cannot fit => fail-fast per the rule.
        PromptAssembler tiny = assemblerWithBudget(8);
        PromptBudgetExceededException ex = assertThrows(
                PromptBudgetExceededException.class,
                () -> tiny.assemble(FIXED_CONFIG, TestWorldView.fixture()));
        assertTrue(ex.estimatedTokens() > ex.maxTokens(), "estimate exceeds the budget");
        assertEquals(8, ex.maxTokens(), "the configured budget is reported");
    }

    @Test
    void withinBudgetReportsTheEstimateAndDoesNotThrow() {
        AssembledPrompt prompt = assemblerWithBudget(100_000)
                .assemble(FIXED_CONFIG, TestWorldView.fixture());
        assertNotNull(prompt);
        assertTrue(prompt.estimatedTokens() > 0, "a non-empty prompt has a positive estimate");
        // Estimate equals the char-based estimator over both messages.
        PromptProperties pp = PromptProperties.defaults();
        int expected = pp.estimateTokens(prompt.systemMessage())
                + pp.estimateTokens(prompt.userMessage());
        assertEquals(expected, prompt.estimatedTokens(), "estimate = system + user token estimate");
    }

    @Test
    void zeroOrNegativeBudgetDisablesTheCheck() {
        // maxTokens == 0 normalises to the default budget (>0). A negative budget is the
        // explicit "disabled" sentinel: budget not enforced, never throws.
        PromptProperties disabled = new PromptProperties(-1, 4);
        assertFalse(disabled.budgetEnforced(), "negative budget disables enforcement");

        AgentRuntimeProperties props = new AgentRuntimeProperties(ModelTier.SMALL, Map.of(), disabled);
        AssembledPrompt prompt = new PromptAssembler(props, new ActionSchemaCatalog())
                .assemble(FIXED_CONFIG, TestWorldView.fixture());
        assertNotNull(prompt, "disabled budget never rejects");
    }

    @Test
    void nullConfigOrWorldViewIsRejected() {
        PromptAssembler a = defaultAssembler();
        assertThrows(IllegalArgumentException.class, () -> a.assemble(null, TestWorldView.fixture()));
        assertThrows(IllegalArgumentException.class, () -> a.assemble(FIXED_CONFIG, null));
    }

    // -- helpers ----------------------------------------------------------------

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        int idx = 0;
        while ((idx = haystack.indexOf(needle, idx)) >= 0) {
            count++;
            idx += needle.length();
        }
        return count;
    }

    /** Normalise line endings so the golden is stable across OS checkouts. */
    private static String normalise(String s) {
        return s.replace("\r\n", "\n").replace("\r", "\n");
    }

    private static String readGolden(String resource) throws IOException {
        try (InputStream in = PromptAssemblerTest.class.getResourceAsStream(resource)) {
            if (in == null) {
                fail("missing golden resource " + resource
                        + " — generate it from the assembler output and commit it");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}