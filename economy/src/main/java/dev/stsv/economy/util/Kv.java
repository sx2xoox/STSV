package dev.stsv.economy.util;

import dev.stsv.core.data.Db;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Small persistent settings and markers ("calendar.epoch", "shop.notice", ...), cached in memory. */
public final class Kv {

    private final Db db;
    private final Map<String, String> cache = new ConcurrentHashMap<>();

    public Kv(Db db) {
        this.db = db;
    }

    public void init() {
        db.migrate("eco_state", List.of(List.of(
                "CREATE TABLE IF NOT EXISTS eco_state (k VARCHAR(191) NOT NULL PRIMARY KEY, v TEXT NOT NULL)")));
        cache.clear();
        for (String[] r : db.sync(c -> Db.query(c, "SELECT k, v FROM eco_state", rs -> new String[]{rs.getString(1), rs.getString(2)}))) {
            cache.put(r[0], r[1]);
        }
    }

    public String get(String k) {
        return cache.get(k);
    }

    public String get(String k, String def) {
        return cache.getOrDefault(k, def);
    }

    public long getLong(String k, long def) {
        try {
            String v = cache.get(k);
            return v == null ? def : Long.parseLong(v);
        } catch (NumberFormatException e) {
            return def;
        }
    }

    public boolean has(String k) {
        return cache.containsKey(k);
    }

    public void set(String k, Object v) {
        String s = String.valueOf(v);
        cache.put(k, s);
        db.execute(db.upsert("eco_state", List.of("k"), List.of("v")), k, s);
    }

    public void remove(String k) {
        cache.remove(k);
        db.execute("DELETE FROM eco_state WHERE k = ?", k);
    }

    /** Removes every key starting with a prefix (e.g. "calendar." on a reset). */
    public void removePrefix(String prefix) {
        cache.keySet().removeIf(k -> k.startsWith(prefix));
        db.execute("DELETE FROM eco_state WHERE k LIKE ?", prefix.replace("%", "") + "%");
    }

    public Map<String, String> all() {
        return Map.copyOf(cache);
    }
}
