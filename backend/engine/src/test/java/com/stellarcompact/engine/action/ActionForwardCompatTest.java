package com.stellarcompact.engine.action;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Forward-compatibility contract (spec section 6): a newer agent may emit an
 * action variant an older engine does not know. Parsing must <em>degrade</em> -
 * the unknown entry becomes an inert {@link UnknownAction} sentinel - never throw,
 * so a single future action in an {@code actions[]} array cannot crash the whole
 * {@link AgentResponse} parse.
 */
class ActionForwardCompatTest {

    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .addModule(new Jdk8Module())
            .build();

    @Test
    void unknownActionTypeDeserialisesToSentinelNotException() throws Exception {
        String json = "{\"type\":\"WarpJump\",\"futureField\":42}";
        Action action = MAPPER.readValue(json, Action.class);
        // The contract is: degrade safely to the inert sentinel rather than throw.
        // (With As.PROPERTY Jackson consumes the discriminator before constructing
        // the default impl, so the sentinel carries the normalised "<unknown>"
        // label, not the literal future name - the label is diagnostic only.)
        assertInstanceOf(UnknownAction.class, action);
    }

    @Test
    void unknownVariantInActionsArrayDoesNotCrashParsing() throws Exception {
        // A mixed batch: a known Explore, a future WarpJump, a known Hold.
        String json = """
                {
                  "messages": [],
                  "schemaVersion": 2,
                  "actions": [
                    { "type": "Explore", "targetSystem": "sys-x" },
                    { "type": "WarpJump", "vector": [1,2,3], "charge": 9.5 },
                    { "type": "Hold" }
                  ]
                }
                """;

        AgentResponse response = MAPPER.readValue(json, AgentResponse.class);

        assertEquals(3, response.actions().size(), "all three entries must parse");
        assertInstanceOf(Action.Explore.class, response.actions().get(0));
        assertInstanceOf(UnknownAction.class, response.actions().get(1));
        assertInstanceOf(Action.Hold.class, response.actions().get(2));
        assertEquals(2, response.schemaVersion(),
                "a newer schemaVersion from the agent is preserved, not rejected");
    }

    @Test
    void unknownTopLevelFieldDoesNotCrashParsing() throws Exception {
        String json = """
                {
                  "messages": [],
                  "actions": [ { "type": "Hold" } ],
                  "schemaVersion": 1,
                  "futureTopLevelKey": { "anything": true }
                }
                """;
        AgentResponse response = MAPPER.readValue(json, AgentResponse.class);
        assertEquals(1, response.actions().size());
    }

    @Test
    void unknownActionMapsToNoneCategory() {
        // The exhaustive resolver classifier treats the sentinel as ignorable.
        assertTrue(ActionCategory.of(new UnknownAction("WarpJump")) == ActionCategory.NONE);
    }
}
