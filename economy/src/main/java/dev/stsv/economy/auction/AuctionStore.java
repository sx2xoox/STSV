package dev.stsv.economy.auction;

import dev.stsv.core.data.Db;
import dev.stsv.economy.mail.MailStore;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/** Auctions in the database; running ones also in memory (bids are decided on the main thread). */
public final class AuctionStore {

    public static final int RUNNING = 0, SOLD = 1, UNSOLD = 2, CANCELLED = 3;

    private final Db db;
    private final Map<Long, Auction> running = new ConcurrentHashMap<>();

    public AuctionStore(Db db) {
        this.db = db;
    }

    public void init() {
        db.migrate("eco_auction", List.of(List.of(
                "CREATE TABLE IF NOT EXISTS eco_auction (" + MailStore.autoId(db) + ", seller CHAR(36), creator CHAR(36), item " + MailStore.blob(db) + " NOT NULL, "
                        + "item_name VARCHAR(128) NOT NULL, amount INT NOT NULL, start_price BIGINT NOT NULL, deposit BIGINT NOT NULL, rare INT NOT NULL, "
                        + "week INT NOT NULL, bid BIGINT NOT NULL, bidder CHAR(36), ends BIGINT NOT NULL, bids INT NOT NULL, state INT NOT NULL, "
                        + "created BIGINT NOT NULL, notified INT NOT NULL DEFAULT 0)",
                "CREATE INDEX eco_auction_state ON eco_auction (state)")));
        running.clear();
        for (Auction a : db.sync(c -> Db.query(c, "SELECT id, seller, creator, item, item_name, amount, start_price, deposit, rare, week, bid, bidder, ends, bids "
                + "FROM eco_auction WHERE state = 0", rs -> new Auction(rs.getLong(1), uuid(rs.getString(2)), uuid(rs.getString(3)), rs.getBytes(4),
                rs.getString(5), rs.getInt(6), rs.getLong(7), rs.getLong(8), rs.getInt(9) != 0, rs.getInt(10), rs.getLong(11), uuid(rs.getString(12)),
                rs.getLong(13), rs.getInt(14))))) {
            running.put(a.id, a);
        }
    }

    private static UUID uuid(String s) {
        return s == null ? null : UUID.fromString(s);
    }

    private static String str(UUID u) {
        return u == null ? null : u.toString();
    }

    public Collection<Auction> running() {
        List<Auction> out = new ArrayList<>(running.values());
        out.sort(java.util.Comparator.comparingLong(a -> a.ends));
        return out;
    }

    public Auction get(long id) {
        return running.get(id);
    }

    /** Running auctions put up by players (server auctions do not take a player slot). */
    public int playerRunning() {
        return (int) running.values().stream().filter(a -> a.seller != null).count();
    }

    public Auction create(UUID seller, UUID creator, byte[] item, String name, int amount, long start, long deposit, boolean rare, int week, long ends, long now) {
        String n = name.length() > 128 ? name.substring(0, 128) : name;
        long id = db.sync(c -> {
            Db.update(c, "INSERT INTO eco_auction (seller, creator, item, item_name, amount, start_price, deposit, rare, week, bid, bidder, ends, bids, state, created) "
                    + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 0, NULL, ?, 0, 0, ?)", str(seller), str(creator), item, n, amount, start, deposit, rare ? 1 : 0, week, ends, now);
            return Db.query(c, "SELECT MAX(id) FROM eco_auction", rs -> rs.getLong(1)).getFirst();
        });
        Auction a = new Auction(id, seller, creator, item, n, amount, start, deposit, rare, week, 0, null, ends, 0);
        running.put(id, a);
        return a;
    }

    public void saveBid(Auction a) {
        db.execute("UPDATE eco_auction SET bid = ?, bidder = ?, ends = ?, bids = ? WHERE id = ?", a.bid, str(a.bidder), a.ends, a.bids, a.id);
    }

    public void finish(Auction a, int state) {
        running.remove(a.id);
        db.execute("UPDATE eco_auction SET state = ?, bid = ?, bidder = ?, ends = ? WHERE id = ?", state, a.bid, str(a.bidder), a.ends, a.id);
    }

    /** Server auctions a player won this week (the rare-item limit). */
    public CompletableFuture<Integer> rareWins(UUID player, int week) {
        return db.async(c -> Db.query(c, "SELECT COUNT(*) FROM eco_auction WHERE bidder = ? AND rare = 1 AND state = 1 AND week = ?",
                rs -> rs.getInt(1), player.toString(), week).getFirst());
    }

    public int rareWinsSync(UUID player, int week) {
        return db.sync(c -> Db.query(c, "SELECT COUNT(*) FROM eco_auction WHERE bidder = ? AND rare = 1 AND state = 1 AND week = ?",
                rs -> rs.getInt(1), player.toString(), week).getFirst());
    }

    public CompletableFuture<Void> reset() {
        running.clear();
        return db.execute("DELETE FROM eco_auction");
    }
}
