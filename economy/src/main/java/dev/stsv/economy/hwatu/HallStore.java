package dev.stsv.economy.hwatu;

import dev.stsv.core.data.Db;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The 도박장 (README "도박장"): which table furniture plays what (eco_hwatu_table: the furniture entity, where it stands, the
 * game and room), and the hall's own settings (eco_hwatu_hall, key → value: the warp /화투방 goes to, the counter NPC, the
 * whiteboard and today's big pots on it). Read whole at start, written through. No Bukkit types: unit-tested on SQLite.
 */
public final class HallStore {

    /** A table furniture bound to a game: the furniture entity (its UUID), where it stood when bound, the game and room ids. */
    public record Binding(UUID furniture, String world, int x, int y, int z, String game, String room) {}

    /** Hall setting keys. */
    public static final String WARP = "warp", COUNTER = "counter", BOARD = "board", RECORDS = "records";

    private final Db db;
    private final Map<UUID, Binding> tables = new HashMap<>();
    private final Map<String, String> hall = new HashMap<>();

    public HallStore(Db db) {
        this.db = db;
    }

    public void init() {
        db.migrate("eco_hwatu_hall", List.of(List.of(
                "CREATE TABLE IF NOT EXISTS eco_hwatu_table (furniture CHAR(36) NOT NULL PRIMARY KEY, world VARCHAR(64) NOT NULL, "
                        + "x INT NOT NULL, y INT NOT NULL, z INT NOT NULL, game VARCHAR(16) NOT NULL, room VARCHAR(32) NOT NULL)",
                "CREATE TABLE IF NOT EXISTS eco_hwatu_hall (k VARCHAR(32) NOT NULL PRIMARY KEY, v TEXT NOT NULL)")));
        tables.clear();
        hall.clear();
        db.sync(c -> {
            for (Binding b : Db.query(c, "SELECT furniture, world, x, y, z, game, room FROM eco_hwatu_table",
                    rs -> new Binding(UUID.fromString(rs.getString(1)), rs.getString(2), rs.getInt(3), rs.getInt(4), rs.getInt(5),
                            rs.getString(6), rs.getString(7)))) {
                tables.put(b.furniture(), b);
            }
            for (String[] kv : Db.query(c, "SELECT k, v FROM eco_hwatu_hall", rs -> new String[]{rs.getString(1), rs.getString(2)})) {
                hall.put(kv[0], kv[1]);
            }
            return null;
        });
    }

    public List<Binding> tables() {
        return List.copyOf(tables.values());
    }

    public Binding table(UUID furniture) {
        return tables.get(furniture);
    }

    /** Binds (or rebinds) a table. */
    public void bind(Binding b) {
        tables.put(b.furniture(), b);
        db.execute(db.upsert("eco_hwatu_table", List.of("furniture"), List.of("world", "x", "y", "z", "game", "room")),
                b.furniture().toString(), b.world(), b.x(), b.y(), b.z(), b.game(), b.room());
    }

    /** The furniture of a binding was found under another entity id (placed again): the row follows it. */
    public void moved(UUID from, Binding to) {
        if (tables.remove(from) != null) db.execute("DELETE FROM eco_hwatu_table WHERE furniture = ?", from.toString());
        bind(to);
    }

    public boolean unbind(UUID furniture) {
        if (tables.remove(furniture) == null) return false;
        db.execute("DELETE FROM eco_hwatu_table WHERE furniture = ?", furniture.toString());
        return true;
    }

    public String get(String key) {
        return hall.get(key);
    }

    /** Sets a hall setting (null removes it). */
    public void set(String key, String value) {
        if (value == null) {
            if (hall.remove(key) != null) db.execute("DELETE FROM eco_hwatu_hall WHERE k = ?", key);
            return;
        }
        hall.put(key, value);
        db.execute(db.upsert("eco_hwatu_hall", List.of("k"), List.of("v")), key, value);
    }
}
