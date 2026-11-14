package dev.stsv.core.emote;

import dev.stsv.core.data.Db;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * The emote wheel as each player arranged it: one row a place of the wheel that has an emote, and a row of place -1
 * for whoever arranged theirs at all (so an emptied wheel stays empty). No Bukkit types (unit-tested on SQLite).
 */
public final class EmoteStore {

    private static final List<List<String>> SCHEMA = List.of(List.of(
            "CREATE TABLE IF NOT EXISTS stsv_emote_wheel (player CHAR(36) NOT NULL, slot INT NOT NULL, emote VARCHAR(64) NOT NULL, "
                    + "PRIMARY KEY (player, slot))"));

    private final Db db;

    public EmoteStore(Db db) {
        this.db = db;
    }

    public void init() {
        db.migrate("emote_wheel", SCHEMA);
    }

    /** A player's wheel (null in a place: empty), or nothing when they never arranged one. */
    public CompletableFuture<Optional<String[]>> wheel(UUID player) {
        return db.async(c -> {
            List<Object[]> rows = Db.query(c, "SELECT slot, emote FROM stsv_emote_wheel WHERE player = ?", rs -> new Object[]{rs.getInt(1), rs.getString(2)},
                    player.toString());
            if (rows.isEmpty()) return Optional.empty();
            String[] wheel = new String[EmoteWheel.SLOTS];
            for (Object[] r : rows) {
                int slot = (Integer) r[0];
                if (slot >= 0 && slot < wheel.length) wheel[slot] = (String) r[1];
            }
            return Optional.of(wheel);
        });
    }

    /** Keeps a player's wheel as it is now. */
    public CompletableFuture<Void> save(UUID player, String[] wheel) {
        String[] copy = wheel.clone();
        return db.async(c -> {
            Db.update(c, "DELETE FROM stsv_emote_wheel WHERE player = ?", player.toString());
            Db.update(c, "INSERT INTO stsv_emote_wheel (player, slot, emote) VALUES (?, ?, ?)", player.toString(), -1, "");
            for (int k = 0; k < copy.length; k++) {
                if (copy[k] != null) Db.update(c, "INSERT INTO stsv_emote_wheel (player, slot, emote) VALUES (?, ?, ?)", player.toString(), k, copy[k]);
            }
            return null;
        });
    }
}
