package dev.stsv.economy.market;

import dev.stsv.core.data.Db;
import dev.stsv.economy.mail.MailStore;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 거래소 listings. Active ones are kept in memory for the menu; a sale is claimed in the database with a
 * conditional update (only one buyer can ever get a listing, even with two clicks or two servers).
 */
public final class MarketStore {

    public static final int ACTIVE = 0, SOLD = 1, CANCELLED = 2, EXPIRED = 3;

    public record Listing(long id, UUID seller, byte[] item, String itemKey, String itemName, int amount, long price, long created, long expires) {
        public double unit() {
            return (double) price / Math.max(1, amount);
        }
    }

    public record Sold(long id, String itemName, int amount, long price, UUID buyer) {}

    private final Db db;
    private final Map<Long, Listing> active = new ConcurrentHashMap<>();

    public MarketStore(Db db) {
        this.db = db;
    }

    public void init() {
        db.migrate("eco_market", List.of(List.of(
                "CREATE TABLE IF NOT EXISTS eco_listing (" + MailStore.autoId(db) + ", seller CHAR(36) NOT NULL, item " + MailStore.blob(db) + " NOT NULL, "
                        + "item_key VARCHAR(128) NOT NULL, item_name VARCHAR(128) NOT NULL, amount INT NOT NULL, price BIGINT NOT NULL, "
                        + "created BIGINT NOT NULL, expires BIGINT NOT NULL, state INT NOT NULL, buyer CHAR(36), closed BIGINT, notified INT NOT NULL DEFAULT 0)",
                "CREATE INDEX eco_listing_state ON eco_listing (state)",
                "CREATE INDEX eco_listing_seller ON eco_listing (seller, state)")));
        active.clear();
        for (Listing l : db.sync(c -> Db.query(c, "SELECT id, seller, item, item_key, item_name, amount, price, created, expires FROM eco_listing WHERE state = 0",
                MarketStore::row))) {
            active.put(l.id(), l);
        }
    }

    private static Listing row(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Listing(rs.getLong(1), UUID.fromString(rs.getString(2)), rs.getBytes(3), rs.getString(4), rs.getString(5),
                rs.getInt(6), rs.getLong(7), rs.getLong(8), rs.getLong(9));
    }

    /** Cheapest per unit first, grouped by item. */
    public List<Listing> active() {
        List<Listing> out = new ArrayList<>(active.values());
        out.sort(Comparator.comparing(Listing::itemKey).thenComparingDouble(Listing::unit).thenComparingLong(Listing::id));
        return out;
    }

    public Listing get(long id) {
        return active.get(id);
    }

    public int count(UUID seller) {
        return (int) active.values().stream().filter(l -> l.seller().equals(seller)).count();
    }

    public Listing add(UUID seller, byte[] item, String key, String name, int amount, long price, long now, long expires) {
        long id = db.sync(c -> {
            return Db.insert(c, "INSERT INTO eco_listing (seller, item, item_key, item_name, amount, price, created, expires, state) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 0)",
                    seller.toString(), item, key, name.length() > 128 ? name.substring(0, 128) : name, amount, price, now, expires);
        });
        Listing l = new Listing(id, seller, item, key, name, amount, price, now, expires);
        active.put(id, l);
        return l;
    }

    /**
     * Takes a listing out of the market for one reason (sold to buyer, cancelled, expired). True only for
     * the first caller; the listing leaves the menu at once.
     */
    public CompletableFuture<Boolean> close(long id, int state, UUID buyer, long now) {
        active.remove(id);
        return db.async(c -> Db.update(c, "UPDATE eco_listing SET state = ?, buyer = ?, closed = ? WHERE id = ? AND state = 0",
                state, buyer == null ? null : buyer.toString(), now, id) == 1);
    }

    /** Puts a claimed listing back (the buyer could not pay after all). */
    public CompletableFuture<Void> reopen(Listing l) {
        active.put(l.id(), l);
        return db.execute("UPDATE eco_listing SET state = 0, buyer = NULL, closed = NULL WHERE id = ?", l.id());
    }

    public List<Listing> expired(long now) {
        return active.values().stream().filter(l -> l.expires() <= now).toList();
    }

    /** Sales the seller has not heard about yet (they were offline); marks them told. */
    public CompletableFuture<List<Sold>> untold(UUID seller) {
        return db.async(c -> {
            List<Sold> out = Db.query(c, "SELECT id, item_name, amount, price, buyer FROM eco_listing WHERE seller = ? AND state = 1 AND notified = 0",
                    rs -> new Sold(rs.getLong(1), rs.getString(2), rs.getInt(3), rs.getLong(4), UUID.fromString(rs.getString(5))), seller.toString());
            Db.update(c, "UPDATE eco_listing SET notified = 1 WHERE seller = ? AND state = 1", seller.toString());
            return out;
        });
    }

    public void told(long id) {
        db.execute("UPDATE eco_listing SET notified = 1 WHERE id = ?", id);
    }

    public CompletableFuture<Void> reset() {
        active.clear();
        return db.execute("DELETE FROM eco_listing");
    }
}
