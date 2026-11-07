package dev.stsv.economy.shop;

import dev.stsv.core.data.Db;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What the server sold to the admin shop: S per item (decays at the daily reset), per player per day
 * (for caps), hourly price snapshots (for the 24h change) and the 상인 행렬 stock sold.
 */
public final class ShopStore {

    public record Snapshot(long at, double price) {}

    private final Db db;
    private final Map<String, Double> sold = new ConcurrentHashMap<>();
    private final Map<String, Integer> playerDay = new ConcurrentHashMap<>();   // player|item -> count today
    private final Map<String, List<Snapshot>> history = new ConcurrentHashMap<>();

    public ShopStore(Db db) {
        this.db = db;
    }

    public void init() {
        db.migrate("eco_shop", List.of(List.of(
                "CREATE TABLE IF NOT EXISTS eco_shop_sold (item VARCHAR(128) NOT NULL PRIMARY KEY, sold DOUBLE NOT NULL)",
                "CREATE TABLE IF NOT EXISTS eco_shop_player (player CHAR(36) NOT NULL, item VARCHAR(128) NOT NULL, amount INT NOT NULL, "
                        + "PRIMARY KEY (player, item))",
                "CREATE TABLE IF NOT EXISTS eco_price (item VARCHAR(128) NOT NULL, at BIGINT NOT NULL, price DOUBLE NOT NULL, PRIMARY KEY (item, at))")));
        sold.clear();
        playerDay.clear();
        history.clear();
        db.sync(c -> {
            for (Object[] r : Db.query(c, "SELECT item, sold FROM eco_shop_sold", rs -> new Object[]{rs.getString(1), rs.getDouble(2)})) {
                sold.put((String) r[0], (Double) r[1]);
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

    public double sold(String item) {
        return sold.getOrDefault(item, 0.0);
    }

    public int soldBy(UUID player, String item) {
        return playerDay.getOrDefault(player + "|" + item, 0);
    }

    public void record(UUID player, String item, int count) {
        double s = sold.merge(item, (double) count, Double::sum);
        int p = playerDay.merge(player + "|" + item, count, Integer::sum);
        db.execute(db.upsert("eco_shop_sold", List.of("item"), List.of("sold")), item, s);
        db.execute(db.upsert("eco_shop_player", List.of("player", "item"), List.of("amount")), player.toString(), item, p);
    }

    /** During the day: S shrinks a little (prices recover); per-player counts stay. */
    public void recover(double factor) {
        if (sold.isEmpty()) return;
        sold.replaceAll((k, v) -> v * factor);
        sold.values().removeIf(v -> v < 0.5);
        Map<String, Double> copy = new HashMap<>(sold);
        db.async(c -> {
            Db.update(c, "DELETE FROM eco_shop_sold");
            for (Map.Entry<String, Double> e : copy.entrySet()) Db.update(c, "INSERT INTO eco_shop_sold (item, sold) VALUES (?, ?)", e.getKey(), e.getValue());
            return null;
        });
    }

    /** The daily reset: S shrinks (prices recover slowly), the per-player counts start over. */
    public void decay(double factor) {
        sold.replaceAll((k, v) -> v * factor);
        sold.values().removeIf(v -> v < 0.5);
        playerDay.clear();
        Map<String, Double> copy = new HashMap<>(sold);
        db.async(c -> {
            Db.update(c, "DELETE FROM eco_shop_sold");
            Db.update(c, "DELETE FROM eco_shop_player");
            for (Map.Entry<String, Double> e : copy.entrySet()) Db.update(c, "INSERT INTO eco_shop_sold (item, sold) VALUES (?, ?)", e.getKey(), e.getValue());
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
            Db.update(c, "DELETE FROM eco_shop_sold");
            Db.update(c, "DELETE FROM eco_shop_player");
            Db.update(c, "DELETE FROM eco_price");
            return null;
        });
    }
}
