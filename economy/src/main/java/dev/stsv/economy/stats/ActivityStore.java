package dev.stsv.economy.stats;

import dev.stsv.core.data.Db;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per player per day: the balance at the start of the day (for 오늘 손익), seconds online and money
 * earned (for the daily report's "하위 25% 시간당 수입"). Today is kept in memory and written as it changes.
 */
public final class ActivityStore {

    public record Day(UUID player, long startBalance, long seconds, long income) {}

    private final Db db;
    private int today = -1;
    private final Map<UUID, long[]> rows = new ConcurrentHashMap<>();   // [startBalance, seconds, income]

    public ActivityStore(Db db) {
        this.db = db;
    }

    public void init(int day) {
        db.migrate("eco_activity", List.of(List.of(
                "CREATE TABLE IF NOT EXISTS eco_activity (day INT NOT NULL, player CHAR(36) NOT NULL, start_balance BIGINT NOT NULL, "
                        + "seconds BIGINT NOT NULL, income BIGINT NOT NULL, PRIMARY KEY (day, player))")));
        load(day);
    }

    private void load(int day) {
        today = day;
        rows.clear();
        for (Day d : db.sync(c -> Db.query(c, "SELECT player, start_balance, seconds, income FROM eco_activity WHERE day = ?",
                rs -> new Day(UUID.fromString(rs.getString(1)), rs.getLong(2), rs.getLong(3), rs.getLong(4)), day))) {
            rows.put(d.player(), new long[]{d.startBalance(), d.seconds(), d.income()});
        }
    }

    public int today() {
        return today;
    }

    /** A new day: forget today's rows (they stay in the database), start counting again. */
    public void newDay(int day) {
        flush();
        load(day);
    }

    /** First sight today records the balance the day started with. */
    public void seen(UUID player, long balance) {
        if (rows.putIfAbsent(player, new long[]{balance, 0, 0}) == null) save(player);
    }

    public boolean seenToday(UUID player) {
        return rows.containsKey(player);
    }

    /** Seconds played today before a player counts as one of the day's players (15 minutes). */
    public static final long ACTIVE_SECONDS = 900;

    /**
     * Players who played at least 15 minutes today (at least 1): the N of the shop's price curve. A ten-second login
     * (an alt account, a quick check) does not widen everyone's sell room.
     */
    public int players() {
        return Math.max(1, (int) rows.values().stream().filter(r -> r[1] >= ACTIVE_SECONDS).count());
    }

    public void addSeconds(UUID player, long seconds) {
        long[] r = rows.get(player);
        if (r != null) r[1] += seconds;
    }

    public void addIncome(UUID player, long amount) {
        long[] r = rows.get(player);
        if (r != null && amount > 0) r[2] += amount;
    }

    public long startBalance(UUID player, long fallback) {
        long[] r = rows.get(player);
        return r == null ? fallback : r[0];
    }

    public Map<UUID, Day> todayRows() {
        Map<UUID, Day> out = new HashMap<>();
        rows.forEach((k, v) -> out.put(k, new Day(k, v[0], v[1], v[2])));
        return out;
    }

    private void save(UUID player) {
        long[] r = rows.get(player);
        if (r == null) return;
        db.execute(db.upsert("eco_activity", List.of("day", "player"), List.of("start_balance", "seconds", "income")),
                today, player.toString(), r[0], r[1], r[2]);
    }

    public void flush() {
        for (UUID u : rows.keySet()) save(u);
    }

    public CompletableFuture<List<Day>> day(int day) {
        return db.async(c -> Db.query(c, "SELECT player, start_balance, seconds, income FROM eco_activity WHERE day = ?",
                rs -> new Day(UUID.fromString(rs.getString(1)), rs.getLong(2), rs.getLong(3), rs.getLong(4)), day));
    }

    public void reset() {
        rows.clear();
        db.execute("DELETE FROM eco_activity");
    }
}
