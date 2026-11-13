package dev.stsv.core.banner;

import dev.stsv.core.data.Db;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** Which title banner a player wears (one row per wearer). No Bukkit types (unit-tested on SQLite). */
public final class BannerStore {

    private static final List<List<String>> SCHEMA = List.of(List.of(
            "CREATE TABLE IF NOT EXISTS title_banner (player CHAR(36) NOT NULL PRIMARY KEY, banner VARCHAR(64) NOT NULL, "
                    + "since BIGINT NOT NULL)"));

    private final Db db;

    public BannerStore(Db db) {
        this.db = db;
    }

    public void init() {
        db.migrate("title_banners", SCHEMA);
    }

    public CompletableFuture<Optional<String>> worn(UUID player) {
        return db.async(c -> Db.query(c, "SELECT banner FROM title_banner WHERE player = ?", rs -> rs.getString(1), player.toString())
                .stream().findFirst());
    }

    /** Wears a banner (null: takes it off). */
    public CompletableFuture<Void> wear(UUID player, String banner, long now) {
        if (banner == null) return db.execute("DELETE FROM title_banner WHERE player = ?", player.toString());
        return db.execute(db.upsert("title_banner", List.of("player"), List.of("banner", "since")), player.toString(), banner, now);
    }

    /** Every wearer (admin overview). */
    public CompletableFuture<Map<UUID, String>> all() {
        return db.async(c -> {
            Map<UUID, String> out = new java.util.LinkedHashMap<>();
            for (Object[] r : Db.query(c, "SELECT player, banner FROM title_banner ORDER BY since", rs -> new Object[]{rs.getString(1), rs.getString(2)})) {
                out.put(UUID.fromString((String) r[0]), (String) r[1]);
            }
            return out;
        });
    }
}
