package com.stellarcompact.engine.hash;

import java.lang.reflect.RecordComponent;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * Golden state-hash utility for the deterministic engine (TEST scope only).
 *
 * <p>The engine's replay contract (see {@code .claude/skills/game-engine-determinism})
 * requires that a fixed seed + fixed action log produce a fixed, byte-for-byte
 * reproducible state. To assert that in tests we need a hash that is
 * <strong>stable across runs and JVMs</strong>. Two sources of nondeterminism
 * must be defeated:
 * <ol>
 *   <li><b>Field / map ordering.</b> {@link java.util.HashMap} iteration order is
 *       unspecified and can leak into a naive {@code toString}. The canonical form
 *       below sorts every map by key, so insertion order is irrelevant.</li>
 *   <li><b>Identity hashes.</b> {@code Object#hashCode} / identity-based hashing is
 *       never used; we hash a deterministic textual canonical form with SHA-256.</li>
 * </ol>
 *
 * <p>This is a deliberately small, dependency-free canonicaliser (JDK only) so the
 * harness does not depend on any module's classpath additions. When real
 * {@code GameState} records land (E1-01) the same {@link #sha256Hex(Object)} entry
 * point applies to them: records expose components via {@code toString} of their
 * fields here, but the canonical traversal handles {@link Map}, {@link List},
 * {@link Optional} and scalars explicitly so collection ordering can be normalised.
 *
 * <p>TEST scope: free use of {@code java.security} is fine here; the engine MAIN
 * purity rule (no I/O / no Spring) does not apply to tests.
 */
public final class GoldenStateHash {

    private GoldenStateHash() {
    }

    /**
     * Produce the canonical, deterministic textual serialization of {@code value}.
     *
     * <p>Rules:
     * <ul>
     *   <li>{@code null} -> {@code "null"}.</li>
     *   <li>{@link Map} -> {@code {k1=...,k2=...}} with keys sorted by their own
     *       canonical form (defeats HashMap iteration-order nondeterminism).</li>
     *   <li>{@link List} -> {@code [..,..]} preserving order (order IS meaningful
     *       for ordered collections such as the action log).</li>
     *   <li>{@link Optional} -> the canonical form of the contained value, or
     *       {@code "null"} when empty.</li>
     *   <li>A {@link Record} -> {@code Type{component=...,...}} with its
     *       components decomposed recursively in <em>declaration</em> order (fixed
     *       and deterministic). This is what lets a {@code GameState} whose fields
     *       are {@link Map}s hash stably: each nested map is reached by the
     *       traversal and sorted, so a record's own {@code toString} (which would
     *       render maps in unspecified iteration order) is never relied upon.</li>
     *   <li>An {@link Enum} -> {@code Type.NAME} using the stable {@code name()},
     *       never the order-sensitive {@code ordinal()}.</li>
     *   <li>A {@link Set} -> {@code (..,..)} with elements sorted by their
     *       canonical form (set iteration order is unspecified).</li>
     *   <li>Everything else -> its {@code toString()} wrapped to record its type,
     *       e.g. {@code String("x")}, {@code Integer(7)}. Recording the type keeps
     *       {@code "7"} (String) distinct from {@code 7} (int).</li>
     * </ul>
     */
    public static String canonical(Object value) {
        StringBuilder sb = new StringBuilder();
        write(value, sb);
        return sb.toString();
    }

    private static void write(Object value, StringBuilder sb) {
        switch (value) {
            case null -> sb.append("null");
            case Map<?, ?> map -> {
                // Sort by canonical key so insertion/iteration order cannot leak.
                TreeMap<String, String> sorted = new TreeMap<>();
                for (Map.Entry<?, ?> e : map.entrySet()) {
                    sorted.put(canonical(e.getKey()), canonical(e.getValue()));
                }
                sb.append('{');
                boolean first = true;
                for (Map.Entry<String, String> e : sorted.entrySet()) {
                    if (!first) {
                        sb.append(',');
                    }
                    first = false;
                    sb.append(e.getKey()).append('=').append(e.getValue());
                }
                sb.append('}');
            }
            case List<?> list -> {
                sb.append('[');
                List<Object> items = new ArrayList<>(list);
                for (int i = 0; i < items.size(); i++) {
                    if (i > 0) {
                        sb.append(',');
                    }
                    write(items.get(i), sb);
                }
                sb.append(']');
            }
            case Optional<?> opt -> write(opt.orElse(null), sb);
            case Set<?> set -> {
                // Set iteration order is unspecified; sort by canonical element form.
                List<String> elements = new ArrayList<>(set.size());
                for (Object element : set) {
                    elements.add(canonical(element));
                }
                elements.sort(null);
                sb.append('(');
                for (int i = 0; i < elements.size(); i++) {
                    if (i > 0) {
                        sb.append(',');
                    }
                    sb.append(elements.get(i));
                }
                sb.append(')');
            }
            case Enum<?> e ->
                // Stable name(), never ordinal(): declaration order must not leak.
                    sb.append(e.getDeclaringClass().getSimpleName()).append('.').append(e.name());
            case Record record -> {
                // Decompose in declaration order; recurse so nested maps get sorted.
                sb.append(record.getClass().getSimpleName()).append('{');
                RecordComponent[] components = record.getClass().getRecordComponents();
                for (int i = 0; i < components.length; i++) {
                    if (i > 0) {
                        sb.append(',');
                    }
                    sb.append(components[i].getName()).append('=');
                    write(invokeComponent(record, components[i]), sb);
                }
                sb.append('}');
            }
            default -> sb.append(value.getClass().getSimpleName())
                    .append('(').append(value).append(')');
        }
    }

    /** Read one record component value via its accessor (deterministic, no I/O). */
    private static Object invokeComponent(Record record, RecordComponent component) {
        try {
            return component.getAccessor().invoke(record);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(
                    "could not read record component " + component.getName(), e);
        }
    }

    /**
     * SHA-256 over the UTF-8 bytes of the canonical form, lowercase hex encoded.
     * Stable across runs and JVMs.
     */
    public static String sha256Hex(Object value) {
        byte[] bytes = canonical(value).getBytes(StandardCharsets.UTF_8);
        MessageDigest md;
        try {
            md = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is a mandated JDK algorithm; absence is unrecoverable.
            throw new IllegalStateException("SHA-256 not available", e);
        }
        byte[] digest = md.digest(bytes);
        StringBuilder hex = new StringBuilder(digest.length * 2);
        for (byte b : digest) {
            hex.append(Character.forDigit((b >> 4) & 0xF, 16));
            hex.append(Character.forDigit(b & 0xF, 16));
        }
        return hex.toString();
    }
}
