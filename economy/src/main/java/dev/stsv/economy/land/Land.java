package dev.stsv.economy.land;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A 땅: chunks with one owner and any number of members who live there together, and a shared 금고
 * that pays the property tax first. owner == null: the server's land (spawn town).
 */
public final class Land {

    public final long id;
    public UUID owner;
    public final long created;
    public long vault;
    public int warnings;
    /** member -> when they joined */
    public final Map<UUID, Long> members = new ConcurrentHashMap<>();
    /** chunk -> when it was claimed, oldest first */
    final Map<ChunkPos, Long> chunks = new LinkedHashMap<>();

    public Land(long id, UUID owner, long created) {
        this.id = id;
        this.owner = owner;
        this.created = created;
    }

    public boolean server() {
        return owner == null;
    }

    public boolean isOwner(UUID p) {
        return p != null && p.equals(owner);
    }

    /** Owner or member: may build, open, use everything here. */
    public boolean allows(UUID p) {
        return p != null && (p.equals(owner) || members.containsKey(p));
    }

    public synchronized int size() {
        return chunks.size();
    }

    public synchronized List<ChunkPos> chunks() {
        return new ArrayList<>(chunks.keySet());
    }

    /** The most recently claimed chunk, or null. */
    public synchronized ChunkPos newest() {
        ChunkPos last = null;
        long at = Long.MIN_VALUE;
        for (Map.Entry<ChunkPos, Long> e : chunks.entrySet()) if (e.getValue() >= at) { at = e.getValue(); last = e.getKey(); }
        return last;
    }

    /** Everyone who lives here: the owner and the members. */
    public Set<UUID> people() {
        Set<UUID> out = new java.util.LinkedHashSet<>();
        if (owner != null) out.add(owner);
        out.addAll(members.keySet());
        return out;
    }
}
