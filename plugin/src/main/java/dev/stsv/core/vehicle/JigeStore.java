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
 *
 * <p>Owner (귀속): a 지게 is its owner's whoever has the item; only {@link #transfer} (/탈것 양도) changes that. Lost items
 * as for vehicles ({@link VehicleStore}, {@link VehicleLedger}): {@code gone} = its item seems lost (not listed, still
 * works if it turns up), {@code holder} = where it was last seen (a player's uuid, {@link VehicleStore#ELSEWHERE}, null).
 */
public final class JigeStore {

    public record Row(long id, UUID owner, String ownerName, long token, byte[] contents, long created, long updated, String holder, boolean gone) {

        public Row(long id, UUID owner, String ownerName, long token, byte[] contents, long created, long updated) {
            this(id, owner, ownerName, token, contents, created, updated, null, false);
        }
    }

    private final Db db;

    public JigeStore(Db db) {
        this.db = db;
    }

    public void init() {
        String id = db.dialect() == Db.Dialect.MYSQL ? "id BIGINT PRIMARY KEY AUTO_INCREMENT" : "id INTEGER PRIMARY KEY AUTOINCREMENT";
        String blob = db.dialect() == Db.Dialect.MYSQL ? "MEDIUMBLOB" : "BLOB";
        db.migrate("jige", List.of(List.of(
                "CREATE TABLE IF NOT EXISTS stsv_jige (" + id + ", owner CHAR(36) NOT NULL, owner_name VARCHAR(16) NOT NULL, "
                        + "token BIGINT NOT NULL, contents " + blob + ", created BIGINT NOT NULL, updated BIGINT NOT NULL)"),
                // 2: where its item was last seen, and whether it seems lost (VehicleLedger)
                List.of("ALTER TABLE stsv_jige ADD COLUMN holder VARCHAR(36)",
                        "ALTER TABLE stsv_jige ADD COLUMN gone INT NOT NULL DEFAULT 0")));
    }

    private static final String COLS = "id, owner, owner_name, token, contents, created, updated, holder, gone";

    private static Row row(ResultSet rs) throws SQLException {
        return new Row(rs.getLong(1), UUID.fromString(rs.getString(2)), rs.getString(3), rs.getLong(4), rs.getBytes(5), rs.getLong(6), rs.getLong(7),
                rs.getString(8), rs.getInt(9) != 0);
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
        return db.async(c -> Db.update(c, "UPDATE stsv_jige SET token = ?, gone = 0, updated = ? WHERE id = ? AND token = ?", newToken, now, id, token) == 1
                ? get(c, id) : null);
    }

    /** The load (null = empty), only from the holder of the current token. true = saved. */
    public CompletableFuture<Boolean> save(long id, long token, byte[] contents, long now) {
        return db.async(c -> Db.update(c, "UPDATE stsv_jige SET contents = ?, updated = ? WHERE id = ? AND token = ?", contents, now, id, token) == 1);
    }

    public CompletableFuture<Row> get(long id) {
        return db.async(c -> get(c, id));
    }

    /**
     * /관리자 지게 회수: a new token whatever the old one was (every item of it, worn or not, opens nothing from now on);
     * the new item is made with this one. The load stays. Returns the row, or null (no such 지게).
     */
    public CompletableFuture<Row> reissue(long id, long newToken, long now) {
        return db.async(c -> Db.update(c, "UPDATE stsv_jige SET token = ?, gone = 0, holder = NULL, updated = ? WHERE id = ?", newToken, now, id) == 1
                ? get(c, id) : null);
    }

    /**
     * /탈것 양도 of a 지게 item: only with the item's token, only from `from` (null: an admin). It becomes the new owner's
     * with a new token: the item given away stops working, a new one is made for them with newToken. The load goes with it.
     */
    public CompletableFuture<VehicleStore.Transfer> transfer(long id, long token, UUID from, UUID to, String toName, long newToken, long now) {
        return db.async(c -> {
            Row r = get(c, id);
            if (r == null || r.token() != token) return VehicleStore.Transfer.STALE;
            if (from != null && !r.owner().equals(from)) return VehicleStore.Transfer.NOT_OWNER;
            return Db.update(c, "UPDATE stsv_jige SET owner = ?, owner_name = ?, token = ?, gone = 0, holder = NULL, updated = ? WHERE id = ? AND token = ?",
                    to.toString(), cut(toName), newToken, now, id, token) == 1 ? VehicleStore.Transfer.DONE : VehicleStore.Transfer.STALE;
        });
    }

    // ------------------------------------------------------------------------------------------
    // Where items are (VehicleLedger), as VehicleStore
    // ------------------------------------------------------------------------------------------

    /** Items seen (id -> token) in one place (a player's uuid or VehicleStore.ELSEWHERE): there still. */
    public CompletableFuture<Integer> seen(java.util.Map<Long, Long> items, String holder) {
        if (items.isEmpty()) return CompletableFuture.completedFuture(0);
        return db.async(c -> {
            int n = 0;
            for (java.util.Map.Entry<Long, Long> e : items.entrySet()) {
                n += Db.update(c, "UPDATE stsv_jige SET gone = 0, holder = ? WHERE id = ? AND token = ? AND (gone <> 0 OR holder IS NULL OR holder <> ?)",
                        holder, e.getKey(), e.getValue(), holder);
            }
            return n;
        });
    }

    /** Every 지게 last seen with this player, not found on them now and not changed since `before`: gone. The ids marked. */
    public CompletableFuture<List<Long>> missing(UUID player, java.util.Set<Long> found, long before) {
        return db.async(c -> {
            List<Long> ids = Db.query(c, "SELECT id FROM stsv_jige WHERE holder = ? AND gone = 0 AND updated < ?", rs -> rs.getLong(1),
                    player.toString(), before);
            List<Long> out = new java.util.ArrayList<>();
            for (long id : ids) {
                if (found.contains(id)) continue;
                if (Db.update(c, "UPDATE stsv_jige SET gone = 1 WHERE id = ? AND gone = 0 AND holder = ?", id, player.toString()) == 1) out.add(id);
            }
            return out;
        });
    }

    /** Its item destroyed where the server saw it (the void): gone, if it was the current one. */
    public CompletableFuture<Boolean> gone(long id, long token) {
        return db.async(c -> Db.update(c, "UPDATE stsv_jige SET gone = 1 WHERE id = ? AND token = ? AND gone = 0", id, token) == 1);
    }

    /** /관리자 탈것 정리: every 지게 not found (id -> token) and not changed since `before` is gone. The ids marked. */
    public CompletableFuture<List<Long>> sweep(java.util.Map<Long, Long> found, long before) {
        return db.async(c -> {
            List<long[]> rows = Db.query(c, "SELECT id, token FROM stsv_jige WHERE gone = 0 AND updated < ?",
                    rs -> new long[]{rs.getLong(1), rs.getLong(2)}, before);
            List<Long> out = new java.util.ArrayList<>();
            for (long[] r : rows) {
                Long t = found.get(r[0]);
                if (t != null && t == r[1]) continue;
                if (Db.update(c, "UPDATE stsv_jige SET gone = 1 WHERE id = ? AND gone = 0", r[0]) == 1) out.add(r[0]);
            }
            return out;
        });
    }

    /** How many 지게 look lost (hidden from {@link #list}), of one owner or all (null). */
    public CompletableFuture<Integer> goneCount(UUID owner) {
        return db.async(c -> (owner == null
                ? Db.query(c, "SELECT COUNT(*) FROM stsv_jige WHERE gone <> 0", rs -> rs.getInt(1))
                : Db.query(c, "SELECT COUNT(*) FROM stsv_jige WHERE owner = ? AND gone <> 0", rs -> rs.getInt(1), owner.toString())).getFirst());
    }

    /** All 지게 that are still there (or one owner's; not gone), oldest first, at most limit. */
    public CompletableFuture<List<Row>> list(UUID owner, int limit) {
        return db.async(c -> owner == null
                ? Db.query(c, "SELECT " + COLS + " FROM stsv_jige WHERE gone = 0 ORDER BY id LIMIT " + Math.max(1, limit), JigeStore::row)
                : Db.query(c, "SELECT " + COLS + " FROM stsv_jige WHERE owner = ? AND gone = 0 ORDER BY id LIMIT " + Math.max(1, limit), JigeStore::row,
                owner.toString()));
    }

    private static String cut(String s) {
        if (s == null) return "?";
        return s.length() <= 16 ? s : s.substring(0, 16);
    }
}
