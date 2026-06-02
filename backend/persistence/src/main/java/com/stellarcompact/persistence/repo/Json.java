package com.stellarcompact.persistence.repo;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Tiny JSON (de)serialisation helper for the {@code jsonb} columns the active-set
 * repositories map (E5-02).
 *
 * <p>A handful of columns in the schema are free-shape lists/maps the engine
 * carries as ordered {@link java.util.List}s / {@link java.util.Map}s rather than
 * dedicated rows: {@code fleet.enroute_path_json}, {@code route.resources_json},
 * {@code treaty.parties_json} / {@code terms_json}. Persisting them as {@code jsonb}
 * keeps the row count bounded and the ordering intact (the engine treats those
 * lists as meaningful order). This helper centralises the single {@link ObjectMapper}
 * the mappers share so JSON shape is consistent across the module.
 *
 * <p>Engine value-ids ({@code SystemId}, {@code FactionId}, ...) serialise as bare
 * JSON strings via their {@code @JsonValue}/{@code @JsonCreator}, so a
 * {@code List<SystemId>} round-trips as a JSON array of strings - exactly what the
 * path/parties columns want.
 */
final class Json {

    /** One shared mapper; engine ids carry their own {@code @JsonValue}/{@code @JsonCreator}. */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private Json() {
    }

    /** Serialise {@code value} to a JSON string for a {@code jsonb} column. */
    static String write(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("could not serialise value to JSON: " + value, e);
        }
    }

    /** Deserialise {@code json} into the generic type described by {@code type}. */
    static <T> T read(String json, TypeReference<T> type) {
        if (json == null) {
            return null;
        }
        try {
            return MAPPER.readValue(json, type);
        } catch (Exception e) {
            throw new IllegalStateException("could not deserialise JSON: " + json, e);
        }
    }
}
