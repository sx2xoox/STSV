package dev.stsv.core.vehicle;

import dev.stsv.core.data.Db;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Every 지게's load, one row each (no Bukkit types: unit-tested on SQLite). The item never carries what is on it: it
 * carries its row's id and a <b>token</b>, and the load stays in the row whatever happens to the item.
 *
 * <p>Copies: putting a 지게 on {@link #claim claims} it: one conditional update that needs the item's token and gives
 * it a new one. Of two copies of one item only the one put on first gets through; every other copy (and the item it was
 * claimed from, if it was copied meanwhile) holds an old token and opens nothing. Saving the load needs the current
 * token too ({@link #save}), so a stale wearer can never write over it.
 */
public final class JigeStore {

    public record Row(long id, UUID owner, String ownerName, long token, byte[] contents, long created, long updated) {}

    private final Db db;

    public JigeStore(Db db) {
        this.db = db;
    }

    public void init() {
        String id = db.dialect() == Db.Dialect.MYSQL ? "id BIGINT PRIMARY KEY AUTO_INCREMENT" : "id INTEGER PRIMARY KEY AUTOINCREMENT";
        String blob = db.dialect() == Db.Dialect.MYSQL ? "MEDIUMBLOB" : "BLOB";
        db.migrate("jige", List.of(List.of(
                "CREATE TABLE IF NOT EXISTS stsv_jige (" + id + ", owner CHAR(36) NOT NULL, owner_name VARCHAR(16) NOT NULL, "
                        + "token BIGINT NOT NULL, contents " + blob + ", created BIGINT NOT NULL, updated BIGINT NOT NULL)")));
    }

    private static final String COLS = "id, owner, owner_name, token, contents, created, updated";

    private static Row row(ResultSet rs) throws SQLException {
        return new Row(rs.getLong(1), UUID.fromString(rs.getString(2)), rs.getString(3), rs.getLong(4), rs.getBytes(5), rs.getLong(6), rs.getLong(7));
    }

    private static Row get(java.sql.Connection c, long id) throws SQLException {
        List<Row> r = Db.query(c, "SELECT " + COLS + " FROM stsv_jige WHERE id = ?", JigeStore::row, id);
        return r.isEmpty() ? null : r.getFirst();
    }

    /** A new, empty 지게 (an admin-made item put on for the first time), already claimed with this token. */
    public CompletableFuture<Row> create(UUID owner, String ownerName, long token, long now) {
        return db.async(c -> get(c, Db.insert(c, "INSERT INTO stsv_jige (owner, owner_name, token, created, updated) VALUES (?, ?, ?, ?, ?)",
                owner.toString(), cut(ownerName), token, now, now)));
    }

    /**
     * Puts it on: only with the item's token (one update: two copies cannot both get through); the token becomes
     * newToken. Returns the row (its load) or null (a stale or copied item).
     */
    public CompletableFuture<Row> claim(long id, long token, long newToken, long now) {
        return db.async(c -> Db.update(c, "UPDATE stsv_jige SET token = ?, updated = ? WHERE id = ? AND token = ?", newToken, now, id, token) == 1
                ? get(c, id) : null);
    }

    /** The load (null = empty), only from the holder of the current token. true = saved. */
    public CompletableFuture<Boolean> save(long id, long token, byte[] contents, long now) {
        return db.async(c -> Db.update(c, "UPDATE stsv_jige SET contents = ?, updated = ? WHERE id = ? AND token = ?", contents, now, id, token) == 1);
    }

    public CompletableFuture<Row> get(long id) {
        return db.async(c -> get(c, id));
    }

    private static String cut(String s) {
        if (s == null) return "?";
        return s.length() <= 16 ? s : s.substring(0, 16);
    }
}
