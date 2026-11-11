package dev.stsv.core.vehicle;

import dev.stsv.core.data.Db;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Every vehicle's {@link VehicleTrip} (stsv_vehicle_trip, one row per vehicle id; no Bukkit types: unit-tested on
 * SQLite). Its own table, so the vehicle table stays as it is.
 */
public final class VehicleTripStore {

    private final Db db;

    public VehicleTripStore(Db db) {
        this.db = db;
    }

    public void init() {
        db.migrate("vehicle-trips", List.of(List.of(
                "CREATE TABLE IF NOT EXISTS stsv_vehicle_trip (id BIGINT PRIMARY KEY, world VARCHAR(64), x DOUBLE NOT NULL, "
                        + "z DOUBLE NOT NULL, odometer DOUBLE NOT NULL, changed BIGINT NOT NULL)")));
    }

    /** Writes a trip (inserted or replaced). */
    public CompletableFuture<Void> save(long id, VehicleTrip.Snapshot t) {
        return db.execute(db.upsert("stsv_vehicle_trip", List.of("id"), List.of("world", "x", "z", "odometer", "changed")),
                id, t.world() == null ? null : t.world().length() <= 64 ? t.world() : t.world().substring(0, 64), t.x(), t.z(), t.odometer(), t.changed());
    }

    public CompletableFuture<Void> delete(long id) {
        return db.execute("DELETE FROM stsv_vehicle_trip WHERE id = ?", id);
    }

    /** Every trip (loaded at start). */
    public Map<Long, VehicleTrip> all() {
        Map<Long, VehicleTrip> out = new HashMap<>();
        db.sync(c -> Db.query(c, "SELECT id, world, x, z, odometer, changed FROM stsv_vehicle_trip",
                rs -> out.put(rs.getLong(1), new VehicleTrip(rs.getString(2), rs.getDouble(3), rs.getDouble(4), rs.getDouble(5), rs.getLong(6)))));
        return out;
    }
}
