package dev.stsv.economy.market;

import dev.stsv.core.data.Db;
import dev.stsv.economy.mail.MailStore;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 거래소 구매 주문. Open orders are kept in memory for the menu. A fill is claimed in the database with one conditional update
 * (only while open, never by the buyer, never past the amount, not once expired), and the buyer's mail and the fill's record are
 * written in the same transaction: two sellers at once, even on two servers, can never take more than the order holds. Money
 * moves only after that commit; a fill or a closed order whose money did not move yet ({@code paid} / {@code refunded} = 0) is
 * settled at the next start.
 */
public final class BuyOrderStore {

    public static final int OPEN = 0, FILLED = 1, CANCELLED = 2, EXPIRED = 3;
    /** Mail reasons: what a buy order bought, and a seller's items that could not be handed over after all. */
    public static final String MAIL = "buy_order", MAIL_RETURN = "buy_order_return";

    public record Order(long id, UUID buyer, String itemKey, String itemName, int amount, int filled, long price, long created, long expires) {
        public int left() {
            return amount - filled;
        }

        /** What it still holds: (amount - filled) x price. */
        public long escrow() {
            return (long) left() * price;
        }
    }

    /** One fill: n of the order's item from seller, gross = n x price taken from the escrow, fee kept back from the seller. */
    public record Fill(long id, long order, UUID buyer, UUID seller, String itemKey, int amount, long gross, long fee, long at) {}

    /** An order taken off (cancelled, expired): refund = the money it still held. */
    public record Closed(Order order, int state, long refund) {}

    /** What a buyer missed while away. */
    public record Untold(List<Fill> fills, List<Order> expired) {}

    private static final String COLS = "id, buyer, item_key, item_name, amount, filled, price, created, expires";
    private static final String FILL_COLS = "id, order_id, buyer, seller, item_key, amount, gross, fee, at";

    private final Db db;
    private final Map<Long, Order> active = new ConcurrentHashMap<>();

    public BuyOrderStore(Db db) {
        this.db = db;
    }

    public void init() {
        db.migrate("eco_buy_order", List.of(List.of(
                "CREATE TABLE IF NOT EXISTS eco_buy_order (" + MailStore.autoId(db) + ", buyer CHAR(36) NOT NULL, item_key VARCHAR(128) NOT NULL, "
                        + "item_name VARCHAR(128) NOT NULL, amount INT NOT NULL, filled INT NOT NULL DEFAULT 0, price BIGINT NOT NULL, "
                        + "escrow BIGINT NOT NULL, created BIGINT NOT NULL, expires BIGINT NOT NULL, state INT NOT NULL, closed BIGINT, "
                        + "refunded INT NOT NULL DEFAULT 0, notified INT NOT NULL DEFAULT 0)",
                "CREATE INDEX eco_buy_order_state ON eco_buy_order (state)",
                "CREATE INDEX eco_buy_order_buyer ON eco_buy_order (buyer, state)",
                "CREATE TABLE IF NOT EXISTS eco_buy_fill (" + MailStore.autoId(db) + ", order_id BIGINT NOT NULL, buyer CHAR(36) NOT NULL, "
                        + "seller CHAR(36) NOT NULL, item_key VARCHAR(128) NOT NULL, amount INT NOT NULL, gross BIGINT NOT NULL, fee BIGINT NOT NULL, "
                        + "at BIGINT NOT NULL, paid INT NOT NULL DEFAULT 0, notified INT NOT NULL DEFAULT 0)",
                "CREATE INDEX eco_buy_fill_order ON eco_buy_fill (order_id)",
                "CREATE INDEX eco_buy_fill_buyer ON eco_buy_fill (buyer, notified)",
                "CREATE INDEX eco_buy_fill_paid ON eco_buy_fill (paid)")));
        active.clear();
        for (Order o : db.sync(c -> Db.query(c, "SELECT " + COLS + " FROM eco_buy_order WHERE state = 0", BuyOrderStore::row))) {
            active.put(o.id(), o);
        }
    }

    private static Order row(java.sql.ResultSet rs) throws SQLException {
        return new Order(rs.getLong(1), UUID.fromString(rs.getString(2)), rs.getString(3), rs.getString(4), rs.getInt(5), rs.getInt(6),
                rs.getLong(7), rs.getLong(8), rs.getLong(9));
    }

    private static Fill fillRow(java.sql.ResultSet rs) throws SQLException {
        return new Fill(rs.getLong(1), rs.getLong(2), UUID.fromString(rs.getString(3)), UUID.fromString(rs.getString(4)), rs.getString(5),
                rs.getInt(6), rs.getLong(7), rs.getLong(8), rs.getLong(9));
    }

    @FunctionalInterface
    private interface Body<T> {
        T run() throws SQLException;
    }

    /** Runs work on c in one transaction: all of it or nothing. */
    private static <T> T tx(Connection c, Body<T> work) throws SQLException {
        boolean auto = c.getAutoCommit();
        c.setAutoCommit(false);
        try {
            T out = work.run();
            c.commit();
            return out;
        } catch (SQLException | RuntimeException e) {
            c.rollback();
            throw e;
        } finally {
            c.setAutoCommit(auto);
        }
    }

    // ------------------------------------------------------------------------------------------
    // Reading (memory)
    // ------------------------------------------------------------------------------------------

    /** Grouped by item, the best (highest) unit price first, then the oldest. */
    public List<Order> active() {
        List<Order> out = new ArrayList<>(active.values());
        out.sort(Comparator.comparing(Order::itemKey).thenComparing(Comparator.comparingLong(Order::price).reversed()).thenComparingLong(Order::id));
        return out;
    }

    public Order get(long id) {
        return active.get(id);
    }

    public int count(UUID buyer) {
        return (int) active.values().stream().filter(o -> o.buyer().equals(buyer)).count();
    }

    /** The money this player's open orders hold (part of their net worth). */
    public long escrowOf(UUID buyer) {
        return active.values().stream().filter(o -> o.buyer().equals(buyer)).mapToLong(Order::escrow).sum();
    }

    /** The best unit price anyone offers for an item now (0: no order). */
    public long best(String itemKey) {
        return active.values().stream().filter(o -> o.itemKey().equals(itemKey)).mapToLong(Order::price).max().orElse(0);
    }

    public List<Order> expired(long now) {
        return active.values().stream().filter(o -> o.expires() <= now).toList();
    }

    // ------------------------------------------------------------------------------------------
    // Writing
    // ------------------------------------------------------------------------------------------

    /** A new open order holding amount x price (the money was taken just before). Blocking, like a 거래소 listing. */
    public Order add(UUID buyer, String key, String name, int amount, long price, long now, long expires) {
        String n = name.length() > 128 ? name.substring(0, 128) : name;
        long escrow = BuyOrders.escrow(amount, price);
        long id = db.sync(c -> Db.insert(c, "INSERT INTO eco_buy_order (buyer, item_key, item_name, amount, filled, price, escrow, created, expires, state) "
                + "VALUES (?, ?, ?, ?, 0, ?, ?, ?, ?, 0)", buyer.toString(), key, n, amount, price, escrow, now, expires));
        Order o = new Order(id, buyer, key, n, amount, 0, price, now, expires);
        active.put(id, o);
        return o;
    }

    /**
     * Claims n of an order for seller and, in the same transaction, records the fill and mails items (each a stack's bytes) to
     * the buyer. Null when refused: the order is gone, closed, expired, the seller is its buyer, or fewer than n are left. Does
     * not touch memory (see {@link #applied}) or money.
     */
    public CompletableFuture<Fill> fill(long id, UUID seller, int n, long fee, List<byte[]> items, long now) {
        if (n <= 0) return CompletableFuture.completedFuture(null);
        return db.async(c -> tx(c, () -> {
            // every SET expression reads filled before it changes (MySQL assigns left to right): filled is set last
            int rows = Db.update(c, "UPDATE eco_buy_order SET state = CASE WHEN filled + ? >= amount THEN 1 ELSE 0 END, "
                            + "closed = CASE WHEN filled + ? >= amount THEN ? ELSE closed END, escrow = escrow - price * ?, filled = filled + ? "
                            + "WHERE id = ? AND state = 0 AND buyer <> ? AND filled + ? <= amount AND expires > ?",
                    n, n, now, n, n, id, seller.toString(), n, now);
            if (rows != 1) return null;
            Order o = Db.query(c, "SELECT " + COLS + " FROM eco_buy_order WHERE id = ?", BuyOrderStore::row, id).getFirst();
            long gross = BuyOrders.escrow(n, o.price());
            long fid = Db.insert(c, "INSERT INTO eco_buy_fill (order_id, buyer, seller, item_key, amount, gross, fee, at, paid, notified) "
                    + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, 0, 0)", id, o.buyer().toString(), seller.toString(), o.itemKey(), n, gross, fee, now);
            for (byte[] item : items) MailStore.send(c, o.buyer(), item, MAIL, now);
            return new Fill(fid, id, o.buyer(), seller, o.itemKey(), n, gross, Math.min(fee, gross), now);
        }));
    }

    /** A committed fill, in memory (main thread): the order shows n fewer, and leaves the menu once full. */
    public void applied(long id, int n) {
        active.computeIfPresent(id, (k, o) -> o.filled() + n >= o.amount() ? null
                : new Order(o.id(), o.buyer(), o.itemKey(), o.itemName(), o.amount(), o.filled() + n, o.price(), o.created(), o.expires()));
    }

    /** The seller of a fill was paid. */
    public CompletableFuture<Void> paid(long fillId) {
        return db.execute("UPDATE eco_buy_fill SET paid = 1 WHERE id = ?", fillId);
    }

    /** Fills whose money did not move (the server stopped in between): paid at start. */
    public List<Fill> unpaid() {
        return db.sync(c -> Db.query(c, "SELECT " + FILL_COLS + " FROM eco_buy_fill WHERE paid = 0 ORDER BY id", BuyOrderStore::fillRow));
    }

    /**
     * Takes an open order off (CANCELLED, EXPIRED) and reads what it still held, in one transaction: once closed no fill can take
     * from it, so the refund is exact. Null for everyone but the first caller. The order leaves the menu at once.
     */
    public CompletableFuture<Closed> close(long id, int state, long now) {
        active.remove(id);
        return db.async(c -> tx(c, () -> {
            if (Db.update(c, "UPDATE eco_buy_order SET state = ?, closed = ? WHERE id = ? AND state = 0", state, now, id) != 1) return null;
            return Db.query(c, "SELECT " + COLS + ", escrow FROM eco_buy_order WHERE id = ?",
                    rs -> new Closed(row(rs), state, rs.getLong(10)), id).getFirst();
        }));
    }

    /** The money of a closed order went back to its buyer. */
    public CompletableFuture<Void> refunded(long id) {
        return db.execute("UPDATE eco_buy_order SET refunded = 1 WHERE id = ?", id);
    }

    /** Closed orders whose money did not go back yet (the server stopped in between): refunded at start. */
    public List<Closed> unrefunded() {
        return db.sync(c -> {
            List<Closed> out = new ArrayList<>();
            for (Object[] r : Db.query(c, "SELECT " + COLS + ", state, escrow FROM eco_buy_order WHERE state <> 0 AND refunded = 0 AND escrow > 0 ORDER BY id",
                    rs -> new Object[]{row(rs), rs.getInt(10), rs.getLong(11)})) {
                out.add(new Closed((Order) r[0], (Integer) r[1], (Long) r[2]));
            }
            return out;
        });
    }

    /** Fills and expiries the buyer has not heard about (they were away); marks them told. */
    public CompletableFuture<Untold> untold(UUID buyer) {
        return db.async(c -> {
            List<Fill> fills = Db.query(c, "SELECT " + FILL_COLS + " FROM eco_buy_fill WHERE buyer = ? AND notified = 0 ORDER BY id",
                    BuyOrderStore::fillRow, buyer.toString());
            List<Order> expired = Db.query(c, "SELECT " + COLS + " FROM eco_buy_order WHERE buyer = ? AND state = 3 AND notified = 0 ORDER BY id",
                    BuyOrderStore::row, buyer.toString());
            Db.update(c, "UPDATE eco_buy_fill SET notified = 1 WHERE buyer = ? AND notified = 0", buyer.toString());
            Db.update(c, "UPDATE eco_buy_order SET notified = 1 WHERE buyer = ? AND state = 3 AND notified = 0", buyer.toString());
            return new Untold(fills, expired);
        });
    }

    /** The buyer saw this fill happen. */
    public void told(long fillId) {
        db.execute("UPDATE eco_buy_fill SET notified = 1 WHERE id = ?", fillId);
    }

    /** The buyer saw this order expire. */
    public void toldExpired(long id) {
        db.execute("UPDATE eco_buy_order SET notified = 1 WHERE id = ?", id);
    }

    /** Everything a fill of this order put in the record (for the admins and the tests). */
    public CompletableFuture<List<Fill>> fills(long orderId) {
        return db.async(c -> Db.query(c, "SELECT " + FILL_COLS + " FROM eco_buy_fill WHERE order_id = ? ORDER BY id", BuyOrderStore::fillRow, orderId));
    }

    /** The stored state of an order: filled, escrow, state (for the tests and the admins). */
    public CompletableFuture<long[]> stored(long id) {
        return db.async(c -> Db.query(c, "SELECT filled, escrow, state FROM eco_buy_order WHERE id = ?",
                rs -> new long[]{rs.getLong(1), rs.getLong(2), rs.getLong(3)}, id).stream().findFirst().orElse(null));
    }

    public CompletableFuture<Void> reset() {
        active.clear();
        return db.async(c -> {
            Db.update(c, "DELETE FROM eco_buy_fill");
            Db.update(c, "DELETE FROM eco_buy_order");
            return null;
        });
    }
}
