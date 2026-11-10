package dev.stsv.core.nickname;

import dev.stsv.core.data.Db;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Nicknames in the shared database (one row per player who ever set a nickname or colour). The key column is
 * unique, so two servers sharing a MySQL database can never hand out the same nickname. Nicknames are not part of
 * any reset (/관리자 초기화 경제 or 기록): they belong to the player, like their account. No Bukkit types.
 */
public final class NicknameStore {

    private static final List<List<String>> SCHEMA = List.of(List.of(
            "CREATE TABLE IF NOT EXISTS nickname (player CHAR(36) NOT NULL PRIMARY KEY, nickname VARCHAR(32), nick_key VARCHAR(32), "
                    + "color VARCHAR(32), changed_at BIGINT NOT NULL, self_sets INT NOT NULL, color_sets INT NOT NULL)",
            "CREATE UNIQUE INDEX nickname_key ON nickname (nick_key)"));

    /** What a save did: ok, or refused because someone else has the nickname; tookFrom = who lost it to a forced set. */
    public record Saved(boolean ok, UUID tookFrom) {}

    private final Db db;

    public NicknameStore(Db db) {
        this.db = db;
    }

    public void init() {
        db.migrate("nicknames", SCHEMA);
    }

    private static NameIndex.Entry row(java.sql.ResultSet rs) throws SQLException {
        return new NameIndex.Entry(UUID.fromString(rs.getString(1)), rs.getString(2), NickColor.decode(rs.getString(3)),
                rs.getLong(4), rs.getInt(5), rs.getInt(6));
    }

    private static final String COLUMNS = "SELECT player, nickname, color, changed_at, self_sets, color_sets FROM nickname";

    /** Every row (server start, reload). Blocking: only while loading. */
    public List<NameIndex.Entry> loadAll() {
        return db.sync(c -> Db.query(c, COLUMNS, NicknameStore::row));
    }

    public CompletableFuture<Optional<NameIndex.Entry>> load(UUID player) {
        return db.async(c -> Db.query(c, COLUMNS + " WHERE player = ?", NicknameStore::row, player.toString()).stream().findFirst());
    }

    /**
     * Writes a player's row. Refused (nothing written) when another player has the same nickname, unless forced:
     * then theirs is taken away first.
     */
    public CompletableFuture<Saved> save(NameIndex.Entry e, boolean force) {
        return db.async(c -> save(c, e, force));
    }

    private Saved save(Connection c, NameIndex.Entry e, boolean force) throws SQLException {
        boolean auto = c.getAutoCommit();
        c.setAutoCommit(false);
        try {
            UUID took = null;
            String key = e.key();
            if (key != null) {
                List<String> other = Db.query(c, "SELECT player FROM nickname WHERE nick_key = ? AND player <> ?", rs -> rs.getString(1),
                        key, e.player().toString());
                if (!other.isEmpty()) {
                    if (!force) {
                        c.rollback();
                        return new Saved(false, null);
                    }
                    took = UUID.fromString(other.getFirst());
                    Db.update(c, "UPDATE nickname SET nickname = NULL, nick_key = NULL WHERE player = ?", other.getFirst());
                }
            }
            Db.update(c, db.upsert("nickname", List.of("player"), List.of("nickname", "nick_key", "color", "changed_at", "self_sets", "color_sets")),
                    e.player().toString(), e.nickname(), key, e.color() == null ? null : e.color().encode(), e.changedAt(), e.selfSets(), e.colorSets());
            c.commit();
            return new Saved(true, took);
        } catch (SQLException ex) {
            c.rollback();
            throw ex;
        } finally {
            c.setAutoCommit(auto);
        }
    }

    /**
     * Whether a nickname is another player's real account name (from the players who ever joined: the profile
     * table). Case does not matter.
     */
    public CompletableFuture<Boolean> isOthersRealName(String nickname, UUID self) {
        return db.async(c -> {
            try {
                return !Db.query(c, "SELECT player FROM profile WHERE LOWER(name) = LOWER(?) AND player <> ?", rs -> 1,
                        nickname, self.toString()).isEmpty();
            } catch (SQLException e) {
                return false;   // no profile table (profiles not loaded): online names are checked anyway
            }
        });
    }

    /** The real account names of these players, as last seen (profile table). */
    public CompletableFuture<java.util.Map<UUID, String>> realNames() {
        return db.async(c -> {
            java.util.Map<UUID, String> out = new java.util.HashMap<>();
            try {
                for (Object[] r : Db.query(c, "SELECT n.player, p.name FROM nickname n JOIN profile p ON p.player = n.player",
                        rs -> new Object[]{rs.getString(1), rs.getString(2)})) {
                    out.put(UUID.fromString((String) r[0]), (String) r[1]);
                }
            } catch (SQLException e) {
                // no profile table
            }
            return out;
        });
    }
}
