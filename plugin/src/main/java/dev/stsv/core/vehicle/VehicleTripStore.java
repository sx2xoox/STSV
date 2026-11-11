package dev.stsv.core.vehicle;

import dev.stsv.core.data.Db;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Every vehicle's {@link VehicleTrip} (stsv_vehicle_trip, one row per vehicle id; no Bukkit types: unit-tested on
 * SQLite). Its own table, so the vehicle table stays as it is. A 지게's trip is kept here too, under its negated id
 * ({@link #jige}), with where its wearer last had it on ({@link VehicleTrip#seen}).
 */
public final class VehicleTripStore {

    private final Db db;

    public VehicleTripStore(Db db) {
        this.db = db;
    }

    /** A 지게's row: its id negated (vehicle ids are positive). */
    public static long jige(long id) {
        return -id;
    }

    public void init() {
        db.migrate("vehicle-trips", List.of(List.of(
                "CREATE TABLE IF NOT EXISTS stsv_vehicle_trip (id BIGINT PRIMARY KEY, world VARCHAR(64), x DOUBLE NOT NULL, "
                        + "z DOUBLE NOT NULL, odometer DOUBLE NOT NULL, changed BIGINT NOT NULL, seen_world VARCHAR(64), seen_x DOUBLE, seen_z DOUBLE)")));
    }

    /** Writes a trip (inserted or replaced). */
    public CompletableFuture<Void> save(long id, VehicleTrip.Snapshot t) {
        return save(id, t, null);
    }

    /** Writes a trip and where its 지게 was last worn (null = not known). */
    public CompletableFuture<Void> save(long id, VehicleTrip.Snapshot t, VehicleTrip.Seen seen) {
        return db.execute(db.upsert("stsv_vehicle_trip", List.of("id"), List.of("world", "x", "z", "odometer", "changed", "seen_world", "seen_x", "seen_z")),
                id, cut(t.world()), t.x(), t.z(), t.odometer(), t.changed(), seen == null ? null : cut(seen.world()),
                seen == null ? null : seen.x(), seen == null ? null : seen.z());
    }

    public CompletableFuture<Void> delete(long id) {
        return db.execute("DELETE FROM stsv_vehicle_trip WHERE id = ?", id);
    }

    /** Every trip (loaded at start). */
    public Map<Long, VehicleTrip> all() {
        Map<Long, VehicleTrip> out = new HashMap<>();
        db.sync(c -> Db.query(c, "SELECT id, world, x, z, odometer, changed, seen_world, seen_x, seen_z FROM stsv_vehicle_trip", rs -> {
            VehicleTrip t = new VehicleTrip(rs.getString(2), rs.getDouble(3), rs.getDouble(4), rs.getDouble(5), rs.getLong(6));
            String sw = rs.getString(7);
            if (sw != null) t.seen = new VehicleTrip.Seen(sw, rs.getDouble(8), rs.getDouble(9));
            return out.put(rs.getLong(1), t);
        }));
        return out;
    }

    private static String cut(String s) {
        return s == null ? null : s.length() <= 64 ? s : s.substring(0, 64);
    }
}
