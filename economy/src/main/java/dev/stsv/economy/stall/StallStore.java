package dev.stsv.economy.stall;

import dev.stsv.core.data.Db;
import dev.stsv.economy.mail.MailStore;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** 노점: numbered stall chests in the market street, who rents them and the price of each kind of goods. */
public final class StallStore {

    public static final class Stall {
        public final int number;
        public final String world;
        public final int x, y, z;
        public UUID owner;
        public long since;
        public final List<Price> prices = new ArrayList<>();

        Stall(int number, String world, int x, int y, int z, UUID owner, long since) {
            this.number = number;
            this.world = world;
            this.x = x;
            this.y = y;
            this.z = z;
            this.owner = owner;
            this.since = since;
        }
    }

    /** template = the item (amount 1) whose look decides what matches; price per unit */
    public record Price(byte[] template, long price) {}

    private final Db db;
    private final Map<Integer, Stall> stalls = new LinkedHashMap<>();

    public StallStore(Db db) {
        this.db = db;
    }

    public void init() {
        db.migrate("eco_stall", List.of(List.of(
                "CREATE TABLE IF NOT EXISTS eco_stall (number INT NOT NULL PRIMARY KEY, world VARCHAR(64) NOT NULL, x INT NOT NULL, y INT NOT NULL, "
                        + "z INT NOT NULL, owner CHAR(36), since BIGINT NOT NULL)",
                "CREATE TABLE IF NOT EXISTS eco_stall_price (stall INT NOT NULL, idx INT NOT NULL, template " + MailStore.blob(db) + " NOT NULL, "
                        + "price BIGINT NOT NULL, PRIMARY KEY (stall, idx))")));
        stalls.clear();
        db.sync(c -> {
            for (Stall s : Db.query(c, "SELECT number, world, x, y, z, owner, since FROM eco_stall ORDER BY number",
                    rs -> new Stall(rs.getInt(1), rs.getString(2), rs.getInt(3), rs.getInt(4), rs.getInt(5),
                            rs.getString(6) == null ? null : UUID.fromString(rs.getString(6)), rs.getLong(7)))) {
                stalls.put(s.number, s);
            }
            for (Object[] r : Db.query(c, "SELECT stall, template, price FROM eco_stall_price ORDER BY stall, idx",
                    rs -> new Object[]{rs.getInt(1), rs.getBytes(2), rs.getLong(3)})) {
                Stall s = stalls.get((Integer) r[0]);
                if (s != null) s.prices.add(new Price((byte[]) r[1], (Long) r[2]));
            }
            return null;
        });
    }

    public Map<Integer, Stall> all() {
        return stalls;
    }

    public Stall at(String world, int x, int y, int z) {
        for (Stall s : stalls.values()) if (s.x == x && s.y == y && s.z == z && s.world.equals(world)) return s;
        return null;
    }

    public Stall of(UUID owner) {
        for (Stall s : stalls.values()) if (owner.equals(s.owner)) return s;
        return null;
    }

    public void create(int number, String world, int x, int y, int z) {
        stalls.put(number, new Stall(number, world, x, y, z, null, 0));
        db.execute(db.upsert("eco_stall", List.of("number"), List.of("world", "x", "y", "z", "owner", "since")), number, world, x, y, z, null, 0L);
    }

    public void delete(int number) {
        stalls.remove(number);
        db.execute("DELETE FROM eco_stall WHERE number = ?", number);
        db.execute("DELETE FROM eco_stall_price WHERE stall = ?", number);
    }

    public void setOwner(Stall s, UUID owner, long now) {
        s.owner = owner;
        s.since = now;
        if (owner == null) s.prices.clear();
        db.execute("UPDATE eco_stall SET owner = ?, since = ? WHERE number = ?", owner == null ? null : owner.toString(), now, s.number);
        if (owner == null) db.execute("DELETE FROM eco_stall_price WHERE stall = ?", s.number);
    }

    public void savePrices(Stall s) {
        List<Price> copy = List.copyOf(s.prices);
        db.async(c -> {
            Db.update(c, "DELETE FROM eco_stall_price WHERE stall = ?", s.number);
            for (int i = 0; i < copy.size(); i++) {
                Db.update(c, "INSERT INTO eco_stall_price (stall, idx, template, price) VALUES (?, ?, ?, ?)", s.number, i, copy.get(i).template(), copy.get(i).price());
            }
            return null;
        });
    }

    public CompletableFuture<Void> reset() {
        for (Stall s : stalls.values()) {
            s.owner = null;
            s.prices.clear();
        }
        return db.async(c -> {
            Db.update(c, "UPDATE eco_stall SET owner = NULL, since = 0");
            Db.update(c, "DELETE FROM eco_stall_price");
            return null;
        });
    }
}
