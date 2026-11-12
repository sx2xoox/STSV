package dev.stsv.economy.fishing;

import dev.stsv.core.data.Db;
import dev.stsv.economy.mail.MailStore;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Placed 통발 and 가두리 (by their furniture display's id): where, whose, the water they gather from, the time they last moved on
 * and what they hold. All in memory, written through on every change.
 */
public final class TrapStore {

    public enum Kind { TRAP, PEN }

    public static final class Trap {
        public final UUID id;
        public final Kind kind;
        public final UUID owner;
        public final String world;
        public final UUID worldId;
        public final int x, y, z;
        public final String habitat;
        public long last;
        /** a trap's catch, in order */
        public final List<ItemStack> stored = new ArrayList<>();
        /** a pen's fish kind (fish.yml species id), how many, and the feed in it */
        public String species;
        public int count;
        public int feed;

        public Trap(UUID id, Kind kind, UUID owner, String world, UUID worldId, int x, int y, int z, String habitat, long last) {
            this.id = id;
            this.kind = kind;
            this.owner = owner;
            this.world = world;
            this.worldId = worldId;
            this.x = x;
            this.y = y;
            this.z = z;
            this.habitat = habitat;
            this.last = last;
        }

        public long chunk() {
            return chunkKey(x >> 4, z >> 4);
        }

        public int storedCount() {
            int n = 0;
            for (ItemStack it : stored) n += it.getAmount();
            return n;
        }
    }

    public static long chunkKey(int cx, int cz) {
        return ((long) cx << 32) ^ (cz & 0xFFFFFFFFL);
    }

    private final Db db;
    private final Map<UUID, Trap> all = new ConcurrentHashMap<>();

    public TrapStore(Db db) {
        this.db = db;
    }

    public void init() {
        db.migrate("eco_fish_traps", List.of(List.of(
                "CREATE TABLE IF NOT EXISTS eco_fish_traps (id CHAR(36) NOT NULL PRIMARY KEY, kind VARCHAR(8) NOT NULL, owner CHAR(36) NOT NULL, "
                        + "world VARCHAR(64) NOT NULL, world_id CHAR(36) NOT NULL, x INT NOT NULL, y INT NOT NULL, z INT NOT NULL, "
                        + "habitat VARCHAR(16) NOT NULL, last_at BIGINT NOT NULL, contents " + MailStore.blob(db) + ", species VARCHAR(64), "
                        + "fish INT NOT NULL, feed INT NOT NULL)")));
        all.clear();
        for (Object[] r : db.sync(c -> Db.query(c, "SELECT id, kind, owner, world, world_id, x, y, z, habitat, last_at, contents, species, fish, feed "
                + "FROM eco_fish_traps", rs -> new Object[]{rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5),
                rs.getInt(6), rs.getInt(7), rs.getInt(8), rs.getString(9), rs.getLong(10), rs.getBytes(11), rs.getString(12), rs.getInt(13),
                rs.getInt(14)}))) {
            Kind kind;
            try {
                kind = Kind.valueOf((String) r[1]);
            } catch (IllegalArgumentException e) {
                continue;
            }
            Trap t = new Trap(UUID.fromString((String) r[0]), kind, UUID.fromString((String) r[2]), (String) r[3], UUID.fromString((String) r[4]),
                    (Integer) r[5], (Integer) r[6], (Integer) r[7], (String) r[8], (Long) r[9]);
            if (r[10] != null) {
                for (ItemStack it : ItemStack.deserializeItemsFromBytes((byte[]) r[10])) if (it != null && !it.isEmpty()) t.stored.add(it);
            }
            t.species = (String) r[11];
            t.count = (Integer) r[12];
            t.feed = (Integer) r[13];
            all.put(t.id, t);
        }
    }

    public Trap get(UUID id) {
        return all.get(id);
    }

    public Collection<Trap> all() {
        return List.copyOf(all.values());
    }

    /** Traps and pens in a chunk of a world (by world name), and of them the pens. */
    public int[] inChunk(String world, int cx, int cz) {
        long key = chunkKey(cx, cz);
        int n = 0, pens = 0;
        for (Trap t : all.values()) {
            if (t.chunk() == key && t.world.equals(world)) {
                n++;
                if (t.kind == Kind.PEN) pens++;
            }
        }
        return new int[]{n, pens};
    }

    public int owned(UUID owner, Kind kind) {
        int n = 0;
        for (Trap t : all.values()) if (t.owner.equals(owner) && t.kind == kind) n++;
        return n;
    }

    public void save(Trap t) {
        all.put(t.id, t);
        byte[] contents = t.stored.isEmpty() ? null : ItemStack.serializeItemsAsBytes(t.stored.toArray(new ItemStack[0]));
        db.execute(db.upsert("eco_fish_traps", List.of("id"), List.of("kind", "owner", "world", "world_id", "x", "y", "z", "habitat", "last_at",
                        "contents", "species", "fish", "feed")),
                t.id.toString(), t.kind.name(), t.owner.toString(), t.world, t.worldId.toString(), t.x, t.y, t.z, t.habitat, t.last, contents,
                t.species, t.count, t.feed);
    }

    public Trap remove(UUID id) {
        Trap t = all.remove(id);
        if (t != null) db.execute("DELETE FROM eco_fish_traps WHERE id = ?", id.toString());
        return t;
    }

    public void reset() {
        all.clear();
        db.execute("DELETE FROM eco_fish_traps");
    }
}
