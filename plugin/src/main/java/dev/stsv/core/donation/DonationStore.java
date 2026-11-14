package dev.stsv.core.donation;

import dev.stsv.core.data.Db;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * The donation currency's books: what each player holds, and every change of it, one line each, for good.
 *
 * <p>This is real money's worth, so nothing is kept in memory as the truth: every change is one transaction in the
 * database (the balance moves only if it stays at or above nothing, and the line is written with the balance it
 * left), and what is shown to a player is read back from it. A purchase is written <b>before</b> anything is handed
 * over, marked not delivered; handing over marks it. So a crash between the two leaves a paid line still to be
 * delivered (the player's next join), never something given and not paid, nor paid and forgotten.
 */
public final class DonationStore {

    /** What a line is. */
    public enum Kind {
        /** An administrator credited a donation. */
        GRANT,
        /** An administrator took currency back (a mistake, a refund of the real money). */
        REVOKE,
        /** The player bought a product. */
        BUY,
        /** A purchase undone by an administrator: the price back. */
        REFUND
    }

    /**
     * One line of the books.
     *
     * @param amount    how the balance changed (a purchase: negative)
     * @param balance   the balance after it
     * @param product   BUY, REFUND: the product's id
     * @param actor     who did it (an administrator's name, "console", the player's own for a purchase)
     * @param delivered BUY: what was bought has been handed over
     */
    public record Entry(long id, UUID player, Kind kind, long amount, long balance, String product, String note, String actor,
                        boolean delivered, long created) {}

    /** How a change went. */
    public enum Outcome { DONE, SHORT, ALREADY }

    /** {@link Outcome#DONE}: the line written; else the balance as it is. */
    public record Result(Outcome outcome, long balance, long entry) {}

    /** A player's account: what they hold, and all they were ever credited and ever spent. */
    public record Account(long balance, long granted, long spent) {
        public static final Account NONE = new Account(0, 0, 0);
    }

    private final Db db;

    public DonationStore(Db db) {
        this.db = db;
    }

    public void init() {
        String id = db.dialect() == Db.Dialect.MYSQL ? "id BIGINT PRIMARY KEY AUTO_INCREMENT" : "id INTEGER PRIMARY KEY AUTOINCREMENT";
        db.migrate("donation", List.of(List.of(
                "CREATE TABLE IF NOT EXISTS stsv_donation_account (player CHAR(36) PRIMARY KEY, name VARCHAR(16) NOT NULL, "
                        + "balance BIGINT NOT NULL, granted BIGINT NOT NULL, spent BIGINT NOT NULL, updated BIGINT NOT NULL)",
                "CREATE TABLE IF NOT EXISTS stsv_donation_log (" + id + ", player CHAR(36) NOT NULL, kind VARCHAR(8) NOT NULL, "
                        + "amount BIGINT NOT NULL, balance BIGINT NOT NULL, product VARCHAR(64), note VARCHAR(200), actor VARCHAR(32), "
                        + "delivered INT NOT NULL, created BIGINT NOT NULL)",
                "CREATE INDEX stsv_donation_log_player ON stsv_donation_log (player, id)")));
    }

    private static Account account(Connection c, UUID player) throws SQLException {
        List<Account> a = Db.query(c, "SELECT balance, granted, spent FROM stsv_donation_account WHERE player = ?",
                rs -> new Account(rs.getLong(1), rs.getLong(2), rs.getLong(3)), player.toString());
        return a.isEmpty() ? Account.NONE : a.getFirst();
    }

    public CompletableFuture<Account> account(UUID player) {
        return db.async(c -> account(c, player));
    }

    private static String cut(String s, int max) {
        return s == null ? null : s.length() <= max ? s : s.substring(0, max);
    }

    /**
     * One change of a balance, all or nothing.
     *
     * @param delta   added to the balance (negative: taken). Refused ({@link Outcome#SHORT}) if the balance would go below nothing
     * @param once    BUY: refused ({@link Outcome#ALREADY}) if the player bought this product before (and it was not refunded)
     */
    public CompletableFuture<Result> change(UUID player, String name, long delta, Kind kind, String product, String note, String actor,
                                            boolean once, long now) {
        return db.async(c -> {
            boolean auto = c.getAutoCommit();
            c.setAutoCommit(false);
            try {
                Result r = change(c, player, name, delta, kind, product, note, actor, once, now);
                if (r.outcome() == Outcome.DONE) c.commit();
                else c.rollback();
                return r;
            } catch (SQLException | RuntimeException e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(auto);
            }
        });
    }

    private static Result change(Connection c, UUID player, String name, long delta, Kind kind, String product, String note, String actor,
                                 boolean once, long now) throws SQLException {
        String id = player.toString();
        if (Db.query(c, "SELECT 1 FROM stsv_donation_account WHERE player = ?", rs -> 1, id).isEmpty()) {
            Db.update(c, "INSERT INTO stsv_donation_account (player, name, balance, granted, spent, updated) VALUES (?, ?, 0, 0, 0, ?)", id, cut(name, 16), now);
        }
        if (once && kind == Kind.BUY && owns(c, player, product)) return new Result(Outcome.ALREADY, account(c, player).balance(), 0);
        long granted = kind == Kind.GRANT ? delta : kind == Kind.REVOKE ? Math.min(0, delta) : 0;
        long spent = kind == Kind.BUY ? -delta : kind == Kind.REFUND ? -delta : 0;
        // the balance moves only if it stays at or above nothing: one statement, so two changes at once cannot both pass
        int moved = Db.update(c, "UPDATE stsv_donation_account SET balance = balance + ?, granted = granted + ?, spent = spent + ?, name = ?, updated = ? "
                + "WHERE player = ? AND balance + ? >= 0", delta, granted, spent, cut(name, 16), now, id, delta);
        long balance = account(c, player).balance();
        if (moved != 1) return new Result(Outcome.SHORT, balance, 0);
        long entry = Db.insert(c, "INSERT INTO stsv_donation_log (player, kind, amount, balance, product, note, actor, delivered, created) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)", id, kind.name(), delta, balance, cut(product, 64), cut(note, 200), cut(actor, 32), kind == Kind.BUY ? 0 : 1, now);
        return new Result(Outcome.DONE, balance, entry);
    }

    /** Has the player bought this product more often than it was refunded? */
    private static boolean owns(Connection c, UUID player, String product) throws SQLException {
        long bought = Db.query(c, "SELECT COUNT(*) FROM stsv_donation_log WHERE player = ? AND kind = ? AND product = ?", rs -> rs.getLong(1),
                player.toString(), Kind.BUY.name(), product).getFirst();
        long refunded = Db.query(c, "SELECT COUNT(*) FROM stsv_donation_log WHERE player = ? AND kind = ? AND product = ?", rs -> rs.getLong(1),
                player.toString(), Kind.REFUND.name(), product).getFirst();
        return bought > refunded;
    }

    private static Entry entry(java.sql.ResultSet rs) throws SQLException {
        return new Entry(rs.getLong(1), UUID.fromString(rs.getString(2)), Kind.valueOf(rs.getString(3)), rs.getLong(4), rs.getLong(5), rs.getString(6),
                rs.getString(7), rs.getString(8), rs.getInt(9) != 0, rs.getLong(10));
    }

    private static final String COLS = "id, player, kind, amount, balance, product, note, actor, delivered, created";

    /** A player's newest lines first. */
    public CompletableFuture<List<Entry>> history(UUID player, int limit) {
        return db.async(c -> Db.query(c, "SELECT " + COLS + " FROM stsv_donation_log WHERE player = ? ORDER BY id DESC LIMIT " + Math.clamp(limit, 1, 200),
                DonationStore::entry, player.toString()));
    }

    /** A player's purchases that were paid and not yet handed over, oldest first. */
    public CompletableFuture<List<Entry>> undelivered(UUID player) {
        return db.async(c -> Db.query(c, "SELECT " + COLS + " FROM stsv_donation_log WHERE player = ? AND kind = ? AND delivered = 0 ORDER BY id",
                DonationStore::entry, player.toString(), Kind.BUY.name()));
    }

    /** The products a player owns (bought more often than refunded). */
    public CompletableFuture<java.util.Set<String>> owned(UUID player) {
        return db.async(c -> {
            java.util.Map<String, Integer> n = new java.util.HashMap<>();
            for (Object[] row : Db.query(c, "SELECT product, kind FROM stsv_donation_log WHERE player = ? AND product IS NOT NULL AND kind IN (?, ?)",
                    rs -> new Object[]{rs.getString(1), rs.getString(2)}, player.toString(), Kind.BUY.name(), Kind.REFUND.name())) {
                n.merge((String) row[0], row[1].equals(Kind.BUY.name()) ? 1 : -1, Integer::sum);
            }
            java.util.Set<String> out = new java.util.HashSet<>();
            n.forEach((product, count) -> {
                if (count > 0) out.add(product);
            });
            return out;
        });
    }

    /**
     * How undoing a purchase went.
     *
     * @param entry      the purchase (null: no such purchase)
     * @param result     the price back ({@link Outcome#DONE}), or {@link Outcome#ALREADY} when it was undone before
     * @param wasPending it had not been handed over yet (and now never will be)
     */
    public record Refunded(Entry entry, Result result, boolean wasPending) {}

    /**
     * Undoes one purchase: its price back to the buyer, once. A purchase not yet handed over is closed in the same
     * step, so it cannot be handed over after its price went back.
     */
    public CompletableFuture<Refunded> refund(long purchase, String note, String actor, long now) {
        return db.async(c -> {
            boolean auto = c.getAutoCommit();
            c.setAutoCommit(false);
            try {
                List<Entry> found = Db.query(c, "SELECT " + COLS + " FROM stsv_donation_log WHERE id = ?", DonationStore::entry, purchase);
                if (found.isEmpty() || found.getFirst().kind() != Kind.BUY) {
                    c.rollback();
                    return new Refunded(null, null, false);
                }
                Entry e = found.getFirst();
                String mark = "#" + e.id();
                boolean before = !Db.query(c, "SELECT 1 FROM stsv_donation_log WHERE player = ? AND kind = ? AND (note = ? OR note LIKE ?)", rs -> 1,
                        e.player().toString(), Kind.REFUND.name(), mark, mark + " %").isEmpty();
                if (before) {
                    c.rollback();
                    return new Refunded(e, new Result(Outcome.ALREADY, account(c, e.player()).balance(), 0), false);
                }
                boolean pending = Db.update(c, "UPDATE stsv_donation_log SET delivered = 1 WHERE id = ? AND delivered = 0", e.id()) == 1;
                List<String> name = Db.query(c, "SELECT name FROM stsv_donation_account WHERE player = ?", rs -> rs.getString(1), e.player().toString());
                Result r = change(c, e.player(), name.isEmpty() ? "?" : name.getFirst(), -e.amount(), Kind.REFUND, e.product(),
                        note == null || note.isBlank() ? mark : mark + " " + note, actor, false, now);
                c.commit();
                return new Refunded(e, r, pending);
            } catch (SQLException | RuntimeException e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(auto);
            }
        });
    }

    /** Marks a purchase handed over; false if it already was (nothing is handed over twice). */
    public CompletableFuture<Boolean> delivered(long entry) {
        return db.async(c -> Db.update(c, "UPDATE stsv_donation_log SET delivered = 1 WHERE id = ? AND delivered = 0", entry) == 1);
    }

    public CompletableFuture<Entry> entry(long id) {
        return db.async(c -> {
            List<Entry> e = Db.query(c, "SELECT " + COLS + " FROM stsv_donation_log WHERE id = ?", DonationStore::entry, id);
            return e.isEmpty() ? null : e.getFirst();
        });
    }

    /** All the currency ever credited and ever spent, and how many players hold any (a report). */
    public CompletableFuture<long[]> totals() {
        return db.async(c -> Db.query(c, "SELECT COALESCE(SUM(granted), 0), COALESCE(SUM(spent), 0), COALESCE(SUM(balance), 0), COUNT(*) FROM stsv_donation_account",
                rs -> new long[]{rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getLong(4)}).getFirst());
    }
}
