package dev.stsv.economy.land;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Finds the claim at a block quickly: claims are filed under every chunk they touch. Pure (unit-tested). */
public final class ClaimIndex {

    private final Map<Long, Claim> byId = new LinkedHashMap<>();
    private final Map<String, Map<Long, List<Claim>>> byChunk = new HashMap<>();

    private static long chunk(int cx, int cz) {
        return ((long) cx << 32) ^ (cz & 0xffffffffL);
    }

    public void add(Claim c) {
        byId.put(c.id, c);
        Map<Long, List<Claim>> w = byChunk.computeIfAbsent(c.world, k -> new HashMap<>());
        for (int cx = c.x1 >> 4; cx <= c.x2 >> 4; cx++) {
            for (int cz = c.z1 >> 4; cz <= c.z2 >> 4; cz++) w.computeIfAbsent(chunk(cx, cz), k -> new ArrayList<>()).add(c);
        }
    }

    public void remove(Claim c) {
        byId.remove(c.id);
        Map<Long, List<Claim>> w = byChunk.get(c.world);
        if (w == null) return;
        for (List<Claim> l : w.values()) l.remove(c);
        w.values().removeIf(List::isEmpty);
    }

    /** Re-files a claim after its corners changed. */
    public void update(Claim c, int nx1, int nz1, int nx2, int nz2) {
        remove(c);
        c.x1 = Math.min(nx1, nx2);
        c.z1 = Math.min(nz1, nz2);
        c.x2 = Math.max(nx1, nx2);
        c.z2 = Math.max(nz1, nz2);
        add(c);
    }

    public Claim at(String world, int x, int z) {
        Map<Long, List<Claim>> w = byChunk.get(world);
        if (w == null) return null;
        List<Claim> l = w.get(chunk(x >> 4, z >> 4));
        if (l == null) return null;
        for (Claim c : l) if (c.contains(world, x, z)) return c;
        return null;
    }

    /** Any claim (other than ignore) overlapping the rectangle. */
    public Claim overlapping(String world, int x1, int z1, int x2, int z2, Claim ignore) {
        int ax1 = Math.min(x1, x2), ax2 = Math.max(x1, x2), az1 = Math.min(z1, z2), az2 = Math.max(z1, z2);
        Map<Long, List<Claim>> w = byChunk.get(world);
        if (w == null) return null;
        for (int cx = ax1 >> 4; cx <= ax2 >> 4; cx++) {
            for (int cz = az1 >> 4; cz <= az2 >> 4; cz++) {
                for (Claim c : w.getOrDefault(chunk(cx, cz), List.of())) {
                    if (c != ignore && c.overlaps(world, ax1, az1, ax2, az2)) return c;
                }
            }
        }
        return null;
    }

    public Claim get(long id) {
        return byId.get(id);
    }

    public Collection<Claim> all() {
        return byId.values();
    }

    public List<Claim> of(UUID owner) {
        List<Claim> out = new ArrayList<>();
        for (Claim c : byId.values()) if (owner.equals(c.owner)) out.add(c);
        out.sort(java.util.Comparator.comparingLong((Claim c) -> c.created).thenComparingLong(c -> c.id));
        return out;
    }

    public long area(UUID owner) {
        long a = 0;
        for (Claim c : byId.values()) if (owner.equals(c.owner)) a += c.area();
        return a;
    }

    public void clear() {
        byId.clear();
        byChunk.clear();
    }
}
