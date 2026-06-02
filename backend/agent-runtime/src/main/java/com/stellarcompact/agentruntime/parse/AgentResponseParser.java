package com.stellarcompact.agentruntime.parse;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.stellarcompact.engine.action.Action;
import com.stellarcompact.engine.action.AgentResponse;
import com.stellarcompact.engine.action.AgentResponse.Message;
import com.stellarcompact.engine.action.UnknownAction;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Coerces a raw LLM completion into a typed, shape-valid {@link AgentResponse}
 * (card E4-03, security-sensitive). This is the untrusted-text boundary: everything
 * downstream (the engine validator E1-04, the resolver E1-05) may assume it received
 * the closed {@code Action} schema, because this stage either produced it or rejected
 * the text with a {@link ParseResult.Rejected}. It never throws on hostile input and
 * never trusts a model self-reported validity field.
 *
 * <h2>Pipeline (in order)</h2>
 * <ol>
 *   <li><b>Raw-size bound.</b> Reject {@link ParseRejectionCode#OVERSIZED} if the raw
 *       text exceeds {@code maxRawChars} before any parsing - a pre-validation DoS
 *       guard (spec section 5a). Jackson {@link StreamReadConstraints} additionally
 *       cap string length, nesting depth and number length inside the parse.</li>
 *   <li><b>Fence strip + JSON extraction.</b> Strip markdown code fences, tolerate
 *       leading/trailing prose, then locate the top-level JSON object by brace-balanced,
 *       string-aware scanning. <b>Zero</b> objects to {@code NO_JSON}; <b>more than
 *       one</b> top-level object to {@code AMBIGUOUS} (spec section 5: never silently
 *       pick one).</li>
 *   <li><b>Tree parse.</b> Read the single object as a {@link JsonNode} with a hardened
 *       mapper (no polymorphic default typing - spec section 5a).</li>
 *   <li><b>Per-action isolation.</b> Bind {@code messages}/{@code schemaVersion} from
 *       the tree, then bind each {@code actions[]} element <em>individually</em>: a
 *       throwing element degrades to the inert {@link UnknownAction} sentinel rather
 *       than aborting the whole response (spec section 5a). A malformed
 *       <em>top-level</em> shape is still rejected {@code SHAPE_INVALID}.</li>
 *   <li><b>Raw element caps.</b> Reject {@code TOO_MANY_ELEMENTS} if the array sizes
 *       exceed the configured caps (spec section 5a). Semantic per-entry caps (text
 *       length, ...) are E1-04's job, not this stage's.</li>
 * </ol>
 *
 * <p><b>Provider neutrality.</b> The input is a plain {@code String} completion; this
 * class touches no vendor type and no {@code ChatClient} - the caller (E4-04) pulls the
 * completion text from the provider-neutral {@code ChatClient} and hands it here. That
 * keeps the defensive parse independent of which model produced the text.
 */
@Component
public final class AgentResponseParser {

    /** Default raw-document char cap (DoS guard). Generous for a compact response. */
    public static final int DEFAULT_MAX_RAW_CHARS = 64 * 1024;
    /** Default per-string char cap inside the JSON (Jackson StreamReadConstraints). */
    public static final int DEFAULT_MAX_STRING_LEN = 32 * 1024;
    /** Default max JSON nesting depth (StreamReadConstraints). */
    public static final int DEFAULT_MAX_NESTING_DEPTH = 32;
    /** Default max number-literal length (StreamReadConstraints). */
    public static final int DEFAULT_MAX_NUMBER_LEN = 100;
    /** Default cap on {@code actions[]} length (raw structural cap). */
    public static final int DEFAULT_MAX_ACTIONS = 64;
    /** Default cap on {@code messages[]} length (raw structural cap). */
    public static final int DEFAULT_MAX_MESSAGES = 64;

    private final ObjectMapper mapper;
    private final int maxRawChars;
    private final int maxActions;
    private final int maxMessages;

    /** Constructs a parser with the default bounds (Spring injects this one). */
    public AgentResponseParser() {
        this(DEFAULT_MAX_RAW_CHARS, DEFAULT_MAX_STRING_LEN, DEFAULT_MAX_NESTING_DEPTH,
                DEFAULT_MAX_NUMBER_LEN, DEFAULT_MAX_ACTIONS, DEFAULT_MAX_MESSAGES);
    }

    /**
     * Full-control constructor (tests pin small bounds here).
     *
     * @param maxRawChars      reject the raw text outright above this length
     * @param maxStringLen     Jackson per-string cap
     * @param maxNestingDepth  Jackson nesting-depth cap
     * @param maxNumberLen     Jackson number-literal cap
     * @param maxActions       cap on {@code actions[]} length
     * @param maxMessages      cap on {@code messages[]} length
     */
    public AgentResponseParser(int maxRawChars, int maxStringLen, int maxNestingDepth,
                               int maxNumberLen, int maxActions, int maxMessages) {
        if (maxRawChars < 1) {
            throw new IllegalArgumentException("maxRawChars must be >= 1");
        }
        if (maxActions < 0 || maxMessages < 0) {
            throw new IllegalArgumentException("element caps must be >= 0");
        }
        this.maxRawChars = maxRawChars;
        this.maxActions = maxActions;
        this.maxMessages = maxMessages;
        this.mapper = hardenedMapper(maxStringLen, maxNestingDepth, maxNumberLen);
    }

    /**
     * Build the hardened mapper used to deserialize untrusted model output.
     *
     * <p><b>Security (spec section 5a).</b> Polymorphic default typing is left OFF and
     * no permissive {@code PolymorphicTypeValidator} is registered: the closed
     * {@code Action} set is resolved purely through its {@code JsonTypeInfo(use =
     * Id.NAME)} plus {@code JsonSubTypes} permit list, so the discriminator can never
     * instantiate an arbitrary (gadget) class. {@link StreamReadConstraints} cap raw
     * resource use during the parse. {@code Jdk8Module} teaches it the {@code Optional}
     * fields the Action records carry.
     */
    private static ObjectMapper hardenedMapper(int maxStringLen, int maxNestingDepth,
                                               int maxNumberLen) {
        StreamReadConstraints constraints = StreamReadConstraints.builder()
                .maxStringLength(maxStringLen)
                .maxNestingDepth(maxNestingDepth)
                .maxNumberLength(maxNumberLen)
                .build();
        // The raw-resource caps are applied at the streaming layer (JsonFactory), the
        // only place this Jackson build accepts them.
        JsonFactory factory = JsonFactory.builder()
                .streamReadConstraints(constraints)
                .build();
        // We deliberately never call activateDefaultTyping(...) and register no
        // PolymorphicTypeValidator: default typing would reopen the gadget hole the
        // closed @JsonSubTypes permit list exists to shut (spec section 5a).
        return JsonMapper.builder(factory)
                .addModule(new Jdk8Module())
                .build();
    }

    /**
     * Coerce raw model output into a typed {@link AgentResponse}, or reject it.
     *
     * @param rawOutput the model completion text (may be null/blank/garbage/hostile)
     * @return a {@link ParseResult.Parsed} on success (shape-valid, not yet
     * engine-validated) or a {@link ParseResult.Rejected} otherwise. Never throws.
     */
    public ParseResult parse(String rawOutput) {
        // 1. Raw-size DoS bound - before touching the parser.
        if (rawOutput == null || rawOutput.isBlank()) {
            return new ParseResult.Rejected(ParseRejectionCode.NO_JSON, "empty output");
        }
        if (rawOutput.length() > maxRawChars) {
            return new ParseResult.Rejected(ParseRejectionCode.OVERSIZED,
                    "raw length " + rawOutput.length() + " > " + maxRawChars);
        }

        // 2. Strip fences + extract exactly one top-level JSON object.
        String defenced = stripCodeFences(rawOutput);
        JsonExtraction extraction = extractSingleObject(defenced);
        if (extraction.code != null) {
            return new ParseResult.Rejected(extraction.code, extraction.detail);
        }

        // 3. Tree parse with the hardened mapper.
        JsonNode root;
        try {
            root = mapper.readTree(extraction.json);
        } catch (JsonProcessingException e) {
            return new ParseResult.Rejected(ParseRejectionCode.MALFORMED_JSON,
                    safe(e.getOriginalMessage()));
        } catch (RuntimeException e) {
            // StreamReadConstraints violations surface as RuntimeException subtypes.
            return new ParseResult.Rejected(ParseRejectionCode.OVERSIZED, safe(e.getMessage()));
        }
        if (root == null || !root.isObject()) {
            return new ParseResult.Rejected(ParseRejectionCode.SHAPE_INVALID,
                    "top-level value is not a JSON object");
        }

        // 4. Bind the top-level shape, isolating per-action failures.
        return bind((ObjectNode) root);
    }

    /**
     * Bind the validated single object to an {@link AgentResponse}, parsing each action
     * element in isolation so one bad element degrades to a sentinel instead of nuking
     * the batch. A malformed top-level shape (wrong JSON type for actions/messages/
     * schemaVersion, or a record invariant in the top-level/Message shape) is rejected.
     */
    private ParseResult bind(ObjectNode root) {
        // schemaVersion: optional, must be an int when present.
        int schemaVersion = AgentResponse.CURRENT_SCHEMA_VERSION;
        JsonNode versionNode = root.get("schemaVersion");
        if (versionNode != null && !versionNode.isNull()) {
            if (!versionNode.isInt() && !versionNode.canConvertToInt()) {
                return new ParseResult.Rejected(ParseRejectionCode.SHAPE_INVALID,
                        "schemaVersion is not an integer");
            }
            schemaVersion = versionNode.asInt();
        }

        // actions[]: optional; when present MUST be an array.
        List<Action> actions = new ArrayList<>();
        JsonNode actionsNode = root.get("actions");
        if (actionsNode != null && !actionsNode.isNull()) {
            if (!actionsNode.isArray()) {
                return new ParseResult.Rejected(ParseRejectionCode.SHAPE_INVALID,
                        "actions is not a JSON array");
            }
            ArrayNode arr = (ArrayNode) actionsNode;
            if (arr.size() > maxActions) {
                return new ParseResult.Rejected(ParseRejectionCode.TOO_MANY_ELEMENTS,
                        "actions length " + arr.size() + " > " + maxActions);
            }
            for (JsonNode element : arr) {
                actions.add(parseActionIsolated(element));
            }
        }

        // messages[]: optional; when present MUST be an array of well-formed Messages.
        List<Message> messages = new ArrayList<>();
        JsonNode messagesNode = root.get("messages");
        if (messagesNode != null && !messagesNode.isNull()) {
            if (!messagesNode.isArray()) {
                return new ParseResult.Rejected(ParseRejectionCode.SHAPE_INVALID,
                        "messages is not a JSON array");
            }
            ArrayNode arr = (ArrayNode) messagesNode;
            if (arr.size() > maxMessages) {
                return new ParseResult.Rejected(ParseRejectionCode.TOO_MANY_ELEMENTS,
                        "messages length " + arr.size() + " > " + maxMessages);
            }
            for (JsonNode element : arr) {
                try {
                    messages.add(mapper.treeToValue(element, Message.class));
                } catch (JsonProcessingException | IllegalArgumentException e) {
                    // A message has no inert sentinel (no recipient means not
                    // deliverable). A malformed message is a malformed top-level shape,
                    // so reject the whole response.
                    return new ParseResult.Rejected(ParseRejectionCode.SHAPE_INVALID,
                            "malformed message entry: " + safe(e.getMessage()));
                }
            }
        }

        try {
            AgentResponse response = new AgentResponse(messages, actions, schemaVersion);
            return new ParseResult.Parsed(response);
        } catch (IllegalArgumentException e) {
            // e.g. negative schemaVersion - a top-level invariant violation.
            return new ParseResult.Rejected(ParseRejectionCode.SHAPE_INVALID, safe(e.getMessage()));
        }
    }

    /**
     * Parse one {@code actions[]} element, never throwing. An unknown {@code type}
     * already degrades to {@link UnknownAction} via {@code defaultImpl} on {@link
     * Action}; this additionally catches a known variant with a malformed nested
     * payload (e.g. a bad Attack target kind) or a record-invariant violation and
     * converts it to the inert sentinel - so one bad action cannot abort the rest
     * (spec section 5a). The sentinel preserves the offending {@code type} label for
     * diagnostics where present.
     */
    private Action parseActionIsolated(JsonNode element) {
        if (element == null || !element.isObject()) {
            return new UnknownAction("<non-object>");
        }
        try {
            return mapper.treeToValue(element, Action.class);
        } catch (JsonProcessingException | IllegalArgumentException e) {
            JsonNode typeNode = element.get("type");
            String label = typeNode != null && typeNode.isTextual() ? typeNode.asText() : null;
            return new UnknownAction(label);
        }
    }

    // ---- raw text handling -----------------------------------------------------

    /**
     * Strip a leading/trailing markdown code fence if the body is fenced. Handles a
     * language-tagged fence and a bare fence. If there is no recognisable fence the text
     * is returned trimmed but otherwise unchanged - trailing prose outside a fence is
     * tolerated by the object-extraction step, not here.
     */
    static String stripCodeFences(String text) {
        String t = text.strip();
        String fence = "```";
        int firstFence = t.indexOf(fence);
        if (firstFence < 0) {
            return t;
        }
        int lastFence = t.lastIndexOf(fence);
        if (lastFence == firstFence) {
            // Only one fence marker - not a balanced fence; leave to extraction.
            return t;
        }
        // Content between the fences. Drop an optional language tag on the open-fence
        // line (e.g. a leading "json").
        int contentStart = firstFence + fence.length();
        int newline = t.indexOf('\n', contentStart);
        if (newline >= 0 && newline < lastFence) {
            String langTag = t.substring(contentStart, newline).strip();
            // A language tag is a short alnum token (json, JSON); anything else is real
            // content on the same line, so do not skip it.
            if (langTag.isEmpty() || langTag.chars().allMatch(Character::isLetterOrDigit)) {
                contentStart = newline + 1;
            }
        }
        return t.substring(contentStart, lastFence).strip();
    }

    /**
     * Locate exactly one top-level JSON object in {@code text}, tolerating prose before
     * and after it. Scans brace depth while respecting string literals and escapes so a
     * close-brace inside a string does not confuse the counter. Returns the first
     * balanced top-level object; if a <em>second</em> top-level JSON value (object or
     * array) starts after it, the output is {@link ParseRejectionCode#AMBIGUOUS}.
     */
    static JsonExtraction extractSingleObject(String text) {
        int firstObjectStart = -1;
        int firstObjectEnd = -1;
        boolean secondTopLevelSeen = false;

        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        int objectStart = -1;

        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            switch (c) {
                case '"' -> inString = true;
                case '{' -> {
                    if (depth == 0) {
                        if (firstObjectEnd >= 0) {
                            secondTopLevelSeen = true;
                        }
                        objectStart = i;
                    }
                    depth++;
                }
                case '}' -> {
                    if (depth > 0) {
                        depth--;
                        if (depth == 0 && firstObjectEnd < 0) {
                            firstObjectStart = objectStart;
                            firstObjectEnd = i + 1;
                        }
                    }
                }
                case '[' -> {
                    // A top-level array after the first object is a second top-level
                    // value (ambiguous). A top-level array before any object means the
                    // whole payload is an array of responses (also ambiguous).
                    if (depth == 0) {
                        secondTopLevelSeen = true;
                    }
                }
                default -> {
                    // ordinary prose / whitespace - ignored
                }
            }
        }

        if (inString || depth != 0) {
            // Unterminated string or unbalanced braces in the candidate region.
            return JsonExtraction.rejected(ParseRejectionCode.MALFORMED_JSON,
                    "unbalanced JSON (depth=" + depth + ", inString=" + inString + ")");
        }
        if (firstObjectEnd < 0) {
            return JsonExtraction.rejected(ParseRejectionCode.NO_JSON,
                    "no top-level JSON object found");
        }
        if (secondTopLevelSeen) {
            return JsonExtraction.rejected(ParseRejectionCode.AMBIGUOUS,
                    "more than one top-level JSON value");
        }
        return JsonExtraction.found(text.substring(firstObjectStart, firstObjectEnd));
    }

    private static String safe(String s) {
        if (s == null) {
            return "";
        }
        // Keep diagnostics bounded; this string is for server logs, never re-prompts.
        return s.length() > 200 ? s.substring(0, 200) + "..." : s;
    }

    /** Internal carrier for {@link #extractSingleObject}: either a JSON string or a code. */
    record JsonExtraction(String json, ParseRejectionCode code, String detail) {
        static JsonExtraction found(String json) {
            return new JsonExtraction(json, null, "");
        }

        static JsonExtraction rejected(ParseRejectionCode code, String detail) {
            return new JsonExtraction(null, code, detail);
        }
    }
}
