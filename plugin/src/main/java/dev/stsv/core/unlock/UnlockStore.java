package dev.stsv.core.unlock;

import dev.stsv.core.data.Db;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * What each player has unlocked for good: a title banner, an emote, a tool skin ... one row a thing, by its kind
 * ("banner") and id ("fire"). Bought in the donation shop or given by an admin. No Bukkit types (unit-tested on SQLite).
 */
public final class UnlockStore {

    private static final List<List<String>> SCHEMA = List.of(List.of(
            "CREATE TABLE IF NOT EXISTS stsv_unlock (player CHAR(36) NOT NULL, kind VARCHAR(24) NOT NULL, id VARCHAR(64) NOT NULL, "
                    + "since BIGINT NOT NULL, source VARCHAR(64), PRIMARY KEY (player, kind, id))"));

    private final Db db;

    public UnlockStore(Db db) {
        this.db = db;
    }

    public void init() {
        db.migrate("unlocks", SCHEMA);
    }

    /** Everything a player has, by kind. */
    public CompletableFuture<Map<String, Set<String>>> owned(UUID player) {
        return db.async(c -> {
            Map<String, Set<String>> out = new HashMap<>();
            for (String[] r : Db.query(c, "SELECT kind, id FROM stsv_unlock WHERE player = ?", rs -> new String[]{rs.getString(1), rs.getString(2)},
                    player.toString())) {
                out.computeIfAbsent(r[0], k -> new TreeSet<>()).add(r[1]);
            }
            return out;
        });
    }

    /** Gives one thing; false when the player had it already. */
    public CompletableFuture<Boolean> grant(UUID player, String kind, String id, String source, long now) {
        return db.async(c -> {
            if (!Db.query(c, "SELECT 1 FROM stsv_unlock WHERE player = ? AND kind = ? AND id = ?", rs -> 1, player.toString(), kind, id).isEmpty()) return false;
            Db.update(c, "INSERT INTO stsv_unlock (player, kind, id, since, source) VALUES (?, ?, ?, ?, ?)", player.toString(), kind, id, now, source);
            return true;
        });
    }

    /** Takes one thing back; false when the player did not have it. */
    public CompletableFuture<Boolean> revoke(UUID player, String kind, String id) {
        return db.async(c -> Db.update(c, "DELETE FROM stsv_unlock WHERE player = ? AND kind = ? AND id = ?", player.toString(), kind, id) > 0);
    }

    /** How many players have each thing of a kind (the admin's overview). */
    public CompletableFuture<Map<String, Integer>> counts(String kind) {
        return db.async(c -> {
            Map<String, Integer> out = new java.util.TreeMap<>();
            for (Object[] r : Db.query(c, "SELECT id, COUNT(*) FROM stsv_unlock WHERE kind = ? GROUP BY id", rs -> new Object[]{rs.getString(1), rs.getInt(2)}, kind)) {
                out.put((String) r[0], (Integer) r[1]);
            }
            return out;
        });
    }
}
