package dev.stsv.core.profile;

import dev.stsv.core.data.Db;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Records that outlive any one content: who played, for how long, what they did, which titles they
 * earned, and the hall of fame. No Bukkit types (unit-tested on SQLite).
 *
 * Stats are counted twice: all-time (period "") and for the current content period, so a new content
 * starts from zero without losing anything. Counting is done in memory on the main thread and written
 * in batches ({@link #flush}), so a busy server never waits on the database.
 */
public final class ProfileStore {

    public static final String ALL_TIME = "";

    public record Period(String id, String name, long started, Long ended) {}

    public record Rank(int rank, UUID player, String name, double value) {}

    private static final List<List<String>> SCHEMA = List.of(List.of(
            "CREATE TABLE IF NOT EXISTS profile (player CHAR(36) NOT NULL PRIMARY KEY, name VARCHAR(16) NOT NULL, "
                    + "first_seen BIGINT NOT NULL, last_seen BIGINT NOT NULL, title VARCHAR(128))",
            "CREATE TABLE IF NOT EXISTS period (id VARCHAR(64) NOT NULL PRIMARY KEY, name VARCHAR(128) NOT NULL, "
                    + "started BIGINT NOT NULL, ended BIGINT)",
            "CREATE TABLE IF NOT EXISTS stat (player CHAR(36) NOT NULL, period VARCHAR(64) NOT NULL, stat VARCHAR(64) NOT NULL, "
                    + "value DOUBLE NOT NULL, PRIMARY KEY (player, period, stat))",
            "CREATE INDEX stat_rank ON stat (period, stat, value)",
            "CREATE TABLE IF NOT EXISTS title_owned (player CHAR(36) NOT NULL, title VARCHAR(128) NOT NULL, granted BIGINT NOT NULL, "
                    + "PRIMARY KEY (player, title))",
            "CREATE TABLE IF NOT EXISTS hall (period VARCHAR(64) NOT NULL, category VARCHAR(64) NOT NULL, rank_no INT NOT NULL, "
                    + "player CHAR(36) NOT NULL, name VARCHAR(16) NOT NULL, value DOUBLE NOT NULL, PRIMARY KEY (period, category, rank_no))"));

    private final Db db;
    private volatile Period current;
    /** player -> (period -> (stat -> delta)) not yet written */
    private final Map<UUID, Map<String, Map<String, Double>>> pending = new HashMap<>();

    public ProfileStore(Db db) {
        this.db = db;
    }

    public void init() {
        db.migrate("profiles", SCHEMA);
        current = db.sync(c -> Db.query(c, "SELECT id, name, started, ended FROM period WHERE ended IS NULL ORDER BY started DESC",
                rs -> new Period(rs.getString(1), rs.getString(2), rs.getLong(3), null)).stream().findFirst().orElse(null));
    }

    public Optional<Period> current() {
        return Optional.ofNullable(current);
    }

    // ------------------------------------------------------------------------------------------
    // Periods
    // ------------------------------------------------------------------------------------------

    /** Starts a content period. Fails if one is running or the id was used before. */
    public synchronized Period start(String id, String name, long now) {
        if (current != null) throw new IllegalStateException("이미 진행 중인 기간이 있습니다: " + current.id());
        if (id.isBlank() || id.length() > 64) throw new IllegalArgumentException("기간 id 는 1~64자");
        boolean used = db.sync(c -> !Db.query(c, "SELECT id FROM period WHERE id = ?", rs -> 1, id).isEmpty());
        if (used) throw new IllegalArgumentException("이미 쓴 기간 id 입니다: " + id);
        flush().join();   // what was counted before belongs to no period
        db.sync(c -> Db.update(c, "INSERT INTO period (id, name, started, ended) VALUES (?, ?, ?, NULL)", id, name, now));
        current = new Period(id, name, now, null);
        return current;
    }

    /** Ends the running period and writes its hall of fame: the top 'size' of every category. */
    public synchronized Map<String, List<Rank>> end(long now, List<String> categories, int size) {
        Period p = current;
        if (p == null) throw new IllegalStateException("진행 중인 기간이 없습니다");
        flush().join();
        Map<String, List<Rank>> hall = new LinkedHashMap<>();
        db.sync(c -> {
            for (String cat : categories) {
                List<Rank> top = top(c, p.id(), cat, size);
                for (Rank r : top) {
                    Db.update(c, "INSERT INTO hall (period, category, rank_no, player, name, value) VALUES (?, ?, ?, ?, ?, ?)",
                            p.id(), cat, r.rank(), r.player().toString(), r.name(), r.value());
                }
                hall.put(cat, top);
            }
            Db.update(c, "UPDATE period SET ended = ? WHERE id = ?", now, p.id());
            return null;
        });
        current = null;
        return hall;
    }

    public CompletableFuture<List<Period>> periods() {
        return db.async(c -> Db.query(c, "SELECT id, name, started, ended FROM period ORDER BY started DESC",
                rs -> new Period(rs.getString(1), rs.getString(2), rs.getLong(3), rs.getObject(4) == null ? null : rs.getLong(4))));
    }

    public CompletableFuture<Map<String, List<Rank>>> hall(String period) {
        return db.async(c -> {
            Map<String, List<Rank>> out = new LinkedHashMap<>();
            for (Object[] r : Db.query(c, "SELECT category, rank_no, player, name, value FROM hall WHERE period = ? ORDER BY category, rank_no",
                    rs -> new Object[]{rs.getString(1), rs.getInt(2), rs.getString(3), rs.getString(4), rs.getDouble(5)}, period)) {
                out.computeIfAbsent((String) r[0], k -> new ArrayList<>())
                        .add(new Rank((int) r[1], UUID.fromString((String) r[2]), (String) r[3], (double) r[4]));
            }
            return out;
        });
    }

    // ------------------------------------------------------------------------------------------
    // Stats
    // ------------------------------------------------------------------------------------------

    /**
     * Testing: forgets every record (stats, periods, hall of fame, titles). Player names stay so
     * commands can still find offline players.
     */
    public synchronized CompletableFuture<Void> reset() {
        pending.clear();
        current = null;
        return db.async(c -> {
            for (String table : List.of("stat", "period", "hall", "title_owned")) Db.update(c, "DELETE FROM " + table);
            Db.update(c, "UPDATE profile SET title = NULL");
            return null;
        });
    }

    /** Counts something (main thread). Goes to all-time and to the running period. */
    public synchronized void add(UUID player, String stat, double delta) {
        if (delta == 0) return;
        Map<String, Map<String, Double>> byPeriod = pending.computeIfAbsent(player, k -> new HashMap<>());
        byPeriod.computeIfAbsent(ALL_TIME, k -> new HashMap<>()).merge(stat, delta, Double::sum);
        Period p = current;
        if (p != null) byPeriod.computeIfAbsent(p.id(), k -> new HashMap<>()).merge(stat, delta, Double::sum);
    }

    /** Writes everything counted so far. */
    public CompletableFuture<Void> flush() {
        Map<UUID, Map<String, Map<String, Double>>> batch;
        synchronized (this) {
            if (pending.isEmpty()) return CompletableFuture.completedFuture(null);
            batch = new HashMap<>(pending);
            pending.clear();
        }
        String sql = db.increment("stat", List.of("player", "period", "stat"), "value");
        return db.async(c -> {
            write(c, sql, batch);
            return null;
        });
    }

    private static void write(java.sql.Connection c, String sql, Map<UUID, Map<String, Map<String, Double>>> batch) throws java.sql.SQLException {
        boolean auto = c.getAutoCommit();
        c.setAutoCommit(false);
        try {
            for (var p : batch.entrySet()) {
                for (var per : p.getValue().entrySet()) {
                    for (var st : per.getValue().entrySet()) Db.update(c, sql, p.getKey().toString(), per.getKey(), st.getKey(), st.getValue());
                }
            }
            c.commit();
        } catch (java.sql.SQLException e) {
            c.rollback();
            throw e;
        } finally {
            c.setAutoCommit(auto);
        }
    }

    /** A player's stats for a period (ALL_TIME or a period id), including what is not written yet. */
    public CompletableFuture<Map<String, Double>> stats(UUID player, String period) {
        return flush().thenCompose(v -> db.async(c -> {
            Map<String, Double> out = new LinkedHashMap<>();
            for (Object[] r : Db.query(c, "SELECT stat, value FROM stat WHERE player = ? AND period = ? ORDER BY stat",
                    rs -> new Object[]{rs.getString(1), rs.getDouble(2)}, player.toString(), period)) {
                out.put((String) r[0], (double) r[1]);
            }
            return out;
        }));
    }

    public CompletableFuture<List<Rank>> top(String period, String stat, int size) {
        return flush().thenCompose(v -> db.async(c -> top(c, period, stat, size)));
    }

    private static List<Rank> top(java.sql.Connection c, String period, String stat, int size) throws java.sql.SQLException {
        List<Rank> out = new ArrayList<>();
        int rank = 1;
        for (Object[] r : Db.query(c, "SELECT s.player, COALESCE(p.name, '?'), s.value FROM stat s LEFT JOIN profile p ON p.player = s.player "
                        + "WHERE s.period = ? AND s.stat = ? AND s.value > 0 ORDER BY s.value DESC LIMIT " + Math.max(1, size),
                rs -> new Object[]{rs.getString(1), rs.getString(2), rs.getDouble(3)}, period, stat)) {
            out.add(new Rank(rank++, UUID.fromString((String) r[0]), (String) r[1], (double) r[2]));
        }
        return out;
    }

    // ------------------------------------------------------------------------------------------
    // Profile and titles
    // ------------------------------------------------------------------------------------------

    public void seen(UUID player, String name, long now) {
        db.execute("INSERT INTO profile (player, name, first_seen, last_seen) VALUES (?, ?, ?, ?) "
                + (db.dialect() == Db.Dialect.MYSQL ? "ON DUPLICATE KEY UPDATE name = VALUES(name), last_seen = VALUES(last_seen)"
                : "ON CONFLICT(player) DO UPDATE SET name = excluded.name, last_seen = excluded.last_seen"), player.toString(), name, now, now);
    }

    public record Titles(Set<String> owned, String equipped) {}

    public CompletableFuture<Titles> titles(UUID player) {
        return db.async(c -> {
            Set<String> owned = new HashSet<>(Db.query(c, "SELECT title FROM title_owned WHERE player = ?", rs -> rs.getString(1), player.toString()));
            List<String> eq = Db.query(c, "SELECT title FROM profile WHERE player = ?", rs -> rs.getString(1), player.toString());
            return new Titles(owned, eq.isEmpty() ? null : eq.getFirst());
        });
    }

    public CompletableFuture<Void> grantTitle(UUID player, String title, long now) {
        return db.execute(db.upsert("title_owned", List.of("player", "title"), List.of("granted")), player.toString(), title, now);
    }

    public CompletableFuture<Void> revokeTitle(UUID player, String title) {
        return db.async(c -> {
            Db.update(c, "DELETE FROM title_owned WHERE player = ? AND title = ?", player.toString(), title);
            Db.update(c, "UPDATE profile SET title = NULL WHERE player = ? AND title = ?", player.toString(), title);
            return null;
        });
    }

    public CompletableFuture<Void> equipTitle(UUID player, String title) {
        return db.execute("UPDATE profile SET title = ? WHERE player = ?", title, player.toString());
    }

    public CompletableFuture<Optional<UUID>> findByName(String name) {
        return db.async(c -> Db.query(c, "SELECT player FROM profile WHERE LOWER(name) = LOWER(?) ORDER BY last_seen DESC",
                rs -> UUID.fromString(rs.getString(1)), name).stream().findFirst());
    }
}
