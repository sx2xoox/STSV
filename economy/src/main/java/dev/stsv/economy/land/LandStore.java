package dev.stsv.economy.land;

import dev.stsv.core.data.Db;
import dev.stsv.economy.mail.MailStore;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Lands, their chunks, members and 금고 history, and what each player spent on land (counted in net worth). */
public final class LandStore {

    /** One line of a 금고's history: amount > 0 in, < 0 out. kind: deposit, withdraw, tax, refund. */
    public record VaultEntry(UUID player, long amount, String kind, long at) {}

    private final Db db;
    private final LandIndex index = new LandIndex();
    private final Map<UUID, Long> spent = new ConcurrentHashMap<>();

    public LandStore(Db db) {
        this.db = db;
    }

    public void init(long now) {
        db.migrate("eco_land", List.of(
                List.of(
                        "CREATE TABLE IF NOT EXISTS eco_claim (" + MailStore.autoId(db) + ", owner CHAR(36), world VARCHAR(64) NOT NULL, "
                                + "x1 INT NOT NULL, z1 INT NOT NULL, x2 INT NOT NULL, z2 INT NOT NULL, created BIGINT NOT NULL)",
                        "CREATE TABLE IF NOT EXISTS eco_claim_member (claim BIGINT NOT NULL, player CHAR(36) NOT NULL, PRIMARY KEY (claim, player))",
                        "CREATE TABLE IF NOT EXISTS eco_land (player CHAR(36) NOT NULL PRIMARY KEY, bought BIGINT NOT NULL, spent BIGINT NOT NULL, warnings INT NOT NULL)"),
                List.of(
                        "CREATE TABLE IF NOT EXISTS eco_territory (" + MailStore.autoId(db) + ", owner CHAR(36), created BIGINT NOT NULL, "
                                + "vault BIGINT NOT NULL, warnings INT NOT NULL)",
                        "CREATE TABLE IF NOT EXISTS eco_territory_chunk (world VARCHAR(64) NOT NULL, cx INT NOT NULL, cz INT NOT NULL, "
                                + "land BIGINT NOT NULL, claimed BIGINT NOT NULL, PRIMARY KEY (world, cx, cz))",
                        "CREATE TABLE IF NOT EXISTS eco_territory_member (land BIGINT NOT NULL, player CHAR(36) NOT NULL, joined BIGINT NOT NULL, "
                                + "PRIMARY KEY (land, player))",
                        "CREATE TABLE IF NOT EXISTS eco_territory_log (" + MailStore.autoId(db) + ", land BIGINT NOT NULL, player CHAR(36), "
                                + "amount BIGINT NOT NULL, kind VARCHAR(16) NOT NULL, at BIGINT NOT NULL)")));
        index.clear();
        spent.clear();
        db.sync(c -> {
            Map<Long, Land> lands = new HashMap<>();
            for (Land l : Db.query(c, "SELECT id, owner, created, vault, warnings FROM eco_territory", rs -> {
                Land l = new Land(rs.getLong(1), rs.getString(2) == null ? null : UUID.fromString(rs.getString(2)), rs.getLong(3));
                l.vault = rs.getLong(4);
                l.warnings = rs.getInt(5);
                return l;
            })) lands.put(l.id, l);
            for (Object[] r : Db.query(c, "SELECT world, cx, cz, land, claimed FROM eco_territory_chunk ORDER BY claimed",
                    rs -> new Object[]{rs.getString(1), rs.getInt(2), rs.getInt(3), rs.getLong(4), rs.getLong(5)})) {
                Land l = lands.get((Long) r[3]);
                if (l != null) l.chunks.put(new ChunkPos((String) r[0], (Integer) r[1], (Integer) r[2]), (Long) r[4]);
            }
            for (Object[] r : Db.query(c, "SELECT land, player, joined FROM eco_territory_member",
                    rs -> new Object[]{rs.getLong(1), rs.getString(2), rs.getLong(3)})) {
                Land l = lands.get((Long) r[0]);
                if (l != null) l.members.put(UUID.fromString((String) r[1]), (Long) r[2]);
            }
            lands.values().forEach(index::add);
            for (Object[] r : Db.query(c, "SELECT player, spent FROM eco_land", rs -> new Object[]{rs.getString(1), rs.getLong(2)})) {
                spent.put(UUID.fromString((String) r[0]), (Long) r[1]);
            }
            return null;
        });
        if (index.all().isEmpty()) convertClaims(now);
    }

    /**
     * Economy 2.0 and before protected rectangles. On the first start with chunks, each owner's
     * rectangles become one land of every chunk they touch (first come first served); members of the
     * old trust lists are not carried over. Land money spent stays counted.
     */
    private void convertClaims(long now) {
        List<Object[]> old = db.sync(c -> Db.query(c, "SELECT owner, world, x1, z1, x2, z2, created FROM eco_claim ORDER BY created, id",
                rs -> new Object[]{rs.getString(1), rs.getString(2), rs.getInt(3), rs.getInt(4), rs.getInt(5), rs.getInt(6), rs.getLong(7)}));
        if (old.isEmpty()) return;
        Map<String, Land> byOwner = new HashMap<>();
        for (Object[] r : old) {
            String key = r[0] == null ? "" : (String) r[0];
            Land l = byOwner.computeIfAbsent(key, k -> create(k.isEmpty() ? null : UUID.fromString(k), (Long) r[6]));
            String world = (String) r[1];
            for (int cx = (Integer) r[2] >> 4; cx <= (Integer) r[4] >> 4; cx++) {
                for (int cz = (Integer) r[3] >> 4; cz <= (Integer) r[5] >> 4; cz++) {
                    ChunkPos p = new ChunkPos(world, cx, cz);
                    if (index.at(p) == null) claim(l, p, (Long) r[6]);
                }
            }
        }
        for (Land l : byOwner.values()) if (l.size() == 0) delete(l);
        // converted once: the rectangles are not read again
        db.execute("DELETE FROM eco_claim_member");
        db.execute("DELETE FROM eco_claim");
    }

    public LandIndex index() {
        return index;
    }

    // ------------------------------------------------------------------------------------------

    public Land create(UUID owner, long now) {
        long id = db.sync(c -> Db.insert(c, "INSERT INTO eco_territory (owner, created, vault, warnings) VALUES (?, ?, 0, 0)",
                owner == null ? null : owner.toString(), now));
        Land l = new Land(id, owner, now);
        index.add(l);
        return l;
    }

    public void claim(Land l, ChunkPos c, long now) {
        index.claim(l, c, now);
        db.execute("INSERT INTO eco_territory_chunk (world, cx, cz, land, claimed) VALUES (?, ?, ?, ?, ?)", c.world(), c.x(), c.z(), l.id, now);
    }

    public void release(Land l, ChunkPos c) {
        index.release(l, c);
        db.execute("DELETE FROM eco_territory_chunk WHERE world = ? AND cx = ? AND cz = ?", c.world(), c.x(), c.z());
    }

    public void delete(Land l) {
        index.remove(l);
        db.execute("DELETE FROM eco_territory_chunk WHERE land = ?", l.id);
        db.execute("DELETE FROM eco_territory_member WHERE land = ?", l.id);
        db.execute("DELETE FROM eco_territory_log WHERE land = ?", l.id);
        db.execute("DELETE FROM eco_territory WHERE id = ?", l.id);
    }

    public void member(Land l, UUID player, boolean add, long now) {
        if (add) {
            l.members.put(player, now);
            db.execute(db.upsert("eco_territory_member", List.of("land", "player"), List.of("joined")), l.id, player.toString(), now);
        } else {
            l.members.remove(player);
            db.execute("DELETE FROM eco_territory_member WHERE land = ? AND player = ?", l.id, player.toString());
        }
    }

    public void setOwner(Land l, UUID owner) {
        l.owner = owner;
        db.execute("UPDATE eco_territory SET owner = ? WHERE id = ?", owner.toString(), l.id);
    }

    /** Saves the 금고 and the unpaid days. */
    public void save(Land l) {
        db.execute("UPDATE eco_territory SET vault = ?, warnings = ? WHERE id = ?", l.vault, l.warnings, l.id);
    }

    /** Changes the 금고 by amount and writes the history line. */
    public void vault(Land l, UUID player, long amount, String kind, long now) {
        l.vault += amount;
        save(l);
        db.execute("INSERT INTO eco_territory_log (land, player, amount, kind, at) VALUES (?, ?, ?, ?, ?)",
                l.id, player == null ? null : player.toString(), amount, kind, now);
    }

    public List<VaultEntry> history(Land l, int limit) {
        return db.sync(c -> Db.query(c, "SELECT player, amount, kind, at FROM eco_territory_log WHERE land = ? ORDER BY at DESC, id DESC LIMIT " + Math.max(1, limit),
                rs -> new VaultEntry(rs.getString(1) == null ? null : UUID.fromString(rs.getString(1)), rs.getLong(2), rs.getString(3), rs.getLong(4)), l.id));
    }

    /** Total each player put into the 금고 (deposits minus withdrawals). */
    public Map<UUID, Long> contributions(Land l) {
        Map<UUID, Long> out = new java.util.LinkedHashMap<>();
        for (Object[] r : db.sync(c -> Db.query(c, "SELECT player, SUM(amount) FROM eco_territory_log WHERE land = ? AND kind IN ('deposit', 'withdraw') "
                + "AND player IS NOT NULL GROUP BY player ORDER BY SUM(amount) DESC", rs -> new Object[]{rs.getString(1), rs.getLong(2)}, l.id))) {
            out.put(UUID.fromString((String) r[0]), (Long) r[1]);
        }
        return out;
    }

    // ------------------------------------------------------------------------------------------

    /** Money a player spent buying chunks (part of their net worth). */
    public long spent(UUID player) {
        return spent.getOrDefault(player, 0L);
    }

    public void addSpent(UUID player, long amount) {
        long v = spent.merge(player, amount, Long::sum);
        db.execute(db.upsert("eco_land", List.of("player"), List.of("bought", "spent", "warnings")), player.toString(), 0, v, 0);
    }

    /** Player lands only (the server's land stays on a reset). */
    public void reset() {
        for (Land l : new ArrayList<>(index.all())) if (!l.server()) index.remove(l);
        spent.clear();
        db.async(c -> {
            Db.update(c, "DELETE FROM eco_territory_chunk WHERE land IN (SELECT id FROM eco_territory WHERE owner IS NOT NULL)");
            Db.update(c, "DELETE FROM eco_territory_member");
            Db.update(c, "DELETE FROM eco_territory_log");
            Db.update(c, "DELETE FROM eco_territory WHERE owner IS NOT NULL");
            Db.update(c, "DELETE FROM eco_claim WHERE owner IS NOT NULL");
            Db.update(c, "DELETE FROM eco_claim_member");
            Db.update(c, "DELETE FROM eco_land");
            return null;
        });
    }
}
