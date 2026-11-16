package dev.stsv.economy.land.log;

import dev.stsv.core.data.Db;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * The land log's tables: every change someone other than the owner made inside a land (stsv_land_log), the money
 * returned by /관리자 테러 (stsv_grief_return: one row per ledger payment, so nothing is returned twice) and what
 * /관리자 테러 did (stsv_grief_audit).
 */
public final class LandLogStore {

    /** What a line is. */
    public enum Action {
        /** a block broken (before = the block, item = a container's contents) */
        BREAK,
        /** a block placed (also a bucket poured, fire lit: before = what was there) */
        PLACE,
        /** items taken out of a container (item = one of them, amount) */
        TAKE,
        /** items put into a container */
        PUT,
        /** a frame, painting, armor stand or animal destroyed (item = the entity, serialized; before = its type) */
        ENTITY_BREAK,
        /** a frame, painting or armor stand put up (after = its uuid) */
        ENTITY_PLACE,
        /** an item taken from a frame or an armor stand (item = the item) */
        ENTITY_TAKE,
        /** our furniture broken (item = its item; before = its id) */
        FURNITURE_BREAK,
        /** our furniture placed (before = its id) */
        FURNITURE_PLACE
    }

    /** One line. owner: the land's owner then (null: the server's land). */
    public record Row(long id, long at, long land, UUID owner, String world, int x, int y, int z, UUID actor, String actorName, Action action,
                      String before, String after, byte[] item, int amount) {}

    /** Per person (and action) in a land: how many, first and last time. */
    public record Tally(UUID actor, String name, Action action, long count, long first, long last) {}

    private final Db db;

    public LandLogStore(Db db) {
        this.db = db;
    }

    public void init() {
        boolean mysql = db.dialect() == Db.Dialect.MYSQL;
        String id = mysql ? "id BIGINT PRIMARY KEY AUTO_INCREMENT" : "id INTEGER PRIMARY KEY AUTOINCREMENT";
        String blob = mysql ? "MEDIUMBLOB" : "BLOB";
        db.migrate("land_log", List.of(List.of(
                "CREATE TABLE IF NOT EXISTS stsv_land_log (" + id + ", at BIGINT NOT NULL, land BIGINT NOT NULL, owner CHAR(36), world VARCHAR(64) NOT NULL, "
                        + "x INT NOT NULL, y INT NOT NULL, z INT NOT NULL, actor CHAR(36) NOT NULL, actor_name VARCHAR(16) NOT NULL, action VARCHAR(16) NOT NULL, "
                        + "before_data TEXT, after_data TEXT, item " + blob + ", amount INT NOT NULL, undone INT NOT NULL DEFAULT 0)",
                "CREATE INDEX stsv_land_log_land ON stsv_land_log (land, at)",
                "CREATE INDEX stsv_land_log_actor ON stsv_land_log (actor, at)",
                "CREATE INDEX stsv_land_log_at ON stsv_land_log (at)",
                "CREATE TABLE IF NOT EXISTS stsv_grief_return (entry BIGINT PRIMARY KEY, at BIGINT NOT NULL, target CHAR(36) NOT NULL, sender CHAR(36) NOT NULL, "
                        + "amount BIGINT NOT NULL, returned BIGINT NOT NULL)",
                "CREATE TABLE IF NOT EXISTS stsv_grief_audit (" + id + ", at BIGINT NOT NULL, admin VARCHAR(32) NOT NULL, target CHAR(36) NOT NULL, "
                        + "target_name VARCHAR(16) NOT NULL, since BIGINT NOT NULL, summary TEXT NOT NULL)")));
    }

    private static final String COLS = "id, at, land, owner, world, x, y, z, actor, actor_name, action, before_data, after_data, item, amount";

    private static Row row(java.sql.ResultSet rs) throws SQLException {
        String owner = rs.getString(4);
        return new Row(rs.getLong(1), rs.getLong(2), rs.getLong(3), owner == null ? null : UUID.fromString(owner), rs.getString(5), rs.getInt(6), rs.getInt(7),
                rs.getInt(8), UUID.fromString(rs.getString(9)), rs.getString(10), Action.valueOf(rs.getString(11)), rs.getString(12), rs.getString(13),
                rs.getBytes(14), rs.getInt(15));
    }

    /** Writes a batch of lines in one transaction (the log's flush, off the main thread). */
    public CompletableFuture<Void> write(List<Row> rows) {
        if (rows.isEmpty()) return CompletableFuture.completedFuture(null);
        return db.async(c -> {
            boolean auto = c.getAutoCommit();
            c.setAutoCommit(false);
            try (var ps = c.prepareStatement("INSERT INTO stsv_land_log (at, land, owner, world, x, y, z, actor, actor_name, action, before_data, after_data, item, amount) "
                    + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
                for (Row r : rows) {
                    ps.setLong(1, r.at());
                    ps.setLong(2, r.land());
                    ps.setString(3, r.owner() == null ? null : r.owner().toString());
                    ps.setString(4, r.world());
                    ps.setInt(5, r.x());
                    ps.setInt(6, r.y());
                    ps.setInt(7, r.z());
                    ps.setString(8, r.actor().toString());
                    ps.setString(9, cut(r.actorName(), 16));
                    ps.setString(10, r.action().name());
                    ps.setString(11, r.before());
                    ps.setString(12, r.after());
                    ps.setBytes(13, r.item());
                    ps.setInt(14, r.amount());
                    ps.addBatch();
                }
                ps.executeBatch();
                c.commit();
            } catch (SQLException | RuntimeException e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(auto);
            }
            return null;
        });
    }

    private static String cut(String s, int n) {
        return s == null ? "?" : s.length() <= n ? s : s.substring(0, n);
    }

    /** Who did what in a land since a time (not yet undone), per person and action. */
    public CompletableFuture<List<Tally>> tally(long land, long since) {
        return db.async(c -> Db.query(c, "SELECT actor, MAX(actor_name), action, SUM(CASE WHEN amount > 0 THEN amount ELSE 1 END), MIN(at), MAX(at) "
                        + "FROM stsv_land_log WHERE land = ? AND at >= ? AND undone = 0 GROUP BY actor, action",
                rs -> new Tally(UUID.fromString(rs.getString(1)), rs.getString(2), Action.valueOf(rs.getString(3)), rs.getLong(4), rs.getLong(5), rs.getLong(6)),
                land, since));
    }

    /** One person's lines (land < 0: everywhere), newest first. */
    public CompletableFuture<List<Row>> recent(UUID actor, long land, long since, int offset, int limit) {
        return db.async(c -> Db.query(c, "SELECT " + COLS + " FROM stsv_land_log WHERE actor = ? AND at >= ? AND undone = 0" + (land >= 0 ? " AND land = ?" : "")
                        + " ORDER BY id DESC LIMIT " + Math.clamp(limit, 1, 500) + " OFFSET " + Math.max(0, offset),
                LandLogStore::row, land >= 0 ? new Object[]{actor.toString(), since, land} : new Object[]{actor.toString(), since}));
    }

    /** All of one person's lines to undo (land < 0: everywhere), oldest first. */
    public CompletableFuture<List<Row>> toUndo(UUID actor, long land, long since) {
        return db.async(c -> Db.query(c, "SELECT " + COLS + " FROM stsv_land_log WHERE actor = ? AND at >= ? AND undone = 0" + (land >= 0 ? " AND land = ?" : "")
                + " ORDER BY id", LandLogStore::row, land >= 0 ? new Object[]{actor.toString(), since, land} : new Object[]{actor.toString(), since}));
    }

    /** Marks lines handled by an undo (so a second undo does not do them again). */
    public CompletableFuture<Void> undone(List<Long> ids) {
        if (ids.isEmpty()) return CompletableFuture.completedFuture(null);
        return db.async(c -> {
            for (int i = 0; i < ids.size(); i += 500) {
                List<Long> part = ids.subList(i, Math.min(ids.size(), i + 500));
                Db.update(c, "UPDATE stsv_land_log SET undone = 1 WHERE id IN (" + "?,".repeat(part.size() - 1) + "?)", part.toArray());
            }
            return null;
        });
    }

    /** Per land: one person's line counts since a time (everywhere): land id, owner, count. */
    public CompletableFuture<List<Object[]>> byLand(UUID actor, long since) {
        return db.async(c -> Db.query(c, "SELECT land, MAX(owner), action, SUM(CASE WHEN amount > 0 THEN amount ELSE 1 END) FROM stsv_land_log "
                        + "WHERE actor = ? AND at >= ? AND undone = 0 GROUP BY land, action",
                rs -> new Object[]{rs.getLong(1), rs.getString(2), Action.valueOf(rs.getString(3)), rs.getLong(4)}, actor.toString(), since));
    }

    /** Lines older than this go (the retention). */
    public CompletableFuture<Integer> prune(long before) {
        return db.async(c -> Db.update(c, "DELETE FROM stsv_land_log WHERE at < ?", before));
    }

    // ------------------------------------------------------------------------------------------
    // Money (/관리자 테러)
    // ------------------------------------------------------------------------------------------

    /**
     * Payments other players made to this player since a time (the core's ledger: player to player, not undone, not
     * returned by /관리자 테러 before).
     */
    public CompletableFuture<List<MoneyReturn.Payment>> payments(UUID target, long since) {
        return db.async(c -> Db.query(c, "SELECT id, at, from_player, amount, reason FROM ledger WHERE to_player = ? AND from_player IS NOT NULL AND from_player <> ? "
                        + "AND kind = 'TRANSFER' AND reverted = 0 AND at >= ? AND id NOT IN (SELECT entry FROM stsv_grief_return) ORDER BY id",
                rs -> new MoneyReturn.Payment(rs.getLong(1), rs.getLong(2), UUID.fromString(rs.getString(3)), rs.getLong(4), rs.getString(5)),
                target.toString(), target.toString(), since));
    }

    public CompletableFuture<Void> returned(List<MoneyReturn.Back> backs, UUID target, long now) {
        return db.async(c -> {
            for (MoneyReturn.Back b : backs) {
                Db.update(c, "INSERT INTO stsv_grief_return (entry, at, target, sender, amount, returned) VALUES (?, ?, ?, ?, ?, ?)",
                        b.payment().entry(), now, target.toString(), b.payment().from().toString(), b.payment().amount(), b.returned());
            }
            return null;
        });
    }

    public CompletableFuture<Void> audit(String admin, UUID target, String name, long since, String summary, long now) {
        return db.async(c -> {
            Db.update(c, "INSERT INTO stsv_grief_audit (at, admin, target, target_name, since, summary) VALUES (?, ?, ?, ?, ?, ?)",
                    now, cut(admin, 32), target.toString(), cut(name, 16), since, summary);
            return null;
        });
    }

    /** For tests: everything, oldest first. */
    List<Row> all(Connection c) throws SQLException {
        return new ArrayList<>(Db.query(c, "SELECT " + COLS + " FROM stsv_land_log ORDER BY id", LandLogStore::row));
    }

    public Db db() {
        return db;
    }
}
