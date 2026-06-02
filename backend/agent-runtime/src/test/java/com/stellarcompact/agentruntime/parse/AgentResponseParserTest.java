package com.stellarcompact.agentruntime.parse;

import com.stellarcompact.engine.action.Action;
import com.stellarcompact.engine.action.AgentResponse;
import com.stellarcompact.engine.action.UnknownAction;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Card E4-03 - structured-output coercion + defensive parse (security-sensitive).
 *
 * <p>Proves the untrusted-text boundary: well-formed output coerces to a typed
 * {@link Action} batch; fenced JSON and trailing prose are tolerated; ambiguous,
 * oversized and garbage output are <b>rejected</b> (never thrown); an unknown action
 * variant degrades to the inert {@link UnknownAction} sentinel (E1-02) instead of
 * nuking the batch; and a self-reported "valid" flag is ignored (never trusted) - the
 * actual shape decides.
 */
class AgentResponseParserTest {

    private final AgentResponseParser parser = new AgentResponseParser();

    // ---- happy paths -----------------------------------------------------------

    @Test
    void cleanJsonParsesToTypedActions() {
        String json = """
                {
                  "schemaVersion": 1,
                  "messages": [ { "to": "faction-b", "text": "hello" } ],
                  "actions": [
                    { "type": "Explore", "targetSystem": "sys-7" },
                    { "type": "Hold" }
                  ]
                }
                """;

        AgentResponse response = parsed(parser.parse(json));
        assertEquals(2, response.actions().size());
        assertInstanceOf(Action.Explore.class, response.actions().get(0));
        assertInstanceOf(Action.Hold.class, response.actions().get(1));
        assertEquals(1, response.messages().size());
        assertEquals("hello", response.messages().get(0).text());
    }

    @Test
    void fencedJsonIsStrippedAndParsed() {
        String fenced = "```json\n"
                + "{ \"schemaVersion\": 1, \"actions\": [ { \"type\": \"Hold\" } ] }\n"
                + "```";
        AgentResponse response = parsed(parser.parse(fenced));
        assertEquals(1, response.actions().size());
        assertInstanceOf(Action.Hold.class, response.actions().get(0));
    }

    @Test
    void bareFencedJsonIsStrippedAndParsed() {
        String fenced = "```\n"
                + "{ \"actions\": [ { \"type\": \"Hold\" } ] }\n"
                + "```";
        AgentResponse response = parsed(parser.parse(fenced));
        assertInstanceOf(Action.Hold.class, response.actions().get(0));
    }

    @Test
    void jsonWithTrailingProseTolerated() {
        String text = "Sure, here is my move for this tick:\n"
                + "{ \"actions\": [ { \"type\": \"Explore\", \"targetSystem\": \"sys-1\" } ] }\n"
                + "Let me know if you need anything else!";
        AgentResponse response = parsed(parser.parse(text));
        assertInstanceOf(Action.Explore.class, response.actions().get(0));
    }

    @Test
    void closeBraceInsideStringDoesNotTruncate() {
        // A string value containing braces must not confuse the brace counter.
        String text = "{ \"actions\": [ { \"type\": \"SendMessage\", \"to\": \"faction-b\", "
                + "\"text\": \"deal looks like {win} for you }\" } ] }";
        AgentResponse response = parsed(parser.parse(text));
        assertInstanceOf(Action.SendMessage.class, response.actions().get(0));
    }

    // ---- ambiguity / garbage / size rejections ---------------------------------

    @Test
    void multipleTopLevelObjectsRejectedAsAmbiguous() {
        String text = "{ \"actions\": [ { \"type\": \"Hold\" } ] }\n"
                + "{ \"actions\": [ { \"type\": \"Explore\", \"targetSystem\": \"sys-2\" } ] }";
        ParseResult.Rejected rejected = rejected(parser.parse(text));
        assertEquals(ParseRejectionCode.AMBIGUOUS, rejected.code());
    }

    @Test
    void topLevelArrayRejectedAsAmbiguous() {
        String text = "[ { \"actions\": [ { \"type\": \"Hold\" } ] } ]";
        ParseResult.Rejected rejected = rejected(parser.parse(text));
        assertEquals(ParseRejectionCode.AMBIGUOUS, rejected.code());
    }

    @Test
    void pureProseRejectedAsNoJson() {
        ParseResult.Rejected rejected = rejected(parser.parse("I think I will just wait."));
        assertEquals(ParseRejectionCode.NO_JSON, rejected.code());
    }

    @Test
    void emptyOutputRejected() {
        assertEquals(ParseRejectionCode.NO_JSON, rejected(parser.parse("   ")).code());
    }

    @Test
    void unbalancedBracesRejectedAsMalformed() {
        ParseResult.Rejected rejected = rejected(parser.parse("{ \"actions\": [ { \"type\": "));
        assertEquals(ParseRejectionCode.MALFORMED_JSON, rejected.code());
    }

    @Test
    void brokenSyntaxInsideBalancedObjectRejectedAsMalformed() {
        // Balanced braces but invalid JSON inside (trailing comma + missing value).
        ParseResult.Rejected rejected = rejected(parser.parse("{ \"actions\": [ , ] }"));
        assertEquals(ParseRejectionCode.MALFORMED_JSON, rejected.code());
    }

    @Test
    void oversizedRawPayloadRejected() {
        // Tiny raw cap so a modest body trips the DoS guard before parsing.
        AgentResponseParser tiny = new AgentResponseParser(
                32,
                AgentResponseParser.DEFAULT_MAX_STRING_LEN,
                AgentResponseParser.DEFAULT_MAX_NESTING_DEPTH,
                AgentResponseParser.DEFAULT_MAX_NUMBER_LEN,
                AgentResponseParser.DEFAULT_MAX_ACTIONS,
                AgentResponseParser.DEFAULT_MAX_MESSAGES);
        String big = "{ \"actions\": [ { \"type\": \"Hold\" }, { \"type\": \"Hold\" } ] }";
        ParseResult.Rejected rejected = rejected(tiny.parse(big));
        assertEquals(ParseRejectionCode.OVERSIZED, rejected.code());
    }

    @Test
    void tooManyActionsRejected() {
        AgentResponseParser capped = new AgentResponseParser(
                AgentResponseParser.DEFAULT_MAX_RAW_CHARS,
                AgentResponseParser.DEFAULT_MAX_STRING_LEN,
                AgentResponseParser.DEFAULT_MAX_NESTING_DEPTH,
                AgentResponseParser.DEFAULT_MAX_NUMBER_LEN,
                2,
                AgentResponseParser.DEFAULT_MAX_MESSAGES);
        String text = "{ \"actions\": [ { \"type\": \"Hold\" }, { \"type\": \"Hold\" }, "
                + "{ \"type\": \"Hold\" } ] }";
        ParseResult.Rejected rejected = rejected(capped.parse(text));
        assertEquals(ParseRejectionCode.TOO_MANY_ELEMENTS, rejected.code());
    }

    // ---- per-action isolation (spec 5a) ----------------------------------------

    @Test
    void unknownActionVariantDegradesToSentinelNotRejection() {
        String text = """
                { "actions": [
                  { "type": "Explore", "targetSystem": "sys-1" },
                  { "type": "WarpJump", "vector": [1,2,3] },
                  { "type": "Hold" }
                ] }
                """;
        AgentResponse response = parsed(parser.parse(text));
        assertEquals(3, response.actions().size());
        assertInstanceOf(Action.Explore.class, response.actions().get(0));
        assertInstanceOf(UnknownAction.class, response.actions().get(1));
        assertInstanceOf(Action.Hold.class, response.actions().get(2));
    }

    @Test
    void malformedNestedTargetKindIsolatedToSentinelNotWholeBatch() {
        // A KNOWN action (Attack) with an unknown nested target.kind throws during a
        // naive single readValue and would nuke the whole response. Isolation degrades
        // only that action to the sentinel; siblings survive (spec section 5a).
        String text = """
                { "actions": [
                  { "type": "Build", "planet": "p-1", "slot": 0, "buildingType": "MINE" },
                  { "type": "Attack", "fleet": "f-1", "target": { "kind": "blackhole" } },
                  { "type": "Hold" }
                ] }
                """;
        AgentResponse response = parsed(parser.parse(text));
        assertEquals(3, response.actions().size());
        assertInstanceOf(Action.Build.class, response.actions().get(0));
        assertInstanceOf(UnknownAction.class, response.actions().get(1));
        assertEquals("Attack", ((UnknownAction) response.actions().get(1)).type(),
                "the offending type label is preserved for diagnostics");
        assertInstanceOf(Action.Hold.class, response.actions().get(2));
    }

    // ---- malformed top-level shape vs. per-action isolation --------------------

    @Test
    void actionsNotAnArrayIsTopLevelShapeReject() {
        ParseResult.Rejected rejected = rejected(parser.parse("{ \"actions\": \"Hold\" }"));
        assertEquals(ParseRejectionCode.SHAPE_INVALID, rejected.code());
    }

    @Test
    void malformedMessageEntryIsTopLevelShapeReject() {
        // A message with no recipient cannot be delivered: there is no inert sentinel,
        // so a malformed message is a malformed top-level shape and is rejected.
        String text = "{ \"messages\": [ { \"text\": \"no recipient\" } ], "
                + "\"actions\": [ { \"type\": \"Hold\" } ] }";
        ParseResult.Rejected rejected = rejected(parser.parse(text));
        assertEquals(ParseRejectionCode.SHAPE_INVALID, rejected.code());
    }

    // ---- never trust the model's self-report -----------------------------------

    @Test
    void selfReportedValidButActuallyInvalidIsNotTrusted() {
        // The model claims "valid: true" while its single action is a known variant with
        // a malformed nested target. We ignore the self-report; the action degrades to a
        // sentinel on shape alone, and the bogus "valid" key is dropped as unknown.
        String text = """
                {
                  "valid": true,
                  "actions": [
                    { "type": "Attack", "fleet": "f-1", "target": { "kind": "totally-legit" } }
                  ]
                }
                """;
        AgentResponse response = parsed(parser.parse(text));
        assertEquals(1, response.actions().size());
        assertInstanceOf(UnknownAction.class, response.actions().get(0),
                "self-reported validity must not promote a malformed action");
    }

    // ---- result-shape sanity ---------------------------------------------------

    @Test
    void parseNeverThrowsOnHostileInput() {
        // A grab-bag of nasties; the contract is a value, never an exception.
        String[] nasties = {
                "",
                "}{",
                "{ \"actions\": [ { \"type\": ",
                "null",
                "42",
                "[[[[",
                "{ \"schemaVersion\": \"not-an-int\" }"
        };
        for (String n : nasties) {
            ParseResult result = parser.parse(n);
            assertTrue(result instanceof ParseResult.Rejected,
                    "expected rejection (not throw) for: " + n);
        }
    }

    @Test
    void rejectedFactoryNormalisesNullDetail() {
        ParseResult.Rejected r = new ParseResult.Rejected(ParseRejectionCode.NO_JSON, null);
        assertSame(ParseRejectionCode.NO_JSON, r.code());
        assertEquals("", r.detail());
    }

    // ---- helpers ---------------------------------------------------------------

    private static AgentResponse parsed(ParseResult result) {
        assertInstanceOf(ParseResult.Parsed.class, result,
                () -> "expected Parsed but was " + result);
        return ((ParseResult.Parsed) result).response();
    }

    private static ParseResult.Rejected rejected(ParseResult result) {
        assertInstanceOf(ParseResult.Rejected.class, result,
                () -> "expected Rejected but was " + result);
        return (ParseResult.Rejected) result;
    }
}
