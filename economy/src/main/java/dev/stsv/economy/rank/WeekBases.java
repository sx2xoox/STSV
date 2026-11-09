package dev.stsv.economy.rank;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Where each player's week started (their net worth then): this week's earnings are worth now minus that start.
 * Money handed between players shifts the start with it ({@link #shift}), so it is nobody's earnings or loss. Pure
 * (unit-tested); the rank module keeps it in the database.
 */
public final class WeekBases {

    private final Map<UUID, Long> start = new ConcurrentHashMap<>();

    public Long get(UUID player) {
        return start.get(player);
    }

    public void put(UUID player, long worth) {
        start.put(player, worth);
    }

    public void clear() {
        start.clear();
    }

    /** The player's start: kept, or (new this week) the worth now, which then is the start; created[0] says which. */
    public long startOf(UUID player, long worthNow, boolean[] created) {
        Long b = start.get(player);
        if (b != null) return b;
        start.put(player, worthNow);
        if (created != null && created.length > 0) created[0] = true;
        return worthNow;
    }

    /**
     * Money from another player (delta > 0) or to one (delta < 0): the start moves by it. Returns the new start, or null
     * when the player has none yet (it is taken later from a worth that already has the money in it).
     */
    public Long shift(UUID player, long delta) {
        Long b = start.get(player);
        if (b == null || delta == 0) return b;
        long moved = b + delta;
        start.put(player, moved);
        return moved;
    }

    public long earned(UUID player, long worthNow) {
        return worthNow - startOf(player, worthNow, null);
    }
}
