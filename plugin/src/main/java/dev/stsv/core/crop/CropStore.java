package dev.stsv.core.crop;

import dev.stsv.core.data.Db;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Planted crops in the database. Everything is loaded at start and saved when it changes. */
public final class CropStore {

    private final Db db;

    public CropStore(Db db) {
        this.db = db;
    }

    public void init() {
        String id = db.dialect() == Db.Dialect.MYSQL ? "id BIGINT PRIMARY KEY AUTO_INCREMENT" : "id INTEGER PRIMARY KEY AUTOINCREMENT";
        db.migrate("crops", List.of(
                List.of(
                        "CREATE TABLE IF NOT EXISTS crop (" + id + ", crop VARCHAR(128) NOT NULL, world VARCHAR(64) NOT NULL, x INT NOT NULL, y INT NOT NULL, "
                                + "z INT NOT NULL, pot CHAR(36), planter CHAR(36), progress BIGINT NOT NULL, water_until BIGINT NOT NULL, dry_since BIGINT NOT NULL, "
                                + "never_dry INT NOT NULL, fertilizer VARCHAR(128), pest_since BIGINT NOT NULL, withered INT NOT NULL, last BIGINT NOT NULL, "
                                + "rolled INT NOT NULL)",
                        "CREATE TABLE IF NOT EXISTS crop_sprinkler (world VARCHAR(64) NOT NULL, x INT NOT NULL, y INT NOT NULL, z INT NOT NULL, "
                                + "radius INT NOT NULL, PRIMARY KEY (world, x, y, z))"),
                List.of("CREATE TABLE IF NOT EXISTS crop_meta (k VARCHAR(64) NOT NULL PRIMARY KEY, v BIGINT NOT NULL)"),
                // vanilla-like growth: an age instead of watered time; pots, sprinklers and withering are gone
                List.of(
                        "ALTER TABLE crop ADD COLUMN age INT NOT NULL DEFAULT 0",
                        "DELETE FROM crop WHERE pot IS NOT NULL OR withered <> 0",
                        "DROP TABLE IF EXISTS crop_sprinkler"),
                // bone meal once per 7 natural ages (column "grown": NATURAL is reserved in MySQL / MariaDB);
                // the mark a young plant gives back with its seed
                List.of(
                        "ALTER TABLE crop ADD COLUMN grown INT NOT NULL DEFAULT 0",
                        "ALTER TABLE crop ADD COLUMN meals INT NOT NULL DEFAULT 0",
                        "ALTER TABLE crop ADD COLUMN carry VARCHAR(128)")));
    }

    private static UUID uuid(String s) {
        return s == null ? null : UUID.fromString(s);
    }

    public List<CropState> loadCrops() {
        return db.sync(c -> Db.query(c, "SELECT id, crop, world, x, y, z, planter, age, grown, meals, carry FROM crop", rs -> {
            CropState s = new CropState(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getInt(4), rs.getInt(5), rs.getInt(6),
                    uuid(rs.getString(7)), rs.getInt(8));
            s.grown = rs.getInt(9);
            s.meals = rs.getInt(10);
            s.carry = rs.getString(11);
            return s;
        }));
    }

    /** New crop with an id chosen by the module (no waiting on the database during play). */
    public void insert(CropState s) {
        db.execute("INSERT INTO crop (id, crop, world, x, y, z, pot, planter, progress, water_until, dry_since, never_dry, "
                        + "fertilizer, pest_since, withered, last, rolled, age, grown, meals, carry) "
                        + "VALUES (?, ?, ?, ?, ?, ?, NULL, ?, 0, 0, 0, 1, NULL, 0, 0, 0, 0, ?, ?, ?, ?)",
                s.id, s.crop, s.world, s.x, s.y, s.z, s.planter == null ? null : s.planter.toString(), s.age, s.grown, s.meals, s.carry);
    }

    public void save(List<CropState> states) {
        if (states.isEmpty()) return;
        List<Object[]> rows = new ArrayList<>();
        for (CropState s : states) rows.add(new Object[]{s.age, s.grown, s.meals, s.carry, s.id});
        db.async(c -> {
            boolean auto = c.getAutoCommit();
            c.setAutoCommit(false);   // one transaction for the whole batch
            try {
                for (Object[] r : rows) Db.update(c, "UPDATE crop SET age = ?, grown = ?, meals = ?, carry = ? WHERE id = ?", r);
                c.commit();
            } catch (java.sql.SQLException e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(auto);
            }
            return null;
        });
    }

    public void delete(long id) {
        db.execute("DELETE FROM crop WHERE id = ?", id);
    }
}
