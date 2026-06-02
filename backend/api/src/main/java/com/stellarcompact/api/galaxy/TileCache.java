package com.stellarcompact.api.galaxy;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Generate-on-miss tile cache: a tile is generated deterministically from
 * {@code (gameSeed, level, x, y)} on a cache miss, then retained, so repeat
 * requests for the same address are served without regenerating (lod-tiling skill:
 * "Generate on miss ... cache"; architecture 02 section 5).
 *
 * <h2>Hilbert-ordered keys for locality</h2>
 * The cache key is {@code (gameSeed, level, hilbertIndex(level, x, y))}, not the
 * raw {@code (x, y)}. Because the Hilbert curve keeps spatially adjacent tiles
 * adjacent in index space, tiles a viewport fetches together cluster in key space
 * - the same property that makes a CDN/range index behave (lod-tiling skill;
 * E8-04). The mapping is a bijection per level, so the Hilbert key identifies the
 * tile exactly (no collisions, fully reversible to {@code (x, y)}).
 *
 * <h2>Cache is an optimisation, never the source of truth</h2>
 * Every entry is reproducible from the seed at any time; eviction only costs a
 * regeneration. Bounded LRU (access-ordered {@code LinkedHashMap}) keeps memory
 * O(capacity) regardless of catalog size - the server never holds the whole
 * catalog (principle 3: scale discipline). The held payload is immutable
 * (records), so sharing it across requests is safe.
 *
 * <p>Thread-safe via a single lock; generation happens under the lock for
 * simplicity and to avoid duplicate work on concurrent misses. Under Spring Boot 4
 * virtual threads, contention here is brief (generation is a bounded, CPU-only
 * per-tile job).
 */
@Component
public class TileCache {

    /** Maximum cached tiles. Bounded so memory stays O(capacity), not O(catalog). */
    public static final int DEFAULT_CAPACITY = 4096;

    private final TileGenerator generator;
    private final ActiveSystemIndex activeSystems;
    private final int capacity;
    private final ReentrantLock lock = new ReentrantLock();
    private final Map<Key, TilePayload> entries;

    public TileCache(TileGenerator generator, ActiveSystemIndex activeSystems) {
        this(generator, activeSystems, DEFAULT_CAPACITY);
    }

    /** Visible for tests: construct with an explicit capacity. */
    public TileCache(TileGenerator generator, ActiveSystemIndex activeSystems,
                     int capacity) {
        this.generator = generator;
        this.activeSystems = activeSystems;
        this.capacity = capacity;
        this.entries = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<Key, TilePayload> eldest) {
                return size() > TileCache.this.capacity;
            }
        };
    }

    /**
     * Returns the tile for {@code (gameSeed, level, x, y)}, generating it on a miss
     * (and caching the result) or serving the cached payload on a hit. The caller
     * must have validated the address.
     */
    public TilePayload get(long gameSeed, int level, int x, int y) {
        Key key = key(gameSeed, level, x, y);
        lock.lock();
        try {
            TilePayload hit = entries.get(key);
            if (hit != null) {
                return hit;
            }
            TilePayload made = generator.generate(gameSeed, level, x, y, activeSystems);
            entries.put(key, made);
            return made;
        } finally {
            lock.unlock();
        }
    }

    /** True if the address is currently cached (visible for tests/metrics). */
    public boolean isCached(long gameSeed, int level, int x, int y) {
        lock.lock();
        try {
            // containsKey does not bump access order (only get(...) does).
            return entries.containsKey(key(gameSeed, level, x, y));
        } finally {
            lock.unlock();
        }
    }

    /** Current number of cached tiles (visible for tests). */
    public int size() {
        lock.lock();
        try {
            return entries.size();
        } finally {
            lock.unlock();
        }
    }

    private static Key key(long gameSeed, int level, int x, int y) {
        // Hilbert-ordered key: identifies the tile exactly (bijection per level)
        // while clustering spatially-adjacent tiles in key space.
        long hilbert = TileGrid.hilbertIndex(level, x, y);
        return new Key(gameSeed, level, hilbert);
    }

    /** The Hilbert-ordered cache key. */
    private record Key(long gameSeed, int level, long hilbert) {
    }
}
