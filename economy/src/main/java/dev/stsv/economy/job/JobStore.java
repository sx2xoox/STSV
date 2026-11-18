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
        /** job -> level XP (every job this person ever had; never past the next unpaid gate) */
        public final Map<String, Double> xp = new ConcurrentHashMap<>();
        /** job -> 숙련: XP earned while the level was held at a gate or the last level, and what people had past Lv30 before 승급 */
        public final Map<String, Double> mastery = new ConcurrentHashMap<>();
        /** job -> gates passed (승급) */
        public final Map<String, Integer> promoted = new ConcurrentHashMap<>();

        Row(UUID player, String job, long changed, int changes) {
            this.player = player;
            this.job = job;
            this.changed = changed;
            this.changes = changes;
        }

        public double xp(String job) {
            return job == null ? 0 : xp.getOrDefault(job, 0.0);
        }

        public double mastery(String job) {
            return job == null ? 0 : mastery.getOrDefault(job, 0.0);
        }

        public int promoted(String job) {
            return job == null ? 0 : promoted.getOrDefault(job, 0);
        }
    }

    private final Db db;
    private final Map<UUID, Row> rows = new ConcurrentHashMap<>();
    private final java.util.Set<UUID> dirty = ConcurrentHashMap.newKeySet();

    /** The patch-time move (v3 above): Lv30 on the old curve and just under Lv35 on the new one. */
    static final double LV30 = 35380, FIRST_GATE_CEILING = 535379;

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
                        "INSERT INTO eco_job_xp (player, job, xp) SELECT player, job, xp + mastery FROM eco_job"),
                // v3 (4세대 · 직업 Lv50): 숙련과 승급을 따로. Once, at this patch: what people had past Lv30 (35,380) stays their 숙련
                // and also becomes level XP, but only up to just under the first gate (Lv35 = 535,380 on the 20,000 curve)
                List.of("ALTER TABLE eco_job_xp ADD COLUMN mastery DOUBLE NOT NULL DEFAULT 0",
                        "ALTER TABLE eco_job_xp ADD COLUMN promoted INT NOT NULL DEFAULT 0",
                        "UPDATE eco_job_xp SET mastery = xp - " + LV30 + " WHERE xp > " + LV30,
                        "UPDATE eco_job_xp SET xp = " + FIRST_GATE_CEILING + " WHERE xp > " + FIRST_GATE_CEILING)));
        rows.clear();
        for (Row r : db.sync(c -> Db.query(c, "SELECT player, job, changed, changes FROM eco_job",
                rs -> new Row(UUID.fromString(rs.getString(1)), rs.getString(2), rs.getLong(3), rs.getInt(4))))) {
            rows.put(r.player, r);
        }
        for (Object[] x : db.sync(c -> Db.query(c, "SELECT player, job, xp, mastery, promoted FROM eco_job_xp",
                rs -> new Object[]{rs.getString(1), rs.getString(2), rs.getDouble(3), rs.getDouble(4), rs.getInt(5)}))) {
            Row r = rows.get(UUID.fromString((String) x[0]));
            if (r == null) continue;
            String job = (String) x[1];
            r.xp.put(job, (Double) x[2]);
            if ((Double) x[3] > 0) r.mastery.put(job, (Double) x[3]);
            if ((Integer) x[4] > 0) r.promoted.put(job, (Integer) x[4]);
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

    public void addMastery(Row r, String job, double add) {
        if (add <= 0) return;
        r.mastery.merge(job, add, Double::sum);
        r.xp.putIfAbsent(job, 0.0);
        dirty.add(r.player);
    }

    public void setPromoted(Row r, String job, int gates) {
        r.promoted.put(job, Math.max(0, gates));
        r.xp.putIfAbsent(job, 0.0);
        dirty.add(r.player);
    }

    /** Gates passed in a job. */
    public int promoted(UUID player, String job) {
        Row r = rows.get(player);
        return r == null ? 0 : r.promoted(job);
    }

    public double mastery(UUID player, String job) {
        Row r = rows.get(player);
        return r == null ? 0 : r.mastery(job);
    }

    private void save(Row r) {
        db.execute(db.upsert("eco_job", List.of("player"), List.of("job", "xp", "mastery", "changed", "changes")),
                r.player.toString(), r.job, 0.0, 0.0, r.changed, r.changes);
        for (Map.Entry<String, Double> e : new LinkedHashMap<>(r.xp).entrySet()) {
            db.execute(db.upsert("eco_job_xp", List.of("player", "job"), List.of("xp", "mastery", "promoted")), r.player.toString(), e.getKey(),
                    e.getValue(), r.mastery(e.getKey()), r.promoted(e.getKey()));
        }
    }

    public void flush() {
        for (UUID u : List.copyOf(dirty)) {
            dirty.remove(u);
            Row r = rows.get(u);
            if (r != null) save(r);
        }
    }

    /** One entry of a ranking: a person and one of their jobs (level XP and 숙련; ranked by the two together). */
    public record Entry(UUID player, String job, double xp, double mastery) {
        public double total() {
            return xp + mastery;
        }
    }

    /** Ranking of one job (everyone who worked it), or of everyone's best job when job == null. */
    public List<Entry> ranking(String job, int n) {
        List<Entry> out = new ArrayList<>();
        for (Row r : rows.values()) {
            if (job != null) {
                double x = r.xp(job);
                if (x > 0 || r.mastery(job) > 0) out.add(new Entry(r.player, job, x, r.mastery(job)));
            } else {
                r.xp.keySet().stream().max(Comparator.comparingDouble(j -> r.xp(j) + r.mastery(j)))
                        .ifPresent(j -> out.add(new Entry(r.player, j, r.xp(j), r.mastery(j))));
            }
        }
        out.sort(Comparator.comparingDouble(Entry::total).reversed());
        return out.subList(0, Math.min(n, out.size()));
    }

    public void reset() {
        rows.clear();
        dirty.clear();
        db.execute("DELETE FROM eco_job");
        db.execute("DELETE FROM eco_job_xp");
    }
}
