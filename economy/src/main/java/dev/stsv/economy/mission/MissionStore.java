package dev.stsv.economy.mission;

import dev.stsv.core.data.Db;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Each player's missions of the current day and week (table eco_mission: one row per mission, with the
 * stat value it counts from and when it was done) and the period's rerolls and bonus (eco_mission_period).
 * Rows of past periods are dropped at the daily turn.
 */
public final class MissionStore {

    /** One mission of a player's set. */
    public static final class Slot {
        public final int index;
        public String mission;
        public double base;
        public long doneAt;

        Slot(int index, String mission, double base, long doneAt) {
            this.index = index;
            this.mission = mission;
            this.base = base;
            this.doneAt = doneAt;
        }

        public boolean done() {
            return doneAt > 0;
        }
    }

    /** A player's set for one day or week ("d12", "w3"). Empty slots: not given yet. */
    public static final class Set {
        public final String period;
        public final List<Slot> slots = new ArrayList<>();
        public int rerolls;
        public long bonusAt;

        Set(String period) {
            this.period = period;
        }

        public long doneCount() {
            return slots.stream().filter(Slot::done).count();
        }

        public boolean allDone() {
            return !slots.isEmpty() && slots.stream().allMatch(Slot::done);
        }
    }

    /** A player's day and week. */
    public record State(Set daily, Set weekly) {}

    private final Db db;

    public MissionStore(Db db) {
        this.db = db;
    }

    public void init() {
        db.migrate("eco_mission", List.of(List.of(
                "CREATE TABLE IF NOT EXISTS eco_mission (player CHAR(36) NOT NULL, period VARCHAR(16) NOT NULL, slot INT NOT NULL, "
                        + "mission VARCHAR(64) NOT NULL, base DOUBLE NOT NULL, done_at BIGINT NOT NULL, PRIMARY KEY (player, period, slot))",
                "CREATE TABLE IF NOT EXISTS eco_mission_period (player CHAR(36) NOT NULL, period VARCHAR(16) NOT NULL, "
                        + "rerolls INT NOT NULL, bonus_at BIGINT NOT NULL, PRIMARY KEY (player, period))")));
    }

    public CompletableFuture<State> load(UUID player, String day, String week) {
        return db.async(c -> new State(read(c, player, day), read(c, player, week)));
    }

    private static Set read(java.sql.Connection c, UUID player, String period) throws java.sql.SQLException {
        Set s = new Set(period);
        for (Slot x : Db.query(c, "SELECT slot, mission, base, done_at FROM eco_mission WHERE player = ? AND period = ? ORDER BY slot",
                rs -> new Slot(rs.getInt(1), rs.getString(2), rs.getDouble(3), rs.getLong(4)), player.toString(), period)) {
            s.slots.add(x);
        }
        for (long[] r : Db.query(c, "SELECT rerolls, bonus_at FROM eco_mission_period WHERE player = ? AND period = ?",
                rs -> new long[]{rs.getInt(1), rs.getLong(2)}, player.toString(), period)) {
            s.rerolls = (int) r[0];
            s.bonusAt = r[1];
        }
        return s;
    }

    /** Adds or changes a slot (the set keeps it in memory). */
    public Slot put(UUID player, Set set, int index, String mission, double base) {
        Slot s = index < set.slots.size() ? set.slots.get(index) : null;
        if (s == null) {
            s = new Slot(index, mission, base, 0);
            set.slots.add(s);
        } else {
            s.mission = mission;
            s.base = base;
            s.doneAt = 0;
        }
        save(player, set.period, s);
        return s;
    }

    public void save(UUID player, String period, Slot s) {
        db.execute(db.upsert("eco_mission", List.of("player", "period", "slot"), List.of("mission", "base", "done_at")),
                player.toString(), period, s.index, s.mission, s.base, s.doneAt);
    }

    public void saveSet(UUID player, Set set) {
        db.execute(db.upsert("eco_mission_period", List.of("player", "period"), List.of("rerolls", "bonus_at")),
                player.toString(), set.period, set.rerolls, set.bonusAt);
    }

    /** Forgets a player's sets of these periods (admin: draw again); read them again once it is done. */
    public CompletableFuture<Void> clear(UUID player, String... periods) {
        return db.async(c -> {
            for (String period : periods) {
                Db.update(c, "DELETE FROM eco_mission WHERE player = ? AND period = ?", player.toString(), period);
                Db.update(c, "DELETE FROM eco_mission_period WHERE player = ? AND period = ?", player.toString(), period);
            }
            return null;
        });
    }

    /** Drops the rows of every period but these two. */
    public void prune(String day, String week) {
        db.execute("DELETE FROM eco_mission WHERE period <> ? AND period <> ?", day, week);
        db.execute("DELETE FROM eco_mission_period WHERE period <> ? AND period <> ?", day, week);
    }

    public CompletableFuture<Void> reset() {
        return db.async(c -> {
            Db.update(c, "DELETE FROM eco_mission");
            Db.update(c, "DELETE FROM eco_mission_period");
            return null;
        });
    }
}
