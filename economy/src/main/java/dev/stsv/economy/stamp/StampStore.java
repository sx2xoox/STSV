package dev.stsv.economy.stamp;

import dev.stsv.core.data.Db;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Every player's stamps (eco_stamp: balance, all earned, mission reroll tickets), their board of each month (eco_stamp_board:
 * cells filled) and what capped sources gave today (eco_stamp_cap). Read whole at start (a row a player), kept in memory,
 * written through.
 */
public final class StampStore {

    /** One player's stamps. */
    public static final class Account {
        public int balance;
        public long earned;
        public int rerolls;
    }

    private final Db db;
    private final Map<UUID, Account> accounts = new ConcurrentHashMap<>();
    /** "player|2026-11" -> cells filled */
    private final Map<String, Integer> boards = new ConcurrentHashMap<>();
    /** "player|source" -> given on capDay */
    private final Map<String, Integer> caps = new ConcurrentHashMap<>();
    private int capDay = -1;

    public StampStore(Db db) {
        this.db = db;
    }

    public void init(int today) {
        db.migrate("eco_stamp", List.of(List.of(
                "CREATE TABLE IF NOT EXISTS eco_stamp (player CHAR(36) NOT NULL PRIMARY KEY, balance INT NOT NULL, earned BIGINT NOT NULL, "
                        + "rerolls INT NOT NULL)",
                "CREATE TABLE IF NOT EXISTS eco_stamp_board (player CHAR(36) NOT NULL, month VARCHAR(8) NOT NULL, filled INT NOT NULL, "
                        + "PRIMARY KEY (player, month))",
                "CREATE TABLE IF NOT EXISTS eco_stamp_cap (player CHAR(36) NOT NULL, day INT NOT NULL, source VARCHAR(32) NOT NULL, "
                        + "n INT NOT NULL, PRIMARY KEY (player, day, source))")));
        accounts.clear();
        boards.clear();
        db.sync(c -> {
            for (Object[] r : Db.query(c, "SELECT player, balance, earned, rerolls FROM eco_stamp",
                    rs -> new Object[]{rs.getString(1), rs.getInt(2), rs.getLong(3), rs.getInt(4)})) {
                Account a = new Account();
                a.balance = (Integer) r[1];
                a.earned = (Long) r[2];
                a.rerolls = (Integer) r[3];
                accounts.put(UUID.fromString((String) r[0]), a);
            }
            for (Object[] r : Db.query(c, "SELECT player, month, filled FROM eco_stamp_board",
                    rs -> new Object[]{rs.getString(1), rs.getString(2), rs.getInt(3)})) {
                boards.put(r[0] + "|" + r[1], (Integer) r[2]);
            }
            return null;
        });
        newDay(today);
    }

    /** Today's caps (yesterday's rows go). */
    public void newDay(int today) {
        capDay = today;
        caps.clear();
        db.execute("DELETE FROM eco_stamp_cap WHERE day <> ?", today);
        for (Object[] r : db.sync(c -> Db.query(c, "SELECT player, source, n FROM eco_stamp_cap WHERE day = ?",
                rs -> new Object[]{rs.getString(1), rs.getString(2), rs.getInt(3)}, today))) {
            caps.put(r[0] + "|" + r[1], (Integer) r[2]);
        }
    }

    public Account account(UUID player) {
        return accounts.getOrDefault(player, new Account());
    }

    private Account mutable(UUID player) {
        return accounts.computeIfAbsent(player, k -> new Account());
    }

    private void save(UUID player, Account a) {
        db.execute(db.upsert("eco_stamp", List.of("player"), List.of("balance", "earned", "rerolls")), player.toString(), a.balance, a.earned, a.rerolls);
    }

    /** Adds earned stamps (balance and all earned). */
    public void earn(UUID player, int n) {
        Account a = mutable(player);
        a.balance += n;
        a.earned += n;
        save(player, a);
    }

    /** Spends stamps; false (nothing changes) if the balance is short. */
    public boolean spend(UUID player, int n) {
        Account a = mutable(player);
        if (a.balance < n) return false;
        a.balance -= n;
        save(player, a);
        return true;
    }

    public void addRerolls(UUID player, int n) {
        Account a = mutable(player);
        a.rerolls = Math.max(0, a.rerolls + n);
        save(player, a);
    }

    /** Uses one mission reroll ticket; false if there is none. */
    public boolean takeReroll(UUID player) {
        Account a = accounts.get(player);
        if (a == null || a.rerolls <= 0) return false;
        a.rerolls--;
        save(player, a);
        return true;
    }

    public int board(UUID player, String month) {
        return boards.getOrDefault(player + "|" + month, 0);
    }

    public void setBoard(UUID player, String month, int filled) {
        boards.put(player + "|" + month, filled);
        db.execute(db.upsert("eco_stamp_board", List.of("player", "month"), List.of("filled")), player.toString(), month, filled);
    }

    public int capped(UUID player, String source) {
        return caps.getOrDefault(player + "|" + source, 0);
    }

    public void addCapped(UUID player, String source, int n) {
        int now = caps.merge(player + "|" + source, n, Integer::sum);
        db.execute(db.upsert("eco_stamp_cap", List.of("player", "day", "source"), List.of("n")), player.toString(), capDay, source, now);
    }

    public void reset() {
        accounts.clear();
        boards.clear();
        caps.clear();
        db.async(c -> {
            Db.update(c, "DELETE FROM eco_stamp");
            Db.update(c, "DELETE FROM eco_stamp_board");
            Db.update(c, "DELETE FROM eco_stamp_cap");
            return null;
        });
    }
}
