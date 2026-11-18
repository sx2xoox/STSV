package dev.stsv.core.minigame.circuit;

import dev.stsv.core.data.Db;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * 서킷 ghosts: every player's fastest lap on every track as a {@link Ghost} (DB stsv_circuit_ghosts, one row a track
 * and player: the lap, the kart and colour, the encoded path). The track record's ghost is the fastest row of the track.
 * What each row is (no path) is kept in memory, read once when the plugin starts; a path is read when a race needs it
 * (a few recent ones stay decoded). At most maxPerTrack rows a track: past that the slowest go (never the fastest).
 * No Bukkit types (unit-tested on SQLite).
 */
public final class GhostStore {

    /** One stored ghost, without its path. */
    public record Meta(String track, UUID player, String name, long lapMs, String kart, String colour, int bytes, long updated) {}

    private static final int CACHED = 12;

    private final Db db;
    private final Map<String, Map<UUID, Meta>> metas = new HashMap<>();
    private final Map<String, Ghost> cache = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Ghost> eldest) {
            return size() > CACHED;
        }
    };

    public GhostStore(Db db) {
        this.db = db;
    }

    /** Creates the table and reads what every ghost is (blocking: only while the plugin starts). */
    public void init() {
        String blob = db.dialect() == Db.Dialect.MYSQL ? "MEDIUMBLOB" : "BLOB";
        db.migrate("circuit_ghosts", List.of(List.of(
                "CREATE TABLE IF NOT EXISTS stsv_circuit_ghosts (track VARCHAR(32) NOT NULL, player CHAR(36) NOT NULL, name VARCHAR(32) NOT NULL, "
                        + "lap_ms BIGINT NOT NULL, kart VARCHAR(32) NOT NULL, colour VARCHAR(16) NOT NULL, data " + blob + " NOT NULL, "
                        + "updated BIGINT NOT NULL, PRIMARY KEY (track, player))")));
        String len = db.dialect() == Db.Dialect.MYSQL ? "LENGTH(data)" : "length(data)";
        List<Meta> all = db.sync(c -> Db.query(c, "SELECT track, player, name, lap_ms, kart, colour, " + len + ", updated FROM stsv_circuit_ghosts",
                rs -> new Meta(rs.getString(1), UUID.fromString(rs.getString(2)), rs.getString(3), rs.getLong(4), rs.getString(5), rs.getString(6),
                        rs.getInt(7), rs.getLong(8))));
        synchronized (this) {
            metas.clear();
            cache.clear();
            for (Meta m : all) metas.computeIfAbsent(m.track(), k -> new HashMap<>()).put(m.player(), m);
        }
    }

    /** The player's ghost on the track, or null. */
    public synchronized Meta mine(String track, UUID player) {
        return metas.getOrDefault(track, Map.of()).get(player);
    }

    /** The track's fastest ghost (the track record's, as far as ghosts go), or null. */
    public synchronized Meta fastest(String track) {
        Meta best = null;
        for (Meta m : metas.getOrDefault(track, Map.of()).values())
            if (best == null || m.lapMs() < best.lapMs() || (m.lapMs() == best.lapMs() && m.updated() < best.updated())) best = m;
        return best;
    }

    public synchronized int count(String track) {
        return metas.getOrDefault(track, Map.of()).size();
    }

    private static String key(String track, UUID player) {
        return track + "/" + player;
    }

    /** A ghost's path (read on the database thread unless it was read lately); completes with null when gone or unreadable. */
    public CompletableFuture<Ghost> load(String track, UUID player) {
        Meta m;
        synchronized (this) {
            Ghost g = cache.get(key(track, player));
            m = mine(track, player);
            if (g != null && m != null && g.lapMs() == m.lapMs()) return CompletableFuture.completedFuture(g);
        }
        if (m == null) return CompletableFuture.completedFuture(null);
        return db.async(c -> {
            List<byte[]> rows = Db.query(c, "SELECT data FROM stsv_circuit_ghosts WHERE track = ? AND player = ?", rs -> rs.getBytes(1), track, player.toString());
            if (rows.isEmpty()) return null;
            try {
                Ghost g = Ghost.decode(rows.getFirst());
                synchronized (this) {
                    cache.put(key(track, player), g);
                }
                return g;
            } catch (IllegalArgumentException e) {
                return null;
            }
        });
    }

    /**
     * A lap driven: kept when it is the player's fastest ghost on the track (memory at once, the database after; the
     * slowest of the track go when there are more than maxPerTrack). Returns true when it was kept.
     */
    public boolean offer(String track, UUID player, String name, String kart, String colour, Ghost ghost, long now, int maxPerTrack) {
        byte[] data;
        try {
            data = ghost.encode();
        } catch (IllegalStateException e) {
            return false;
        }
        Meta row;
        List<Meta> drop = new ArrayList<>();
        synchronized (this) {
            Meta old = mine(track, player);
            if (old != null && old.lapMs() <= ghost.lapMs()) return false;
            row = new Meta(track, player, name, ghost.lapMs(), kart, colour, data.length, now);
            Map<UUID, Meta> t = metas.computeIfAbsent(track, k -> new HashMap<>());
            t.put(player, row);
            cache.put(key(track, player), ghost);
            int max = Math.max(1, maxPerTrack);
            if (t.size() > max) {
                List<Meta> order = new ArrayList<>(t.values());
                order.sort(Comparator.comparingLong(Meta::lapMs).thenComparingLong(Meta::updated));
                for (Meta m : order.subList(max, order.size())) {
                    if (m.player().equals(player)) continue;     // never the lap just driven
                    drop.add(m);
                }
                for (Meta m : drop) {
                    t.remove(m.player());
                    cache.remove(key(track, m.player()));
                }
            }
        }
        db.async(c -> Db.update(c, db.upsert("stsv_circuit_ghosts", List.of("track", "player"), List.of("name", "lap_ms", "kart", "colour", "data", "updated")),
                row.track(), row.player().toString(), row.name(), row.lapMs(), row.kart(), row.colour(), data, row.updated()));
        for (Meta m : drop) db.async(c -> Db.update(c, "DELETE FROM stsv_circuit_ghosts WHERE track = ? AND player = ?", m.track(), m.player().toString()));
        return true;
    }

    /** Wipes ghosts: of a track (null = every track), of a player (null = everyone). Returns how many went. */
    public synchronized CompletableFuture<Integer> reset(String track, UUID player) {
        int n = 0;
        for (Map.Entry<String, Map<UUID, Meta>> e : metas.entrySet()) {
            if (track != null && !e.getKey().equals(track)) continue;
            if (player == null) {
                n += e.getValue().size();
                e.getValue().clear();
            } else if (e.getValue().remove(player) != null) n++;
        }
        cache.clear();
        StringBuilder sql = new StringBuilder("DELETE FROM stsv_circuit_ghosts WHERE 1 = 1");
        List<Object> args = new ArrayList<>();
        if (track != null) {
            sql.append(" AND track = ?");
            args.add(track);
        }
        if (player != null) {
            sql.append(" AND player = ?");
            args.add(player.toString());
        }
        int count = n;
        return db.async(c -> Db.update(c, sql.toString(), args.toArray())).thenApply(x -> count);
    }
}
