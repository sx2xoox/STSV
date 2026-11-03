package dev.stsv.core.crop;

import dev.stsv.core.data.Db;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Planted crops and sprinklers in the database. Everything is loaded at start and saved when changed. */
public final class CropStore {

    public record Sprinkler(String world, int x, int y, int z, int radius) {}

    private final Db db;

    public CropStore(Db db) {
        this.db = db;
    }

    public void init() {
        String id = db.dialect() == Db.Dialect.MYSQL ? "id BIGINT PRIMARY KEY AUTO_INCREMENT" : "id INTEGER PRIMARY KEY AUTOINCREMENT";
        db.migrate("crops", List.of(List.of(
                "CREATE TABLE IF NOT EXISTS crop (" + id + ", crop VARCHAR(128) NOT NULL, world VARCHAR(64) NOT NULL, x INT NOT NULL, y INT NOT NULL, "
                        + "z INT NOT NULL, pot CHAR(36), planter CHAR(36), progress BIGINT NOT NULL, water_until BIGINT NOT NULL, dry_since BIGINT NOT NULL, "
                        + "never_dry INT NOT NULL, fertilizer VARCHAR(128), pest_since BIGINT NOT NULL, withered INT NOT NULL, last BIGINT NOT NULL, "
                        + "rolled INT NOT NULL)",
                "CREATE TABLE IF NOT EXISTS crop_sprinkler (world VARCHAR(64) NOT NULL, x INT NOT NULL, y INT NOT NULL, z INT NOT NULL, "
                        + "radius INT NOT NULL, PRIMARY KEY (world, x, y, z))")));
    }

    private static UUID uuid(String s) {
        return s == null ? null : UUID.fromString(s);
    }

    public List<CropState> loadCrops() {
        return db.sync(c -> Db.query(c, "SELECT id, crop, world, x, y, z, pot, planter, progress, water_until, dry_since, never_dry, fertilizer, "
                + "pest_since, withered, last, rolled FROM crop", rs -> {
            CropState s = new CropState(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getInt(4), rs.getInt(5), rs.getInt(6),
                    uuid(rs.getString(7)), uuid(rs.getString(8)), rs.getLong(16));
            s.progress = rs.getLong(9);
            s.waterUntil = rs.getLong(10);
            s.drySince = rs.getLong(11);
            s.neverDry = rs.getInt(12) != 0;
            s.fertilizer = rs.getString(13);
            s.pestSince = rs.getLong(14);
            s.withered = rs.getInt(15) != 0;
            s.rolledStage = rs.getInt(17);
            return s;
        }));
    }

    public List<Sprinkler> loadSprinklers() {
        return db.sync(c -> Db.query(c, "SELECT world, x, y, z, radius FROM crop_sprinkler",
                rs -> new Sprinkler(rs.getString(1), rs.getInt(2), rs.getInt(3), rs.getInt(4), rs.getInt(5))));
    }

    /** New crop: stored at once (it needs its id). */
    public void insert(CropState s) {
        s.id = db.sync(c -> Db.insert(c, "INSERT INTO crop (crop, world, x, y, z, pot, planter, progress, water_until, dry_since, never_dry, "
                        + "fertilizer, pest_since, withered, last, rolled) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                s.crop, s.world, s.x, s.y, s.z, str(s.pot), str(s.planter), s.progress, s.waterUntil, s.drySince, s.neverDry ? 1 : 0,
                s.fertilizer, s.pestSince, s.withered ? 1 : 0, s.last, s.rolledStage));
    }

    public void save(List<CropState> states) {
        if (states.isEmpty()) return;
        List<Object[]> rows = new ArrayList<>();
        for (CropState s : states) {
            rows.add(new Object[]{s.progress, s.waterUntil, s.drySince, s.neverDry ? 1 : 0, s.fertilizer, s.pestSince, s.withered ? 1 : 0,
                    s.last, s.rolledStage, s.id});
        }
        db.async(c -> {
            for (Object[] r : rows) {
                Db.update(c, "UPDATE crop SET progress = ?, water_until = ?, dry_since = ?, never_dry = ?, fertilizer = ?, pest_since = ?, "
                        + "withered = ?, last = ?, rolled = ? WHERE id = ?", r);
            }
            return null;
        });
    }

    public void delete(long id) {
        db.execute("DELETE FROM crop WHERE id = ?", id);
    }

    public void putSprinkler(Sprinkler s) {
        db.execute(db.upsert("crop_sprinkler", List.of("world", "x", "y", "z"), List.of("radius")), s.world(), s.x(), s.y(), s.z(), s.radius());
    }

    public void deleteSprinkler(String world, int x, int y, int z) {
        db.execute("DELETE FROM crop_sprinkler WHERE world = ? AND x = ? AND y = ? AND z = ?", world, x, y, z);
    }

    private static String str(UUID u) {
        return u == null ? null : u.toString();
    }
}
