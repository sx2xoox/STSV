package dev.stsv.core.vehicle;

import dev.stsv.core.data.Db;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * 작업기: which tool each vehicle has on (its id in vehicle_tools.yml), in its own table ({@code stsv_vehicle_tool}, beside
 * {@link VehicleStore}'s row of the vehicle). No row = the vehicle as it comes. Kept across restarts; a vehicle picked up into
 * an item forgets it (placed again, it comes as it is). No Bukkit types: unit-tested on SQLite.
 */
public final class VehicleToolStore {

    private final Db db;

    public VehicleToolStore(Db db) {
        this.db = db;
    }

    public void init() {
        db.migrate("vehicle_tools", List.of(List.of(
                "CREATE TABLE IF NOT EXISTS stsv_vehicle_tool (id BIGINT PRIMARY KEY, tool VARCHAR(64) NOT NULL, updated BIGINT NOT NULL)")));
    }

    /** Every vehicle with a tool on (loaded at start): id -> tool id. */
    public Map<Long, String> all() {
        return db.sync(c -> {
            Map<Long, String> out = new HashMap<>();
            for (Object[] r : Db.query(c, "SELECT id, tool FROM stsv_vehicle_tool", rs -> new Object[]{rs.getLong(1), rs.getString(2)})) {
                out.put((Long) r[0], (String) r[1]);
            }
            return out;
        });
    }

    /** Saves the tool a vehicle has on (null or blank: none, the row goes). */
    public CompletableFuture<Void> save(long id, String tool, long now) {
        if (tool == null || tool.isBlank()) return delete(id);
        return db.execute(db.upsert("stsv_vehicle_tool", List.of("id"), List.of("tool", "updated")), id, tool, now);
    }

    public CompletableFuture<Void> delete(long id) {
        return db.execute("DELETE FROM stsv_vehicle_tool WHERE id = ?", id);
    }
}
