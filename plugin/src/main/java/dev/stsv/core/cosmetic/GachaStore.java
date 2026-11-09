package dev.stsv.core.cosmetic;

import dev.stsv.core.data.Db;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 치장 뽑기 per player in the shared database (table cosmetic_gacha): the pity counter (draws in a row
 * without the pity rarity), all draws ever, and the draws of the current day (for an optional daily limit).
 * Kept in memory; every change is written on the database thread.
 */
public final class GachaStore {

    private static final List<List<String>> SCHEMA = List.of(List.of(
            "CREATE TABLE IF NOT EXISTS cosmetic_gacha (player CHAR(36) NOT NULL PRIMARY KEY, pity INT NOT NULL, "
                    + "total_pulls BIGINT NOT NULL, day_key INT NOT NULL, day_pulls INT NOT NULL)"));

    /** One player's counters. day = days since the epoch (server time zone) of dayPulls. */
    public record Row(int pity, long total, int day, int dayPulls) {
        static final Row NONE = new Row(0, 0, 0, 0);

        /** Draws made on the given day. */
        public int pullsOn(int today) {
            return day == today ? dayPulls : 0;
        }

        /** After n more draws today, with the pity counter at pityAfter. */
        public Row after(int n, int pityAfter, int today) {
            return new Row(pityAfter, total + n, today, pullsOn(today) + n);
        }
    }

    private final Db db;
    private final Map<UUID, Row> rows = new HashMap<>();

    public GachaStore(Db db) {
        this.db = db;
    }

    public synchronized void load() {
        db.migrate("cosmetic_gacha", SCHEMA);
        rows.clear();
        db.sync(c -> {
            for (Object[] r : Db.query(c, "SELECT player, pity, total_pulls, day_key, day_pulls FROM cosmetic_gacha",
                    rs -> new Object[]{rs.getString(1), rs.getInt(2), rs.getLong(3), rs.getInt(4), rs.getInt(5)})) {
                rows.put(UUID.fromString((String) r[0]), new Row((Integer) r[1], (Long) r[2], (Integer) r[3], (Integer) r[4]));
            }
            return null;
        });
    }

    public synchronized Row get(UUID player) {
        return rows.getOrDefault(player, Row.NONE);
    }

    public synchronized void set(UUID player, Row row) {
        rows.put(player, row);
        db.execute(db.upsert("cosmetic_gacha", List.of("player"), List.of("pity", "total_pulls", "day_key", "day_pulls")),
                player.toString(), row.pity(), row.total(), row.day(), row.dayPulls());
    }
}
