package dev.stsv.economy;

import dev.stsv.core.data.Db;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Which player trades count toward achievements and missions (market_sold, auctions_won), so they cannot be farmed by two
 * friends (or two accounts) selling each other dirt for 1 G over and over. A trade counts only
 * <ul>
 *   <li>at a meaningful price: at least credit.min-price (100 G) and at least credit.shop-ratio x what the shop pays for the
 *       same goods (base price x amount; items the shop does not buy only need the fixed floor), and</li>
 *   <li>once per pair per credit.pair-hours (24 h): the same seller and buyer (or winner and seller) count once a day, however
 *       often they trade. Kept in the database, so a restart does not open the window again.</li>
 * </ul>
 * The trade itself always happens; only the count is withheld. Server auctions (no seller) always count: they are run by the
 * server and limited per week anyway.
 */
public final class TradeCredit {

    public static final String MARKET = "market", AUCTION = "auction";

    private final Db db;
    /** kind|player|partner -> when it last counted */
    private final Map<String, Long> last = new ConcurrentHashMap<>();

    public TradeCredit(Db db) {
        this.db = db;
    }

    public void init() {
        db.migrate("eco_trade_credit", List.of(List.of(
                "CREATE TABLE IF NOT EXISTS eco_trade_credit (k VARCHAR(100) NOT NULL PRIMARY KEY, at BIGINT NOT NULL)")));
        last.clear();
        for (Object[] r : db.sync(c -> Db.query(c, "SELECT k, at FROM eco_trade_credit", rs -> new Object[]{rs.getString(1), rs.getLong(2)}))) {
            last.put((String) r[0], (Long) r[1]);
        }
    }

    /** The least price that counts: the fixed floor, or the shop's value of the goods times the ratio if that is more. */
    public static long floor(double shopValue, long minPrice, double shopRatio) {
        long byShop = shopValue > 0 && shopRatio > 0 ? (long) Math.ceil(shopValue * shopRatio - 1e-9) : 0;
        return Math.max(Math.max(0, minPrice), byShop);
    }

    /** Is this price meaningful for goods the shop values at shopValue (0 = the shop does not buy them)? */
    public static boolean meaningful(long price, double shopValue, long minPrice, double shopRatio) {
        return price >= floor(shopValue, minPrice, shopRatio);
    }

    /** Pure: may this pair count again now, having last counted at lastAt (null = never)? */
    public static boolean windowOpen(Long lastAt, long now, double pairHours) {
        return lastAt == null || pairHours <= 0 || now - lastAt >= (long) (pairHours * 3_600_000L);
    }

    private static String key(String kind, UUID player, UUID partner) {
        return kind + "|" + player + "|" + partner;
    }

    /**
     * Decides whether a finished trade counts for player (the seller on the 거래소, the winner of an auction) with partner (the
     * buyer, the auction's seller; null = the server), and remembers it when it does. Main thread.
     */
    public boolean credit(String kind, UUID player, UUID partner, long price, double shopValue, TradeRules rules, long now) {
        if (partner == null) return true;
        if (player.equals(partner)) return false;
        if (!meaningful(price, shopValue, rules.creditMinPrice(), rules.creditShopRatio())) return false;
        String k = key(kind, player, partner);
        if (!windowOpen(last.get(k), now, rules.creditPairHours())) return false;
        last.put(k, now);
        db.execute(db.upsert("eco_trade_credit", List.of("k"), List.of("at")), k, now);
        return true;
    }

    public java.util.concurrent.CompletableFuture<Void> reset() {
        last.clear();
        return db.execute("DELETE FROM eco_trade_credit");
    }
}
