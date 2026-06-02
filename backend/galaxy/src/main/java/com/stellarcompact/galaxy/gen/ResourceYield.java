package com.stellarcompact.galaxy.gen;

import java.util.EnumMap;
import java.util.Map;

/**
 * An immutable, deterministic per-resource yield vector (game-design 01 section
 * 2 favoured-yield column). Backed by an {@link EnumMap} keyed by {@link
 * Resource}; missing resources read as zero. Values are small non-negative
 * integer weights representing a biome's procedural baseline output.
 *
 * <p>Pure value type: every factory copies its input into an unmodifiable map,
 * so instances cannot be mutated after construction and equality is structural.
 * Iteration order is {@link Resource} declaration order (stable), which keeps any
 * downstream hashing/serialisation byte-identical across JVMs.
 */
public record ResourceYield(Map<Resource, Integer> values) {

    public ResourceYield {
        EnumMap<Resource, Integer> copy = new EnumMap<>(Resource.class);
        for (Map.Entry<Resource, Integer> e : values.entrySet()) {
            int v = e.getValue();
            if (v != 0) {
                copy.put(e.getKey(), v);
            }
        }
        values = java.util.Collections.unmodifiableMap(copy);
    }

    /** The yield for a resource, or 0 if this biome does not favour it. */
    public int get(Resource r) {
        return values.getOrDefault(r, 0);
    }

    /** An empty yield vector (Toxic worlds produce nothing untreated). */
    public static ResourceYield none() {
        return new ResourceYield(new EnumMap<>(Resource.class));
    }

    /** A single-resource yield. */
    public static ResourceYield of(Resource r, int amount) {
        EnumMap<Resource, Integer> m = new EnumMap<>(Resource.class);
        m.put(r, amount);
        return new ResourceYield(m);
    }

    /** A flat, balanced yield across all four resources (Terran generalist). */
    public static ResourceYield balanced(int amount) {
        EnumMap<Resource, Integer> m = new EnumMap<>(Resource.class);
        for (Resource r : Resource.values()) {
            m.put(r, amount);
        }
        return new ResourceYield(m);
    }

    /** A copy of this vector with {@code amount} added to {@code r}. */
    public ResourceYield plus(Resource r, int amount) {
        EnumMap<Resource, Integer> m = new EnumMap<>(Resource.class);
        m.putAll(values);
        m.merge(r, amount, Integer::sum);
        return new ResourceYield(m);
    }
}
