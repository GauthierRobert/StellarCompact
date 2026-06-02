package com.stellarcompact.agentruntime.prompt;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.stellarcompact.engine.action.Action;
import com.stellarcompact.engine.action.AgentResponse;
import com.stellarcompact.engine.action.EspionageOperation;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * The static, engine-derived prompt sections (card E4-02): a compact rules summary,
 * the strict output JSON schema describing the <em>closed</em> {@link Action} set, and
 * one worked example {@link AgentResponse}. These are the parts of the system prompt
 * that are the same for every Sovereign — only persona/goals/constraints and the
 * WorldView vary per call.
 *
 * <p><b>Single source of truth for the closed set.</b> The list of legal action
 * {@code type} discriminators is read at construction from the engine's
 * {@code @JsonSubTypes} permit list on {@link Action} — so if a variant is ever added
 * to the engine, the schema text the model sees stays in lock-step (and the golden
 * test will flag the change). We deliberately do NOT hand-maintain the variant set in
 * two places. The per-variant payload field descriptions are a curated text block
 * (the engine records carry no machine-readable field docs), kept adjacent to the
 * authoritative list so drift is obvious.
 *
 * <p>The catalog is immutable and stateless; one instance is shared across all calls.
 */
@Component
public final class ActionSchemaCatalog {

    /** The wire schema version the model is told to emit (mirrors the engine). */
    public static final int SCHEMA_VERSION = AgentResponse.CURRENT_SCHEMA_VERSION;

    private final List<String> actionTypes;
    private final String rulesSummary;
    private final String outputSchema;
    private final String workedExample;

    public ActionSchemaCatalog() {
        this.actionTypes = readClosedActionTypes();
        this.rulesSummary = buildRulesSummary();
        this.outputSchema = buildOutputSchema(this.actionTypes);
        this.workedExample = buildWorkedExample();
    }

    /** @return the closed list of legal action {@code type} values, in permit order. */
    public List<String> actionTypes() {
        return actionTypes;
    }

    /** @return the compact rules-of-the-game summary block (system-prompt section). */
    public String rulesSummary() {
        return rulesSummary;
    }

    /** @return the strict output JSON schema block (the closed Action set). */
    public String outputSchema() {
        return outputSchema;
    }

    /** @return one worked, valid {@link AgentResponse} example block. */
    public String workedExample() {
        return workedExample;
    }

    // -- closed-set extraction --------------------------------------------------

    /**
     * Reads the legal action discriminators from the engine's {@code @JsonSubTypes}
     * on {@link Action}, in declaration order, dropping the {@code UnknownAction}
     * forward-compat sentinel (an agent must never be told to emit it).
     */
    private static List<String> readClosedActionTypes() {
        JsonSubTypes subTypes = Action.class.getAnnotation(JsonSubTypes.class);
        if (subTypes == null) {
            throw new IllegalStateException(
                    "engine Action is missing @JsonSubTypes - cannot derive the closed schema");
        }
        return Arrays.stream(subTypes.value())
                .map(JsonSubTypes.Type::name)
                .filter(name -> !"Unknown".equals(name))
                .toList();
    }

    // -- section builders (stable text; golden-tested) --------------------------

    private static String buildRulesSummary() {
        return RULES_SUMMARY;
    }

    private static String buildOutputSchema(List<String> actionTypes) {
        String espionageOps = Arrays.stream(EspionageOperation.values())
                .map(Enum::name)
                .collect(Collectors.joining(" | "));
        String typeList = String.join(", ", actionTypes);
        return OUTPUT_SCHEMA_TEMPLATE.formatted(SCHEMA_VERSION, typeList, espionageOps);
    }

    private static String buildWorkedExample() {
        return WORKED_EXAMPLE_TEMPLATE.formatted(SCHEMA_VERSION);
    }

    private static final String RULES_SUMMARY = """
            RULES OF PLAY (compact summary):
            - You are an autonomous Sovereign (an AI faction) in a real-time galactic strategy game.
            - The galaxy is refereed by a deterministic ENGINE. You PROPOSE actions; the engine DISPOSES.
              You never mutate game state directly and you cannot cheat - illegal moves are rejected.
            - Each tick you receive a WorldView (your entire perception, fog-of-war filtered: you can
              see your own state in full, neighbours only by ownership and rough strength) and you
              return messages (non-binding diplomacy) and actions (the binding moves).
            - Resolution order: diplomatic-state actions resolve FIRST. A kinetic action
              (Attack/Blockade/Raid) against an OWNED target requires a war-state with that faction;
              a non-aggression/alliance/ceasefire treaty FORBIDS attacking that partner until broken.
            - Spend only what your stockpiles cover; build only on owned planets with a free slot;
              move fleets only along real lanes from where they currently are.
            - Negotiation text is non-binding - only accepted structured proposals (treaties/trades)
              bind. Breaking an active treaty costs reputation.
            - If you have nothing useful to do, return a single Hold action.""";

    private static final String OUTPUT_SCHEMA_TEMPLATE = """
            STRICT OUTPUT SCHEMA - respond with ONE JSON object, nothing else (no prose, no
            markdown fences). It MUST match exactly:

            {
              "schemaVersion": %d,
              "messages": [ { "to": "<factionId>", "text": "<string>" } ],
              "actions":  [ <Action>, ... ]
            }

            Each <Action> is ONE object tagged by a "type" discriminator. "type" MUST be one of
            this CLOSED set (anything else is dropped):
              %s

            Action payloads (only the named fields; ids are strings you saw in the WorldView):
              Explore        { "type":"Explore",        "targetSystem": "<systemId>" }
              Colonize       { "type":"Colonize",       "planet": "<planetId>", "viaFleet": "<fleetId>" }
              Build          { "type":"Build",          "planet": "<planetId>", "slot": <int>, "buildingType": "<BuildingType>" }
              Research       { "type":"Research",       "techId": "<techId>" }
              Terraform      { "type":"Terraform",      "planet": "<planetId>" }
              BuildFleet     { "type":"BuildFleet",     "system": "<systemId>", "shipSpec": "<string>" }
              MoveFleet      { "type":"MoveFleet",      "fleet": "<fleetId>", "path": ["<systemId>", ...], "destination": "<systemId>" }
              EstablishRoute { "type":"EstablishRoute", "systemA": "<systemId>", "systemB": "<systemId>", "kind": "<RouteKind>", "resources": ["<PhysicalResource>", ...], "volume": <number> }
              SendMessage    { "type":"SendMessage",    "to": "<factionId>", "text": "<string>" }
              ProposeTrade   { "type":"ProposeTrade",   "to": "<factionId>", "give": <ResourceBundle>, "receive": <ResourceBundle>, "expiresIn": <int|null> }
              AcceptTrade    { "type":"AcceptTrade",    "offerId": "<offerId>" }
              DeclineTrade   { "type":"DeclineTrade",   "offerId": "<offerId>" }
              WithdrawTrade  { "type":"WithdrawTrade",  "offerId": "<offerId>" }
              ProposeTreaty  { "type":"ProposeTreaty",  "to": "<factionId>", "treatyType": "<TreatyType>", "terms": { "<k>":"<v>" }, "duration": <int|null> }
              AcceptTreaty   { "type":"AcceptTreaty",   "treatyId": "<treatyId>" }
              DeclineTreaty  { "type":"DeclineTreaty",   "treatyId": "<treatyId>" }
              BreakTreaty    { "type":"BreakTreaty",    "treatyId": "<treatyId>" }
              Tribute        { "type":"Tribute",        "to": "<factionId>", "resources": <ResourceBundle> }
              DemandTribute  { "type":"DemandTribute",  "from": "<factionId>", "resources": <ResourceBundle>, "orElse": "<string|null>" }
              DeclareWar     { "type":"DeclareWar",     "target": "<factionId>" }
              Attack         { "type":"Attack",         "fleet": "<fleetId>", "target": { "kind":"system", "system":"<systemId>" } OR { "kind":"fleet", "fleet":"<fleetId>" } }
              Blockade       { "type":"Blockade",       "fleet": "<fleetId>", "target": { "kind":"route", "route":"<routeId>" } OR { "kind":"system", "system":"<systemId>" } }
              Raid           { "type":"Raid",           "fleet": "<fleetId>", "routeId": "<routeId>" }
              Espionage      { "type":"Espionage",      "target": "<factionId>", "operationType": "<%s>" }
              Hold           { "type":"Hold" }

            A <ResourceBundle> is { "energy":<n>, "minerals":<n>, "food":<n>, "tech":<n>, "influence":<n> }.
            Emit only the fields shown; do NOT invent fields or action types.""";

    private static final String WORKED_EXAMPLE_TEMPLATE = """
            WORKED EXAMPLE (a small, valid response - adapt to YOUR WorldView, do not copy ids):

            {
              "schemaVersion": %d,
              "messages": [
                { "to": "faction-2", "text": "Proposing a non-aggression pact along our shared border." }
              ],
              "actions": [
                { "type": "Build", "planet": "planet-7", "slot": 0, "buildingType": "MINE" },
                { "type": "Explore", "targetSystem": "system-12" },
                { "type": "Hold" }
              ]
            }""";
}