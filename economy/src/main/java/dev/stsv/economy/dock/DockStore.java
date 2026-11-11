package dev.stsv.economy.dock;

import dev.stsv.core.data.Db;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * The 하역장 (stsv_eco_dock: one row each, kept until /관리자 하역장 제거) and every sale made at one (stsv_eco_dock_sale:
 * a log, never changed). No Bukkit types: unit-tested on SQLite.
 */
public final class DockStore {

    /** One sale: who sold what from which vehicle, for how much, how far it came. */
    public record Sale(long at, String dock, long vehicle, UUID owner, UUID seller, String items, long paid, double distance, double odometer) {}

    private final Db db;

    public DockStore(Db db) {
        this.db = db;
    }

    public void init() {
        String id = db.dialect() == Db.Dialect.MYSQL ? "id BIGINT PRIMARY KEY AUTO_INCREMENT" : "id INTEGER PRIMARY KEY AUTOINCREMENT";
        String text = db.dialect() == Db.Dialect.MYSQL ? "MEDIUMTEXT" : "TEXT";
        db.migrate("eco-docks", List.of(List.of(
                "CREATE TABLE IF NOT EXISTS stsv_eco_dock (name VARCHAR(32) PRIMARY KEY, kind VARCHAR(8) NOT NULL, world VARCHAR(64) NOT NULL, "
                        + "x INT NOT NULL, y INT NOT NULL, z INT NOT NULL, facing INT NOT NULL, chunks " + text + ", created BIGINT NOT NULL)",
                "CREATE TABLE IF NOT EXISTS stsv_eco_dock_sale (" + id + ", at BIGINT NOT NULL, dock VARCHAR(32) NOT NULL, vehicle BIGINT NOT NULL, "
                        + "owner CHAR(36) NOT NULL, seller CHAR(36) NOT NULL, items " + text + ", paid BIGINT NOT NULL, distance DOUBLE NOT NULL, "
                        + "odometer DOUBLE NOT NULL)",
                "CREATE INDEX stsv_eco_dock_sale_at ON stsv_eco_dock_sale (at)"),
                // 2: each dock's own price (percent of the base, /관리자 하역장 가격); NULL = the global docks.price-ratio
                List.of("ALTER TABLE stsv_eco_dock ADD COLUMN price_pct INT")));
        db.migrate("eco-terminals", List.of(List.of(
                "CREATE TABLE IF NOT EXISTS stsv_eco_terminal (name VARCHAR(32) PRIMARY KEY, kind VARCHAR(8) NOT NULL, world VARCHAR(64) NOT NULL, "
                        + "x INT NOT NULL, y INT NOT NULL, z INT NOT NULL, facing INT NOT NULL, chunks " + text + ", created BIGINT NOT NULL)")));
    }

    /** The departure terminals (출발 터미널: a {@link Dock} of kind TERMINAL each, in a table of their own). */
    public List<Dock> terminals() {
        return db.sync(c -> Db.query(c, "SELECT name, world, x, y, z, facing, chunks, created FROM stsv_eco_terminal ORDER BY created, name",
                rs -> new Dock(rs.getString(1), DockLayout.Kind.TERMINAL, rs.getString(2), rs.getInt(3), rs.getInt(4), rs.getInt(5),
                        rs.getInt(6), rs.getString(7), rs.getLong(8))));
    }

    public CompletableFuture<Void> saveTerminal(Dock d) {
        return db.execute(db.upsert("stsv_eco_terminal", List.of("name"), List.of("kind", "world", "x", "y", "z", "facing", "chunks", "created")),
                d.name(), d.kind().name(), d.world(), d.x(), d.y(), d.z(), d.facing(), d.chunks(), d.created());
    }

    public CompletableFuture<Boolean> deleteTerminal(String name) {
        return db.async(c -> Db.update(c, "DELETE FROM stsv_eco_terminal WHERE name = ?", name) == 1);
    }

    public List<Dock> all() {
        return db.sync(c -> Db.query(c, "SELECT name, kind, world, x, y, z, facing, chunks, created, price_pct FROM stsv_eco_dock ORDER BY created, name",
                rs -> new Dock(rs.getString(1), DockLayout.Kind.valueOf(rs.getString(2)), rs.getString(3), rs.getInt(4), rs.getInt(5), rs.getInt(6),
                        rs.getInt(7), rs.getString(8), rs.getLong(9), price(rs.getObject(10)))));
    }

    /** A stored price_pct: NULL (or anything out of range) = the global ratio. */
    static int price(Object stored) {
        if (!(stored instanceof Number n)) return Dock.DEFAULT_PRICE;
        int v = n.intValue();
        return v >= Dock.MIN_PRICE && v <= Dock.MAX_PRICE ? v : Dock.DEFAULT_PRICE;
    }

    private static Integer column(Dock d) {
        return d.ownPrice() ? d.price() : null;
    }

    public CompletableFuture<Void> save(Dock d) {
        return db.execute(db.upsert("stsv_eco_dock", List.of("name"), List.of("kind", "world", "x", "y", "z", "facing", "chunks", "created", "price_pct")),
                d.name(), d.kind().name(), d.world(), d.x(), d.y(), d.z(), d.facing(), d.chunks(), d.created(), column(d));
    }

    /** A dock's own price (percent), or {@link Dock#DEFAULT_PRICE} for the global ratio again; false if there is no such dock. */
    public CompletableFuture<Boolean> price(String name, int pct) {
        return db.async(c -> Db.update(c, "UPDATE stsv_eco_dock SET price_pct = ? WHERE name = ?",
                pct == Dock.DEFAULT_PRICE ? null : pct, name) == 1);
    }

    public CompletableFuture<Boolean> delete(String name) {
        return db.async(c -> Db.update(c, "DELETE FROM stsv_eco_dock WHERE name = ?", name) == 1);
    }

    public CompletableFuture<Void> log(Sale s) {
        return db.execute("INSERT INTO stsv_eco_dock_sale (at, dock, vehicle, owner, seller, items, paid, distance, odometer) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                s.at(), s.dock(), s.vehicle(), s.owner().toString(), s.seller().toString(), s.items(), s.paid(), s.distance(), s.odometer());
    }

    /** The latest sales (newest first), at most limit; dock null = every dock. */
    public CompletableFuture<List<Sale>> sales(String dock, int limit) {
        return db.async(c -> Db.query(c, "SELECT at, dock, vehicle, owner, seller, items, paid, distance, odometer FROM stsv_eco_dock_sale"
                        + (dock == null ? "" : " WHERE dock = ?") + " ORDER BY id DESC LIMIT " + Math.max(1, limit),
                rs -> new Sale(rs.getLong(1), rs.getString(2), rs.getLong(3), UUID.fromString(rs.getString(4)), UUID.fromString(rs.getString(5)),
                        rs.getString(6), rs.getLong(7), rs.getDouble(8), rs.getDouble(9)), dock == null ? new Object[0] : new Object[]{dock}));
    }
}
