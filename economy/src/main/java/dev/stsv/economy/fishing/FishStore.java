package dev.stsv.economy.fishing;

import dev.stsv.core.data.Db;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** The codex: every player's catches per fish (how many, the biggest, when first). All in memory. */
public final class FishStore {

    public static final class Entry {
        public int count;
        public double best;
        public long first;
    }

    private final Db db;
    private final Map<UUID, Map<String, Entry>> log = new ConcurrentHashMap<>();

    public FishStore(Db db) {
        this.db = db;
    }

    public void init() {
        db.migrate("eco_fish", List.of(List.of(
                "CREATE TABLE IF NOT EXISTS eco_fish_log (player CHAR(36) NOT NULL, species VARCHAR(64) NOT NULL, count INT NOT NULL, "
                        + "best DOUBLE NOT NULL, first_at BIGINT NOT NULL, PRIMARY KEY (player, species))")));
        log.clear();
        for (Object[] r : db.sync(c -> Db.query(c, "SELECT player, species, count, best, first_at FROM eco_fish_log",
                rs -> new Object[]{rs.getString(1), rs.getString(2), rs.getInt(3), rs.getDouble(4), rs.getLong(5)}))) {
            Entry e = new Entry();
            e.count = (Integer) r[2];
            e.best = (Double) r[3];
            e.first = (Long) r[4];
            log.computeIfAbsent(UUID.fromString((String) r[0]), k -> new ConcurrentHashMap<>()).put((String) r[1], e);
        }
    }

    /** Everyone with at least one catch. */
    public java.util.Set<UUID> players() {
        return java.util.Set.copyOf(log.keySet());
    }

    public Map<String, Entry> of(UUID player) {
        return log.getOrDefault(player, Map.of());
    }

    /** Records a catch; returns true if it is the first of its kind for that player. */
    public boolean record(UUID player, String species, double size, long now) {
        Map<String, Entry> m = log.computeIfAbsent(player, k -> new ConcurrentHashMap<>());
        Entry e = m.get(species);
        boolean first = e == null;
        if (first) {
            e = new Entry();
            e.first = now;
            m.put(species, e);
        }
        e.count++;
        e.best = Math.max(e.best, size);
        db.execute(db.upsert("eco_fish_log", List.of("player", "species"), List.of("count", "best", "first_at")),
                player.toString(), species, e.count, e.best, e.first);
        return first;
    }

    public void reset() {
        log.clear();
        db.execute("DELETE FROM eco_fish_log");
    }
}
