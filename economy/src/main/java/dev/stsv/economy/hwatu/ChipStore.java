package dev.stsv.economy.hwatu;

import dev.stsv.core.data.Db;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 화투방 chips (README "화투방"): every player's chips, a number on the account and never an item (eco_hwatu_chip: chips,
 * and for the day it was last touched: chips bought and the net won (+) / lost (-) that day), and the log of every hand
 * (eco_hwatu_hand) for /관리자 화투방 기록. Accounts are read whole at start, kept in memory, written through.
 */
public final class ChipStore {

    public static final class Account {
        public long chips;
        public int day;
        public long bought;
        public long net;
    }

    /** One logged hand. players: "uuid,uuid,..."; detail: who put in what and got what, readable. */
    public record Hand(long id, long at, String game, String room, String players, long pot, long burned, String detail) {}

    private final Db db;
    private final Map<UUID, Account> accounts = new ConcurrentHashMap<>();

    public ChipStore(Db db) {
        this.db = db;
    }

    public void init() {
        String id = db.dialect() == Db.Dialect.MYSQL ? "id BIGINT PRIMARY KEY AUTO_INCREMENT" : "id INTEGER PRIMARY KEY AUTOINCREMENT";
        db.migrate("eco_hwatu", List.of(List.of(
                "CREATE TABLE IF NOT EXISTS eco_hwatu_chip (player CHAR(36) NOT NULL PRIMARY KEY, chips BIGINT NOT NULL, day INT NOT NULL, "
                        + "bought BIGINT NOT NULL, net BIGINT NOT NULL)",
                "CREATE TABLE IF NOT EXISTS eco_hwatu_hand (" + id + ", at BIGINT NOT NULL, game VARCHAR(16) NOT NULL, room VARCHAR(32) NOT NULL, "
                        + "players TEXT NOT NULL, pot BIGINT NOT NULL, burned BIGINT NOT NULL, detail TEXT NOT NULL)")));
        accounts.clear();
        db.sync(c -> {
            for (Object[] r : Db.query(c, "SELECT player, chips, day, bought, net FROM eco_hwatu_chip",
                    rs -> new Object[]{rs.getString(1), rs.getLong(2), rs.getInt(3), rs.getLong(4), rs.getLong(5)})) {
                Account a = new Account();
                a.chips = (Long) r[1];
                a.day = (Integer) r[2];
                a.bought = (Long) r[3];
                a.net = (Long) r[4];
                accounts.put(UUID.fromString((String) r[0]), a);
            }
            return null;
        });
    }

    /** A copy of the account, with the day's counters at 0 if they belong to another day. */
    public Account account(UUID player, int today) {
        Account a = accounts.get(player), out = new Account();
        if (a == null) return out;
        out.chips = a.chips;
        out.day = today;
        if (a.day == today) {
            out.bought = a.bought;
            out.net = a.net;
        }
        return out;
    }

    private Account mutable(UUID player, int today) {
        Account a = accounts.computeIfAbsent(player, k -> new Account());
        if (a.day != today) {
            a.day = today;
            a.bought = 0;
            a.net = 0;
        }
        return a;
    }

    private void save(UUID player, Account a) {
        db.execute(db.upsert("eco_hwatu_chip", List.of("player"), List.of("chips", "day", "bought", "net")), player.toString(), a.chips, a.day, a.bought, a.net);
    }

    /** Chips bought (the gold was taken by the caller). */
    public void bought(UUID player, long n, int today) {
        Account a = mutable(player, today);
        a.chips += n;
        a.bought += n;
        save(player, a);
    }

    /** Takes n chips to cash them; false (nothing changes) if fewer than n are free (keep = chips held in a running hand). */
    public boolean cash(UUID player, long n, long keep, int today) {
        Account a = mutable(player, today);
        if (n <= 0 || a.chips - keep < n) return false;
        a.chips -= n;
        save(player, a);
        return true;
    }

    /** A hand's result for one player: chips won (+) or lost (-). Never below 0 (a hand never asks for more than was held). */
    public void settle(UUID player, long delta, int today) {
        Account a = mutable(player, today);
        a.chips = Math.max(0, a.chips + delta);
        a.net += delta;
        save(player, a);
    }

    /** Admin: sets a player's chips. */
    public void set(UUID player, long chips, int today) {
        Account a = mutable(player, today);
        a.chips = Math.max(0, chips);
        save(player, a);
    }

    /** All chips held by everyone (the gold they stand for is held outside the balances). */
    public long total() {
        long t = 0;
        for (Account a : accounts.values()) t += a.chips;
        return t;
    }

    public void log(long at, String game, String room, List<UUID> players, long pot, long burned, String detail) {
        StringBuilder ids = new StringBuilder();
        for (UUID u : players) ids.append(ids.isEmpty() ? "" : ",").append(u);
        db.execute("INSERT INTO eco_hwatu_hand (at, game, room, players, pot, burned, detail) VALUES (?, ?, ?, ?, ?, ?, ?)",
                at, game, room, ids.toString(), pot, burned, detail);
    }

    /** The latest hands, newest first; player null = everyone's. */
    public List<Hand> hands(UUID player, int limit) {
        return db.sync(c -> player == null
                ? Db.query(c, "SELECT id, at, game, room, players, pot, burned, detail FROM eco_hwatu_hand ORDER BY id DESC LIMIT " + limit, ChipStore::row)
                : Db.query(c, "SELECT id, at, game, room, players, pot, burned, detail FROM eco_hwatu_hand WHERE players LIKE ? ORDER BY id DESC LIMIT " + limit,
                ChipStore::row, "%" + player + "%"));
    }

    private static Hand row(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Hand(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getLong(6), rs.getLong(7), rs.getString(8));
    }

    /** Everyone holding chips (for the reset refund). */
    public List<Map.Entry<UUID, Long>> holders() {
        List<Map.Entry<UUID, Long>> out = new ArrayList<>();
        for (var e : accounts.entrySet()) if (e.getValue().chips > 0) out.add(Map.entry(e.getKey(), e.getValue().chips));
        return out;
    }

    public void reset() {
        accounts.clear();
        db.async(c -> {
            Db.update(c, "DELETE FROM eco_hwatu_chip");
            Db.update(c, "DELETE FROM eco_hwatu_hand");
            return null;
        });
    }
}
