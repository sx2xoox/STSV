package dev.stsv.economy.job;

import dev.stsv.core.data.Db;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Who has which job now, when they last changed, and their XP in every job they have worked (levels stay
 * with the job: changing back finds the old level). All rows in memory, written in batches.
 */
public final class JobStore {

    public static final class Row {
        public final UUID player;
        public String job;
        public long changed;
        public int changes;
        /** job -> XP (every job this person ever had) */
        public final Map<String, Double> xp = new ConcurrentHashMap<>();

        Row(UUID player, String job, long changed, int changes) {
            this.player = player;
            this.job = job;
            this.changed = changed;
            this.changes = changes;
        }

        public double xp(String job) {
            return job == null ? 0 : xp.getOrDefault(job, 0.0);
        }
    }

    private final Db db;
    private final Map<UUID, Row> rows = new ConcurrentHashMap<>();
    private final java.util.Set<UUID> dirty = ConcurrentHashMap.newKeySet();

    public JobStore(Db db) {
        this.db = db;
    }

    public void init() {
        db.migrate("eco_job", List.of(
                List.of("CREATE TABLE IF NOT EXISTS eco_job (player CHAR(36) NOT NULL PRIMARY KEY, job VARCHAR(32) NOT NULL, xp DOUBLE NOT NULL, "
                        + "mastery DOUBLE NOT NULL, changed BIGINT NOT NULL, changes INT NOT NULL)"),
                // v2: XP per job. What people had stays on the job they have now (old 숙련 included)
                List.of("CREATE TABLE IF NOT EXISTS eco_job_xp (player CHAR(36) NOT NULL, job VARCHAR(32) NOT NULL, xp DOUBLE NOT NULL, "
                                + "PRIMARY KEY (player, job))",
                        "INSERT INTO eco_job_xp (player, job, xp) SELECT player, job, xp + mastery FROM eco_job")));
        rows.clear();
        for (Row r : db.sync(c -> Db.query(c, "SELECT player, job, changed, changes FROM eco_job",
                rs -> new Row(UUID.fromString(rs.getString(1)), rs.getString(2), rs.getLong(3), rs.getInt(4))))) {
            rows.put(r.player, r);
        }
        for (Object[] x : db.sync(c -> Db.query(c, "SELECT player, job, xp FROM eco_job_xp",
                rs -> new Object[]{rs.getString(1), rs.getString(2), rs.getDouble(3)}))) {
            Row r = rows.get(UUID.fromString((String) x[0]));
            if (r != null) r.xp.put((String) x[1], (Double) x[2]);
        }
    }

    public Row get(UUID player) {
        return rows.get(player);
    }

    public String job(UUID player) {
        Row r = rows.get(player);
        return r == null ? null : r.job;
    }

    /** XP in a job (0 if never worked). */
    public double xp(UUID player, String job) {
        Row r = rows.get(player);
        return r == null ? 0 : r.xp(job);
    }

    /** Takes a job: the XP of every job stays where it is. */
    public Row choose(UUID player, String job, long now) {
        Row r = rows.get(player);
        if (r == null) {
            r = new Row(player, job, now, 1);
            rows.put(player, r);
        } else {
            r.job = job;
            r.changed = now;
            r.changes++;
        }
        save(r);
        return r;
    }

    public void addXp(Row r, String job, double add) {
        r.xp.merge(job, add, Double::sum);
        dirty.add(r.player);
    }

    public void setXp(Row r, String job, double xp) {
        r.xp.put(job, Math.max(0, xp));
        dirty.add(r.player);
    }

    private void save(Row r) {
        db.execute(db.upsert("eco_job", List.of("player"), List.of("job", "xp", "mastery", "changed", "changes")),
                r.player.toString(), r.job, 0.0, 0.0, r.changed, r.changes);
        for (Map.Entry<String, Double> e : new LinkedHashMap<>(r.xp).entrySet()) {
            db.execute(db.upsert("eco_job_xp", List.of("player", "job"), List.of("xp")), r.player.toString(), e.getKey(), e.getValue());
        }
    }

    public void flush() {
        for (UUID u : List.copyOf(dirty)) {
            dirty.remove(u);
            Row r = rows.get(u);
            if (r != null) save(r);
        }
    }

    /** One entry of a ranking: a person and one of their jobs. */
    public record Entry(UUID player, String job, double xp) {}

    /** XP ranking of one job (everyone who worked it), or of everyone's best job when job == null. */
    public List<Entry> ranking(String job, int n) {
        List<Entry> out = new ArrayList<>();
        for (Row r : rows.values()) {
            if (job != null) {
                double x = r.xp(job);
                if (x > 0) out.add(new Entry(r.player, job, x));
            } else {
                r.xp.entrySet().stream().max(Map.Entry.comparingByValue())
                        .ifPresent(e -> out.add(new Entry(r.player, e.getKey(), e.getValue())));
            }
        }
        out.sort(Comparator.comparingDouble(Entry::xp).reversed());
        return out.subList(0, Math.min(n, out.size()));
    }

    public void reset() {
        rows.clear();
        dirty.clear();
        db.execute("DELETE FROM eco_job");
        db.execute("DELETE FROM eco_job_xp");
    }
}
