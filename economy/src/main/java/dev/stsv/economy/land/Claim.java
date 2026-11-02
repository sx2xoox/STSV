package dev.stsv.economy.land;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** A protected rectangle, full height. owner == null: a server claim (spawn town). */
public final class Claim {

    public final long id;
    public final UUID owner;
    public final String world;
    public int x1, z1, x2, z2;   // inclusive, x1 <= x2, z1 <= z2
    public final long created;
    public final Set<UUID> members = ConcurrentHashMap.newKeySet();

    public Claim(long id, UUID owner, String world, int ax, int az, int bx, int bz, long created) {
        this.id = id;
        this.owner = owner;
        this.world = world;
        this.x1 = Math.min(ax, bx);
        this.z1 = Math.min(az, bz);
        this.x2 = Math.max(ax, bx);
        this.z2 = Math.max(az, bz);
        this.created = created;
    }

    public long area() {
        return (long) (x2 - x1 + 1) * (z2 - z1 + 1);
    }

    public int width() {
        return x2 - x1 + 1;
    }

    public int depth() {
        return z2 - z1 + 1;
    }

    public boolean contains(String w, int x, int z) {
        return world.equals(w) && x >= x1 && x <= x2 && z >= z1 && z <= z2;
    }

    public boolean overlaps(String w, int ax1, int az1, int ax2, int az2) {
        return world.equals(w) && ax1 <= x2 && ax2 >= x1 && az1 <= z2 && az2 >= z1;
    }

    public boolean allows(UUID player) {
        return player != null && (player.equals(owner) || members.contains(player));
    }

    public boolean server() {
        return owner == null;
    }
}
