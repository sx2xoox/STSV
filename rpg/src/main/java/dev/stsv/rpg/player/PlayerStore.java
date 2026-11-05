package dev.stsv.rpg.player;

import dev.stsv.core.data.Db;

import java.util.List;
import java.util.UUID;

/** Level and experience per player (table rpg_players). Writes go through the database's single worker, in order. */
final class PlayerStore {

    private final Db db;

    PlayerStore(Db db) {
        this.db = db;
    }

    void init() {
        db.migrate("rpg_players", List.of(List.of(
                "CREATE TABLE IF NOT EXISTS rpg_players (uuid VARCHAR(36) NOT NULL PRIMARY KEY, level INT NOT NULL, exp BIGINT NOT NULL)")));
    }

    /** Blocking: call off the main thread (pre-login) or for a single row. {level, exp}, or null if new. */
    long[] load(UUID id) {
        List<long[]> rows = db.sync(c -> Db.query(c, "SELECT level, exp FROM rpg_players WHERE uuid = ?",
                rs -> new long[]{rs.getInt(1), rs.getLong(2)}, id.toString()));
        return rows.isEmpty() ? null : rows.get(0);
    }

    void save(UUID id, int level, long exp) {
        db.execute(db.upsert("rpg_players", List.of("uuid"), List.of("level", "exp")), id.toString(), level, exp);
    }
}
