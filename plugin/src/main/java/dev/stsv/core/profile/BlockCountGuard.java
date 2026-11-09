package dev.stsv.core.profile;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Which block placements and breaks count for blocks_placed / blocks_broken (records, achievements, missions), so
 * that placing and breaking a torch on one spot over and over counts nothing: breaking a block a player placed does
 * not count, and placing on a spot where a block was broken in the last few minutes does not count either. Mining
 * what the world made and building on new spots counts as before. Remembers the latest spots only (the oldest are
 * forgotten past the capacity; nothing is kept over a restart). Pure (unit-tested); positions are any key.
 */
public final class BlockCountGuard {

    private final long reuseMs;
    private final Map<Object, Boolean> placed;
    private final Map<Object, Long> broken;

    public BlockCountGuard(int capacity, long reuseMs) {
        this.reuseMs = reuseMs;
        this.placed = lru(capacity);
        this.broken = lru(capacity);
    }

    private static <V> Map<Object, V> lru(int capacity) {
        return new LinkedHashMap<>(256, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<Object, V> eldest) {
                return size() > capacity;
            }
        };
    }

    /** A player placed a block at pos: does it count? */
    public boolean place(Object pos, long now) {
        placed.put(pos, Boolean.TRUE);
        Long b = broken.get(pos);
        return b == null || now - b >= reuseMs;
    }

    /** A player broke the block at pos: does it count? (not when a player placed it) */
    public boolean breakBlock(Object pos, long now) {
        broken.put(pos, now);
        return placed.remove(pos) == null;
    }
}
