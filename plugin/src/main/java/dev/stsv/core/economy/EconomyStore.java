package dev.stsv.core.economy;

import dev.stsv.core.data.Db;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Money: one balance per player (whole gold, G) and a ledger of every movement. No Bukkit types
 * (unit-tested on SQLite).
 *
 * Balances live in memory (main-thread decisions never wait on the database) and every change is
 * written right away on the database thread as an INCREMENT (balance = balance + delta), so writes
 * can land in any order on a pooled MySQL and still add up. The ledger keeps who, to whom, how much,
 * what kind and why: that is what the daily statistics, the admin history and the undo work from.
 */
public final class EconomyStore {

    /** What a movement means for the economy as a whole. */
    public enum Kind {
        /** money appears (shop buys from a player, quest reward, start money) */
        FAUCET,
        /** money disappears (tax, fee, land, shop sale to a player) */
        SINK,
        /** player to player (pay, trade, auction) */
        TRANSFER,
        /** into or out of a cheque item: not created, not destroyed */
        CHEQUE,
        /** an admin correction */
        ADMIN,
        /**
         * held outside the player's balance for a while, neither created nor destroyed: an auction bid or deposit (paid
         * back or passed on later), money put in a land 금고 (paid back, or destroyed later as tax: {@link #burn}),
         * the price of shares (paid back when they are sold, at that day's price)
         */
        ESCROW
    }

    public record Entry(long id, long at, UUID from, UUID to, long amount, Kind kind, String reason, String note, boolean reverted) {}

    public record Stats(long faucet, long sink, long transfer, long total, int accounts, double gini) {}

    /** cashed_by of a cheque voided by an undo */
    public static final UUID VOID = new UUID(0, 0);

    private final Db db;
    private final Map<UUID, Long> balances = new ConcurrentHashMap<>();
    /** issuer -> the money in their cheques nobody cashed yet (part of what they own) */
    private final Map<UUID, Long> outstanding = new ConcurrentHashMap<>();

    /** A cashed cheque: its amount (-1 = already used or never issued) and who wrote it (null then). */
    public record Claim(long amount, UUID issuer) {}

    public EconomyStore(Db db) {
        this.db = db;
    }

    private List<List<String>> schema() {
        String id = db.dialect() == Db.Dialect.MYSQL ? "id BIGINT PRIMARY KEY AUTO_INCREMENT" : "id INTEGER PRIMARY KEY AUTOINCREMENT";
        return List.of(List.of(
                "CREATE TABLE IF NOT EXISTS account (player CHAR(36) NOT NULL PRIMARY KEY, balance BIGINT NOT NULL)",
                "CREATE TABLE IF NOT EXISTS ledger (" + id + ", at BIGINT NOT NULL, from_player CHAR(36), to_player CHAR(36), "
                        + "amount BIGINT NOT NULL, kind VARCHAR(16) NOT NULL, reason VARCHAR(64) NOT NULL, note VARCHAR(255), "
                        + "reverted INT NOT NULL DEFAULT 0)",
                "CREATE INDEX ledger_at ON ledger (at)",
                "CREATE INDEX ledger_from ON ledger (from_player, at)",
                "CREATE INDEX ledger_to ON ledger (to_player, at)",
                "CREATE TABLE IF NOT EXISTS cheque (id CHAR(36) NOT NULL PRIMARY KEY, amount BIGINT NOT NULL, issuer CHAR(36) NOT NULL, "
                        + "issued_at BIGINT NOT NULL, cashed_by CHAR(36), cashed_at BIGINT)"));
    }

    public void init() {
        db.migrate("economy", schema());
        balances.clear();
        for (Object[] r : db.sync(c -> Db.query(c, "SELECT player, balance FROM account", rs -> new Object[]{rs.getString(1), rs.getLong(2)}))) {
            balances.put(UUID.fromString((String) r[0]), (Long) r[1]);
        }
        db.sync(c -> {
            loadOutstanding(c);
            return null;
        });
    }

    private void loadOutstanding(Connection c) throws SQLException {
        Map<UUID, Long> fresh = new HashMap<>();
        for (Object[] r : Db.query(c, "SELECT issuer, SUM(amount) FROM cheque WHERE cashed_by IS NULL GROUP BY issuer",
                rs -> new Object[]{rs.getString(1), rs.getLong(2)})) {
            fresh.put(UUID.fromString((String) r[0]), (Long) r[1]);
        }
        outstanding.clear();
        outstanding.putAll(fresh);
    }

    /** The money in the cheques a player wrote that nobody cashed yet. */
    public long outstanding(UUID issuer) {
        return outstanding.getOrDefault(issuer, 0L);
    }

    // ------------------------------------------------------------------------------------------
    // Balances
    // ------------------------------------------------------------------------------------------

    /** Opens an account on first join with the start money. Returns false if it already existed. */
    public synchronized boolean open(UUID player, long start, long now) {
        if (hasAccount(player)) return false;
        if (start > 0) {
            move(null, player, start, Kind.FAUCET, "start", null, now);
        } else {
            balances.put(player, 0L);
            db.execute(db.increment("account", List.of("player"), "balance"), player.toString(), 0L);
        }
        return true;
    }

    public boolean hasAccount(UUID player) {
        return balances.containsKey(player);
    }

    public long balance(UUID player) {
        return balances.getOrDefault(player, 0L);
    }

    public Map<UUID, Long> balances() {
        return Collections.unmodifiableMap(balances);
    }

    /**
     * Moves money. from = null: it comes from nowhere (faucet / admin), to = null: it goes nowhere (sink).
     * Refused (false) when amount is not positive or 'from' cannot afford it; nothing changes then.
     */
    public synchronized boolean move(UUID from, UUID to, long amount, Kind kind, String reason, String note, long now) {
        if (amount <= 0 || (from == null && to == null) || (from != null && from.equals(to))) return false;
        if (from != null && balance(from) < amount) return false;
        apply(from, to, amount);
        write(from, to, amount, kind, reason, note, now);
        return true;
    }

    /** Admin: set a balance to an exact value (recorded as one ADMIN movement of the difference). */
    public synchronized void set(UUID player, long value, String note, long now) {
        long delta = value - balance(player);
        if (delta == 0) {
            if (!hasAccount(player)) {
                balances.put(player, 0L);
                db.execute(db.increment("account", List.of("player"), "balance"), player.toString(), 0L);
            }
            return;
        }
        if (delta > 0) {
            apply(null, player, delta);
            write(null, player, delta, Kind.ADMIN, "admin_set", note, now);
        } else {
            apply(player, null, -delta);
            write(player, null, -delta, Kind.ADMIN, "admin_set", note, now);
        }
    }

    /**
     * Money held outside every balance (a land 금고) is destroyed: one SINK line in the ledger, no balance changes.
     * (Money put there was recorded as ESCROW, so it is counted as destroyed only now, once.)
     */
    public void burn(long amount, String reason, String note, long now) {
        if (amount <= 0) return;
        write(null, null, amount, Kind.SINK, reason, note, now);
    }

    private void apply(UUID from, UUID to, long amount) {
        if (from != null) balances.merge(from, -amount, Long::sum);
        if (to != null) balances.merge(to, amount, Long::sum);
    }

    private void write(UUID from, UUID to, long amount, Kind kind, String reason, String note, long now) {
        String inc = db.increment("account", List.of("player"), "balance");
        db.async(c -> {
            transaction(c, () -> {
                if (from != null) Db.update(c, inc, from.toString(), -amount);
                if (to != null) Db.update(c, inc, to.toString(), amount);
                Db.update(c, "INSERT INTO ledger (at, from_player, to_player, amount, kind, reason, note, reverted) VALUES (?, ?, ?, ?, ?, ?, ?, 0)",
                        now, from == null ? null : from.toString(), to == null ? null : to.toString(), amount, kind.name(),
                        cut(reason, 64), note == null ? null : cut(note, 255));
            });
            return null;
        });
    }

    private interface SqlRun {
        void run() throws SQLException;
    }

    private static void transaction(Connection c, SqlRun body) throws SQLException {
        boolean auto = c.getAutoCommit();
        c.setAutoCommit(false);
        try {
            body.run();
            c.commit();
        } catch (SQLException e) {
            c.rollback();
            throw e;
        } finally {
            c.setAutoCommit(auto);
        }
    }

    private static String cut(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }

    public List<Map.Entry<UUID, Long>> top(int n) {
        List<Map.Entry<UUID, Long>> all = new ArrayList<>(balances.entrySet());
        all.sort(Map.Entry.<UUID, Long>comparingByValue(Comparator.reverseOrder()));
        return all.subList(0, Math.min(n, all.size()));
    }

    // ------------------------------------------------------------------------------------------
    // Ledger: history, undo, statistics
    // ------------------------------------------------------------------------------------------

    private static Entry entry(java.sql.ResultSet rs) throws SQLException {
        String f = rs.getString(3), t = rs.getString(4);
        return new Entry(rs.getLong(1), rs.getLong(2), f == null ? null : UUID.fromString(f), t == null ? null : UUID.fromString(t),
                rs.getLong(5), Kind.valueOf(rs.getString(6)), rs.getString(7), rs.getString(8), rs.getInt(9) != 0);
    }

    private static final String COLS = "id, at, from_player, to_player, amount, kind, reason, note, reverted";

    public CompletableFuture<List<Entry>> history(UUID player, int limit) {
        return db.async(c -> Db.query(c, "SELECT " + COLS + " FROM ledger WHERE from_player = ? OR to_player = ? ORDER BY id DESC LIMIT " + Math.max(1, limit),
                EconomyStore::entry, player.toString(), player.toString()));
    }

    /**
     * Undoes every movement involving the player since 'since' (newest first): each is reversed for both
     * sides (so a duped amount paid on to a friend comes back out of the friend too) and marked reverted.
     * Balances may go below zero; that is the point of an undo. Returns how many movements were undone.
     */
    public CompletableFuture<Integer> revert(UUID player, long since, long now) {
        return db.async(c -> {
            List<Entry> entries = new ArrayList<>(Db.query(c, "SELECT " + COLS + " FROM ledger WHERE (from_player = ? OR to_player = ?) AND at >= ? AND reverted = 0 AND reason <> 'revert' ORDER BY id DESC",
                    EconomyStore::entry, player.toString(), player.toString(), since));
            // a cheque this player wrote: void it if nobody cashed it yet, otherwise undo the cashing too
            // (whoever holds it) - else the undo would hand the money back while the cheque is still worth it
            for (Entry e : List.copyOf(entries)) {
                if (!e.reason().equals("cheque_issue") || e.note() == null) continue;
                Db.update(c, "UPDATE cheque SET cashed_by = ?, cashed_at = ? WHERE id = ? AND cashed_by IS NULL", VOID.toString(), now, e.note());
                for (Entry cash : Db.query(c, "SELECT " + COLS + " FROM ledger WHERE reason = 'cheque_cash' AND note = ? AND reverted = 0",
                        EconomyStore::entry, e.note())) {
                    if (entries.stream().noneMatch(x -> x.id() == cash.id())) entries.add(cash);
                }
            }
            loadOutstanding(c);   // voided cheques are no longer anybody's
            return entries;
        }).thenApply(entries -> {
            String inc = db.increment("account", List.of("player"), "balance");
            synchronized (this) {
                for (Entry e : entries) {
                    apply(e.to(), e.from(), e.amount());
                }
            }
            db.async(c -> {
                transaction(c, () -> {
                    for (Entry e : entries) {
                        if (e.to() != null) Db.update(c, inc, e.to().toString(), -e.amount());
                        if (e.from() != null) Db.update(c, inc, e.from().toString(), e.amount());
                        Db.update(c, "UPDATE ledger SET reverted = 1 WHERE id = ?", e.id());
                    }
                    if (!entries.isEmpty()) {
                        Db.update(c, "INSERT INTO ledger (at, from_player, to_player, amount, kind, reason, note, reverted) VALUES (?, NULL, ?, 0, 'ADMIN', 'revert', ?, 0)",
                                now, player.toString(), entries.size() + " movements undone since " + since);
                    }
                });
                return null;
            });
            return entries.size();
        });
    }

    /** Totals by kind since a moment (e.g. today 05:00), the money in circulation and its inequality. */
    public CompletableFuture<Stats> stats(long since) {
        return db.async(c -> {
            Map<String, Long> byKind = new HashMap<>();
            for (Object[] r : Db.query(c, "SELECT kind, SUM(amount) FROM ledger WHERE at >= ? AND reverted = 0 GROUP BY kind",
                    rs -> new Object[]{rs.getString(1), rs.getLong(2)}, since)) {
                byKind.put((String) r[0], (Long) r[1]);
            }
            List<Long> values = new ArrayList<>(balances.values());
            long total = values.stream().mapToLong(Long::longValue).sum();
            return new Stats(byKind.getOrDefault("FAUCET", 0L), byKind.getOrDefault("SINK", 0L), byKind.getOrDefault("TRANSFER", 0L),
                    total, values.size(), gini(values));
        });
    }

    /** 0 = everyone has the same, 1 = one person has everything. Negative balances count as 0. */
    public static double gini(List<Long> values) {
        List<Long> v = new ArrayList<>(values.stream().map(x -> Math.max(0L, x)).toList());
        long sum = v.stream().mapToLong(Long::longValue).sum();
        int n = v.size();
        if (n == 0 || sum == 0) return 0;
        Collections.sort(v);
        double acc = 0;
        int i = 1;
        for (long x : v) acc += (double) i++ * x;
        return 2 * acc / (n * (double) sum) - (n + 1.0) / n;
    }

    // ------------------------------------------------------------------------------------------
    // Cheques: money as an item (can be lost, stolen, used to bribe)
    // ------------------------------------------------------------------------------------------

    /** Takes the money and records a cheque; returns its id, or null if the player cannot afford it. */
    public synchronized String issueCheque(UUID player, long amount, long now) {
        String id = UUID.randomUUID().toString();
        if (!move(player, null, amount, Kind.CHEQUE, "cheque_issue", id, now)) return null;
        outstanding.merge(player, amount, Long::sum);
        db.execute("INSERT INTO cheque (id, amount, issuer, issued_at) VALUES (?, ?, ?, ?)", id, amount, player.toString(), now);
        return id;
    }

    /**
     * Marks a cheque cashed, once: the database accepts it only if nobody cashed it before, so a copied
     * item cannot be cashed twice. Completes with its amount (the caller pays it out on the main thread
     * with {@link #move}), or -1 if it was already used or never issued.
     */
    public CompletableFuture<Long> claimCheque(String id, UUID player, long now) {
        return claim(id, player, now).thenApply(Claim::amount);
    }

    /** {@link #claimCheque}, also saying who wrote the cheque. */
    public CompletableFuture<Claim> claim(String id, UUID player, long now) {
        return db.async(c -> {
            int changed = Db.update(c, "UPDATE cheque SET cashed_by = ?, cashed_at = ? WHERE id = ? AND cashed_by IS NULL", player.toString(), now, id);
            if (changed != 1) return new Claim(-1, null);
            Claim claim = Db.query(c, "SELECT amount, issuer FROM cheque WHERE id = ?", rs -> new Claim(rs.getLong(1), UUID.fromString(rs.getString(2))), id).getFirst();
            outstanding.computeIfPresent(claim.issuer(), (k, v) -> v - claim.amount() > 0 ? v - claim.amount() : null);
            return claim;
        });
    }

    // ------------------------------------------------------------------------------------------

    /** Testing: wipes every balance, the ledger and all cheques. */
    public synchronized CompletableFuture<Void> reset() {
        balances.clear();
        outstanding.clear();
        return db.async(c -> {
            transaction(c, () -> {
                Db.update(c, "DELETE FROM account");
                Db.update(c, "DELETE FROM ledger");
                Db.update(c, "DELETE FROM cheque");
            });
            return null;
        });
    }
}
