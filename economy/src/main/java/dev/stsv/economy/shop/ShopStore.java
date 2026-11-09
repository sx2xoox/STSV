package dev.stsv.economy.shop;

import dev.stsv.core.data.Db;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What each player sold to the admin shop: S per player per item (their own price curve: recovers through the day, shrinks
 * at the daily reset, dropped once it is under half a piece, so only players who sold lately are kept), the count per player
 * per day (for caps), and hourly market-price snapshots (for the 24h change).
 */
public final class ShopStore {

    public record Snapshot(long at, double price) {}

    /** An S under this is gone (the price is back to the market price within a hair): not kept in memory or the table. */
    static final double FORGET = 0.5;

    private final Db db;
    private final Map<UUID, Map<String, Double>> sold = new ConcurrentHashMap<>();   // player -> item -> S
    private final Map<String, Integer> playerDay = new ConcurrentHashMap<>();   // player|item -> count today
    private final Map<String, List<Snapshot>> history = new ConcurrentHashMap<>();

    public ShopStore(Db db) {
        this.db = db;
    }

    public void init() {
        db.migrate("eco_shop", List.of(
                List.of("CREATE TABLE IF NOT EXISTS eco_shop_sold (item VARCHAR(128) NOT NULL PRIMARY KEY, sold DOUBLE NOT NULL)",
                        "CREATE TABLE IF NOT EXISTS eco_shop_player (player CHAR(36) NOT NULL, item VARCHAR(128) NOT NULL, amount INT NOT NULL, "
                                + "PRIMARY KEY (player, item))",
                        "CREATE TABLE IF NOT EXISTS eco_price (item VARCHAR(128) NOT NULL, at BIGINT NOT NULL, price DOUBLE NOT NULL, PRIMARY KEY (item, at))"),
                // prices per player: each player's own S. Today's counts are the best guess of it; the server-wide S and the
                // snapshots of the shared price mean nothing any more (the 24h change would compare against them)
                List.of("CREATE TABLE IF NOT EXISTS eco_shop_psold (player CHAR(36) NOT NULL, item VARCHAR(128) NOT NULL, sold DOUBLE NOT NULL, "
                                + "PRIMARY KEY (player, item))",
                        "INSERT INTO eco_shop_psold (player, item, sold) SELECT player, item, amount FROM eco_shop_player",
                        "DROP TABLE IF EXISTS eco_shop_sold",
                        "DELETE FROM eco_price")));
        sold.clear();
        playerDay.clear();
        history.clear();
        db.sync(c -> {
            for (Object[] r : Db.query(c, "SELECT player, item, sold FROM eco_shop_psold", rs -> new Object[]{rs.getString(1), rs.getString(2), rs.getDouble(3)})) {
                UUID player;
                try {
                    player = UUID.fromString((String) r[0]);
                } catch (IllegalArgumentException e) {
                    continue;
                }
                if ((Double) r[2] >= FORGET) sold.computeIfAbsent(player, k -> new ConcurrentHashMap<>()).put((String) r[1], (Double) r[2]);
            }
            for (Object[] r : Db.query(c, "SELECT player, item, amount FROM eco_shop_player", rs -> new Object[]{rs.getString(1), rs.getString(2), rs.getInt(3)})) {
                playerDay.put(r[0] + "|" + r[1], (Integer) r[2]);
            }
            long since = System.currentTimeMillis() - KEEP;
            for (Object[] r : Db.query(c, "SELECT item, at, price FROM eco_price WHERE at >= ? ORDER BY at", rs -> new Object[]{rs.getString(1), rs.getLong(2), rs.getDouble(3)}, since)) {
                history.computeIfAbsent((String) r[0], k -> new ArrayList<>()).add(new Snapshot((Long) r[1], (Double) r[2]));
            }
            return null;
        });
    }

    /** This player's S of the item: what their price curve counts (0 for nobody, or nothing sold lately). */
    public double sold(UUID player, String item) {
        if (player == null) return 0;
        Map<String, Double> m = sold.get(player);
        return m == null ? 0 : m.getOrDefault(item, 0.0);
    }

    /** Everybody's S of the item added up: what the server sold of it lately (the 시장 폭락 picks the biggest). */
    public double soldTotal(String item) {
        double sum = 0;
        for (Map<String, Double> m : sold.values()) sum += m.getOrDefault(item, 0.0);
        return sum;
    }

    /** How many players have an S kept (memory holds only the players who sold lately). */
    public int sellers() {
        return sold.size();
    }

    public int soldBy(UUID player, String item) {
        return playerDay.getOrDefault(player + "|" + item, 0);
    }

    /** The day's counts row that holds what a player sold today in gold (판매 소득세), beside the items' counts. */
    static final String INCOME = "#income";

    /** Gold this player got from the shop today, before the 판매 소득세 (starts over at the daily reset). */
    public long income(UUID player) {
        return playerDay.getOrDefault(player + "|" + INCOME, 0);
    }

    public void addIncome(UUID player, long gold) {
        if (gold <= 0) return;
        int p = playerDay.merge(player + "|" + INCOME, (int) Math.min(Integer.MAX_VALUE, gold), (a, b) -> (int) Math.min(Integer.MAX_VALUE, (long) a + b));
        db.execute(db.upsert("eco_shop_player", List.of("player", "item"), List.of("amount")), player.toString(), INCOME, p);
    }

    public void record(UUID player, String item, int count) {
        double s = sold.computeIfAbsent(player, k -> new ConcurrentHashMap<>()).merge(item, (double) count, Double::sum);
        int p = playerDay.merge(player + "|" + item, count, Integer::sum);
        db.execute(db.upsert("eco_shop_psold", List.of("player", "item"), List.of("sold")), player.toString(), item, s);
        db.execute(db.upsert("eco_shop_player", List.of("player", "item"), List.of("amount")), player.toString(), item, p);
    }

    /** Every S times factor; the ones under half a piece are forgotten, and a player with none left with them. */
    private void shrink(double factor) {
        for (var it = sold.entrySet().iterator(); it.hasNext(); ) {
            Map<String, Double> m = it.next().getValue();
            m.replaceAll((k, v) -> v * factor);
            m.values().removeIf(v -> v < FORGET);
            if (m.isEmpty()) it.remove();
        }
    }

    /** The same in the table: one queue, so a sale recorded after this (an absolute value) lands after it, as in memory. */
    private static void shrink(java.sql.Connection c, double factor) throws java.sql.SQLException {
        Db.update(c, "UPDATE eco_shop_psold SET sold = sold * ?", factor);
        Db.update(c, "DELETE FROM eco_shop_psold WHERE sold < ?", FORGET);
    }

    /** During the day: every player's S shrinks a little (their prices recover); per-player counts stay. */
    public void recover(double factor) {
        if (sold.isEmpty()) return;
        shrink(factor);
        db.async(c -> {
            shrink(c, factor);
            return null;
        });
    }

    /** The daily reset: every player's S shrinks by daily-decay (prices recover), the per-player counts start over. */
    public void decay(double factor) {
        shrink(factor);
        playerDay.clear();
        db.async(c -> {
            shrink(c, factor);
            Db.update(c, "DELETE FROM eco_shop_player");
            Db.update(c, "DELETE FROM eco_price WHERE at < ?", System.currentTimeMillis() - KEEP);
            return null;
        });
    }

    /** How long price snapshots are kept, in the table (the daily decay) and in memory alike. */
    static final long KEEP = 3 * 86_400_000L;

    public void snapshot(String item, long at, double price) {
        List<Snapshot> h = history.computeIfAbsent(item, k -> new ArrayList<>());
        h.add(new Snapshot(at, price));
        // a server that runs for weeks without a restart does not pile up snapshots nobody asks about any more
        h.removeIf(x -> x.at() < at - KEEP);
        db.execute(db.upsert("eco_price", List.of("item", "at"), List.of("price")), item, at, price);
    }

    /** The price about 24 hours ago (latest snapshot at or before then), or null if none that old. */
    public Double priceAgo(String item, long now, long ago) {
        Double best = null;
        for (Snapshot s : history.getOrDefault(item, List.of())) {
            if (s.at() <= now - ago) best = s.price();
        }
        return best;
    }

    public void reset() {
        sold.clear();
        playerDay.clear();
        history.clear();
        db.async(c -> {
            Db.update(c, "DELETE FROM eco_shop_psold");
            Db.update(c, "DELETE FROM eco_shop_player");
            Db.update(c, "DELETE FROM eco_price");
            return null;
        });
    }
}
