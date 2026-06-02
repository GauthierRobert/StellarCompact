package com.stellarcompact.persistence;

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
 * Canonical, order-insensitive state hash for the persistence round-trip test
 * (E5-02). A byte-for-byte copy of the engine's test-scope
 * {@code com.stellarcompact.engine.hash.GoldenStateHash}: the engine declares it
 * in TEST scope so it is not on the persistence classpath, and the persistence
 * round-trip needs the exact same canonical equality the determinism contract uses
 * (sort maps by key, sort sets, decompose records in declaration order, enums by
 * name()). Keeping a faithful copy here lets the round-trip assert
 * {@code hash(loaded) == hash(original)} with the same semantics the engine uses.
 */
final class GoldenHash {

    private GoldenHash() {
    }

    static String canonical(Object value) {
        StringBuilder sb = new StringBuilder();
        write(value, sb);
        return sb.toString();
    }

    private static void write(Object value, StringBuilder sb) {
        switch (value) {
            case null -> sb.append("null");
            case Map<?, ?> map -> {
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
                    sb.append(e.getDeclaringClass().getSimpleName()).append('.').append(e.name());
            case Record record -> {
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

    private static Object invokeComponent(Record record, RecordComponent component) {
        try {
            return component.getAccessor().invoke(record);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(
                    "could not read record component " + component.getName(), e);
        }
    }

    static String sha256Hex(Object value) {
        byte[] bytes = canonical(value).getBytes(StandardCharsets.UTF_8);
        MessageDigest md;
        try {
            md = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
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
