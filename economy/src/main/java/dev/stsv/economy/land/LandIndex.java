package dev.stsv.economy.land;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Which land owns which chunk, and who belongs where. A player owns at most one land and is a member
 * of at most one other; their free chunks count where they live (the land they are a member of, else
 * their own). Pure (unit-tested).
 */
public final class LandIndex {

    private final Map<Long, Land> byId = new LinkedHashMap<>();
    private final Map<ChunkPos, Land> byChunk = new HashMap<>();

    public void add(Land l) {
        byId.put(l.id, l);
        for (ChunkPos c : l.chunks()) byChunk.put(c, l);
    }

    public void remove(Land l) {
        byId.remove(l.id);
        for (ChunkPos c : l.chunks()) byChunk.remove(c, l);
    }

    public void claim(Land l, ChunkPos c, long at) {
        synchronized (l) {
            l.chunks.put(c, at);
        }
        byChunk.put(c, l);
    }

    public void release(Land l, ChunkPos c) {
        synchronized (l) {
            l.chunks.remove(c);
        }
        byChunk.remove(c, l);
    }

    public Land at(ChunkPos c) {
        return byChunk.get(c);
    }

    public Land at(String world, int bx, int bz) {
        return byChunk.get(ChunkPos.ofBlock(world, bx, bz));
    }

    public Land get(long id) {
        return byId.get(id);
    }

    public Collection<Land> all() {
        return byId.values();
    }

    /** The land this player owns (not the server's), or null. */
    public Land ownedBy(UUID p) {
        for (Land l : byId.values()) if (p.equals(l.owner)) return l;
        return null;
    }

    /** The land this player is a member of, or null. */
    public Land memberOf(UUID p) {
        for (Land l : byId.values()) if (l.members.containsKey(p)) return l;
        return null;
    }

    /** Where the player lives: where their free chunks count. */
    public Land home(UUID p) {
        Land m = memberOf(p);
        return m != null ? m : ownedBy(p);
    }

    /** How many people's free chunks count on this land. */
    public int residents(Land l) {
        if (l.server()) return 0;
        int n = 0;
        for (UUID p : l.people()) if (home(p) == l) n++;
        return n;
    }

    public Land server() {
        for (Land l : byId.values()) if (l.server()) return l;
        return null;
    }

    public List<Land> players() {
        List<Land> out = new ArrayList<>();
        for (Land l : byId.values()) if (!l.server()) out.add(l);
        return out;
    }

    public void clear() {
        byId.clear();
        byChunk.clear();
    }
}
