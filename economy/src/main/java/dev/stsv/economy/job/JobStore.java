package dev.stsv.economy.job;

import dev.stsv.core.data.Db;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Who has which job, their XP and mastery (숙련), and when they last changed. All rows in memory. */
public final class JobStore {

    public static final class Row {
        public final UUID player;
        public String job;
        public double xp;
        public double mastery;
        public long changed;
        public int changes;

        Row(UUID player, String job, double xp, double mastery, long changed, int changes) {
            this.player = player;
            this.job = job;
            this.xp = xp;
            this.mastery = mastery;
            this.changed = changed;
            this.changes = changes;
        }
    }

    private final Db db;
    private final Map<UUID, Row> rows = new ConcurrentHashMap<>();
    private final java.util.Set<UUID> dirty = ConcurrentHashMap.newKeySet();

    public JobStore(Db db) {
        this.db = db;
    }

    public void init() {
        db.migrate("eco_job", List.of(List.of(
                "CREATE TABLE IF NOT EXISTS eco_job (player CHAR(36) NOT NULL PRIMARY KEY, job VARCHAR(32) NOT NULL, xp DOUBLE NOT NULL, "
                        + "mastery DOUBLE NOT NULL, changed BIGINT NOT NULL, changes INT NOT NULL)")));
        rows.clear();
        for (Row r : db.sync(c -> Db.query(c, "SELECT player, job, xp, mastery, changed, changes FROM eco_job",
                rs -> new Row(UUID.fromString(rs.getString(1)), rs.getString(2), rs.getDouble(3), rs.getDouble(4), rs.getLong(5), rs.getInt(6))))) {
            rows.put(r.player, r);
        }
    }

    public Row get(UUID player) {
        return rows.get(player);
    }

    public String job(UUID player) {
        Row r = rows.get(player);
        return r == null ? null : r.job;
    }

    /** Takes a job: XP starts over, mastery stays with the person. */
    public Row choose(UUID player, String job, long now) {
        Row r = rows.get(player);
        if (r == null) {
            r = new Row(player, job, 0, 0, now, 1);
            rows.put(player, r);
        } else {
            r.job = job;
            r.xp = 0;
            r.changed = now;
            r.changes++;
        }
        save(r);
        return r;
    }

    public void touch(Row r) {
        dirty.add(r.player);
    }

    private void save(Row r) {
        db.execute(db.upsert("eco_job", List.of("player"), List.of("job", "xp", "mastery", "changed", "changes")),
                r.player.toString(), r.job, r.xp, r.mastery, r.changed, r.changes);
    }

    public void flush() {
        for (UUID u : List.copyOf(dirty)) {
            dirty.remove(u);
            Row r = rows.get(u);
            if (r != null) save(r);
        }
    }

    /** Mastery ranking of one job (or all when job == null). */
    public List<Row> ranking(String job, int n) {
        List<Row> out = new ArrayList<>();
        for (Row r : rows.values()) if (job == null || job.equals(r.job)) out.add(r);
        out.sort(Comparator.comparingDouble((Row r) -> r.mastery + r.xp).reversed());
        return out.subList(0, Math.min(n, out.size()));
    }

    public void reset() {
        rows.clear();
        dirty.clear();
        db.execute("DELETE FROM eco_job");
    }
}
