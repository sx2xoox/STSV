package dev.stsv.core.tpa;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Who asked whom for a teleport (/텔포, /텔포여기), who refuses all requests (/텔포차단) and who must still wait after a
 * trip. One pending request per sender (a new one replaces it); a target can have several and accepts the latest
 * or one by its sender's name. Requests expire after the timeout. Pure: times are passed in (tested).
 */
public final class TpaBook {

    /** TO = the sender goes to the target (/텔포), HERE = the target comes to the sender (/텔포여기). */
    public enum Kind { TO, HERE }

    public record Request(UUID sender, String senderName, UUID target, String targetName, Kind kind, long at) {
        /** The player who is teleported. */
        public UUID mover() {
            return kind == Kind.TO ? sender : target;
        }

        /** The player the mover goes to. */
        public UUID destination() {
            return kind == Kind.TO ? target : sender;
        }
    }

    /** NEW = sent; REPLACED = sent, the sender's earlier live request (previous) dropped; SAME = that very request is already pending, nothing sent. */
    public enum Status { NEW, REPLACED, SAME }

    public record Added(Status status, Request previous) {}

    private final Map<UUID, Request> bySender = new LinkedHashMap<>();
    private final Set<UUID> blocking = new HashSet<>();
    private final Map<UUID, Long> lastTrip = new HashMap<>();
    private long timeoutMs;
    private long cooldownMs;

    public TpaBook(long timeoutMs, long cooldownMs) {
        settings(timeoutMs, cooldownMs);
    }

    public void settings(long timeoutMs, long cooldownMs) {
        this.timeoutMs = Math.max(1000, timeoutMs);
        this.cooldownMs = Math.max(0, cooldownMs);
    }

    public boolean expired(Request r, long now) {
        return now - r.at() >= timeoutMs;
    }

    /** Milliseconds until the request expires (0 when it has). */
    public long expiresIn(Request r, long now) {
        return Math.max(0, r.at() + timeoutMs - now);
    }

    public Added add(Request r) {
        Request old = live(bySender.get(r.sender()), r.at());
        if (old != null && old.target().equals(r.target()) && old.kind() == r.kind()) return new Added(Status.SAME, old);
        bySender.remove(r.sender());   // re-insert at the end: the newest last
        bySender.put(r.sender(), r);
        return old == null ? new Added(Status.NEW, null) : new Added(Status.REPLACED, old);
    }

    /** The sender's pending request, or null. */
    public Request outgoing(UUID sender, long now) {
        return live(bySender.get(sender), now);
    }

    /** Pending requests to a player, the newest first. */
    public List<Request> incoming(UUID target, long now) {
        List<Request> out = new ArrayList<>();
        for (Request r : bySender.values()) if (r.target().equals(target) && !expired(r, now)) out.add(r);
        Collections.reverse(out);   // later sent = later in the map
        out.sort(Comparator.comparingLong(Request::at).reversed());   // stable: same time keeps the later one first
        return out;
    }

    /**
     * Takes (removes and returns) a pending request to the target: the newest one, or with a name the one from that
     * sender (exact name, ignoring case; else the only one whose sender's name starts with it). null = none.
     */
    public Request take(UUID target, String fromName, long now) {
        List<Request> mine = incoming(target, now);
        Request pick = null;
        if (fromName == null || fromName.isBlank()) {
            pick = mine.isEmpty() ? null : mine.getFirst();
        } else {
            String want = fromName.trim().toLowerCase(Locale.ROOT);
            for (Request r : mine) if (r.senderName().toLowerCase(Locale.ROOT).equals(want)) { pick = r; break; }
            if (pick == null) {
                List<Request> starts = mine.stream().filter(r -> r.senderName().toLowerCase(Locale.ROOT).startsWith(want)).toList();
                if (starts.size() == 1) pick = starts.getFirst();
            }
        }
        if (pick != null) bySender.remove(pick.sender());
        return pick;
    }

    /** Withdraws the sender's pending request (/텔포취소); the removed request, or null when there was none. */
    public Request cancel(UUID sender, long now) {
        Request r = bySender.remove(sender);
        return live(r, now);
    }

    /** Removes and returns every request that has expired. */
    public List<Request> expire(long now) {
        List<Request> out = new ArrayList<>();
        for (Iterator<Request> it = bySender.values().iterator(); it.hasNext(); ) {
            Request r = it.next();
            if (expired(r, now)) {
                out.add(r);
                it.remove();
            }
        }
        return out;
    }

    /** A player left: removes and returns the live requests they sent or received. */
    public List<Request> forget(UUID player, long now) {
        List<Request> out = new ArrayList<>();
        for (Iterator<Request> it = bySender.values().iterator(); it.hasNext(); ) {
            Request r = it.next();
            if (r.sender().equals(player) || r.target().equals(player)) {
                if (!expired(r, now)) out.add(r);
                it.remove();
            }
        }
        blocking.remove(player);
        return out;
    }

    /** /텔포차단: true = the player now refuses every request. */
    public boolean toggleBlocking(UUID player) {
        if (blocking.remove(player)) return false;
        blocking.add(player);
        return true;
    }

    public void setBlocking(UUID player, boolean on) {
        if (on) blocking.add(player);
        else blocking.remove(player);
    }

    public boolean blocking(UUID player) {
        return blocking.contains(player);
    }

    /** A trip the player asked for happened: their next request must wait for the cooldown. */
    public void tripDone(UUID requester, long now) {
        lastTrip.put(requester, now);
    }

    /** Milliseconds before the player may send a request again (0 = now). */
    public long cooldownLeft(UUID player, long now) {
        Long last = lastTrip.get(player);
        if (last == null) return 0;
        long left = last + cooldownMs - now;
        if (left <= 0) lastTrip.remove(player);
        return Math.max(0, left);
    }

    private Request live(Request r, long now) {
        return r == null || expired(r, now) ? null : r;
    }
}
