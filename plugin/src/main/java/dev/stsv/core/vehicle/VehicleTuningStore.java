package dev.stsv.core.vehicle;

import dev.stsv.core.data.Db;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * What each vehicle was given in the tuning shop ({@link VehicleTuning.Custom}), by the vehicle's number: one row a
 * tuned vehicle, the look as one line ({@link VehicleTuning.Custom#encode}). A table of its own beside
 * stsv_vehicle: a vehicle that is deleted leaves a row nobody reads.
 */
public final class VehicleTuningStore {

    private final Db db;

    public VehicleTuningStore(Db db) {
        this.db = db;
    }

    public void init() {
        db.migrate("vehicle_tuning", java.util.List.of(java.util.List.of(
                "CREATE TABLE IF NOT EXISTS stsv_vehicle_tuning (vehicle BIGINT PRIMARY KEY, custom VARCHAR(512) NOT NULL, updated BIGINT NOT NULL)")));
    }

    /** Every tuned vehicle (when the server starts: few rows, short lines). */
    public Map<Long, VehicleTuning.Custom> load() {
        return db.sync(c -> {
            Map<Long, VehicleTuning.Custom> out = new HashMap<>();
            for (Object[] row : Db.query(c, "SELECT vehicle, custom FROM stsv_vehicle_tuning", rs -> new Object[]{rs.getLong(1), rs.getString(2)})) {
                VehicleTuning.Custom custom = VehicleTuning.Custom.decode((String) row[1]);
                if (!custom.chosen().isEmpty()) out.put((Long) row[0], custom);
            }
            return out;
        });
    }

    /** Writes a vehicle's look (the stock look: its row goes). */
    public CompletableFuture<Void> save(long vehicle, VehicleTuning.Custom custom, long now) {
        String line = custom.encode();
        return db.async(c -> {
            if (line.isEmpty()) {
                Db.update(c, "DELETE FROM stsv_vehicle_tuning WHERE vehicle = ?", vehicle);
            } else if (Db.update(c, "UPDATE stsv_vehicle_tuning SET custom = ?, updated = ? WHERE vehicle = ?", line, now, vehicle) == 0) {
                Db.update(c, "INSERT INTO stsv_vehicle_tuning (vehicle, custom, updated) VALUES (?, ?, ?)", vehicle, line, now);
            }
            return null;
        });
    }
}
