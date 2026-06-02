package com.stellarcompact.agentruntime.validate;

import com.stellarcompact.agentruntime.parse.AgentResponseParser;
import com.stellarcompact.agentruntime.prompt.AssembledPrompt;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * E4-04 validate-after-parse + single re-prompt -&gt; Hold. Exercises the loop against
 * the real {@code ChatClient} wiring over a {@link ScriptedChatModel} stub, the real
 * {@link AgentResponseParser} (E4-03) and the real engine {@code ActionValidator}
 * (E1-04). Asserts the at-most-one-retry invariant, that the rejection reason is fed
 * back, and that a post-retry failure ends in a Hold with no invalid action surviving.
 */
class ActionValidationLoopTest {

    private final ActionValidationLoop loop = new ActionValidationLoop(new AgentResponseParser());

    private final AssembledPrompt prompt = new AssembledPrompt(
            "SYSTEM: rules + schema", "WORLDVIEW: ...", 10);

    private final ValidationContext ctx =
            ValidationContext.of(TestFixtures.baseState(), TestFixtures.ALPHA, TestFixtures.profile());

    // A valid action: build a mine on ALPHA's own planet (free slot, affordable, ungated).
    private static final String VALID_BUILD =
            "{\"actions\":[{\"type\":\"Build\",\"planet\":\"planetA\",\"slot\":0,"
                    + "\"buildingType\":\"MINE\"}]}";

    // An invalid action: build on BETA's planet -> NOT_OWNED.
    private static final String INVALID_BUILD_NOT_OWNED =
            "{\"actions\":[{\"type\":\"Build\",\"planet\":\"planetB\",\"slot\":0,"
                    + "\"buildingType\":\"MINE\"}]}";

    // One valid + one invalid action together.
    private static final String MIXED =
            "{\"actions\":["
                    + "{\"type\":\"Build\",\"planet\":\"planetA\",\"slot\":0,\"buildingType\":\"MINE\"},"
                    + "{\"type\":\"Build\",\"planet\":\"planetB\",\"slot\":0,\"buildingType\":\"MINE\"}"
                    + "]}";

    private static final String EXPLICIT_HOLD =
            "{\"actions\":[{\"type\":\"Hold\"}]}";

    private ChatClient client(ScriptedChatModel model) {
        return ChatClient.create(model);
    }

    @Test
    void firstTryValidDoesNotRePrompt() {
        ScriptedChatModel model = new ScriptedChatModel(VALID_BUILD);

        ValidatedDecision d = loop.decide(client(model), prompt, ctx);

        assertEquals(1, model.callCount(), "a clean first turn must not re-prompt");
        assertFalse(d.rePrompted());
        assertFalse(d.holds());
        assertEquals(1, d.validActions().size());
    }

    @Test
    void parseRejectionTriggersExactlyOneRePromptThenSucceeds() {
        ScriptedChatModel model = new ScriptedChatModel("I refuse to answer in JSON.", VALID_BUILD);

        ValidatedDecision d = loop.decide(client(model), prompt, ctx);

        assertEquals(2, model.callCount(), "exactly one re-prompt on a parse failure");
        assertTrue(d.rePrompted());
        assertFalse(d.holds());
        assertEquals(1, d.validActions().size());
        // The re-prompt (second call) must carry the closed parse-reason feedback.
        assertTrue(model.promptText(1).contains("NO_JSON"),
                "the re-prompt must feed back the closed parse rejection code");
        assertTrue(model.promptText(1).contains(RejectionFeedback.HEADER),
                "the re-prompt must include the corrective header");
    }

    @Test
    void validationRejectionFeedsReasonIntoRePrompt() {
        ScriptedChatModel model = new ScriptedChatModel(INVALID_BUILD_NOT_OWNED, VALID_BUILD);

        ValidatedDecision d = loop.decide(client(model), prompt, ctx);

        assertEquals(2, model.callCount());
        assertTrue(d.rePrompted());
        // The engine NOT_OWNED reason must appear verbatim in the re-prompt.
        assertTrue(model.promptText(1).contains("NOT_OWNED"),
                "the engine rejection code must be fed back");
        assertTrue(model.promptText(1).contains("not owned by you"),
                "the engine human-readable message must be fed back verbatim");
        assertFalse(d.holds());
        assertEquals(1, d.validActions().size());
    }

    @Test
    void postRetryFailureEndsInHoldWithNoInvalidAction() {
        // Both turns return the SAME invalid action -> after the single re-prompt the
        // faction holds; no invalid action leaks into the result.
        ScriptedChatModel model =
                new ScriptedChatModel(INVALID_BUILD_NOT_OWNED, INVALID_BUILD_NOT_OWNED);

        ValidatedDecision d = loop.decide(client(model), prompt, ctx);

        assertEquals(2, model.callCount(), "at most one re-prompt even when it also fails");
        assertTrue(d.rePrompted());
        assertTrue(d.holds(), "post-retry failure must hold");
        assertTrue(d.validActions().isEmpty(), "no invalid action may survive");
    }

    @Test
    void retryRejectionDoesNotTriggerAFurtherRePrompt() {
        // Three replies queued, but only the first two may ever be consumed.
        ScriptedChatModel model = new ScriptedChatModel(
                "garbage not json", "still not json", VALID_BUILD);

        ValidatedDecision d = loop.decide(client(model), prompt, ctx);

        assertEquals(2, model.callCount(),
                "the loop must terminate after one re-prompt regardless of further failure");
        assertTrue(d.holds());
        assertTrue(d.validActions().isEmpty());
    }

    @Test
    void mixedValidAndInvalidRePromptsAndKeepsTheFinalSet() {
        // First turn: one valid + one invalid -> re-prompt. Second turn: both valid.
        ScriptedChatModel model = new ScriptedChatModel(MIXED, VALID_BUILD);

        ValidatedDecision d = loop.decide(client(model), prompt, ctx);

        assertEquals(2, model.callCount(), "any rejection in the batch forces the single re-prompt");
        assertTrue(d.rePrompted());
        assertFalse(d.holds());
        assertEquals(1, d.validActions().size(), "the clean second turn's actions are final");
        assertTrue(model.promptText(1).contains("NOT_OWNED"));
    }

    @Test
    void modelErrorOnFirstTurnDegradesToRePromptThenHoldOnSecondError() {
        ScriptedChatModel model = new ScriptedChatModel();
        model.throwOnNextCall(); // first call throws (provider timeout/error)

        ValidatedDecision d = loop.decide(client(model), prompt, ctx);

        // First call threw -> counted as a turn; one re-prompt issued; second returns "".
        assertEquals(2, model.callCount());
        assertTrue(d.rePrompted());
        assertTrue(d.holds(), "a model error degrades to Hold after the single re-prompt");
        assertTrue(d.validActions().isEmpty());
    }

    @Test
    void allValidFirstTryWithExplicitHoldDoesNotRePrompt() {
        ScriptedChatModel model = new ScriptedChatModel(EXPLICIT_HOLD);

        ValidatedDecision d = loop.decide(client(model), prompt, ctx);

        assertEquals(1, model.callCount(), "an explicit Hold validates -> no re-prompt");
        assertFalse(d.rePrompted());
        // A lone Hold is valid; it survives as an action (the orchestrator may collapse it).
        assertFalse(d.holds(), "a valid explicit Hold action is not an empty/dropped batch");
        assertEquals(1, d.validActions().size());
    }
}
