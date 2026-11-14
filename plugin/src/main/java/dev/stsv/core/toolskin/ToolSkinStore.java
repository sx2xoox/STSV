package dev.stsv.core.toolskin;

import dev.stsv.core.data.Db;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** Which tool skin each player has switched on, one for a kind of tool. No Bukkit types (unit-tested on SQLite). */
public final class ToolSkinStore {

    private static final List<List<String>> SCHEMA = List.of(List.of(
            "CREATE TABLE IF NOT EXISTS stsv_tool_skin_worn (player CHAR(36) NOT NULL, tool VARCHAR(24) NOT NULL, skin VARCHAR(64) NOT NULL, "
                    + "PRIMARY KEY (player, tool))"));

    private final Db db;

    public ToolSkinStore(Db db) {
        this.db = db;
    }

    public void init() {
        db.migrate("tool_skins", SCHEMA);
    }

    /** tool (ToolSkins.Tool's name) → skin id. */
    public CompletableFuture<Map<String, String>> worn(UUID player) {
        return db.async(c -> {
            Map<String, String> out = new HashMap<>();
            for (String[] r : Db.query(c, "SELECT tool, skin FROM stsv_tool_skin_worn WHERE player = ?", rs -> new String[]{rs.getString(1), rs.getString(2)},
                    player.toString())) {
                out.put(r[0], r[1]);
            }
            return out;
        });
    }

    /** Switches a skin on for its kind of tool (null: that kind's off). */
    public CompletableFuture<Void> wear(UUID player, String tool, String skin) {
        if (skin == null) return db.execute("DELETE FROM stsv_tool_skin_worn WHERE player = ? AND tool = ?", player.toString(), tool);
        return db.execute(db.upsert("stsv_tool_skin_worn", List.of("player", "tool"), List.of("skin")), player.toString(), tool, skin);
    }

    public CompletableFuture<Void> clear(UUID player) {
        return db.execute("DELETE FROM stsv_tool_skin_worn WHERE player = ?", player.toString());
    }
}
