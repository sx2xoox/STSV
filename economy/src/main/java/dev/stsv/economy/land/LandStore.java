package dev.stsv.economy.land;

import dev.stsv.core.data.Db;
import dev.stsv.economy.mail.MailStore;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Claims, members and each player's bought area / money spent / unpaid-tax warnings. */
public final class LandStore {

    public static final class Account {
        public long bought;
        public long spent;
        public int warnings;
    }

    private final Db db;
    private final ClaimIndex index = new ClaimIndex();
    private final Map<UUID, Account> accounts = new ConcurrentHashMap<>();

    public LandStore(Db db) {
        this.db = db;
    }

    public void init() {
        db.migrate("eco_land", List.of(List.of(
                "CREATE TABLE IF NOT EXISTS eco_claim (" + MailStore.autoId(db) + ", owner CHAR(36), world VARCHAR(64) NOT NULL, "
                        + "x1 INT NOT NULL, z1 INT NOT NULL, x2 INT NOT NULL, z2 INT NOT NULL, created BIGINT NOT NULL)",
                "CREATE TABLE IF NOT EXISTS eco_claim_member (claim BIGINT NOT NULL, player CHAR(36) NOT NULL, PRIMARY KEY (claim, player))",
                "CREATE TABLE IF NOT EXISTS eco_land (player CHAR(36) NOT NULL PRIMARY KEY, bought BIGINT NOT NULL, spent BIGINT NOT NULL, warnings INT NOT NULL)")));
        index.clear();
        accounts.clear();
        db.sync(c -> {
            for (Claim cl : Db.query(c, "SELECT id, owner, world, x1, z1, x2, z2, created FROM eco_claim", rs -> new Claim(rs.getLong(1),
                    rs.getString(2) == null ? null : UUID.fromString(rs.getString(2)), rs.getString(3), rs.getInt(4), rs.getInt(5), rs.getInt(6), rs.getInt(7), rs.getLong(8)))) {
                index.add(cl);
            }
            for (Object[] r : Db.query(c, "SELECT claim, player FROM eco_claim_member", rs -> new Object[]{rs.getLong(1), rs.getString(2)})) {
                Claim cl = index.get((Long) r[0]);
                if (cl != null) cl.members.add(UUID.fromString((String) r[1]));
            }
            for (Object[] r : Db.query(c, "SELECT player, bought, spent, warnings FROM eco_land", rs -> new Object[]{rs.getString(1), rs.getLong(2), rs.getLong(3), rs.getInt(4)})) {
                Account a = new Account();
                a.bought = (Long) r[1];
                a.spent = (Long) r[2];
                a.warnings = (Integer) r[3];
                accounts.put(UUID.fromString((String) r[0]), a);
            }
            return null;
        });
    }

    public ClaimIndex index() {
        return index;
    }

    public Account account(UUID player) {
        return accounts.computeIfAbsent(player, k -> new Account());
    }

    public void save(UUID player) {
        Account a = account(player);
        db.execute(db.upsert("eco_land", List.of("player"), List.of("bought", "spent", "warnings")), player.toString(), a.bought, a.spent, a.warnings);
    }

    public Claim create(UUID owner, String world, int x1, int z1, int x2, int z2, long now) {
        long id = db.sync(c -> {
            return Db.insert(c, "INSERT INTO eco_claim (owner, world, x1, z1, x2, z2, created) VALUES (?, ?, ?, ?, ?, ?, ?)",
                    owner == null ? null : owner.toString(), world, Math.min(x1, x2), Math.min(z1, z2), Math.max(x1, x2), Math.max(z1, z2), now);
        });
        Claim cl = new Claim(id, owner, world, x1, z1, x2, z2, now);
        index.add(cl);
        return cl;
    }

    public void resize(Claim cl, int x1, int z1, int x2, int z2) {
        index.update(cl, x1, z1, x2, z2);
        db.execute("UPDATE eco_claim SET x1 = ?, z1 = ?, x2 = ?, z2 = ? WHERE id = ?", cl.x1, cl.z1, cl.x2, cl.z2, cl.id);
    }

    public void delete(Claim cl) {
        index.remove(cl);
        db.execute("DELETE FROM eco_claim WHERE id = ?", cl.id);
        db.execute("DELETE FROM eco_claim_member WHERE claim = ?", cl.id);
    }

    public void member(Claim cl, UUID player, boolean add) {
        if (add) {
            cl.members.add(player);
            db.execute(db.upsert("eco_claim_member", List.of("claim", "player"), List.of()), cl.id, player.toString());
        } else {
            cl.members.remove(player);
            db.execute("DELETE FROM eco_claim_member WHERE claim = ? AND player = ?", cl.id, player.toString());
        }
    }

    public Map<UUID, Account> accounts() {
        return accounts;
    }

    /** Player claims only (server claims stay on a reset). */
    public void reset() {
        for (Claim cl : List.copyOf(index.all())) if (!cl.server()) index.remove(cl);
        accounts.clear();
        db.async(c -> {
            Db.update(c, "DELETE FROM eco_claim_member WHERE claim IN (SELECT id FROM eco_claim WHERE owner IS NOT NULL)");
            Db.update(c, "DELETE FROM eco_claim WHERE owner IS NOT NULL");
            Db.update(c, "DELETE FROM eco_land");
            return null;
        });
    }
}
