package dev.stsv.economy.dock;

import dev.stsv.core.data.Db;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Inspected trips (stsv_eco_cargo_trip, {@link CargoTrip}): one row each, kept after they end (the log). The sealed ones
 * are read at start, so a seal outlasts a restart, an unloaded chunk or a vehicle parked half way. No Bukkit types:
 * unit-tested on SQLite.
 */
public final class TripStore {

    private final Db db;

    public TripStore(Db db) {
        this.db = db;
    }

    public void init() {
        String id = db.dialect() == Db.Dialect.MYSQL ? "id BIGINT PRIMARY KEY AUTO_INCREMENT" : "id INTEGER PRIMARY KEY AUTOINCREMENT";
        String text = db.dialect() == Db.Dialect.MYSQL ? "MEDIUMTEXT" : "TEXT";
        db.migrate("eco-cargo-trips", List.of(List.of(
                "CREATE TABLE IF NOT EXISTS stsv_eco_cargo_trip (" + id + ", vehicle BIGINT NOT NULL, owner CHAR(36) NOT NULL, "
                        + "terminal VARCHAR(32) NOT NULL, dock VARCHAR(32) NOT NULL, manifest " + text + ", total BIGINT NOT NULL, "
                        + "changed BIGINT NOT NULL, odometer DOUBLE NOT NULL, straight DOUBLE NOT NULL, sealed BIGINT NOT NULL, "
                        + "status VARCHAR(12) NOT NULL, ended BIGINT NOT NULL)",
                "CREATE INDEX stsv_eco_cargo_trip_status ON stsv_eco_cargo_trip (status)"),
                // 2: the dock's ratio at seal time (docks have prices of their own); 0 = sealed before that
                List.of("ALTER TABLE stsv_eco_cargo_trip ADD COLUMN ratio DOUBLE NOT NULL DEFAULT 0")));
    }

    /** A new sealed trip: stored, with its id. */
    public CargoTrip open(CargoTrip t) {
        long newId = db.sync(c -> {
            try (var st = c.prepareStatement("INSERT INTO stsv_eco_cargo_trip (vehicle, owner, terminal, dock, manifest, total, changed, odometer, "
                    + "straight, sealed, status, ended, ratio) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", java.sql.Statement.RETURN_GENERATED_KEYS)) {
                Object[] v = {t.vehicle(), t.owner().toString(), t.terminal(), t.dock(), t.manifest(), t.total(), t.changed(), t.odometer(),
                        t.straight(), t.sealed(), t.status().name(), t.ended(), t.ratio()};
                for (int i = 0; i < v.length; i++) st.setObject(i + 1, v[i]);
                st.executeUpdate();
                try (var rs = st.getGeneratedKeys()) {
                    return rs.next() ? rs.getLong(1) : -1L;
                }
            }
        });
        return t.withId(newId);
    }

    /** A trip ended (its status and when). */
    public CompletableFuture<Void> end(CargoTrip t) {
        return db.execute("UPDATE stsv_eco_cargo_trip SET status = ?, ended = ? WHERE id = ?", t.status().name(), t.ended(), t.id());
    }

    private static final String COLS = "id, vehicle, owner, terminal, dock, manifest, total, changed, odometer, straight, sealed, status, ended, ratio";

    private static CargoTrip row(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new CargoTrip(rs.getLong(1), rs.getLong(2), UUID.fromString(rs.getString(3)), rs.getString(4), rs.getString(5), rs.getString(6),
                rs.getLong(7), rs.getLong(8), rs.getDouble(9), rs.getDouble(10), rs.getLong(11), CargoTrip.Status.valueOf(rs.getString(12)),
                rs.getLong(13), rs.getDouble(14));
    }

    /** Every trip still sealed. */
    public List<CargoTrip> sealed() {
        return db.sync(c -> Db.query(c, "SELECT " + COLS + " FROM stsv_eco_cargo_trip WHERE status = 'SEALED' ORDER BY id", TripStore::row));
    }

    /** The latest trips (newest first), at most limit. */
    public List<CargoTrip> latest(int limit) {
        return db.sync(c -> Db.query(c, "SELECT " + COLS + " FROM stsv_eco_cargo_trip ORDER BY id DESC LIMIT " + Math.max(1, limit), TripStore::row));
    }
}
