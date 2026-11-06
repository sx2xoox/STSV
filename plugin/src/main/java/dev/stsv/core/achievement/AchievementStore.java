package dev.stsv.core.achievement;

import dev.stsv.core.data.Db;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Who finished which achievement and when, and whether the reward was taken (table achievement_done).
 * Online players are kept in memory; every change is written straight away on the database thread.
 */
public final class AchievementStore {

    private static final List<List<String>> SCHEMA = List.of(
            List.of("CREATE TABLE IF NOT EXISTS achievement_done (player CHAR(36) NOT NULL, id VARCHAR(128) NOT NULL, "
                    + "done_at BIGINT NOT NULL, claimed_at BIGINT, PRIMARY KEY (player, id))"),
            // where a stat stood when the achievement first applied to the player: only what comes after counts
            List.of("CREATE TABLE IF NOT EXISTS achievement_base (player CHAR(36) NOT NULL, id VARCHAR(128) NOT NULL, "
                    + "base DOUBLE NOT NULL, PRIMARY KEY (player, id))"));

    /** done_at, and claimed_at (0 = not taken yet) */
    public record Done(long doneAt, long claimedAt) {
        public boolean claimed() {
            return claimedAt > 0;
        }
    }

    private final Db db;
    private final Map<UUID, Map<String, Done>> online = new ConcurrentHashMap<>();
    /** online players: achievement -> the stat when it started counting */
    private final Map<UUID, Map<String, Double>> bases = new ConcurrentHashMap<>();
    /** achievement -> how many players have it (for "달성한 사람") */
    private final Map<String, Integer> counts = new ConcurrentHashMap<>();

    public AchievementStore(Db db) {
        this.db = db;
    }

    public void init() {
        db.migrate("achievements", SCHEMA);
        counts.clear();
        db.sync(c -> {
            for (Object[] r : Db.query(c, "SELECT id, COUNT(*) FROM achievement_done GROUP BY id", rs -> new Object[]{rs.getString(1), rs.getInt(2)})) {
                counts.put((String) r[0], (Integer) r[1]);
            }
            return null;
        });
    }

    /** Reads a player's records into memory (on join). */
    public CompletableFuture<Void> load(UUID player) {
        return db.async(c -> {
            Map<String, Done> m = new ConcurrentHashMap<>();
            for (Object[] r : Db.query(c, "SELECT id, done_at, claimed_at FROM achievement_done WHERE player = ?",
                    rs -> new Object[]{rs.getString(1), rs.getLong(2), rs.getLong(3)}, player.toString())) {
                m.put((String) r[0], new Done((Long) r[1], (Long) r[2]));
            }
            Map<String, Double> b = new ConcurrentHashMap<>();
            for (Object[] r : Db.query(c, "SELECT id, base FROM achievement_base WHERE player = ?",
                    rs -> new Object[]{rs.getString(1), rs.getDouble(2)}, player.toString())) {
                b.put((String) r[0], (Double) r[1]);
            }
            bases.put(player, b);
            online.put(player, m);
            return null;
        });
    }

    public void unload(UUID player) {
        online.remove(player);
        bases.remove(player);
    }

    /** The stat value this achievement counts from for an online player (null: not started yet). */
    public Double base(UUID player, String id) {
        Map<String, Double> b = bases.get(player);
        return b == null ? null : b.get(id);
    }

    /** Starts counting this achievement for the player from this value (once). */
    public void startBase(UUID player, String id, double value) {
        Map<String, Double> b = bases.get(player);
        if (b == null || b.putIfAbsent(id, value) != null) return;
        db.execute("INSERT INTO achievement_base (player, id, base) VALUES (?, ?, ?)", player.toString(), id, value);
    }

    public boolean loaded(UUID player) {
        return online.containsKey(player);
    }

    /** An online player's records (empty if not loaded). */
    public Map<String, Done> of(UUID player) {
        return online.getOrDefault(player, Map.of());
    }

    /** Marks it done (not yet taken). Returns false if it already was. */
    public boolean complete(UUID player, String id, long now) {
        Map<String, Done> m = online.get(player);
        if (m != null && m.containsKey(id)) return false;
        if (m != null) m.put(id, new Done(now, 0));
        counts.merge(id, 1, Integer::sum);
        db.execute("INSERT INTO achievement_done (player, id, done_at, claimed_at) VALUES (?, ?, ?, 0)", player.toString(), id, now);
        return true;
    }

    public void claimed(UUID player, String id, long now) {
        Map<String, Done> m = online.get(player);
        if (m != null) m.computeIfPresent(id, (k, d) -> new Done(d.doneAt(), now));
        db.execute("UPDATE achievement_done SET claimed_at = ? WHERE player = ? AND id = ?", now, player.toString(), id);
    }

    /** Removes the record (admin). */
    public void revoke(UUID player, String id) {
        Map<String, Done> m = online.get(player);
        if (m != null && m.remove(id) != null) counts.computeIfPresent(id, (k, n) -> n > 1 ? n - 1 : null);
        else if (m == null) counts.computeIfPresent(id, (k, n) -> n > 1 ? n - 1 : null);
        db.execute("DELETE FROM achievement_done WHERE player = ? AND id = ?", player.toString(), id);
    }

    public int count(String id) {
        return counts.getOrDefault(id, 0);
    }

    /** Testing: forgets everything. */
    public CompletableFuture<Void> reset() {
        counts.clear();
        for (Map<String, Done> m : online.values()) m.clear();
        for (Map<String, Double> m : bases.values()) m.clear();
        return db.async(c -> {
            Db.update(c, "DELETE FROM achievement_done");
            Db.update(c, "DELETE FROM achievement_base");
            return null;
        });
    }

    /** Every record of every player, with the player's last known name (for the points ranking). */
    public record Row(UUID player, String name, String id, long doneAt) {}

    public CompletableFuture<List<Row>> all() {
        return db.async(c -> Db.query(c, "SELECT a.player, p.name, a.id, a.done_at FROM achievement_done a LEFT JOIN profile p ON p.player = a.player",
                rs -> new Row(UUID.fromString(rs.getString(1)), rs.getString(2), rs.getString(3), rs.getLong(4))));
    }

    /** Offline lookup (admin info). */
    public CompletableFuture<Map<String, Done>> read(UUID player) {
        return db.async(c -> {
            Map<String, Done> m = new HashMap<>();
            for (Object[] r : Db.query(c, "SELECT id, done_at, claimed_at FROM achievement_done WHERE player = ?",
                    rs -> new Object[]{rs.getString(1), rs.getLong(2), rs.getLong(3)}, player.toString())) {
                m.put((String) r[0], new Done((Long) r[1], (Long) r[2]));
            }
            return m;
        });
    }
}
