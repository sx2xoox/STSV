package dev.stsv.core.vehicle;

import dev.stsv.core.data.Db;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Every vehicle, in one table (no Bukkit types: unit-tested on SQLite). The database is the truth: a vehicle in the
 * world is not a saved entity but a row in state WORLD with its position; the plugin shows it (entities that are never
 * saved with the chunk) while its chunk is loaded. So a crash can neither leave a second copy in an old chunk save nor
 * lose it: at worst it is back where it was last saved (every few seconds while driving, and when it stops).
 *
 * <p>Items: a vehicle picked up is a row in state ITEM, and its item carries the row's id and a <b>token</b>. Placing it
 * needs that token and changes it ({@link #place}: one conditional update), so of two copies of one item only the first
 * placed works; the rest are worthless. A vehicle sent back by an admin while its owner was away is OWED (handed over on
 * their next join, once).
 *
 * <p>The storage bed's contents (serialized items) stay on the row, whatever state it is in.
 *
 * <p>Owner (귀속): a vehicle is its owner's wherever its item is; placing it never changes that ({@link #place} refuses anyone
 * else but an admin), only {@link #transfer} / {@link #transferItem} (/탈것 양도) does.
 *
 * <p>Still there? An item can be lost without the plugin being told (thrown into lava, /clear, deleted in creative). A row
 * in state ITEM whose item is gone as far as the server can tell ({@link VehicleLedger}) becomes GONE: hidden from every
 * list, but kept with its token, so an item that does turn up again (it was in a chest after all) still places, and is
 * listed again as soon as it is seen ({@link #seen}). {@code holder} says where its item was last seen: a player's uuid
 * (their inventory or ender chest), {@link #ELSEWHERE} (the ground, a chest, a mailbox...) or null (not seen yet).
 *
 * <p>Grants ({@code stsv_vehicle_grant}, /탈것 권한): the players an owner allows to drive, pull and open the beds of all
 * their vehicles, one row per (owner, player), with the player's name as it was then (listed while they are offline).
 */
public final class VehicleStore {

    public enum State {
        /** placed in a world */
        WORLD,
        /** an item somewhere */
        ITEM,
        /** an item handed over later (mailbox, next join) */
        OWED,
        /** an item that seems lost (not listed; it still works if it turns up) */
        GONE;

        String db() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }

        static State of(String s) {
            return valueOf(s.toUpperCase(java.util.Locale.ROOT));
        }
    }

    /** Where a vehicle stands: world (uid, name), position of its ground centre, yaw. */
    public record Pos(UUID world, String worldName, double x, double y, double z, float yaw) {}

    /**
     * Is the world a vehicle stands in gone for good (it goes back to its owner as an item)? Not while a world with its
     * id is loaded. Gone: a world of its name is loaded with another id (made again: /관리자 야생초기화), or none is and
     * no folder of that name is left. Only not loaded now (the folder is there): it waits.
     */
    public static boolean worldGone(boolean worldLoaded, boolean sameNameLoaded, boolean folderLeft) {
        if (worldLoaded) return false;
        return sameNameLoaded || !folderLeft;
    }

    public record Row(long id, String item, UUID owner, String ownerName, State state, long token, Pos pos, byte[] cargo,
                      long created, long updated, String holder) {

        public Row(long id, String item, UUID owner, String ownerName, State state, long token, Pos pos, byte[] cargo, long created,
                   long updated) {
            this(id, item, owner, ownerName, state, token, pos, cargo, created, updated, null);
        }
    }

    /** {@link Row#holder} of an item seen somewhere that is not a player's inventory (the ground, a chest, a mailbox). */
    public static final String ELSEWHERE = "-";

    /** How {@link #place} went. */
    public enum Placing { PLACED, STALE, NOT_OWNER }

    /** {@link #place}'s answer: the row placed (PLACED), or why not; for NOT_OWNER the row as it is (whose it is). */
    public record Placed(Placing outcome, Row row) {}

    /** How a transfer (/탈것 양도) went. */
    public enum Transfer { DONE, STALE, NOT_OWNER }

    /** An owner allowed a player (all the owner's vehicles). */
    public record Grant(UUID owner, UUID player, String playerName, long created) {}

    private final Db db;

    public VehicleStore(Db db) {
        this.db = db;
    }

    public void init() {
        String id = db.dialect() == Db.Dialect.MYSQL ? "id BIGINT PRIMARY KEY AUTO_INCREMENT" : "id INTEGER PRIMARY KEY AUTOINCREMENT";
        String blob = db.dialect() == Db.Dialect.MYSQL ? "MEDIUMBLOB" : "BLOB";
        db.migrate("vehicles", List.of(List.of(
                "CREATE TABLE IF NOT EXISTS stsv_vehicle (" + id + ", item VARCHAR(128) NOT NULL, owner CHAR(36) NOT NULL, "
                        + "owner_name VARCHAR(16) NOT NULL, state VARCHAR(8) NOT NULL, token BIGINT NOT NULL, world CHAR(36), "
                        + "world_name VARCHAR(64), x DOUBLE, y DOUBLE, z DOUBLE, yaw DOUBLE, cargo " + blob + ", "
                        + "created BIGINT NOT NULL, updated BIGINT NOT NULL)",
                "CREATE INDEX stsv_vehicle_owner ON stsv_vehicle (owner)",
                "CREATE INDEX stsv_vehicle_state ON stsv_vehicle (state)"),
                // 2: /탈것 권한 (who else may drive, pull and open the beds of an owner's vehicles)
                List.of("CREATE TABLE IF NOT EXISTS stsv_vehicle_grant (owner CHAR(36) NOT NULL, player CHAR(36) NOT NULL, "
                        + "player_name VARCHAR(16) NOT NULL, created BIGINT NOT NULL, PRIMARY KEY (owner, player))"),
                // 3: where an item was last seen (VehicleLedger: lost items become GONE)
                List.of("ALTER TABLE stsv_vehicle ADD COLUMN holder VARCHAR(36)",
                        "CREATE INDEX stsv_vehicle_holder ON stsv_vehicle (holder)")));
    }

    // ------------------------------------------------------------------------------------------
    // Grants (/탈것 권한)
    // ------------------------------------------------------------------------------------------

    private static Grant grant(ResultSet rs) throws SQLException {
        return new Grant(UUID.fromString(rs.getString(1)), UUID.fromString(rs.getString(2)), rs.getString(3), rs.getLong(4));
    }

    /** Allows a player (their name refreshed when already allowed). true = newly allowed. */
    public CompletableFuture<Boolean> grant(UUID owner, UUID player, String playerName, long now) {
        return db.async(c -> {
            int n = Db.update(c, "UPDATE stsv_vehicle_grant SET player_name = ? WHERE owner = ? AND player = ?", cut(playerName),
                    owner.toString(), player.toString());
            if (n > 0) return false;
            Db.update(c, "INSERT INTO stsv_vehicle_grant (owner, player, player_name, created) VALUES (?, ?, ?, ?)", owner.toString(),
                    player.toString(), cut(playerName), now);
            return true;
        });
    }

    /** Takes a permission back. true = it was there. */
    public CompletableFuture<Boolean> revoke(UUID owner, UUID player) {
        return db.async(c -> Db.update(c, "DELETE FROM stsv_vehicle_grant WHERE owner = ? AND player = ?", owner.toString(), player.toString()) > 0);
    }

    /** Every grant (loaded at start; there are few). */
    public List<Grant> grants() {
        return db.sync(c -> Db.query(c, "SELECT owner, player, player_name, created FROM stsv_vehicle_grant ORDER BY created, player",
                VehicleStore::grant));
    }

    /** One owner's grants, oldest first. */
    public CompletableFuture<List<Grant>> grants(UUID owner) {
        return db.async(c -> Db.query(c, "SELECT owner, player, player_name, created FROM stsv_vehicle_grant WHERE owner = ? ORDER BY created, player",
                VehicleStore::grant, owner.toString()));
    }

    private static final String COLS = "id, item, owner, owner_name, state, token, world, world_name, x, y, z, yaw, cargo, created, updated, holder";
    /** an item that may be anywhere (lost or not) */
    private static final String LOOSE = "state IN ('item', 'gone')";

    private static Row row(ResultSet rs) throws SQLException {
        String w = rs.getString(7);
        Pos pos = w == null ? null : new Pos(UUID.fromString(w), rs.getString(8), rs.getDouble(9), rs.getDouble(10), rs.getDouble(11),
                (float) rs.getDouble(12));
        return new Row(rs.getLong(1), rs.getString(2), UUID.fromString(rs.getString(3)), rs.getString(4), State.of(rs.getString(5)),
                rs.getLong(6), pos, rs.getBytes(13), rs.getLong(14), rs.getLong(15), rs.getString(16));
    }

    private static Row get(Connection c, long id) throws SQLException {
        List<Row> r = Db.query(c, "SELECT " + COLS + " FROM stsv_vehicle WHERE id = ?", VehicleStore::row, id);
        return r.isEmpty() ? null : r.getFirst();
    }

    /** A new vehicle placed in the world (an admin-made item has no row yet). */
    public CompletableFuture<Row> create(String item, UUID owner, String ownerName, Pos pos, long token, long now) {
        return db.async(c -> {
            long id = Db.insert(c, "INSERT INTO stsv_vehicle (item, owner, owner_name, state, token, world, world_name, x, y, z, yaw, "
                            + "created, updated) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", item, owner.toString(), cut(ownerName),
                    State.WORLD.db(), token, pos.world().toString(), cut64(pos.worldName()), pos.x(), pos.y(), pos.z(), (double) pos.yaw(), now, now);
            return get(c, id);
        });
    }

    /**
     * Places a picked-up vehicle: only if the item's token is the row's and it is an item now (lost or not: one that
     * turns up again works), and only by its owner (anyOwner: an admin, who places it for its owner). One step on the
     * database's single thread, so two copies of one item cannot both get through. The owner stays; the token changes.
     */
    public CompletableFuture<Placed> place(long id, long token, UUID placer, boolean anyOwner, Pos pos, long newToken, long now) {
        return db.async(c -> {
            Row r = get(c, id);
            if (r == null || r.token() != token || (r.state() != State.ITEM && r.state() != State.GONE)) return new Placed(Placing.STALE, null);
            if (!anyOwner && !r.owner().equals(placer)) return new Placed(Placing.NOT_OWNER, r);
            int n = Db.update(c, "UPDATE stsv_vehicle SET state = ?, token = ?, holder = NULL, world = ?, world_name = ?, "
                            + "x = ?, y = ?, z = ?, yaw = ?, updated = ? WHERE id = ? AND token = ? AND " + LOOSE,
                    State.WORLD.db(), newToken, pos.world().toString(), cut64(pos.worldName()),
                    pos.x(), pos.y(), pos.z(), (double) pos.yaw(), now, id, token);
            return n == 1 ? new Placed(Placing.PLACED, get(c, id)) : new Placed(Placing.STALE, null);
        });
    }

    /** Where a vehicle in the world is now. */
    public CompletableFuture<Void> move(long id, Pos pos, long now) {
        return db.execute("UPDATE stsv_vehicle SET world = ?, world_name = ?, x = ?, y = ?, z = ?, yaw = ?, updated = ? WHERE id = ? AND state = ?",
                pos.world().toString(), cut64(pos.worldName()), pos.x(), pos.y(), pos.z(), (double) pos.yaw(), now, id, State.WORLD.db());
    }

    /** The storage bed's contents (null = empty). */
    public CompletableFuture<Void> cargo(long id, byte[] cargo, long now) {
        return db.execute("UPDATE stsv_vehicle SET cargo = ?, updated = ? WHERE id = ?", cargo, now, id);
    }

    /**
     * From the world into an item (picked up, or sent back by an admin: then owed = true, handed over later).
     * Only a vehicle in the world; true = done (once).
     */
    public CompletableFuture<Boolean> takeIn(long id, long newToken, boolean owed, long now) {
        return db.async(c -> Db.update(c, "UPDATE stsv_vehicle SET state = ?, token = ?, holder = NULL, updated = ? WHERE id = ? AND state = ?",
                (owed ? State.OWED : State.ITEM).db(), newToken, now, id, State.WORLD.db()) == 1);
    }

    /**
     * An admin's 회수 of a vehicle that is not in the world (an item someone lost, or owed): a new token (every copy of
     * its old item stops working), owed to its owner. true = done.
     */
    public CompletableFuture<Boolean> reissue(long id, long newToken, long now) {
        return db.async(c -> Db.update(c, "UPDATE stsv_vehicle SET state = ?, token = ?, holder = NULL, updated = ? WHERE id = ? AND state <> ?",
                State.OWED.db(), newToken, now, id, State.WORLD.db()) == 1);
    }

    /**
     * A vehicle just picked up whose item could not be handed over (its owner left meanwhile, or has no room): owed
     * instead, so it is delivered on their next join ({@link #delivered}). Only the item of this token; true = done.
     */
    public CompletableFuture<Boolean> owe(long id, long token, long now) {
        return db.async(c -> Db.update(c, "UPDATE stsv_vehicle SET state = ?, holder = NULL, updated = ? WHERE id = ? AND token = ? AND " + LOOSE,
                State.OWED.db(), now, id, token) == 1);
    }

    /**
     * An owed vehicle handed over (once: the second call for the same row finds nothing to change). holder: where it went
     * ({@link #ELSEWHERE} for a mailbox, null when not known).
     */
    public CompletableFuture<Boolean> delivered(long id, long token, String holder, long now) {
        return db.async(c -> Db.update(c, "UPDATE stsv_vehicle SET state = ?, holder = ?, updated = ? WHERE id = ? AND token = ? AND state = ?",
                State.ITEM.db(), holder, now, id, token, State.OWED.db()) == 1);
    }

    /**
     * An admin-made or shop-bought item (no row yet) given to someone else (/탈것 양도): a new row, owed to them (handed
     * over as {@link #delivered} says), with this token on the item made for them.
     */
    public CompletableFuture<Row> createOwed(String item, UUID owner, String ownerName, long token, long now) {
        return db.async(c -> get(c, Db.insert(c, "INSERT INTO stsv_vehicle (item, owner, owner_name, state, token, created, updated) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?)", item, owner.toString(), cut(ownerName), State.OWED.db(), token, now, now)));
    }

    /**
     * /탈것 양도 of a vehicle standing in the world: it stays where it is, its owner changes (only from `from`; null =
     * whoever owns it: an admin).
     */
    public CompletableFuture<Transfer> transfer(long id, UUID from, UUID to, String toName, long now) {
        return db.async(c -> {
            Row r = get(c, id);
            if (r == null || r.state() != State.WORLD) return Transfer.STALE;
            if (from != null && !r.owner().equals(from)) return Transfer.NOT_OWNER;
            return Db.update(c, "UPDATE stsv_vehicle SET owner = ?, owner_name = ?, updated = ? WHERE id = ? AND state = ?", to.toString(),
                    cut(toName), now, id, State.WORLD.db()) == 1 ? Transfer.DONE : Transfer.STALE;
        });
    }

    /**
     * /탈것 양도 of a vehicle item: only with the item's token (a copy or an old item does nothing), only from `from` (null:
     * an admin). It becomes the new owner's, owed to them with a new token: the item given away stops working, a new one
     * is handed to them ({@link #delivered}).
     */
    public CompletableFuture<Transfer> transferItem(long id, long token, UUID from, UUID to, String toName, long newToken, long now) {
        return db.async(c -> {
            Row r = get(c, id);
            if (r == null || r.token() != token || (r.state() != State.ITEM && r.state() != State.GONE)) return Transfer.STALE;
            if (from != null && !r.owner().equals(from)) return Transfer.NOT_OWNER;
            return Db.update(c, "UPDATE stsv_vehicle SET owner = ?, owner_name = ?, state = ?, token = ?, holder = NULL, updated = ? "
                    + "WHERE id = ? AND token = ? AND " + LOOSE, to.toString(), cut(toName), State.OWED.db(), newToken, now, id, token) == 1
                    ? Transfer.DONE : Transfer.STALE;
        });
    }

    // ------------------------------------------------------------------------------------------
    // Where items are (VehicleLedger)
    // ------------------------------------------------------------------------------------------

    /**
     * Items seen (id -> token) in one place: holder = a player's uuid or {@link #ELSEWHERE}. Each with its current token
     * is an item still (a GONE one is back). Returns how many rows that changed.
     */
    public CompletableFuture<Integer> seen(java.util.Map<Long, Long> items, String holder) {
        if (items.isEmpty()) return CompletableFuture.completedFuture(0);
        return db.async(c -> {
            int n = 0;
            for (java.util.Map.Entry<Long, Long> e : items.entrySet()) {
                n += Db.update(c, "UPDATE stsv_vehicle SET state = ?, holder = ? WHERE id = ? AND token = ? AND " + LOOSE
                        + " AND (state <> ? OR holder IS NULL OR holder <> ?)", State.ITEM.db(), holder, e.getKey(), e.getValue(), State.ITEM.db(), holder);
            }
            return n;
        });
    }

    /**
     * A player's inventory and ender chest were looked through and these ids were there: every other item last seen with
     * them, not changed since `before` (one on its way into their hands is not missing yet), is GONE. Returns the ids marked.
     */
    public CompletableFuture<List<Long>> missing(UUID player, java.util.Set<Long> found, long before) {
        return db.async(c -> {
            List<Long> ids = Db.query(c, "SELECT id FROM stsv_vehicle WHERE holder = ? AND state = ? AND updated < ?", rs -> rs.getLong(1),
                    player.toString(), State.ITEM.db(), before);
            List<Long> out = new java.util.ArrayList<>();
            for (long id : ids) {
                if (found.contains(id)) continue;
                if (Db.update(c, "UPDATE stsv_vehicle SET state = ? WHERE id = ? AND state = ? AND holder = ?", State.GONE.db(), id,
                        State.ITEM.db(), player.toString()) == 1) out.add(id);
            }
            return out;
        });
    }

    /** An item destroyed where the server saw it (burnt, despawned, fallen out of the world): GONE, if it was the current one. */
    public CompletableFuture<Boolean> gone(long id, long token) {
        return db.async(c -> Db.update(c, "UPDATE stsv_vehicle SET state = ? WHERE id = ? AND token = ? AND state = ?", State.GONE.db(), id,
                token, State.ITEM.db()) == 1);
    }

    /**
     * /관리자 탈것 정리: every item not among these (id -> token: found anywhere the server can look now) and not changed
     * since `before` is GONE. Returns the ids marked.
     */
    public CompletableFuture<List<Long>> sweep(java.util.Map<Long, Long> found, long before) {
        return db.async(c -> {
            List<long[]> items = Db.query(c, "SELECT id, token FROM stsv_vehicle WHERE state = ? AND updated < ?",
                    rs -> new long[]{rs.getLong(1), rs.getLong(2)}, State.ITEM.db(), before);
            List<Long> out = new java.util.ArrayList<>();
            for (long[] it : items) {
                Long t = found.get(it[0]);
                if (t != null && t == it[1]) continue;
                if (Db.update(c, "UPDATE stsv_vehicle SET state = ? WHERE id = ? AND state = ?", State.GONE.db(), it[0], State.ITEM.db()) == 1) out.add(it[0]);
            }
            return out;
        });
    }

    public CompletableFuture<Boolean> delete(long id) {
        return db.async(c -> Db.update(c, "DELETE FROM stsv_vehicle WHERE id = ?", id) == 1);
    }

    public CompletableFuture<Row> get(long id) {
        return db.async(c -> get(c, id));
    }

    /** Every vehicle in the world (loaded at start: the plugin shows them as their chunks load). */
    public List<Row> placed() {
        return db.sync(c -> Db.query(c, "SELECT " + COLS + " FROM stsv_vehicle WHERE state = ? ORDER BY id", VehicleStore::row, State.WORLD.db()));
    }

    public CompletableFuture<List<Row>> owed(UUID owner) {
        return db.async(c -> Db.query(c, "SELECT " + COLS + " FROM stsv_vehicle WHERE owner = ? AND state = ? ORDER BY id",
                VehicleStore::row, owner.toString(), State.OWED.db()));
    }

    /** All vehicles that are still there (or one owner's; not GONE), oldest first, at most limit. */
    public CompletableFuture<List<Row>> list(UUID owner, int limit) {
        return db.async(c -> owner == null
                ? Db.query(c, "SELECT " + COLS + " FROM stsv_vehicle WHERE state <> ? ORDER BY id LIMIT " + Math.max(1, limit), VehicleStore::row,
                State.GONE.db())
                : Db.query(c, "SELECT " + COLS + " FROM stsv_vehicle WHERE owner = ? AND state <> ? ORDER BY id LIMIT " + Math.max(1, limit),
                VehicleStore::row, owner.toString(), State.GONE.db()));
    }

    /** The vehicles that look lost (GONE), of one owner or all (null), oldest first, at most limit. */
    public CompletableFuture<List<Row>> listGone(UUID owner, int limit) {
        return db.async(c -> owner == null
                ? Db.query(c, "SELECT " + COLS + " FROM stsv_vehicle WHERE state = ? ORDER BY id LIMIT " + Math.max(1, limit), VehicleStore::row,
                State.GONE.db())
                : Db.query(c, "SELECT " + COLS + " FROM stsv_vehicle WHERE owner = ? AND state = ? ORDER BY id LIMIT " + Math.max(1, limit),
                VehicleStore::row, owner.toString(), State.GONE.db()));
    }

    /** How many look lost (GONE: hidden from {@link #list}), of one owner or all (null). */
    public CompletableFuture<Integer> goneCount(UUID owner) {
        return db.async(c -> (owner == null
                ? Db.query(c, "SELECT COUNT(*) FROM stsv_vehicle WHERE state = ?", rs -> rs.getInt(1), State.GONE.db())
                : Db.query(c, "SELECT COUNT(*) FROM stsv_vehicle WHERE owner = ? AND state = ?", rs -> rs.getInt(1), owner.toString(),
                State.GONE.db())).getFirst());
    }

    private static String cut(String s) {
        if (s == null) return "?";
        return s.length() <= 16 ? s : s.substring(0, 16);
    }

    private static String cut64(String s) {
        if (s == null) return "?";
        return s.length() <= 64 ? s : s.substring(0, 64);
    }
}
