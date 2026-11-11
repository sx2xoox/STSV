package dev.stsv.core.vehicle;

import dev.stsv.core.data.Db;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Where the towed units of an articulated vehicle stand ({@link TrainSpec}): one row per vehicle in its own table
 * ({@code stsv_vehicle_train}, beside {@link VehicleStore}'s row of the vehicle, which keeps the driven unit's place),
 * every towed unit's x, y, z and yaw, front to back. No Bukkit types: unit-tested on SQLite.
 *
 * <p>Saved together with the vehicle's own place (while it moves, when it stops, when its chunk goes), so after a restart
 * the train stands bent as it was left. A vehicle picked up into an item forgets it: placed again, it stands straight.
 */
public final class TrainStore {

    private final Db db;

    public TrainStore(Db db) {
        this.db = db;
    }

    public void init() {
        db.migrate("vehicle_trains", List.of(List.of(
                "CREATE TABLE IF NOT EXISTS stsv_vehicle_train (id BIGINT PRIMARY KEY, units VARCHAR(1024) NOT NULL, updated BIGINT NOT NULL)")));
    }

    /** Every saved train (loaded at start): vehicle id -> its towed units. */
    public Map<Long, List<VehiclePhysics.State>> all() {
        return db.sync(c -> {
            Map<Long, List<VehiclePhysics.State>> out = new HashMap<>();
            for (Object[] r : Db.query(c, "SELECT id, units FROM stsv_vehicle_train", rs -> new Object[]{rs.getLong(1), rs.getString(2)})) {
                List<VehiclePhysics.State> units = decode((String) r[1]);
                if (units != null) out.put((Long) r[0], units);
            }
            return out;
        });
    }

    /** Saves a train's towed units (replacing what was saved). */
    public CompletableFuture<Void> save(long id, List<VehiclePhysics.State> units, long now) {
        return db.execute(db.upsert("stsv_vehicle_train", List.of("id"), List.of("units", "updated")), id, encode(units), now);
    }

    public CompletableFuture<Void> delete(long id) {
        return db.execute("DELETE FROM stsv_vehicle_train WHERE id = ?", id);
    }

    /** "x,y,z,yaw;x,y,z,yaw;..." front to back. */
    public static String encode(List<VehiclePhysics.State> units) {
        StringBuilder b = new StringBuilder();
        for (VehiclePhysics.State s : units) {
            if (!b.isEmpty()) b.append(';');
            b.append(String.format(Locale.ROOT, "%.5f,%.5f,%.5f,%.4f", s.x, s.y, s.z, s.yaw));
        }
        return b.toString();
    }

    /** The units saved by {@link #encode}; null when it cannot be read. */
    public static List<VehiclePhysics.State> decode(String text) {
        if (text == null || text.isBlank()) return null;
        List<VehiclePhysics.State> out = new ArrayList<>();
        try {
            for (String unit : text.split(";")) {
                String[] p = unit.split(",");
                if (p.length != 4) return null;
                VehiclePhysics.State s = new VehiclePhysics.State(Double.parseDouble(p[0]), Double.parseDouble(p[1]), Double.parseDouble(p[2]),
                        Float.parseFloat(p[3]));
                s.onGround = true;
                out.add(s);
            }
        } catch (NumberFormatException e) {
            return null;
        }
        return out;
    }
}
